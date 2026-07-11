import Foundation

final class BunsetsuState {
    let reading: String
    private(set) var committedSurface = ""
    private(set) var offset = 0
    private(set) var activeLength: Int

    init(reading: String, initialLength: Int) {
        self.reading = reading
        activeLength = max(1, min(initialLength, Array(reading).count))
    }

    var remainingCharacters: [Character] { Array(reading).dropFirst(offset).map { $0 } }
    var activeReading: String { String(remainingCharacters.prefix(activeLength)) }
    var trailingReading: String { String(remainingCharacters.dropFirst(activeLength)) }
    var markedText: String { committedSurface + String(Array(reading).dropFirst(offset)) }
    var canShrink: Bool { activeLength > 1 }
    var canExpand: Bool { activeLength < remainingCharacters.count }
    var isComplete: Bool { offset >= Array(reading).count }

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
}
