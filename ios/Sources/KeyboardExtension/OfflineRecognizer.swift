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
            let segments = Self.split(strokes: strokes, canvasSize: canvasSize)
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

    private struct InkSegment { let strokes: [[CGPoint]]; let size: CGSize }

    /// Split only when stroke order and geometry clearly describe left/right characters.
    private static func split(strokes: [[CGPoint]], canvasSize: CGSize) -> [InkSegment] {
        let nonempty = strokes.filter { !$0.isEmpty }
        guard nonempty.count >= 2, canvasSize.width > 1 else {
            return [InkSegment(strokes: strokes, size: canvasSize)]
        }
        func bounds(_ values: ArraySlice<[CGPoint]>) -> CGRect? {
            let points = values.flatMap { $0 }
            guard let first = points.first else { return nil }
            return points.dropFirst().reduce(CGRect(origin: first, size: .zero)) { $0.union(CGRect(origin: $1, size: .zero)) }
        }
        var best: (cut: Int, score: CGFloat)?
        for cut in 1..<nonempty.count {
            guard let left = bounds(nonempty[..<cut]), let right = bounds(nonempty[cut...]) else { continue }
            let gap = right.minX - left.maxX
            let centerDistance = right.midX - left.midX
            let valid = left.midX < canvasSize.width * 0.48 && right.midX > canvasSize.width * 0.52 &&
                gap >= max(8, canvasSize.width * 0.035) &&
                right.maxX - left.minX >= canvasSize.width * 0.48 &&
                centerDistance >= canvasSize.width * 0.25
            let score = gap + centerDistance * 0.25
            if valid, best == nil || score > best!.score { best = (cut, score) }
        }
        guard let cut = best?.cut else { return [InkSegment(strokes: strokes, size: canvasSize)] }
        func crop(_ values: ArraySlice<[CGPoint]>) -> InkSegment {
            let box = bounds(values)!
            let content = max(box.width, box.height, 1)
            let padding = max(4, content * 0.08)
            let side = content + padding * 2
            let offset = CGPoint(x: padding + (content - box.width) / 2 - box.minX,
                                 y: padding + (content - box.height) / 2 - box.minY)
            return InkSegment(strokes: values.map { stroke in
                stroke.map { CGPoint(x: $0.x + offset.x, y: $0.y + offset.y) }
            }, size: CGSize(width: side, height: side))
        }
        return [crop(nonempty[..<cut]), crop(nonempty[cut...])]
    }
}
