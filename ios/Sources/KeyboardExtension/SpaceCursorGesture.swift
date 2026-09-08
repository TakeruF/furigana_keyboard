import CoreGraphics
import Foundation

/// Touch-framework-independent state machine that converts horizontal movement into abstract
/// cursor steps. It deliberately has no knowledge of text, Unicode, or grapheme boundaries.
final class SpaceCursorGesture {
    struct Config: Equatable {
        let longPressDuration: TimeInterval
        let activationDistance: CGFloat
        let cursorStepDistance: CGFloat

        init(longPressDuration: TimeInterval, activationDistance: CGFloat, cursorStepDistance: CGFloat) {
            precondition(longPressDuration > 0)
            precondition(activationDistance > 0 && activationDistance.isFinite)
            precondition(cursorStepDistance > 0 && cursorStepDistance.isFinite)
            self.longPressDuration = longPressDuration
            self.activationDistance = activationDistance
            self.cursorStepDistance = cursorStepDistance
        }
    }

    enum Effect: Equatable {
        case tap
        case cursorModeStarted
        case cursorStep(deltaInSteps: Int)
    }

    private enum Phase { case idle, pressed, cursor }

    let config: Config
    private var phase: Phase = .idle
    private var downTime: TimeInterval = 0
    private var downX: CGFloat = 0
    private var lastX: CGFloat = 0
    private var cursorAnchorX: CGFloat = 0
    private var tapEligible = false

    init(config: Config) {
        self.config = config
    }

    var isCursorMode: Bool { phase == .cursor }
    var isTracking: Bool { phase != .idle }

    func onDown(x: CGFloat, time: TimeInterval, insideKey: Bool = true) -> [Effect] {
        phase = .pressed
        downTime = time
        downX = x
        lastX = x
        cursorAnchorX = x
        tapEligible = insideKey
        return []
    }

    func onMove(x: CGFloat, time: TimeInterval, insideKey: Bool) -> [Effect] {
        guard phase != .idle else { return [] }
        lastX = x
        if !insideKey || abs(x - downX) >= config.activationDistance { tapEligible = false }
        let started = maybeStartCursor(time: time)
        if !started.isEmpty { return started }
        return cursorStepIfNeeded()
    }

    /// Called by the view's delayed work so an early drag can activate after the hold time.
    func onLongPressTimeout(time: TimeInterval) -> [Effect] {
        guard phase == .pressed else { return [] }
        return maybeStartCursor(time: time)
    }

    func onUp(x: CGFloat, time: TimeInterval, insideKey: Bool) -> [Effect] {
        guard phase != .idle else { return [] }
        lastX = x
        if !insideKey || abs(x - downX) >= config.activationDistance { tapEligible = false }
        var effects = maybeStartCursor(time: time)
        if phase == .cursor {
            effects += cursorStepIfNeeded()
        } else if tapEligible, insideKey, time - downTime < config.longPressDuration {
            effects.append(.tap)
        }
        reset()
        return effects
    }

    @discardableResult
    func onCancel() -> [Effect] {
        reset()
        return []
    }

    /// A second touch invalidates the complete gesture; its later lift cannot become a tap.
    @discardableResult
    func onAdditionalTouch() -> [Effect] { onCancel() }

    private func maybeStartCursor(time: TimeInterval) -> [Effect] {
        guard phase == .pressed,
              time - downTime >= config.longPressDuration,
              abs(lastX - downX) >= config.activationDistance else { return [] }
        phase = .cursor
        cursorAnchorX = lastX
        tapEligible = false
        return [.cursorModeStarted]
    }

    private func cursorStepIfNeeded() -> [Effect] {
        guard phase == .cursor else { return [] }
        let deltaInSteps = Int((lastX - cursorAnchorX) / config.cursorStepDistance)
        guard deltaInSteps != 0 else { return [] }
        cursorAnchorX += CGFloat(deltaInSteps) * config.cursorStepDistance
        return [.cursorStep(deltaInSteps: deltaInSteps)]
    }

    private func reset() {
        phase = .idle
        tapEligible = false
    }
}
