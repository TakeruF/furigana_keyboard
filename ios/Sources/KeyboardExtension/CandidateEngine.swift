import Foundation

enum CandidateKind { case character, word, segmentShrink, segmentExpand, status }

struct KanaAnalysis {
    /// Converter and dictionary output in source order, over-fetched beyond the display
    /// capacity so the terminal policy, not the fetch limit, owns the visible list.
    let wordCandidates: [WordCandidate]
    let conversions: [KanaKanjiConversion]
}

struct KeyboardCandidate: Equatable {
    let text: String
    let readings: [String]
    let kind: CandidateKind
    let bunsetsuReading: String?
    let bunsetsuRightID: Int?
    let bunsetsuGeneration: Int?

    init(
        _ text: String,
        readings: [String] = [],
        kind: CandidateKind = .word,
        bunsetsuReading: String? = nil,
        bunsetsuRightID: Int? = nil,
        bunsetsuGeneration: Int? = nil
    ) {
        self.text = text
        self.readings = readings
        self.kind = kind
        self.bunsetsuReading = bunsetsuReading
        self.bunsetsuRightID = bunsetsuRightID
        self.bunsetsuGeneration = bunsetsuGeneration
    }
}

final class CandidateEngine {
    private let queue = DispatchQueue(label: "FuriganaKeyboard.candidates", qos: .userInitiated)
    private let repository: ReadingRepository?
    private let generationLock = NSLock()
    private var generation = 0

    init(bundle: Bundle = .main) { repository = ReadingRepository(bundle: bundle) }

    func invalidate() { _ = nextGeneration() }

    /// Ranks recognized surfaces and their dictionary completions together, so a well-known
    /// word can outrank a shape the recognizer was unsure about, while the shape it was most
    /// sure about always keeps a slot.
    func resolveHandwriting(base: String, recognized: [RecognitionCandidate], completion: @escaping ([KeyboardCandidate]) -> Void) {
        submit(completion) { repository in
            let surfaces = recognized.map {
                ShapedSurfaceCandidate(
                    surface: base + $0.text,
                    shapeCost: $0.shapeCost,
                    isRecognizerRawTop: $0.isRecognizerRawTop
                )
            }
            var exactReadings: [String: [String]] = [:]
            var suggestions: [String: [WordCandidate]] = [:]
            for (index, shaped) in surfaces.enumerated() where exactReadings[shaped.surface] == nil {
                exactReadings[shaped.surface] = repository.readings(for: shaped.surface)
                guard index < Self.completionSourceLimit else { continue }
                suggestions[shaped.surface] = repository.suggestions(surfacePrefix: shaped.surface, limit: 4)
            }
            let shaped = WordCandidateResolver.shapedCandidates(
                surfaces,
                exactReadings: exactReadings,
                suggestions: suggestions
            )
            let evidence = repository.lexicalEvidence(for: shaped.map(\.surface))
            let recognizedSurfaces = Set(surfaces.map(\.surface))
            return WordCandidateResolver.resolveShaped(
                shaped,
                lexicalEvidence: evidence,
                limit: Self.candidateLimit
            ).map {
                KeyboardCandidate(
                    $0.surface,
                    readings: $0.readings,
                    kind: recognizedSurfaces.contains($0.surface) ? .character : .word
                )
            }
        }
    }

    func suggestSurface(_ prefix: String, completion: @escaping ([KeyboardCandidate]) -> Void) {
        submit(completion) { repository in
            repository.suggestions(surfacePrefix: prefix, limit: 8).map {
                KeyboardCandidate($0.surface, readings: $0.readings)
            }
        }
    }

    func analyzeKana(
        _ kana: String,
        initialRightID: Int = 0,
        initialContextSurface: String? = nil,
        requiredBoundary: Int? = nil,
        completion: @escaping (KanaAnalysis) -> Void
    ) {
        let request = nextGeneration()
        guard let repository else { completion(KanaAnalysis(wordCandidates: [], conversions: [])); return }
        queue.async { [weak self] in
            guard let self, self.isCurrent(request) else { return }
            let data = repository.conversionData(for: kana)
            let conversions = KanaKanjiConverter.convert(
                reading: kana,
                lexemes: data.lexemes,
                connections: data.connections,
                preserveSegmentations: true,
                initialRightID: initialRightID,
                initialContextSurface: initialContextSurface,
                requiredBoundary: requiredBoundary,
                contextModel: repository.contextModel,
                isCancelled: { [weak self] in !(self?.isCurrent(request) ?? false) }
            )
            guard self.isCurrent(request) else { return }
            let converted = conversions.map { WordCandidate(surface: $0.surface, readings: [kana]) }
            let prefix = repository.suggestions(readingPrefix: kana, limit: Self.analysisCandidateLimit)
            let wordCandidates = Self.uniqueBySurface(
                converted + prefix,
                limit: Self.analysisCandidateLimit
            )
            DispatchQueue.main.async {
                guard self.isCurrent(request) else { return }
                completion(KanaAnalysis(wordCandidates: wordCandidates, conversions: conversions))
            }
        }
    }

    private func submit(_ completion: @escaping ([KeyboardCandidate]) -> Void,
                        operation: @escaping (ReadingRepository) -> [KeyboardCandidate]) {
        let request = nextGeneration()
        guard let repository else { completion([]); return }
        queue.async { [weak self] in
            guard let self, self.isCurrent(request) else { return }
            let result = operation(repository)
            DispatchQueue.main.async {
                guard self.isCurrent(request) else { return }
                completion(result)
            }
        }
    }

    private func nextGeneration() -> Int {
        generationLock.lock()
        defer { generationLock.unlock() }
        generation += 1
        return generation
    }

    private func isCurrent(_ request: Int) -> Bool {
        generationLock.lock()
        defer { generationLock.unlock() }
        return request == generation
    }

    private static let completionSourceLimit = 5
    private static let candidateLimit = 10
    /// Over-fetch beyond the eight visible slots so rejected entries cannot consume them.
    static let analysisCandidateLimit = 24

    private static func uniqueBySurface(_ values: [WordCandidate], limit: Int) -> [WordCandidate] {
        var seen = Set<String>()
        return values.filter { seen.insert($0.surface).inserted }.prefix(limit).map { $0 }
    }
}
