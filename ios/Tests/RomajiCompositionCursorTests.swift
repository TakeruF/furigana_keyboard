import XCTest

final class RomajiCompositionCursorTests: XCTestCase {
    private func editing(_ display: String, cursorFromEnd: Int = 0) -> (RomajiCompositionCursor, RomajiCompositionEditor) {
        let cursor = RomajiCompositionCursor()
        cursor.replace(display: display, resolvedPrefix: display)
        if cursorFromEnd > 0 { XCTAssertTrue(cursor.moveCursorByGrapheme(-cursorFromEnd)) }
        return (cursor, RomajiCompositionEditor(cursor: cursor))
    }

    func testMiddleInsertResolveAndDeletePreserveTheRightSuffix() {
        let (cursor, editor) = editing("かな", cursorFromEnd: 1)

        XCTAssertTrue(editor.append("k").isApplied)
        XCTAssertEqual(cursor.displayText, "かkな")
        XCTAssertNil(cursor.conversionSlice(), "pending romaji must not reach the dictionary")

        XCTAssertTrue(editor.append("i").isApplied)
        XCTAssertEqual(cursor.displayText, "かきな")
        XCTAssertEqual(cursor.conversionSlice()?.request.reading, "かき")
        XCTAssertEqual(cursor.conversionSlice()?.resolvedSuffix, "な")
        XCTAssertEqual(cursor.conversionSlice()?.isWholeComposition, false)

        XCTAssertTrue(editor.deleteBeforeCursor().isApplied)
        XCTAssertEqual(cursor.displayText, "かな")
        XCTAssertEqual(cursor.conversionSlice()?.request.reading, "か")
        XCTAssertEqual(cursor.conversionSlice()?.resolvedSuffix, "な")
    }

    func testUnresolvedRawDeletionNeverConsumesTheResolvedSuffix() {
        let (cursor, editor) = editing("かな", cursorFromEnd: 1)

        XCTAssertTrue(editor.append("s").isApplied)
        XCTAssertTrue(editor.append("h").isApplied)
        XCTAssertEqual(cursor.displayText, "かshな")

        XCTAssertTrue(editor.deleteBeforeCursor().isApplied)
        XCTAssertEqual(cursor.displayText, "かsな")
        XCTAssertTrue(editor.deleteBeforeCursor().isApplied)
        XCTAssertEqual(cursor.displayText, "かな")
        XCTAssertEqual(cursor.pendingEdit, nil)
    }

    func testDeletingPastTheLastRawUnitFallsBackToGraphemeDeletion() {
        let (cursor, editor) = editing("か")

        XCTAssertTrue(editor.append("s").isApplied)
        XCTAssertEqual(cursor.displayText, "かs")
        XCTAssertTrue(editor.deleteBeforeCursor().isApplied)
        XCTAssertEqual(cursor.displayText, "か")
        XCTAssertTrue(editor.deleteBeforeCursor().isApplied)
        XCTAssertEqual(cursor.displayText, "")
        XCTAssertFalse(cursor.isActive)
    }

    func testAWholeCompositionSliceIsMarkedAsSuch() {
        let (cursor, _) = editing("かな")

        let slice = cursor.conversionSlice()

        XCTAssertEqual(slice?.request.reading, "かな")
        XCTAssertEqual(slice?.resolvedSuffix, "")
        XCTAssertEqual(slice?.isWholeComposition, true)
    }

    func testAnEmptyCompositionProducesNoConversionRequest() {
        let cursor = RomajiCompositionCursor()

        XCTAssertNil(cursor.conversionSlice())
        XCTAssertFalse(cursor.isActive)
    }

    func testACursorMoveInvalidatesAnInFlightConversionRequest() {
        let (cursor, _) = editing("かな")
        let request = try? XCTUnwrap(cursor.conversionSlice()?.request)

        XCTAssertEqual(cursor.isCurrent(request!), true)
        XCTAssertTrue(cursor.moveCursorByGrapheme(-1))
        XCTAssertFalse(cursor.isCurrent(request!))
    }

    func testAnEditInvalidatesAnInFlightConversionRequest() {
        let (cursor, editor) = editing("かな")
        let request = try? XCTUnwrap(cursor.conversionSlice()?.request)

        _ = editor.append("k")

        XCTAssertFalse(cursor.isCurrent(request!))
    }

    func testTheMarkedTextSelectionFollowsTheCursorInUTF16() {
        let (cursor, _) = editing("あ\u{20B9F}い")

        XCTAssertEqual(cursor.markedTextSelectedRange, NSRange(location: 4, length: 0))
        XCTAssertTrue(cursor.moveCursorByGrapheme(-1))
        XCTAssertEqual(cursor.markedTextSelectedRange, NSRange(location: 3, length: 0))
        XCTAssertTrue(cursor.moveCursorByGrapheme(-1))
        XCTAssertEqual(cursor.markedTextSelectedRange, NSRange(location: 1, length: 0))
    }

    func testClearingEndsTheCompositionWithoutReusingStaleRequests() {
        let (cursor, _) = editing("かな")
        let request = try? XCTUnwrap(cursor.conversionSlice()?.request)

        cursor.clear()

        XCTAssertFalse(cursor.isActive)
        XCTAssertFalse(cursor.isCurrent(request!))
    }
}
