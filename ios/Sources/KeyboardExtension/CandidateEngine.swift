import Foundation

enum CandidateKind { case character, word, segmentShrink, segmentExpand, status }

struct KanaAnalysis {
    let candidates: [KeyboardCandidate]
    let conversions: [KanaKanjiConversion]
}

struct KeyboardCandidate: Equatable {
    let text: String
    let readings: [String]
    let kind: CandidateKind

    init(_ text: String, readings: [String] = [], kind: CandidateKind = .word) {
        self.text = text; self.readings = readings; self.kind = kind
    }
}

final class CandidateEngine {
    private let queue = DispatchQueue(label: "FuriganaKeyboard.candidates", qos: .userInitiated)
    private let repository: ReadingRepository?
    private var generation = 0

    init(bundle: Bundle = .main) { repository = ReadingRepository(bundle: bundle) }

    func invalidate() { generation += 1 }

    func resolveHandwriting(base: String, recognized: [RecognitionCandidate], completion: @escaping ([KeyboardCandidate]) -> Void) {
        submit(completion) { repository in
            let direct = recognized.map {
                KeyboardCandidate(base + $0.text, readings: repository.readings(for: base + $0.text), kind: .character)
            }
            let completions = direct.prefix(5).flatMap { candidate in
                repository.suggestions(surfacePrefix: candidate.text, limit: 4)
                    .filter { $0.surface != candidate.text }
                    .map { KeyboardCandidate($0.surface, readings: $0.readings) }
            }
            return Self.unique(direct + completions, limit: 10)
        }
    }

    func suggestSurface(_ prefix: String, completion: @escaping ([KeyboardCandidate]) -> Void) {
        submit(completion) { repository in
            repository.suggestions(surfacePrefix: prefix, limit: 8).map {
                KeyboardCandidate($0.surface, readings: $0.readings)
            }
        }
    }

    func convertKana(_ kana: String, completion: @escaping ([KeyboardCandidate]) -> Void) {
        analyzeKana(kana) { completion($0.candidates) }
    }

    func analyzeKana(_ kana: String, completion: @escaping (KanaAnalysis) -> Void) {
        generation += 1
        let request = generation
        guard let repository else { completion(KanaAnalysis(candidates: [], conversions: [])); return }
        queue.async { [weak self] in
            let data = repository.conversionData(for: kana)
            let conversions = KanaKanjiConverter.convert(
                reading: kana,
                lexemes: data.lexemes,
                connections: data.connections,
                preserveSegmentations: true
            )
            let converted = conversions
                .map { KeyboardCandidate($0.surface, readings: [kana]) }
            let prefix = repository.suggestions(readingPrefix: kana, limit: 8).map {
                KeyboardCandidate($0.surface, readings: $0.readings)
            }
            let scripts = [KeyboardCandidate(kana, readings: [kana]),
                           KeyboardCandidate(RomajiKanaConverter.toKatakana(kana), readings: [kana])]
            let candidates = Self.unique(converted + prefix + scripts, limit: 10)
            DispatchQueue.main.async {
                guard let self, request == self.generation else { return }
                completion(KanaAnalysis(candidates: candidates, conversions: conversions))
            }
        }
    }

    private func submit(_ completion: @escaping ([KeyboardCandidate]) -> Void,
                        operation: @escaping (ReadingRepository) -> [KeyboardCandidate]) {
        generation += 1
        let request = generation
        guard let repository else { completion([]); return }
        queue.async { [weak self] in
            let result = operation(repository)
            DispatchQueue.main.async {
                guard let self, request == self.generation else { return }
                completion(result)
            }
        }
    }

    private static func unique(_ values: [KeyboardCandidate], limit: Int) -> [KeyboardCandidate] {
        var seen = Set<String>()
        return values.filter { seen.insert($0.text).inserted }.prefix(limit).map { $0 }
    }
}
