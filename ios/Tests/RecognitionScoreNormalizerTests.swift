import XCTest
@testable import FuriganaKeyboard

final class RecognitionScoreNormalizerTests: XCTestCase {
    func testNativeScoresAnchorTheBestCandidateAtZeroWithoutStretchingANarrowRange() {
        let candidates = normalize([raw("嶽", 10), raw("学", 9.9), raw("岳", 8)])

        assertCosts([0, 0.05, 1], candidates.map(\.shapeCost))
        XCTAssertTrue(candidates.allSatisfy { $0.shapeCost >= 0 && $0.shapeCost <= 1 })
        XCTAssertTrue(candidates.allSatisfy { $0.evidence.source == .zinnia })
    }

    func testNonFiniteNativeScoresFallBackToRankCostWithoutLosingRawEvidence() {
        let candidates = normalize([raw("日", .nan), raw("目", .infinity), raw("曰", 2)])

        assertCosts([0, 0.1, 0.2], candidates.map(\.shapeCost))
        XCTAssertTrue(candidates[0].evidence.rawScore?.isNaN == true)
        XCTAssertEqual(candidates[1].evidence.rawScore, .infinity)
        XCTAssertEqual(candidates.map(\.evidence.rawRank), [0, 1, 2])
    }

    func testMissingNativeScoresFallBackToRankCost() {
        let candidates = normalize([raw("日"), raw("目"), raw("曰")])

        assertCosts([0, 0.1, 0.2], candidates.map(\.shapeCost))
        XCTAssertEqual(candidates.map(\.text), ["日", "目", "曰"])
    }

    func testEqualNativeScoresHaveEqualCostAndKeepStableRawRanks() {
        let candidates = normalize([raw("日", 4), raw("目", 4), raw("曰", 4)])

        XCTAssertEqual(candidates.map(\.text), ["日", "目", "曰"])
        assertCosts([0, 0, 0], candidates.map(\.shapeCost))
        XCTAssertEqual(candidates.map(\.evidence.rawRank), [0, 1, 2])
    }

    func testExactDuplicatesKeepFirstOccurrenceAndOriginalRank() {
        let candidates = normalize([raw("日"), raw("日"), raw("目"), raw("曰")])

        XCTAssertEqual(candidates.map(\.text), ["日", "目", "曰"])
        XCTAssertEqual(candidates.map(\.evidence.rawRank), [0, 2, 3])
        assertCosts([0, 0.1, 0.2], candidates.map(\.shapeCost))
    }

    func testSingleCandidateIsZeroCostAndIdentifiableAsRecognizerRawTop() {
        let candidate = try? XCTUnwrap(normalize([raw("日", 42)]).first)

        XCTAssertEqual(candidate?.shapeCost, 0)
        XCTAssertEqual(candidate?.evidence.rawScore, 42)
        XCTAssertEqual(candidate?.evidence.source, .zinnia)
        XCTAssertEqual(candidate?.evidence.rawRank, 0)
        XCTAssertEqual(candidate?.isRecognizerRawTop, true)
        XCTAssertEqual(candidate?.evidence.components.isEmpty, true)
    }

    func testEmptyInputProducesNoCandidates() {
        XCTAssertTrue(normalize([]).isEmpty)
    }

    func testAlreadyNormalizedCandidatesAreLeftUntouched() {
        let normalized = normalize([raw("日", 10), raw("目", 9)])

        XCTAssertEqual(RecognitionScoreNormalizer.normalizeIfNeeded(normalized), normalized)
    }

    func testBridgedCandidatesWithoutACommonCostAreNormalized() {
        let bridged = [
            RecognitionCandidate(text: "日", score: 10),
            RecognitionCandidate(text: "目", score: 9)
        ]

        let candidates = RecognitionScoreNormalizer.normalizeIfNeeded(bridged, source: .zinnia)

        assertCosts([0, 1], candidates.map(\.shapeCost))
        XCTAssertTrue(bridged.allSatisfy { $0.shapeCost.isNaN })
    }

    private func normalize(_ raw: [RawRecognitionCandidate]) -> [RecognitionCandidate] {
        RecognitionScoreNormalizer.normalize(raw, source: .zinnia)
    }

    private func raw(_ text: String, _ score: Float? = nil) -> RawRecognitionCandidate {
        RawRecognitionCandidate(text, score)
    }

    private func assertCosts(_ expected: [Float], _ actual: [Float], line: UInt = #line) {
        XCTAssertEqual(expected.count, actual.count, line: line)
        for (expectedCost, actualCost) in zip(expected, actual) {
            XCTAssertEqual(expectedCost, actualCost, accuracy: 0.00001, line: line)
        }
    }
}
