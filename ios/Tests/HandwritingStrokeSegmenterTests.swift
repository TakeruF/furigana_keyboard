import CoreGraphics
import XCTest

final class HandwritingStrokeSegmenterTests: XCTestCase {
    func testDeletesSideBySideInkOneCharacterAtATime() {
        let size = CGSize(width: 400, height: 240)
        let ink = [
            stroke(35, 35, 125, 200),
            stroke(45, 115, 120, 115),
            stroke(260, 35, 350, 200),
            stroke(270, 115, 345, 115)
        ]

        let left = HandwritingStrokeSegmenter.removingLastCharacter(from: ink, canvasSize: size)
        XCTAssertEqual(left?.count, 2)
        XCTAssertEqual(
            HandwritingStrokeSegmenter.removingLastCharacter(from: left ?? [], canvasSize: size),
            []
        )
        XCTAssertNil(HandwritingStrokeSegmenter.removingLastCharacter(from: [], canvasSize: size))
    }

    func testDeletesAllStrokesOfACompactSingleCharacter() {
        let ink = [
            stroke(135, 120, 185, 40),
            stroke(205, 40, 255, 120)
        ]

        XCTAssertEqual(
            HandwritingStrokeSegmenter.removingLastCharacter(
                from: ink,
                canvasSize: CGSize(width: 400, height: 240)
            ),
            []
        )
    }

    private func stroke(_ coordinates: CGFloat...) -> [CGPoint] {
        stride(from: 0, to: coordinates.count, by: 2).map {
            CGPoint(x: coordinates[$0], y: coordinates[$0 + 1])
        }
    }
}
