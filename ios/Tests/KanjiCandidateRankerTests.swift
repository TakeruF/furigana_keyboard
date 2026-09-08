import XCTest
@testable import FuriganaKeyboard

final class KanjiCandidateRankerTests: XCTestCase {
    func testCommonJoyoKanjiWinsWhenNativeScoresAreClose() {
        let ranked = KanjiCandidateRanker.rank(
            [candidate("嶽", 10.0), candidate("学", 9.9), candidate("岳", 9.8)],
            priorities: [
                "嶽": KanjiUsagePriority(grade: 0, frequency: 0),
                "学": KanjiUsagePriority(grade: 1, frequency: 63),
                "岳": KanjiUsagePriority(grade: 8, frequency: 1_314)
            ]
        )

        XCTAssertEqual(ranked.first?.text, "学")
    }

    func testCommonUseBiasDoesNotOverrideAClearShapeScoreGap() {
        let ranked = KanjiCandidateRanker.rank(
            [candidate("嶽", 10.0), candidate("巖", 9.9), candidate("学", 8.0), candidate("岳", 7.9)],
            priorities: ["学": KanjiUsagePriority(grade: 1, frequency: 63)]
        )

        XCTAssertEqual(ranked.map(\.text), ["嶽", "巖", "学", "岳"])
    }

    func testRawTopIdentitySurvivesCommonUseReranking() {
        let normalized = RecognitionScoreNormalizer.normalize(
            [RawRecognitionCandidate("嶽", 10), RawRecognitionCandidate("学", 9.9)],
            source: .zinnia
        )

        let ranked = KanjiCandidateRanker.rank(
            normalized,
            priorities: ["学": KanjiUsagePriority(grade: 1, frequency: 63)]
        )

        XCTAssertEqual(ranked.first?.text, "学")
        let rawTop = ranked.filter(\.isRecognizerRawTop)
        XCTAssertEqual(rawTop.map(\.text), ["嶽"])
        XCTAssertEqual(rawTop.first?.evidence.rawScore, 10)
        XCTAssertEqual(rawTop.first?.evidence.rawRank, 0)
    }

    func testKanaSlotsAreNeverReorderedWithKanji() {
        let ranked = KanjiCandidateRanker.rank(
            [candidate("あ", 10), candidate("亜", 9.9), candidate("安", 9.8)],
            priorities: [
                "亜": KanjiUsagePriority(grade: 8, frequency: 1_509),
                "安": KanjiUsagePriority(grade: 3, frequency: 144)
            ]
        )

        XCTAssertEqual(ranked.first?.text, "あ")
    }

    func testKanaAndKanjiSlotsRemainFixedEvenWhenKanjiHasLowerCost() {
        let ranked = KanjiCandidateRanker.rank(
            [
                normalizedCandidate("あ", cost: 0.4, rank: 0),
                normalizedCandidate("亜", cost: 0, rank: 1),
                normalizedCandidate("安", cost: 0.05, rank: 2)
            ],
            priorities: ["安": KanjiUsagePriority(grade: 3, frequency: 144)]
        )

        XCTAssertEqual(ranked[0].text, "あ")
        XCTAssertEqual(Set(ranked.dropFirst().map(\.text)), ["亜", "安"])
    }

    func testASingleKanjiSlotIsNormalizedButNeverReordered() {
        let ranked = KanjiCandidateRanker.rank(
            [candidate("あ", 10), candidate("学", 9.9), candidate("い", 9.8)],
            priorities: ["学": KanjiUsagePriority(grade: 1, frequency: 63)]
        )

        XCTAssertEqual(ranked.map(\.text), ["あ", "学", "い"])
        XCTAssertTrue(ranked.allSatisfy { $0.shapeCost.isFinite })
    }

    func testSupplementaryPlaneKanjiCountsAsAKanjiSlot() {
        let ranked = KanjiCandidateRanker.rank(
            [candidate("\u{20B9F}", 10), candidate("学", 9.95)],
            priorities: ["学": KanjiUsagePriority(grade: 1, frequency: 63)]
        )

        XCTAssertEqual(ranked.first?.text, "学")
    }

    private func candidate(_ text: String, _ score: Float) -> RecognitionCandidate {
        RecognitionCandidate(text: text, score: score)
    }

    private func normalizedCandidate(_ text: String, cost: Float, rank: Int) -> RecognitionCandidate {
        RecognitionCandidate(
            text: text,
            shapeCost: cost,
            evidence: RecognitionEvidence(
                source: .zinnia,
                rawScore: 10 - Float(rank),
                rawRank: rank
            )
        )
    }
}
