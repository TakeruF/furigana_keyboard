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

    func testLeadingBunsetsuIncludesParticleInsteadOfUsingFirstToken() {
        let segments = [
            ConversionSegment(start: 0, end: 3, reading: "わたし", surface: "私",
                              leftID: 2, rightID: 2, wordCost: 0, isCopy: false),
            ConversionSegment(start: 3, end: 4, reading: "も", surface: "も",
                              leftID: 5, rightID: 5, wordCost: 0, isCopy: false),
            ConversionSegment(start: 4, end: 6, reading: "そう", surface: "そう",
                              leftID: 9, rightID: 9, wordCost: 0, isCopy: false),
            ConversionSegment(start: 6, end: 7, reading: "し", surface: "し",
                              leftID: 7, rightID: 7, wordCost: 0, isCopy: false),
            ConversionSegment(start: 7, end: 9, reading: "たい", surface: "たい",
                              leftID: 6, rightID: 6, wordCost: 0, isCopy: false)
        ]

        XCTAssertEqual(KanaKanjiConverter.leadingBunsetsuLength(segments: segments, totalLength: 9), 4)
    }

    func testNoInternalBunsetsuBoundaryReturnsNil() {
        let segments = [
            ConversionSegment(start: 0, end: 2, reading: "そう", surface: "そう",
                              leftID: 9, rightID: 9, wordCost: 0, isCopy: false),
            ConversionSegment(start: 2, end: 3, reading: "し", surface: "し",
                              leftID: 7, rightID: 7, wordCost: 0, isCopy: false),
            ConversionSegment(start: 3, end: 5, reading: "たい", surface: "たい",
                              leftID: 6, rightID: 6, wordCost: 0, isCopy: false)
        ]

        XCTAssertNil(KanaKanjiConverter.leadingBunsetsuLength(segments: segments, totalLength: 5))
    }
}
