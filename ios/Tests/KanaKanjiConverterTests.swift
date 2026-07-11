import XCTest
@testable import FuriganaKeyboard

final class KanaKanjiConverterTests: XCTestCase {
    func testDictionaryPathBeatsKanaCopyFallback() {
        let lexemes = [
            ConversionLexeme(start: 0, end: 2, reading: "かな", surface: "仮名",
                             leftID: 3, rightID: 3, wordCost: 100)
        ]
        let connections = [
            ConversionConnection(rightID: 0, leftID: 3, cost: 0),
            ConversionConnection(rightID: 3, leftID: 1, cost: 0)
        ]
        let result = KanaKanjiConverter.convert(reading: "かな", lexemes: lexemes, connections: connections)
        XCTAssertEqual(result.first?.surface, "仮名")
        XCTAssertEqual(result.first?.segments.first?.reading, "かな")
    }

    func testPureCopyPathIsNotPresentedAsDictionaryConversion() {
        XCTAssertTrue(KanaKanjiConverter.convert(reading: "かな", lexemes: [], connections: []).isEmpty)
    }

    func testMalformedLexemeIsIgnored() {
        let malformed = ConversionLexeme(start: 0, end: 1, reading: "な", surface: "名",
                                         leftID: 3, rightID: 3, wordCost: 1)
        XCTAssertTrue(KanaKanjiConverter.convert(reading: "かな", lexemes: [malformed], connections: []).isEmpty)
    }
}
