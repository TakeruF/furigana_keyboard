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
        let values = strokes.map { $0.map(NSValue.init(cgPoint:)) }
        let width = max(1, Int(canvasSize.width.rounded()))
        let height = max(1, Int(canvasSize.height.rounded()))

        queue.async { [weak self] in
            guard let self, let engine = self.engine else {
                DispatchQueue.main.async { completion([]) }
                return
            }
            var seen = Set<String>()
            let candidates = engine.recognizeStrokes(values, width: width, height: height, limit: 10)
                .compactMap { result -> RecognitionCandidate? in
                    guard seen.insert(result.text).inserted else { return nil }
                    return RecognitionCandidate(
                        text: result.text,
                        reading: self.readings?.primaryReading(for: result.text),
                        score: result.score
                    )
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
}

