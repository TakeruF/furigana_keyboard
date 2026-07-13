import CoreGraphics

struct HandwritingInkSegment {
    let strokes: [[CGPoint]]
    let size: CGSize
}

/// Shared left/right character detection for recognition and handwritten deletion.
enum HandwritingStrokeSegmenter {
    static func split(strokes: [[CGPoint]], canvasSize: CGSize) -> [HandwritingInkSegment] {
        let nonempty = strokes.filter { !$0.isEmpty }
        guard let cut = splitIndex(strokes: nonempty, canvasSize: canvasSize) else {
            return [HandwritingInkSegment(strokes: strokes, size: canvasSize)]
        }
        return [crop(nonempty[..<cut]), crop(nonempty[cut...])]
    }

    /// Return the remaining strokes after deleting the right character, or all ink for one character.
    static func removingLastCharacter(
        from strokes: [[CGPoint]],
        canvasSize: CGSize
    ) -> [[CGPoint]]? {
        let nonempty = strokes.filter { !$0.isEmpty }
        guard !nonempty.isEmpty else { return nil }
        guard let cut = splitIndex(strokes: nonempty, canvasSize: canvasSize) else { return [] }
        return Array(nonempty[..<cut])
    }

    static func isSecondCharacterStart(
        strokes: [[CGPoint]],
        canvasWidth: CGFloat,
        x: CGFloat
    ) -> Bool {
        guard let current = bounds(strokes[...]) else { return false }
        let width = max(1, canvasWidth)
        return current.midX < width * 0.48 && current.maxX < width * 0.58 &&
            x > width * 0.52 && x - current.maxX >= max(8, width * 0.035)
    }

    private static func splitIndex(strokes: [[CGPoint]], canvasSize: CGSize) -> Int? {
        guard strokes.count >= 2, canvasSize.width > 1 else { return nil }
        var best: (cut: Int, score: CGFloat)?
        for cut in 1..<strokes.count {
            guard let left = bounds(strokes[..<cut]), let right = bounds(strokes[cut...]) else { continue }
            let gap = right.minX - left.maxX
            let centerDistance = right.midX - left.midX
            let valid = left.midX < canvasSize.width * 0.48 && right.midX > canvasSize.width * 0.52 &&
                gap >= max(8, canvasSize.width * 0.035) &&
                right.maxX - left.minX >= canvasSize.width * 0.48 &&
                centerDistance >= canvasSize.width * 0.25
            let score = gap + centerDistance * 0.25
            if valid, best == nil || score > best!.score { best = (cut, score) }
        }
        return best?.cut
    }

    private static func crop(_ values: ArraySlice<[CGPoint]>) -> HandwritingInkSegment {
        let box = bounds(values)!
        let content = max(box.width, box.height, 1)
        let padding = max(4, content * 0.08)
        let side = content + padding * 2
        let offset = CGPoint(
            x: padding + (content - box.width) / 2 - box.minX,
            y: padding + (content - box.height) / 2 - box.minY
        )
        return HandwritingInkSegment(
            strokes: values.map { stroke in
                stroke.map { CGPoint(x: $0.x + offset.x, y: $0.y + offset.y) }
            },
            size: CGSize(width: side, height: side)
        )
    }

    private static func bounds(_ values: ArraySlice<[CGPoint]>) -> CGRect? {
        let points = values.flatMap { $0 }
        guard let first = points.first else { return nil }
        return points.dropFirst().reduce(CGRect(origin: first, size: .zero)) {
            $0.union(CGRect(origin: $1, size: .zero))
        }
    }
}
