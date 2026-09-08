import XCTest
@testable import FuriganaKeyboard

final class RecognitionCandidateTests: XCTestCase {
    func testCandidateRetainsReading() {
        let candidate = RecognitionCandidate(text: "漢", reading: "かん", score: 0.5)
        XCTAssertEqual(candidate.reading, "かん")
    }

    func testBridgedCandidateKeepsRawScoreAndCarriesNoCommonCostYet() {
        let candidate = RecognitionCandidate(text: "漢", score: 42)

        XCTAssertEqual(candidate.score, 42)
        XCTAssertTrue(candidate.shapeCost.isNaN)
        XCTAssertEqual(candidate.evidence.source, .legacyNative)
        XCTAssertFalse(candidate.isRecognizerRawTop)
    }

    func testAComposedCandidateIsRawTopOnlyWhenEveryHalfIs() {
        let bothHalvesTop = composed(rawTop: [true, true])
        let oneHalfTop = composed(rawTop: [true, false])

        XCTAssertTrue(bothHalvesTop.isRecognizerRawTop)
        XCTAssertFalse(oneHalfTop.isRecognizerRawTop)
        XCTAssertEqual(bothHalvesTop.score, -0.25)
        XCTAssertEqual(bothHalvesTop.evidence.components.map(\.text), ["漢", "字"])
    }

    func testAComposedCandidateWithoutComponentsIsNeverRawTop() {
        let candidate = RecognitionCandidate(
            text: "漢字",
            shapeCost: 0,
            evidence: RecognitionEvidence(source: .sideBySide)
        )

        XCTAssertFalse(candidate.isRecognizerRawTop)
    }

    private func composed(rawTop: [Bool]) -> RecognitionCandidate {
        let components = zip(["漢", "字"], rawTop).enumerated().map { position, pair in
            RecognitionComponentEvidence(
                position: position,
                text: pair.0,
                shapeCost: 0,
                source: .zinnia,
                rawScore: 10,
                rawRank: pair.1 ? 0 : 1,
                isRecognizerRawTop: pair.1
            )
        }
        return RecognitionCandidate(
            text: "漢字",
            shapeCost: 0.25,
            evidence: RecognitionEvidence(source: .sideBySide, components: components)
        )
    }

    func testAttachingAReadingKeepsShapeEvidence() {
        let candidate = RecognitionCandidate(
            text: "漢",
            shapeCost: 0.5,
            evidence: RecognitionEvidence(source: .zinnia, rawScore: 9, rawRank: 1)
        ).withReading("かん")

        XCTAssertEqual(candidate.reading, "かん")
        XCTAssertEqual(candidate.shapeCost, 0.5)
        XCTAssertEqual(candidate.evidence.rawRank, 1)
    }
}
