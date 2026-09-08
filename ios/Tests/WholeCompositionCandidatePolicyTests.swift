import XCTest

final class WholeCompositionCandidatePolicyTests: XCTestCase {
    private let fallbacks = [
        WordCandidate(surface: "かんじ", readings: ["かんじ"]),
        WordCandidate(surface: "カンジ", readings: ["かんじ"])
    ]

    func testWholeConversionsPrecedePredictionsAndFallbacks() {
        let built = WholeCompositionCandidatePolicy.build(
            reading: "かんじ",
            dictionaryCandidates: [
                WordCandidate(surface: "感じる", readings: ["かんじる"]),
                WordCandidate(surface: "漢字", readings: ["かんじ"])
            ],
            scriptFallbacks: fallbacks,
            limit: 8
        )

        XCTAssertEqual(built.map(\.surface), ["漢字", "感じる", "かんじ", "カンジ"])
        XCTAssertEqual(built.map(\.kind), [.wholeConversion, .prediction, .scriptFallback, .scriptFallback])
    }

    func testACandidateSupportedOnlyByAStrictReadingPrefixIsExcluded() {
        let built = WholeCompositionCandidatePolicy.build(
            reading: "かんじ",
            dictionaryCandidates: [WordCandidate(surface: "мочь", readings: ["かん"])],
            scriptFallbacks: fallbacks,
            limit: 8
        )

        XCTAssertEqual(built.map(\.surface), ["かんじ", "カンジ"])
    }

    func testRejectedEntriesDoNotConsumeDisplaySlots() {
        let dictionary = (0..<8).map { WordCandidate(surface: "部分\($0)", readings: ["かん"]) } +
            [WordCandidate(surface: "漢字", readings: ["かんじ"])]

        let built = WholeCompositionCandidatePolicy.build(
            reading: "かんじ",
            dictionaryCandidates: dictionary,
            scriptFallbacks: fallbacks,
            limit: 3
        )

        XCTAssertEqual(built.map(\.surface), ["漢字", "かんじ", "カンジ"])
    }

    func testDuplicateSurfacesKeepTheirStrongestCategory() {
        let built = WholeCompositionCandidatePolicy.build(
            reading: "かんじ",
            dictionaryCandidates: [
                WordCandidate(surface: "漢字", readings: ["かんじや"]),
                WordCandidate(surface: "漢字", readings: ["かんじ"])
            ],
            scriptFallbacks: [],
            limit: 8
        )

        XCTAssertEqual(built.map(\.surface), ["漢字"])
        XCTAssertEqual(built.first?.kind, .wholeConversion)
    }

    func testAnEmptyReadingOrZeroLimitProducesNothing() {
        XCTAssertTrue(
            WholeCompositionCandidatePolicy.build(
                reading: "", dictionaryCandidates: [], scriptFallbacks: fallbacks, limit: 8
            ).isEmpty
        )
        XCTAssertTrue(
            WholeCompositionCandidatePolicy.build(
                reading: "かんじ", dictionaryCandidates: [], scriptFallbacks: fallbacks, limit: 0
            ).isEmpty
        )
    }
}
