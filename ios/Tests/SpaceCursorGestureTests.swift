import CoreGraphics
import XCTest

final class SpaceCursorGestureTests: XCTestCase {
    private let config = SpaceCursorGesture.Config(
        longPressDuration: 0.5,
        activationDistance: 10,
        cursorStepDistance: 12
    )

    private func gesture() -> SpaceCursorGesture { SpaceCursorGesture(config: config) }

    func testAQuickPressInsideTheKeyIsATap() {
        let gesture = gesture()

        XCTAssertTrue(gesture.onDown(x: 50, time: 0).isEmpty)
        XCTAssertEqual(gesture.onUp(x: 52, time: 0.1, insideKey: true), [.tap])
        XCTAssertFalse(gesture.isTracking)
    }

    func testHoldingWithoutMovingNeverEntersCursorMode() {
        let gesture = gesture()
        _ = gesture.onDown(x: 50, time: 0)

        XCTAssertTrue(gesture.onLongPressTimeout(time: 1).isEmpty)
        XCTAssertFalse(gesture.isCursorMode)
        XCTAssertEqual(gesture.onUp(x: 50, time: 1.1, insideKey: true), [])
    }

    func testDraggingWithoutHoldingNeverEntersCursorMode() {
        let gesture = gesture()
        _ = gesture.onDown(x: 50, time: 0)

        XCTAssertTrue(gesture.onMove(x: 200, time: 0.1, insideKey: true).isEmpty)
        XCTAssertFalse(gesture.isCursorMode)
    }

    func testHoldingAndDraggingEntersCursorModeAndEmitsSteps() {
        let gesture = gesture()
        _ = gesture.onDown(x: 50, time: 0)
        _ = gesture.onMove(x: 62, time: 0.2, insideKey: true)

        XCTAssertEqual(gesture.onLongPressTimeout(time: 0.6), [.cursorModeStarted])
        XCTAssertTrue(gesture.isCursorMode)
        XCTAssertEqual(gesture.onMove(x: 86, time: 0.7, insideKey: true), [.cursorStep(deltaInSteps: 2)])
        XCTAssertEqual(gesture.onMove(x: 62, time: 0.8, insideKey: true), [.cursorStep(deltaInSteps: -2)])
    }

    func testASubStepDragEmitsNothingAndDoesNotLoseItsRemainder() {
        let gesture = gesture()
        _ = gesture.onDown(x: 50, time: 0)
        _ = gesture.onMove(x: 62, time: 0.2, insideKey: true)
        _ = gesture.onLongPressTimeout(time: 0.6)

        XCTAssertTrue(gesture.onMove(x: 70, time: 0.7, insideKey: true).isEmpty)
        XCTAssertEqual(gesture.onMove(x: 74, time: 0.8, insideKey: true), [.cursorStep(deltaInSteps: 1)])
    }

    func testACursorDragNeverEndsAsATap() {
        let gesture = gesture()
        _ = gesture.onDown(x: 50, time: 0)
        _ = gesture.onMove(x: 62, time: 0.2, insideKey: true)
        _ = gesture.onLongPressTimeout(time: 0.6)

        XCTAssertFalse(gesture.onUp(x: 62, time: 0.7, insideKey: true).contains(.tap))
    }

    func testLeavingTheKeyCancelsTheTap() {
        let gesture = gesture()
        _ = gesture.onDown(x: 50, time: 0)
        _ = gesture.onMove(x: 52, time: 0.1, insideKey: false)

        XCTAssertEqual(gesture.onUp(x: 52, time: 0.2, insideKey: true), [])
    }

    func testASecondTouchInvalidatesTheWholeGesture() {
        let gesture = gesture()
        _ = gesture.onDown(x: 50, time: 0)

        gesture.onAdditionalTouch()

        XCTAssertFalse(gesture.isTracking)
        XCTAssertEqual(gesture.onUp(x: 50, time: 0.1, insideKey: true), [])
    }

    func testEventsBeforeATouchDownAreIgnored() {
        let gesture = gesture()

        XCTAssertTrue(gesture.onMove(x: 50, time: 0, insideKey: true).isEmpty)
        XCTAssertTrue(gesture.onUp(x: 50, time: 0, insideKey: true).isEmpty)
        XCTAssertTrue(gesture.onLongPressTimeout(time: 1).isEmpty)
    }
}
