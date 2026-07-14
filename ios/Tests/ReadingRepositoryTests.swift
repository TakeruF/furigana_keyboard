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
