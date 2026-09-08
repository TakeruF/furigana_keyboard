import XCTest

final class SurfaceLexicalEvidenceTests: XCTestCase {
    func testBatchNormalizationDropsEmptyAndDuplicateSurfacesAndHoldsTheLimit() {
        let input = ["", "家", "家"] + (0..<30).map { "候補\($0)" }

        let evidence = SurfaceLexicalEvidenceBatch.unknown(input)

        XCTAssertEqual(evidence.count, SurfaceLexicalEvidenceBatch.maximumSurfaces)
        XCTAssertEqual(SurfaceLexicalEvidenceBatch.normalize(input).first, "家")
        XCTAssertNil(evidence[""])
        XCTAssertTrue(evidence.values.allSatisfy { $0.unknown && $0.costAdjustment == 0 })
    }

    func testUnknownEvidenceIsNeutralAndShapeCostCombinationIsClamped() {
        let unknown = SurfaceLexicalEvidence.unknown("未知語")

        XCTAssertEqual(unknown.applyToShapeCost(0.27), 0.27)
        XCTAssertEqual(unknown.applyToShapeCost(0), 0)
        XCTAssertEqual(unknown.applyToShapeCost(1), 1)
    }

    func testAnExactDictionaryHitEarnsTheFullBoundedDiscount() {
        let exact = SurfaceLexicalEvidence.match(
            surface: "学校", exact: true, properName: false, placeName: false
        )

        XCTAssertFalse(exact.unknown)
        XCTAssertFalse(exact.named)
        XCTAssertEqual(exact.costAdjustment, SurfaceLexicalEvidence.minimumCostAdjustment)
        XCTAssertEqual(exact.applyToShapeCost(0.5), 0.42, accuracy: 0.00001)
        // The discount is bounded, so evidence can never drive a cost below the band.
        XCTAssertEqual(exact.applyToShapeCost(0), 0)
    }

    func testNamedEntriesEarnASmallerDiscountThanOrdinaryWords() {
        let place = SurfaceLexicalEvidence.match(
            surface: "青森", exact: true, properName: false, placeName: true
        )
        let ordinary = SurfaceLexicalEvidence.match(
            surface: "学校", exact: true, properName: false, placeName: false
        )

        XCTAssertTrue(place.named)
        XCTAssertGreaterThan(place.costAdjustment, ordinary.costAdjustment)
        XCTAssertLessThan(place.costAdjustment, 0)
    }
}
