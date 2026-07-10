import XCTest
@testable import FuriganaKeyboard

final class RecognitionCandidateTests: XCTestCase {
    func testCandidateRetainsReading() {
        let candidate = RecognitionCandidate(text: "漢", reading: "かん", score: 0.5)
        XCTAssertEqual(candidate.reading, "かん")
    }
}

