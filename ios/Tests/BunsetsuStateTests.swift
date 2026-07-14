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

    func testFullCommitKeepsOriginalPathAndPreviousRightPos() {
        let state = contextualState()

        XCTAssertTrue(state.select(surface: "私も", rightID: 5))
        XCTAssertEqual(state.reading, "わたしもなぞをよむ")
        XCTAssertEqual(state.activeReading, "なぞを")
        XCTAssertEqual(state.previousRightID, 5)
        XCTAssertEqual(state.previousContextSurface, "私")
        XCTAssertEqual(state.remainingSegmentCandidates.map(\.reading), ["なぞ", "を", "よむ"])

        XCTAssertTrue(state.select(surface: "謎を", rightID: 5))
        XCTAssertEqual(state.activeReading, "よむ")
        XCTAssertEqual(state.token().previousContextSurface, "謎")
        XCTAssertTrue(state.select(surface: "読む", rightID: 7))

        XCTAssertTrue(state.isComplete)
        XCTAssertEqual(state.committedSurface, "私も謎を読む")
    }

    func testDeletionKeepsCommittedContextAndDoesNotRestartComposition() {
        let state = contextualState()
        XCTAssertTrue(state.select(surface: "私も", rightID: 5))

        XCTAssertTrue(state.deleteLastScalar())

        XCTAssertEqual(state.remainingReading, "なぞをよ")
        XCTAssertEqual(state.committedSurface, "私も")
        XCTAssertEqual(state.previousRightID, 5)
        XCTAssertEqual(state.activeReading, "なぞを")
    }

    func testRangeChangeInvalidatesOldAnalysisAndInstallsForcedFullPath() {
        let state = contextualState()
        let stale = state.token()

        state.shrink()
        XCTAssertEqual(state.activeReading, "わたし")
        XCTAssertFalse(state.isCurrent(stale))

        let current = state.token()
        let conversion = KanaKanjiConversion(
            surface: "私も謎を読む",
            reading: state.remainingReading,
            cost: 0,
            segments: contextualSegments()
        )
        XCTAssertTrue(state.applyAnalysis(
            [conversion],
            token: current,
            requestedBoundary: 3
        ))
        XCTAssertEqual(state.activeReading, "わたし")
        XCTAssertEqual(state.remainingSegmentCandidates.map(\.reading),
                       ["わたし", "も", "なぞ", "を", "よむ"])
    }

    func testEmptyUnknownRemainderKeepsNaturalBoundaryInsteadOfOneCharacter() {
        let state = contextualState()
        XCTAssertTrue(state.select(surface: "私も", rightID: 5))
        let token = state.token()

        XCTAssertFalse(state.applyAnalysis([], token: token, requestedBoundary: nil))

        XCTAssertTrue(state.isCurrent(token))
        XCTAssertEqual(state.activeReading, "なぞを")
        XCTAssertEqual(state.options().first?.surface, "謎を")
        XCTAssertGreaterThan(state.activeLength, 1)
    }

    func testRapidCandidateTapCannotReuseAnOldGeneration() {
        let state = contextualState()
        let oldGeneration = state.generation
        let oldToken = state.token()

        XCTAssertTrue(state.select(surface: "私も", rightID: 5))

        XCTAssertFalse(state.isCurrentCandidate(
            reading: "わたしも",
            generation: oldGeneration
        ))
        XCTAssertFalse(state.isCurrent(oldToken))
        XCTAssertEqual(state.activeReading, "なぞを")
    }

    private func contextualState() -> BunsetsuState {
        BunsetsuState(
            reading: "わたしもなぞをよむ",
            initialLength: 4,
            conversionPaths: [contextualSegments()]
        )
    }

    private func contextualSegments() -> [ConversionSegment] {
        [
            segment(0, 3, "わたし", "私", 2),
            segment(3, 4, "も", "も", 5),
            segment(4, 6, "なぞ", "謎", 3),
            segment(6, 7, "を", "を", 5),
            segment(7, 9, "よむ", "読む", 7)
        ]
    }

    private func segment(
        _ start: Int,
        _ end: Int,
        _ reading: String,
        _ surface: String,
        _ pos: Int
    ) -> ConversionSegment {
        ConversionSegment(
            start: start,
            end: end,
            reading: reading,
            surface: surface,
            leftID: pos,
            rightID: pos,
            wordCost: 0,
            isCopy: false
        )
    }
}
