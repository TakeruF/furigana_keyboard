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

    func testDeleteRemovesVisibleSyllable() {
        XCTAssertEqual(RomajiKanaConverter.deleteLastUnit("sakura"), "saku")
        XCTAssertEqual(RomajiKanaConverter.deleteLastUnit("sh"), "s")
        XCTAssertEqual(RomajiKanaConverter.deleteLastUnit("kyu"), "ki")
    }

    func testKatakanaFallback() {
        XCTAssertEqual(RomajiKanaConverter.toKatakana("ふりがな"), "フリガナ")
    }
}
