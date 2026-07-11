import UIKit

final class HandwritingCanvasView: UIView {
    var onInkChanged: (([[CGPoint]], CGSize) -> Void)?
    var onNewCharacterGate: (() -> Bool)?
    var hapticsEnabled = true

    private(set) var strokes: [[CGPoint]] = []
    private var activeStroke: [CGPoint] = []
    private var recognitionWork: DispatchWorkItem?
    private var resultsDelivered = false
    private var lastPoint: CGPoint?
    private var lastTime: TimeInterval = 0
    private var activeWidths: [CGFloat] = []
    private var strokeWidths: [[CGFloat]] = []

    override init(frame: CGRect) {
        super.init(frame: frame)
        isMultipleTouchEnabled = false
        backgroundColor = .secondarySystemBackground
        layer.cornerRadius = 14
        layer.masksToBounds = true
        isAccessibilityElement = true
        accessibilityLabel = AppStrings.text("handwriting")
        accessibilityHint = AppStrings.text("write_hint")
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    func markResultsDelivered() { resultsDelivered = true }

    func clear() {
        recognitionWork?.cancel()
        strokes.removeAll(); strokeWidths.removeAll()
        activeStroke.removeAll(); activeWidths.removeAll()
        resultsDelivered = false
        setNeedsDisplay()
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let touch = touches.first else { return }
        recognitionWork?.cancel()
        let point = touch.location(in: self)
        if resultsDelivered, !isClearlyStartingRightCharacter(at: point.x), onNewCharacterGate?() == true {
            clear()
        }
        resultsDelivered = false
        activeStroke = [point]
        activeWidths = [4.5]
        lastPoint = point
        lastTime = touch.timestamp
        if hapticsEnabled { UIImpactFeedbackGenerator(style: .soft).impactOccurred(intensity: 0.35) }
        setNeedsDisplay()
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let touch = touches.first else { return }
        for sample in event?.coalescedTouches(for: touch) ?? [touch] { append(sample) }
        setNeedsDisplay()
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        if let touch = touches.first { append(touch) }
        if !activeStroke.isEmpty { strokes.append(activeStroke); strokeWidths.append(activeWidths) }
        activeStroke.removeAll(); activeWidths.removeAll(); lastPoint = nil
        setNeedsDisplay()
        let work = DispatchWorkItem { [weak self] in
            guard let self, !self.strokes.isEmpty else { return }
            self.onInkChanged?(self.strokes, self.bounds.size)
        }
        recognitionWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.42, execute: work)
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        activeStroke.removeAll(); activeWidths.removeAll(); lastPoint = nil
        setNeedsDisplay()
    }

    private func append(_ touch: UITouch) {
        let point = touch.location(in: self)
        let distance = lastPoint.map { hypot(point.x - $0.x, point.y - $0.y) } ?? 0
        let elapsed = max(0.001, touch.timestamp - lastTime)
        let speed = distance / elapsed / 1_000
        let target = 6 - min(1, speed / 1.5) * 3.5
        let width = (activeWidths.last ?? 4.5) + (target - (activeWidths.last ?? 4.5)) * 0.3
        activeStroke.append(point); activeWidths.append(width)
        lastPoint = point; lastTime = touch.timestamp
    }

    private func isClearlyStartingRightCharacter(at x: CGFloat) -> Bool {
        let points = strokes.flatMap { $0 }
        guard !points.isEmpty else { return false }
        let minX = points.map(\.x).min()!, maxX = points.map(\.x).max()!
        let center = (minX + maxX) / 2
        return center < bounds.width * 0.48 && maxX < bounds.width * 0.58 &&
            x > bounds.width * 0.52 && x - maxX >= max(8, bounds.width * 0.035)
    }

    override func draw(_ rect: CGRect) {
        guard let context = UIGraphicsGetCurrentContext() else { return }
        context.saveGState()
        context.setStrokeColor(UIColor.tertiaryLabel.withAlphaComponent(0.35).cgColor)
        context.setLineWidth(1)
        context.setLineDash(phase: 0, lengths: [4, 6])
        context.move(to: CGPoint(x: bounds.midX, y: 12))
        context.addLine(to: CGPoint(x: bounds.midX, y: bounds.maxY - 12))
        context.strokePath()
        context.restoreGState()

        if strokes.isEmpty && activeStroke.isEmpty {
            let text = AppStrings.text("write_hint") as NSString
            let attributes: [NSAttributedString.Key: Any] = [
                .font: UIFont.systemFont(ofSize: 14), .foregroundColor: UIColor.tertiaryLabel
            ]
            let size = text.size(withAttributes: attributes)
            text.draw(at: CGPoint(x: bounds.midX - size.width / 2, y: bounds.midY - size.height / 2), withAttributes: attributes)
        }
        for (points, widths) in zip(strokes + (activeStroke.isEmpty ? [] : [activeStroke]),
                                    strokeWidths + (activeStroke.isEmpty ? [] : [activeWidths])) {
            guard let first = points.first else { continue }
            context.setStrokeColor(UIColor.label.cgColor)
            context.setLineCap(.round); context.setLineJoin(.round)
            if points.count == 1 {
                context.setFillColor(UIColor.label.cgColor)
                context.fillEllipse(in: CGRect(x: first.x - 2.2, y: first.y - 2.2, width: 4.4, height: 4.4))
            } else {
                for index in 1..<points.count {
                    context.setLineWidth(widths.indices.contains(index) ? widths[index] : 4.5)
                    context.beginPath(); context.move(to: points[index - 1]); context.addLine(to: points[index]); context.strokePath()
                }
            }
        }
    }
}
