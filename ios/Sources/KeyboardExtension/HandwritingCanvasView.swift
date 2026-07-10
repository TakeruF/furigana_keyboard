import UIKit

final class HandwritingCanvasView: UIView {
    var onInkChanged: (([[CGPoint]], CGSize) -> Void)?
    private(set) var strokes: [[CGPoint]] = []
    private var activeStroke: [CGPoint] = []

    override init(frame: CGRect) {
        super.init(frame: frame)
        isMultipleTouchEnabled = false
        backgroundColor = .secondarySystemBackground
        layer.cornerRadius = 15
        layer.masksToBounds = true
        isAccessibilityElement = true
        accessibilityLabel = "手書きエリア"
        accessibilityHint = "一文字を書いてください"
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func clear() {
        strokes.removeAll()
        activeStroke.removeAll()
        setNeedsDisplay()
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let point = touches.first?.location(in: self) else { return }
        activeStroke = [point]
        setNeedsDisplay()
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let touch = touches.first else { return }
        for sample in event?.coalescedTouches(for: touch) ?? [touch] {
            activeStroke.append(sample.location(in: self))
        }
        setNeedsDisplay()
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        if let point = touches.first?.location(in: self) { activeStroke.append(point) }
        if !activeStroke.isEmpty { strokes.append(activeStroke) }
        activeStroke.removeAll()
        setNeedsDisplay()
        onInkChanged?(strokes, bounds.size)
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        activeStroke.removeAll()
        setNeedsDisplay()
    }

    override func draw(_ rect: CGRect) {
        guard let context = UIGraphicsGetCurrentContext() else { return }
        context.setStrokeColor(UIColor.label.cgColor)
        context.setLineWidth(5)
        context.setLineCap(.round)
        context.setLineJoin(.round)
        for stroke in strokes + (activeStroke.isEmpty ? [] : [activeStroke]) {
            guard let first = stroke.first else { continue }
            context.beginPath()
            context.move(to: first)
            for point in stroke.dropFirst() { context.addLine(to: point) }
            if stroke.count == 1 {
                context.addLine(to: CGPoint(x: first.x + 0.1, y: first.y + 0.1))
            }
            context.strokePath()
        }
    }
}

