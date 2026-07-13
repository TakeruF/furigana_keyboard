import XCTest
@testable import FuriganaKeyboard

final class RomajiKanaConverterTests: XCTestCase {
    func testIncrementalConversion() {
        XCTAssertEqual(RomajiKanaConverter.convert("konnichiha").kana, "こんにちは")
        XCTAssertEqual(RomajiKanaConverter.convert("gakkou").kana, "がっこう")
        XCTAssertEqual(RomajiKanaConverter.convert("sh").pending, "sh")
    }

    func testTerminalNRemainsAmbiguous() {
        let value = RomajiKanaConverter.convert("kan")
        XCTAssertEqual(value.kana, "かん")
        XCTAssertTrue(value.hasAmbiguousTerminalN)
        XCTAssertFalse(RomajiKanaConverter.convert("kanna").hasUnresolvedInput)
    }

    func testConvertsMozcCompatibleExtendedRomaji() {
        // Google Mozc: src/data/preedit/romanji-hiragana.tsv
        let cases = [
            "thi": "てぃ", "thu": "てゅ", "the": "てぇ", "tho": "てょ",
            "dhi": "でぃ", "dhu": "でゅ", "dhe": "でぇ", "dho": "でょ",
            "twu": "とぅ", "dwu": "どぅ", "ye": "いぇ",
            "kwa": "くぁ", "kwi": "くぃ", "kwe": "くぇ", "kwo": "くぉ",
            "gwa": "ぐぁ", "gwi": "ぐぃ", "gwe": "ぐぇ", "gwo": "ぐぉ",
            "xka": "ヵ", "xke": "ヶ", "lka": "ヵ", "lke": "ヶ",
            "wha": "うぁ", "whi": "うぃ", "whe": "うぇ", "who": "うぉ"
        ]

        for (romaji, kana) in cases {
            XCTAssertEqual(RomajiKanaConverter.convert(romaji).displayText, kana, romaji)
        }
        XCTAssertEqual(RomajiKanaConverter.convert("ti").displayText, "ち")
        XCTAssertEqual(RomajiKanaConverter.convert("di").displayText, "ぢ")
    }

    func testDeleteRemovesVisibleSyllable() {
        XCTAssertEqual(RomajiKanaConverter.deleteLastUnit("sakura"), "saku")
        XCTAssertEqual(RomajiKanaConverter.deleteLastUnit("sh"), "s")
        XCTAssertEqual(RomajiKanaConverter.deleteLastUnit("kyu"), "ki")
        XCTAssertEqual(RomajiKanaConverter.deleteLastUnit("thi"), "te")
    }

    func testKatakanaFallback() {
        XCTAssertEqual(RomajiKanaConverter.toKatakana("ふりがな"), "フリガナ")
    }
}
