import CoreGraphics
import Foundation

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
            let segments = HandwritingStrokeSegmenter.split(strokes: strokes, canvasSize: canvasSize)
            let raw: [(text: String, score: Float)]
            if segments.count == 2 {
                let left = self.recognize(engine: engine, strokes: segments[0].strokes, size: segments[0].size, limit: 4)
                let right = self.recognize(engine: engine, strokes: segments[1].strokes, size: segments[1].size, limit: 4)
                raw = left.flatMap { lhs in right.map { rhs in
                    (lhs.text + rhs.text, (lhs.score + rhs.score) / 2)
                }}.sorted { $0.score > $1.score }
            } else {
                raw = self.recognize(engine: engine, strokes: strokes, size: canvasSize, limit: 12)
            }
            var seen = Set<String>()
            let unique = raw.filter { seen.insert($0.text).inserted }
            let ranked = self.rankCommonKanji(unique)
            let candidates = ranked.prefix(10).map { result in
                RecognitionCandidate(text: result.text,
                                     reading: self.readings?.primaryReading(for: result.text),
                                     score: result.score)
            }
            DispatchQueue.main.async {
                guard request == self.generation else { return }
                completion(candidates)
            }
        }
    }

    func cancel() {
        generation += 1
    }

    private func recognize(engine: FKZinniaRecognizer, strokes: [[CGPoint]], size: CGSize, limit: Int) -> [(text: String, score: Float)] {
        let values = strokes.map { $0.map(NSValue.init(cgPoint:)) }
        return engine.recognizeStrokes(values, width: max(1, Int(size.width.rounded())),
                                       height: max(1, Int(size.height.rounded())), limit: limit)
            .map { ($0.text, $0.score) }
    }

    private func rankCommonKanji(_ values: [(text: String, score: Float)]) -> [(text: String, score: Float)] {
        guard let readings else { return values }
        let priorities = readings.priorities(for: values.map(\.text))
        return values.enumerated().sorted { lhs, rhs in
            func bonus(_ text: String) -> Float {
                guard let value = priorities[text] else { return 0 }
                let category: Float = (1...8).contains(value.grade) ? 0.012 : (value.grade == 9 ? 0.003 : 0)
                let frequency: Float = value.frequency <= 500 ? 0.009 : (value.frequency <= 1_000 ? 0.006 : (value.frequency <= 2_500 ? 0.003 : 0))
                return category + frequency
            }
            let left = lhs.element.score + bonus(lhs.element.text)
            let right = rhs.element.score + bonus(rhs.element.text)
            return left == right ? lhs.offset < rhs.offset : left > right
        }.map(\.element)
    }

}
