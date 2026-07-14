import XCTest
@testable import FuriganaKeyboard

final class RomajiConversionStateTests: XCTestCase {
    func testSpaceSelectsThenCyclesCandidates() {
        let state = RomajiConversionState()
        state.compositionEdited(hasComposition: true)

        XCTAssertEqual(state.space(candidateCount: 3), .selectCandidate(0))
        XCTAssertEqual(state.space(candidateCount: 3), .selectCandidate(1))
        XCTAssertEqual(state.space(candidateCount: 3), .selectCandidate(2))
        XCTAssertEqual(state.space(candidateCount: 3), .selectCandidate(0))
    }

    func testEnterCommitsFirstOrSelectedCandidate() {
        let state = RomajiConversionState()
        state.compositionEdited(hasComposition: true)

        XCTAssertEqual(state.enter(candidateCount: 2), .commitCandidate(0))
        XCTAssertEqual(state.space(candidateCount: 2), .selectCandidate(0))
        XCTAssertEqual(state.space(candidateCount: 2), .selectCandidate(1))
        XCTAssertEqual(state.enter(candidateCount: 2), .commitCandidate(1))
    }

    func testEmptyAndMissingCandidateActionsMatchAndroidStateContract() {
        let state = RomajiConversionState()
        XCTAssertEqual(state.space(candidateCount: 0), .insertSpace)
        XCTAssertEqual(state.enter(candidateCount: 0), .sendEditorAction)

        state.compositionEdited(hasComposition: true)
        XCTAssertEqual(state.space(candidateCount: 0), .commitComposition)
        XCTAssertEqual(state.enter(candidateCount: 0), .commitComposition)
    }
}
