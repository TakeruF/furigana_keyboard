import XCTest
@testable import FuriganaKeyboard

final class BunsetsuStateTests: XCTestCase {
    func testRangeAdjustmentAndSequentialSelection() {
        let state = BunsetsuState(reading: "にほんご", initialLength: 3)
        XCTAssertEqual(state.activeReading, "にほん")
        state.shrink()
        XCTAssertEqual(state.activeReading, "にほ")
        state.expand()
        state.select(surface: "日本")
        XCTAssertEqual(state.markedText, "日本ご")
        XCTAssertEqual(state.activeReading, "ご")
        state.select(surface: "語")
        XCTAssertTrue(state.isComplete)
        XCTAssertEqual(state.committedSurface, "日本語")
    }
}
