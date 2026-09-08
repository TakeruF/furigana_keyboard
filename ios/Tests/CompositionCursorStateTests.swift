import XCTest

final class CompositionCursorStateTests: XCTestCase {
    func testAResolvedPrefixBecomesAnExplicitPendingRange() {
        let state = CompositionCursorState.create(displayText: "かk", resolvedPrefix: "か")

        XCTAssertEqual(state.pendingRange, CompositionScalarRange(start: 1, end: 2))
        XCTAssertEqual(state.pendingDisplay, "k")
        XCTAssertEqual(state.cursorScalar, 1)
        XCTAssertEqual(state.resolvedScalarEnd, 1)
        XCTAssertFalse(state.canRequestDictionary)
    }

    func testFullyResolvedTextHasNoPendingRange() {
        let state = CompositionCursorState.create(displayText: "かな")

        XCTAssertNil(state.pendingRange)
        XCTAssertEqual(state.cursorScalar, 2)
        XCTAssertTrue(state.canRequestDictionary)
    }

    // MARK: - Grapheme movement

    func testCursorMovesBySupplementaryPlaneCharacterAsOneUnit() {
        let state = CompositionCursorState.create(displayText: "あ\u{20B9F}い")

        XCTAssertEqual(state.scalarLength, 3)
        XCTAssertEqual(state.cursorUTF16, 4)
        XCTAssertTrue(state.moveCursorByGrapheme(-1).cursorChanged)
        XCTAssertEqual(state.cursorScalar, 2)
        XCTAssertTrue(state.moveCursorByGrapheme(-1).cursorChanged)
        XCTAssertEqual(state.cursorScalar, 1)
        XCTAssertEqual(state.cursorUTF16, 1)
    }

    func testCursorMovesOverACombiningDakutenAsOneUnit() {
        let state = CompositionCursorState.create(displayText: "か\u{304B}\u{3099}")

        XCTAssertEqual(state.scalarLength, 3)
        XCTAssertTrue(state.moveCursorByGrapheme(-1).cursorChanged)
        XCTAssertEqual(state.cursorScalar, 1)
    }

    func testCursorMovesOverAZeroWidthJoinerSequenceAsOneUnit() {
        let state = CompositionCursorState.create(displayText: "あ👨‍👩‍👧")

        XCTAssertTrue(state.moveCursorByGrapheme(-1).cursorChanged)
        XCTAssertEqual(state.cursorScalar, 1)
    }

    func testCursorMovementClampsAtBothEnds() {
        let state = CompositionCursorState.create(displayText: "かな")

        XCTAssertTrue(state.moveCursorByGrapheme(-9).cursorChanged)
        XCTAssertEqual(state.cursorScalar, 0)
        XCTAssertFalse(state.moveCursorByGrapheme(-1).cursorChanged)
        XCTAssertTrue(state.moveCursorByGrapheme(9).cursorChanged)
        XCTAssertEqual(state.cursorScalar, 2)
    }

    func testCursorNeverEntersPendingInput() {
        let state = CompositionCursorState.create(displayText: "かsh", resolvedPrefix: "か")

        let moved = state.moveCursorByGrapheme(-1)

        XCTAssertEqual(moved, .rejected(state.snapshot(), reason: .pendingInputActive))
        XCTAssertEqual(state.cursorScalar, 1)
    }

    // MARK: - Editing around the cursor

    func testDeletingAGraphemeNeverSplitsASurrogatePair() {
        let state = CompositionCursorState.create(displayText: "あ\u{20B9F}")

        XCTAssertTrue(state.deleteGraphemeBeforeCursor().compositionChanged)

        XCTAssertEqual(state.text, "あ")
        XCTAssertEqual(state.cursorScalar, 1)
    }

    func testDeletingAtTheStartChangesNothing() {
        let state = CompositionCursorState.create(displayText: "かな", cursorScalar: 0)

        let result = state.deleteGraphemeBeforeCursor()

        XCTAssertTrue(result.isApplied)
        XCTAssertFalse(result.compositionChanged)
        XCTAssertEqual(state.text, "かな")
    }

    func testInsertingAtAnInteriorCursorPreservesTheSuffix() {
        let state = CompositionCursorState.create(displayText: "かな", cursorScalar: 1)

        state.insertAtCursor("き")

        XCTAssertEqual(state.text, "かきな")
        XCTAssertEqual(state.cursorScalar, 2)
    }

    func testReplacingThePrefixPreservesTheExactSuffix() {
        let state = CompositionCursorState.create(displayText: "わたしはがくせい", cursorScalar: 4)

        state.replacePrefixAtCursor("私は")

        XCTAssertEqual(state.text, "私はがくせい")
        XCTAssertEqual(state.cursorScalar, 2)
    }

    // MARK: - Pending romaji as a middle range

    func testPendingInputInTheMiddlePreservesBothSides() {
        let state = CompositionCursorState.create(displayText: "かな", cursorScalar: 1)

        state.replacePendingDisplay("k")

        XCTAssertEqual(state.text, "かkな")
        XCTAssertEqual(state.pendingRange, CompositionScalarRange(start: 1, end: 2))
        XCTAssertEqual(state.cursorScalar, 2)
        XCTAssertEqual(state.slice().resolvedSuffix, "な")
    }

    func testResolvingPendingInputPreservesBothSides() {
        let state = CompositionCursorState.create(displayText: "かな", cursorScalar: 1)
        state.replacePendingDisplay("k")

        state.resolvePendingDisplay("き")

        XCTAssertEqual(state.text, "かきな")
        XCTAssertNil(state.pendingRange)
        XCTAssertEqual(state.cursorScalar, 2)
        XCTAssertTrue(state.canRequestDictionary)
    }

    func testResolvingWithoutPendingInputIsRejected() {
        let state = CompositionCursorState.create(displayText: "かな")

        XCTAssertEqual(
            state.resolvePendingDisplay("き"),
            .rejected(state.snapshot(), reason: .pendingRangeMismatch)
        )
    }

    func testAnEditIntersectingPendingInputIsRejected() {
        let state = CompositionCursorState.create(displayText: "かな", cursorScalar: 1)
        state.replacePendingDisplay("sh")

        let result = state.replaceScalarRange(from: 0, to: 2, with: "")

        XCTAssertEqual(result, .rejected(state.snapshot(), reason: .unresolvedBoundary))
        XCTAssertEqual(state.text, "かshな")
    }

    func testAnEditLeftOfPendingInputShiftsItsRange() {
        let state = CompositionCursorState.create(displayText: "かな", cursorScalar: 2)
        state.replacePendingDisplay("k")
        XCTAssertEqual(state.pendingRange, CompositionScalarRange(start: 2, end: 3))

        state.replaceScalarRange(from: 0, to: 1, with: "")

        XCTAssertEqual(state.text, "なk")
        XCTAssertEqual(state.pendingRange, CompositionScalarRange(start: 1, end: 2))
    }

    // MARK: - Revisions

    func testCompositionAndCursorRevisionsAdvanceIndependently() {
        let state = CompositionCursorState.create(displayText: "かな")
        let start = state.revision

        state.moveCursorByGrapheme(-1)
        let afterCursor = state.revision
        XCTAssertEqual(afterCursor.composition, start.composition)
        XCTAssertEqual(afterCursor.cursor, start.cursor + 1)

        state.insertAtCursor("き")
        XCTAssertEqual(state.revision.composition, start.composition + 1)
    }

    func testRawInputChangingToTheSameDisplayStillAdvancesTheCompositionRevision() {
        let state = CompositionCursorState.create(displayText: "か", resolvedPrefix: "")
        let before = state.revision.composition

        state.replacePendingDisplay("か", logicalInputChanged: true)

        XCTAssertEqual(state.text, "か")
        XCTAssertEqual(state.revision.composition, before + 1)
    }

    func testAStaleRevisionIsRejectedWithoutChangingAnything() {
        let state = CompositionCursorState.create(displayText: "かな")
        let stale = state.revision
        state.insertAtCursor("き")

        let result = state.insertAtCursor("く", expected: stale)

        XCTAssertEqual(result, .rejected(state.snapshot(), reason: .staleRevision))
        XCTAssertEqual(state.text, "かなき")
    }

    // MARK: - Offset conversion

    func testUTF16OffsetsSplittingASurrogatePairAreRejected() {
        let state = CompositionCursorState.create(displayText: "\u{20B9F}")

        XCTAssertEqual(state.utf16ToScalar(0), 0)
        XCTAssertNil(state.utf16ToScalar(1))
        XCTAssertEqual(state.utf16ToScalar(2), 1)
        XCTAssertNil(state.utf16ToScalar(3))
        XCTAssertEqual(state.scalarToUTF16(1), 2)
        XCTAssertNil(state.scalarToUTF16(2))
    }

    func testSliceExposesTheTextOnBothSidesOfTheCursor() {
        let state = CompositionCursorState.create(displayText: "わたしはがくせい", cursorScalar: 4)

        let slice = state.slice()

        XCTAssertEqual(slice.prefix, "わたしは")
        XCTAssertEqual(slice.suffix, "がくせい")
        XCTAssertEqual(slice.resolvedSuffix, "がくせい")
        XCTAssertEqual(slice.unresolvedSuffix, "")
    }
}
