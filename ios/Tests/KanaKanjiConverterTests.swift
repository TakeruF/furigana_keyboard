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

    func testLoanwordKatakanaIsAScoredLatticeEdge() {
        let result = KanaKanjiConverter.convert(
            reading: "すかいつりー",
            lexemes: [],
            connections: []
        ).first

        XCTAssertEqual(result?.surface, "スカイツリー")
        XCTAssertEqual(result?.segments.count, 1)
        XCTAssertEqual(result?.segments.first?.isCopy, false)
        XCTAssertEqual(
            KanaKanjiConverter.convert(
                reading: "ゔぁーじょん",
                lexemes: [],
                connections: []
            ).first?.surface,
            "ヴァージョン"
        )
    }

    func testLoneSmallTsuDoesNotTurnNativeMixedWordsIntoKatakana() {
        XCTAssertTrue(KanaKanjiConverter.convert(
            reading: "ひっこし",
            lexemes: [],
            connections: []
        ).isEmpty)
        let lexeme = ConversionLexeme(
            start: 0, end: 4, reading: "ひっこし", surface: "引っ越し",
            leftID: 3, rightID: 3, wordCost: 100
        )
        XCTAssertEqual(
            KanaKanjiConverter.convert(
                reading: "ひっこし",
                lexemes: [lexeme],
                connections: []
            ).first?.surface,
            "引っ越し"
        )
    }

    func testLegitimateKanjiKanaSurfaceIsNotBlanketPenalized() {
        let lexeme = ConversionLexeme(
            start: 0, end: 6, reading: "とりあつかい", surface: "取り扱い",
            leftID: 3, rightID: 3, wordCost: 100
        )
        XCTAssertEqual(
            KanaKanjiConverter.convert(
                reading: "とりあつかい",
                lexemes: [lexeme],
                connections: []
            ).first?.surface,
            "取り扱い"
        )
    }

    func testMalformedLexemeIsIgnored() {
        let malformed = ConversionLexeme(start: 0, end: 1, reading: "な", surface: "名",
                                         leftID: 3, rightID: 3, wordCost: 1)
        XCTAssertTrue(KanaKanjiConverter.convert(reading: "かな", lexemes: [malformed], connections: []).isEmpty)
    }

    func testSupplementaryScalarUsesScalarOffsetsInsteadOfCharacterOrUTF16Offsets() {
        let reading = "𠮟ほん"
        let lexeme = ConversionLexeme(
            start: 1, end: 3, reading: "ほん", surface: "本",
            leftID: 3, rightID: 3, wordCost: 100
        )

        let result = KanaKanjiConverter.convert(
            reading: reading,
            lexemes: [lexeme],
            connections: []
        ).first

        XCTAssertEqual(result?.surface, "𠮟本")
        XCTAssertEqual(result?.segments.first?.start, 0)
        XCTAssertEqual(result?.segments.first?.end, 1)
        XCTAssertEqual(result?.segments.last?.start, 1)
        XCTAssertEqual(result?.segments.last?.end, 3)
    }

    func testEmojiIsCopiedAsOneUnicodeScalarAroundKnownText() {
        let reading = "🙂ほん"
        let lexeme = ConversionLexeme(
            start: 1, end: 3, reading: "ほん", surface: "本",
            leftID: 3, rightID: 3, wordCost: 100
        )

        let result = KanaKanjiConverter.convert(
            reading: reading,
            lexemes: [lexeme],
            connections: []
        ).first

        XCTAssertEqual(result?.surface, "🙂本")
        XCTAssertEqual(result?.segments.first?.start, 0)
        XCTAssertEqual(result?.segments.first?.end, 1)
    }

    func testFortyNineScalarsReturnEmpty() {
        let reading = String(repeating: "あ", count: 49)
        let lexeme = ConversionLexeme(
            start: 0, end: 1, reading: "あ", surface: "亜",
            leftID: 3, rightID: 3, wordCost: 100
        )

        XCTAssertTrue(KanaKanjiConverter.convert(
            reading: reading,
            lexemes: [lexeme],
            connections: []
        ).isEmpty)
    }

    func testCancellationReturnsEmptyPromptly() {
        var checks = 0
        let reading = String(repeating: "かな", count: 20)
        let lexemes = (0..<reading.unicodeScalars.count).map { start in
            ConversionLexeme(
                start: start, end: start + 1, reading: start.isMultiple(of: 2) ? "か" : "な",
                surface: start.isMultiple(of: 2) ? "可" : "名",
                leftID: 3, rightID: 3, wordCost: 100
            )
        }

        let result = KanaKanjiConverter.convert(
            reading: reading,
            lexemes: lexemes,
            connections: [],
            isCancelled: {
                checks += 1
                return checks > 3
            }
        )

        XCTAssertTrue(result.isEmpty)
        XCTAssertLessThan(checks, 20)
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

    func testInitialRightPosScoresSuffixFromCommittedContextInsteadOfBOS() {
        let lexemes = [
            ConversionLexeme(start: 0, end: 2, reading: "はし", surface: "橋",
                             leftID: 3, rightID: 3, wordCost: 100),
            ConversionLexeme(start: 0, end: 2, reading: "はし", surface: "箸",
                             leftID: 4, rightID: 4, wordCost: 0)
        ]
        let connections = [
            ConversionConnection(rightID: 0, leftID: 3, cost: 1_000),
            ConversionConnection(rightID: 0, leftID: 4, cost: 0),
            ConversionConnection(rightID: 5, leftID: 3, cost: 0),
            ConversionConnection(rightID: 5, leftID: 4, cost: 1_000),
            ConversionConnection(rightID: 3, leftID: 1, cost: 0),
            ConversionConnection(rightID: 4, leftID: 1, cost: 0)
        ]

        let bos = KanaKanjiConverter.convert(
            reading: "はし", lexemes: lexemes, connections: connections
        )
        let contextual = KanaKanjiConverter.convert(
            reading: "はし",
            lexemes: lexemes,
            connections: connections,
            initialRightID: 5
        )

        XCTAssertEqual(bos.first?.surface, "箸")
        XCTAssertEqual(contextual.first?.surface, "橋")
    }

    func testRequiredBoundaryReevaluatesWholePathThroughAdjustedEdge() {
        let lexemes = [
            ConversionLexeme(start: 0, end: 3, reading: "あいう", surface: "一語",
                             leftID: 3, rightID: 3, wordCost: 0),
            ConversionLexeme(start: 0, end: 1, reading: "あ", surface: "甲",
                             leftID: 5, rightID: 5, wordCost: 10),
            ConversionLexeme(start: 1, end: 3, reading: "いう", surface: "乙",
                             leftID: 3, rightID: 3, wordCost: 10)
        ]
        let connections = (0...15).flatMap { right in
            (0...15).map { left in
                ConversionConnection(rightID: right, leftID: left, cost: 0)
            }
        }

        let unconstrained = KanaKanjiConverter.convert(
            reading: "あいう", lexemes: lexemes, connections: connections
        )
        let constrained = KanaKanjiConverter.convert(
            reading: "あいう",
            lexemes: lexemes,
            connections: connections,
            requiredBoundary: 1
        )

        XCTAssertEqual(unconstrained.first?.surface, "一語")
        XCTAssertEqual(constrained.first?.surface, "甲乙")
        XCTAssertTrue(constrained.first?.segments.contains(where: { $0.end == 1 }) == true)
    }
}
