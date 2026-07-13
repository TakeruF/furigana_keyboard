import Foundation

/// Cross-platform integer scoring defined by docs/conversion-scoring.md.
enum ConversionCost {
    private static let unknownKanjiFrequency = 3_000
    private static let kanjiFrequencyScale = 100
    private static let kanaContentWordPenalty = 500
    private static let contentWordPOSIDs: Set<Int> = [3, 4, 7, 8, 9]

    static func adjustedWordCost(
        reading: String,
        surface: String,
        leftID: Int,
        rawWordCost: Int,
        frequencyByHanLiteral: [String: Int]
    ) -> Int {
        let kanaPenalty = ConversionText.scalarEquals(surface, reading) && contentWordPOSIDs.contains(leftID)
            ? kanaContentWordPenalty : 0
        let literals = hanLiterals(in: surface)
        let frequencyPenalty: Int64
        if literals.isEmpty {
            frequencyPenalty = 0
        } else {
            let sum = literals.reduce(Int64(0)) { partial, literal in
                let stored = frequencyByHanLiteral[literal] ?? unknownKanjiFrequency
                let frequency = stored > 0 ? min(stored, unknownKanjiFrequency) : unknownKanjiFrequency
                return partial + Int64(frequency / kanjiFrequencyScale)
            }
            frequencyPenalty = sum / Int64(literals.count)
        }
        let raw = Int64(clampInt32(Int64(rawWordCost)))
        return clampInt32(
            raw + Int64(kanaPenalty) + frequencyPenalty - Int64(ConversionText.scalarCount(surface))
        )
    }

    static func hanLiterals(in value: String) -> [String] {
        value.unicodeScalars.compactMap { scalar in
            isHan(scalar.value) ? String(scalar) : nil
        }
    }

    static func clampInt32(_ value: Int64) -> Int {
        Int(min(max(value, Int64(Int32.min)), Int64(Int32.max)))
    }

    private static func isHan(_ value: UInt32) -> Bool {
        switch value {
        case 0x2E80...0x2EFF,
             0x2F00...0x2FDF,
             0x3005,
             0x3007,
             0x3021...0x3029,
             0x3038...0x303B,
             0x3400...0x4DBF,
             0x4E00...0x9FFF,
             0xF900...0xFAFF,
             0x16FE2...0x16FE3,
             0x16FF0...0x16FF1,
             0x20000...0x2EE5F,
             0x2F800...0x2FA1F,
             0x30000...0x323AF:
            return true
        default:
            return false
        }
    }
}

/// Unicode-scalar operations whose behavior must match Kotlin exactly.
enum ConversionText {
    static func scalarCount(_ value: String) -> Int { value.unicodeScalars.count }

    static func scalarValues(_ value: String) -> [UInt32] {
        value.unicodeScalars.map(\.value)
    }

    static func scalarEquals(_ left: String, _ right: String) -> Bool {
        scalarValues(left) == scalarValues(right)
    }

    static func scalarOrder(_ left: String, _ right: String) -> ComparisonResult {
        let leftValues = scalarValues(left)
        let rightValues = scalarValues(right)
        for (lhs, rhs) in zip(leftValues, rightValues) where lhs != rhs {
            return lhs < rhs ? .orderedAscending : .orderedDescending
        }
        if leftValues.count == rightValues.count { return .orderedSame }
        return leftValues.count < rightValues.count ? .orderedAscending : .orderedDescending
    }

    static func scalarBoundaries(_ value: String) -> [String.Index] {
        var result = [value.startIndex]
        var index = value.startIndex
        while index < value.endIndex {
            index = value.unicodeScalars.index(after: index)
            result.append(index)
        }
        return result
    }

    static func scalarSubstring(_ value: String, start: Int, end: Int) -> String? {
        let boundaries = scalarBoundaries(value)
        guard start >= 0, end >= start, end < boundaries.count else { return nil }
        return String(value[boundaries[start]..<boundaries[end]])
    }
}
