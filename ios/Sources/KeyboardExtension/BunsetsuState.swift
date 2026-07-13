import Foundation

final class BunsetsuState {
    let reading: String
    private(set) var committedSurface = ""
    private(set) var offset = 0
    private(set) var activeLength: Int

    init(reading: String, initialLength: Int) {
        self.reading = reading
        activeLength = max(1, min(initialLength, reading.unicodeScalars.count))
    }

    var remainingCharacters: [Unicode.Scalar] {
        Array(reading.unicodeScalars.dropFirst(offset))
    }
    var remainingReading: String { Self.string(from: remainingCharacters[...]) }
    var activeReading: String { Self.string(from: remainingCharacters.prefix(activeLength)) }
    var trailingReading: String { Self.string(from: remainingCharacters.dropFirst(activeLength)) }
    var markedText: String { committedSurface + remainingReading }
    var canShrink: Bool { activeLength > 1 }
    var canExpand: Bool { activeLength < remainingCharacters.count }
    var isComplete: Bool { offset >= reading.unicodeScalars.count }

    func shrink() { if canShrink { activeLength -= 1 } }
    func expand() { if canExpand { activeLength += 1 } }

    func select(surface: String) {
        committedSurface += surface
        offset += activeLength
        activeLength = min(1, remainingCharacters.count)
    }

    func setSuggestedLength(_ length: Int) {
        guard !remainingCharacters.isEmpty else { return }
        activeLength = max(1, min(length, remainingCharacters.count))
    }

    private static func string<S: Sequence>(from scalars: S) -> String where S.Element == Unicode.Scalar {
        var output = ""
        output.unicodeScalars.append(contentsOf: scalars)
        return output
    }
}
