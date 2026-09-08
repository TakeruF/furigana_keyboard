import XCTest
@testable import FuriganaKeyboard

final class ReadingRepositoryTests: XCTestCase {
    private func repository() throws -> ReadingRepository {
        let extensions = Bundle.main.builtInPlugInsURL
        let bundleURL = extensions?.appendingPathComponent("FuriganaKeyboardExtension.appex")
        guard let bundleURL, let bundle = Bundle(url: bundleURL), let repository = ReadingRepository(bundle: bundle) else {
            throw XCTSkip("Embedded keyboard resources are unavailable in this test host")
        }
        return repository
    }

    func testBundledDatabaseProvidesReadingsAndWordSuggestions() throws {
        let repository = try repository()
        XCTAssertTrue(repository.readings(for: "漢").contains("カン"))
        XCTAssertTrue(repository.suggestions(readingPrefix: "にほん").contains { $0.surface == "日本" })
    }

    func testBundledConversionLexemesProduceJapaneseCandidate() throws {
        let repository = try repository()
        let data = repository.conversionData(for: "にほん")
        XCTAssertTrue(data.lexemes.contains { $0.surface == "日本" })
        let converted = KanaKanjiConverter.convert(reading: "にほん", lexemes: data.lexemes, connections: data.connections)
        XCTAssertTrue(converted.contains { $0.surface == "日本" })
    }

    func testLexicalEvidenceAnswersKnownAndUnknownSurfacesInOneBoundedBatch() throws {
        let repository = try repository()
        let unknown = "\u{20B9F}\u{20B9F}\u{20B9F}"

        let evidence = repository.lexicalEvidence(for: ["学校", "漢", "", "学校", unknown])

        XCTAssertEqual(evidence.count, 3)
        XCTAssertNil(evidence[""])
        XCTAssertEqual(evidence["学校"]?.exact, true)
        XCTAssertEqual(evidence["漢"]?.exact, true)
        XCTAssertEqual(evidence[unknown]?.unknown, true)
        XCTAssertEqual(evidence[unknown]?.costAdjustment, 0)
    }

    func testEveryRequestedSurfaceIsAnsweredUpToTheBatchLimit() throws {
        let repository = try repository()

        let evidence = repository.lexicalEvidence(for: (0..<40).map { "候補\($0)" })

        XCTAssertEqual(evidence.count, SurfaceLexicalEvidenceBatch.maximumSurfaces)
        XCTAssertTrue(evidence.values.allSatisfy(\.unknown))
    }

    func testKnownSurfacesEarnADiscountInsideTheBoundedBand() throws {
        let repository = try repository()

        let evidence = repository.lexicalEvidence(for: ["学校", "青森", "日本"])

        for surface in ["学校", "青森", "日本"] {
            let entry = try XCTUnwrap(evidence[surface])
            XCTAssertTrue(entry.exact, surface)
            XCTAssertLessThan(entry.costAdjustment, 0, surface)
            XCTAssertGreaterThanOrEqual(
                entry.costAdjustment,
                SurfaceLexicalEvidence.minimumCostAdjustment,
                surface
            )
        }
    }

    /// Evidence sits in the recognition path, so a lookup that starts scanning a whole table
    /// again would be felt on every stroke. The bound is far above the indexed cost.
    func testLexicalEvidenceStaysOffTheFullTableScanPath() throws {
        let repository = try repository()
        let surfaces = ["学校", "漢字", "国際版", "嶽", "岳", "青森"]
        _ = repository.lexicalEvidence(for: surfaces)

        let elapsed = (0..<5).map { _ -> Double in
            let start = Date()
            _ = repository.lexicalEvidence(for: surfaces)
            return Date().timeIntervalSince(start) * 1000
        }.sorted()

        XCTAssertLessThan(elapsed[2], 50, "median \(elapsed[2]) ms over \(elapsed)")
    }

    func testBundledConversionLexemesIncludeFullWidthKatakanaReading() throws {
        let repository = try repository()
        let data = repository.conversionData(for: "しゃつ")
        XCTAssertTrue(data.lexemes.contains { $0.reading == "しゃつ" && $0.surface == "シャツ" })
        XCTAssertEqual(
            KanaKanjiConverter.convert(
                reading: "しゃつ",
                lexemes: data.lexemes,
                connections: data.connections
            ).first?.surface,
            "シャツ"
        )
    }
}
