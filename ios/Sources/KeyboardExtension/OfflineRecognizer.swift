import CoreGraphics
import Foundation

/// Fully offline recognizer backed by the bundled Zinnia Japanese model.
///
/// Raw Zinnia scores never leave this type. Every batch is normalized into the
/// shared lower-is-better shape-cost band first, so side-by-side halves stay
/// comparable with each other and the common-use bias cannot outweigh a clear
/// difference in shape evidence.
final class OfflineRecognizer {
    private let queue = DispatchQueue(label: "FuriganaKeyboard.recognition", qos: .userInitiated)
    private let engine: FKZinniaRecognizer?
    private let readings: ReadingRepository?
    private var generation = 0

    init(bundle: Bundle = .main) {
        let modelPath = bundle.path(forResource: "handwriting-ja", ofType: "model")
        engine = modelPath.flatMap { try? FKZinniaRecognizer(modelPath: $0) }
        readings = ReadingRepository(bundle: bundle)
    }

    var isReady: Bool { engine != nil }

    func recognize(strokes: [[CGPoint]], canvasSize: CGSize, completion: @escaping ([RecognitionCandidate]) -> Void) {
        generation += 1
        let request = generation
        queue.async { [weak self] in
            guard let self, let engine = self.engine else {
                DispatchQueue.main.async { completion([]) }
                return
            }
            let candidates = self.ranked(engine: engine, strokes: strokes, canvasSize: canvasSize)
            DispatchQueue.main.async {
                guard request == self.generation else { return }
                completion(candidates)
            }
        }
    }

    func cancel() {
        generation += 1
    }

    private func ranked(engine: FKZinniaRecognizer, strokes: [[CGPoint]], canvasSize: CGSize) -> [RecognitionCandidate] {
        let segments = HandwritingStrokeSegmenter.split(strokes: strokes, canvasSize: canvasSize)
        var candidates: [RecognitionCandidate] = []
        if segments.count == 2 {
            let left = recognizeSegment(engine: engine, strokes: segments[0].strokes, size: segments[0].size, limit: Self.perCharacterLimit)
            let right = recognizeSegment(engine: engine, strokes: segments[1].strokes, size: segments[1].size, limit: Self.perCharacterLimit)
            // A half that recognizes nothing is a segmentation mistake, not a dead end.
            candidates = left.isEmpty || right.isEmpty
                ? recognizeSegment(engine: engine, strokes: strokes, size: canvasSize, limit: Self.resultLimit)
                : Self.combine(left: left, right: right)
        } else {
            candidates = recognizeSegment(engine: engine, strokes: strokes, size: canvasSize, limit: Self.resultLimit)
        }
        guard !candidates.isEmpty else { return [] }
        let priorities = readings?.priorities(for: candidates.map(\.text)) ?? [:]
        return KanjiCandidateRanker.rank(candidates, priorities: priorities)
            .prefix(Self.resultLimit)
            .map { $0.withReading(readings?.primaryReading(for: $0.text)) }
    }

    private func recognizeSegment(
        engine: FKZinniaRecognizer,
        strokes: [[CGPoint]],
        size: CGSize,
        limit: Int
    ) -> [RecognitionCandidate] {
        let values = strokes.map { $0.map(NSValue.init(cgPoint:)) }
        let raw = engine.recognizeStrokes(
            values,
            width: max(1, Int(size.width.rounded())),
            height: max(1, Int(size.height.rounded())),
            limit: limit
        ).map { RawRecognitionCandidate($0.text, $0.score) }
        return RecognitionScoreNormalizer.normalize(raw, source: .zinnia)
    }

    /// Averages the two halves' shape costs and keeps each half's own evidence.
    private static func combine(
        left: [RecognitionCandidate],
        right: [RecognitionCandidate]
    ) -> [RecognitionCandidate] {
        let composed = left.prefix(perCharacterLimit).enumerated().flatMap { leftIndex, lhs in
            right.prefix(perCharacterLimit).enumerated().map { rightIndex, rhs in
                (
                    candidate: RecognitionCandidate(
                        text: lhs.text + rhs.text,
                        shapeCost: (lhs.shapeCost + rhs.shapeCost) / 2,
                        evidence: RecognitionEvidence(
                            source: .sideBySide,
                            components: [component(at: 0, lhs), component(at: 1, rhs)]
                        )
                    ),
                    componentRank: leftIndex + rightIndex
                )
            }
        }.sorted { lhs, rhs in
            if lhs.candidate.shapeCost != rhs.candidate.shapeCost {
                return lhs.candidate.shapeCost < rhs.candidate.shapeCost
            }
            return lhs.componentRank < rhs.componentRank
        }
        var seen = Set<String>()
        return composed.filter { seen.insert($0.candidate.text).inserted }
            .prefix(resultLimit)
            .map(\.candidate)
    }

    private static func component(at position: Int, _ candidate: RecognitionCandidate) -> RecognitionComponentEvidence {
        RecognitionComponentEvidence(
            position: position,
            text: candidate.text,
            shapeCost: candidate.shapeCost,
            source: candidate.evidence.source,
            rawScore: candidate.evidence.rawScore,
            rawRank: candidate.evidence.rawRank,
            isRecognizerRawTop: candidate.isRecognizerRawTop
        )
    }

    private static let perCharacterLimit = 4
    private static let resultLimit = 10
}
