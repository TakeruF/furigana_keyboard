import XCTest

final class WordCandidateResolverTests: XCTestCase {
    func testRecognizedSurfacesAreKeptEvenWithoutADictionaryReading() {
        let shaped = WordCandidateResolver.shapedCandidates(
            [surface("嶽", cost: 0, rawTop: true), surface("岳", cost: 0.3, rawTop: false)],
            exactReadings: ["岳": ["たけ"]],
            suggestions: [:]
        )

        XCTAssertEqual(shaped.map(\.surface), ["嶽", "岳"])
        XCTAssertEqual(shaped.first?.readings, [])
        XCTAssertTrue(shaped[0].isRecognizerRawTop)
    }

    func testCompletionsInheritShapeEvidenceButNeverTheRawTopMarking() {
        let shaped = WordCandidateResolver.shapedCandidates(
            [surface("国", cost: 0.2, rawTop: true)],
            exactReadings: ["国": ["くに"]],
            suggestions: ["国": [WordCandidate(surface: "国際", readings: ["こくさい"])]]
        )

        XCTAssertEqual(shaped.map(\.surface), ["国", "国際"])
        XCTAssertEqual(shaped[1].shapeCost, 0.2)
        XCTAssertFalse(shaped[1].isRecognizerRawTop)
    }

    func testASuggestionEqualToItsSourceSurfaceIsNotDuplicated() {
        let shaped = WordCandidateResolver.shapedCandidates(
            [surface("国", cost: 0, rawTop: true)],
            exactReadings: [:],
            suggestions: ["国": [WordCandidate(surface: "国", readings: ["くに"])]]
        )

        XCTAssertEqual(shaped.map(\.surface), ["国"])
    }

    func testDictionaryEvidenceReordersCandidatesWithinTheShapeCostBand() {
        let candidates = [
            word("嶽", cost: 0.2, rawTop: true),
            word("岳", cost: 0.25, rawTop: false)
        ]

        let resolved = WordCandidateResolver.resolveShaped(
            candidates,
            lexicalEvidence: [
                "岳": .match(surface: "岳", exact: true, properName: false, placeName: false)
            ],
            limit: 8
        )

        XCTAssertEqual(resolved.map(\.surface), ["岳", "嶽"])
        XCTAssertEqual(resolved[0].rankingShapeCost, 0.25)
        XCTAssertEqual(resolved[0].shapeCost, 0.17, accuracy: 0.00001)
        XCTAssertNil(resolved[1].lexicalEvidence)
    }

    func testEvidenceCannotPushACandidateBelowTheShapeCostFloor() {
        let resolved = WordCandidateResolver.resolveShaped(
            [word("嶽", cost: 0, rawTop: true), word("岳", cost: 0.05, rawTop: false)],
            lexicalEvidence: [
                "岳": .match(surface: "岳", exact: true, properName: false, placeName: false)
            ],
            limit: 8
        )

        // Both land on the 0 floor, so the recognizer's own order decides.
        XCTAssertEqual(resolved.map(\.surface), ["嶽", "岳"])
        XCTAssertEqual(resolved[1].shapeCost, 0)
    }

    func testEvidenceCannotOutweighAShapeCostGapLargerThanTheDiscount() {
        let resolved = WordCandidateResolver.resolveShaped(
            [word("嶽", cost: 0, rawTop: true), word("岳", cost: 0.5, rawTop: false)],
            lexicalEvidence: [
                "岳": .match(surface: "岳", exact: true, properName: false, placeName: false)
            ],
            limit: 8
        )

        XCTAssertEqual(resolved.map(\.surface), ["嶽", "岳"])
    }

    func testTheRecognizerRawTopKeepsASlotEvenWhenTheLimitWouldDropIt() {
        let candidates = (0..<8).map { word("語\($0)", cost: 0.1, rawTop: false) }
            + [word("嶽", cost: 0.9, rawTop: true)]

        let resolved = WordCandidateResolver.resolveShaped(
            candidates,
            lexicalEvidence: [:],
            limit: 4
        )

        XCTAssertEqual(resolved.count, 4)
        XCTAssertEqual(resolved.last?.surface, "嶽")
        XCTAssertEqual(resolved.map(\.surface).filter { $0 == "嶽" }.count, 1)
    }

    func testARawTopAlreadyInsideTheLimitIsNotDuplicated() {
        let resolved = WordCandidateResolver.resolveShaped(
            [word("嶽", cost: 0, rawTop: true), word("岳", cost: 0.5, rawTop: false)],
            lexicalEvidence: [:],
            limit: 2
        )

        XCTAssertEqual(resolved.map(\.surface), ["嶽", "岳"])
    }

    func testEqualCostsKeepTheirIncomingOrder() {
        let resolved = WordCandidateResolver.resolveShaped(
            [word("日", cost: 0.4, rawTop: false),
             word("目", cost: 0.4, rawTop: false),
             word("曰", cost: 0.4, rawTop: false)],
            lexicalEvidence: [:],
            limit: 8
        )

        XCTAssertEqual(resolved.map(\.surface), ["日", "目", "曰"])
    }

    func testAnUnnormalizedShapeCostIsTreatedAsTheWorstShapeEvidence() {
        let resolved = WordCandidateResolver.resolveShaped(
            [word("嶽", cost: .nan, rawTop: false), word("岳", cost: 0.5, rawTop: false)],
            lexicalEvidence: [
                "嶽": .match(surface: "嶽", exact: true, properName: false, placeName: false)
            ],
            limit: 8
        )

        XCTAssertEqual(resolved.map(\.surface), ["岳", "嶽"])
        XCTAssertEqual(resolved[1].rankingShapeCost, 1)
    }

    func testAZeroLimitProducesNoCandidates() {
        XCTAssertTrue(
            WordCandidateResolver.resolveShaped(
                [word("嶽", cost: 0, rawTop: true)],
                lexicalEvidence: [:],
                limit: 0
            ).isEmpty
        )
    }

    private func surface(_ text: String, cost: Float, rawTop: Bool) -> ShapedSurfaceCandidate {
        ShapedSurfaceCandidate(surface: text, shapeCost: cost, isRecognizerRawTop: rawTop)
    }

    private func word(_ text: String, cost: Float, rawTop: Bool) -> ShapedWordCandidate {
        ShapedWordCandidate(surface: text, readings: [], shapeCost: cost, isRecognizerRawTop: rawTop)
    }
}
