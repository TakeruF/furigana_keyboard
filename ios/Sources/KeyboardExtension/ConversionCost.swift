import Foundation

/// Cross-platform integer scoring defined by docs/conversion-scoring.md.
enum ConversionCost {
    private static let unknownKanjiFrequency = 3_000
    private static let kanjiFrequencyScale = 100
    private static let kanaContentWordPenalty = 500
    private static let weakKatakanaCuePenalty = 1_200
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
        let katakanaPenalty = ConversionText.isKatakanaTransliteration(reading: reading, surface: surface) &&
            !ConversionText.isLoanwordLikeReading(reading) ? weakKatakanaCuePenalty : 0
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
            raw + Int64(kanaPenalty) + Int64(katakanaPenalty) + frequencyPenalty -
                Int64(ConversionText.scalarCount(surface))
        )
    }

    static func hanLiterals(in value: String) -> [String] {
        value.unicodeScalars.compactMap { scalar in
            ConversionText.isHan(scalar.value) ? String(scalar) : nil
        }
    }

    static func clampInt32(_ value: Int64) -> Int {
        Int(min(max(value, Int64(Int32.min)), Int64(Int32.max)))
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

    static func toKatakana(_ value: String) -> String {
        String(value.unicodeScalars.map { scalar -> Character in
            if (0x3041...0x3096).contains(scalar.value),
               let converted = UnicodeScalar(scalar.value + 0x60) {
                return Character(String(converted))
            }
            return Character(String(scalar))
        })
    }

    static func isKatakanaTransliteration(reading: String, surface: String) -> Bool {
        surface == toKatakana(reading) && surface != reading &&
        surface.unicodeScalars.allSatisfy { (0x30A1...0x30FA).contains($0.value) || $0.value == 0x30FC }
    }

    static func containsHan(_ value: String) -> Bool {
        value.unicodeScalars.contains { isHan($0.value) }
    }

    static func isPureHan(_ value: String) -> Bool {
        !value.isEmpty && value.unicodeScalars.allSatisfy { isHan($0.value) }
    }

    static func isUnconvertedHiragana(reading: String, surface: String) -> Bool {
        surface == reading && surface.unicodeScalars.allSatisfy {
            (0x3041...0x3096).contains($0.value) || $0.value == 0x30FC
        }
    }

    /// Strong script cues; a lone small tsu is excluded to protect 引っ越し.
    static func isLoanwordLikeReading(_ value: String) -> Bool {
        var smallTsuCount = 0
        for scalar in value.unicodeScalars {
            if isLoanwordMarker(scalar.value) { return true }
            if scalar.value == 0x3063 { smallTsuCount += 1 }
        }
        return smallTsuCount >= 2
    }

    static func isLoanwordMarker(_ value: UInt32) -> Bool {
        value == 0x30FC || value == 0x3094 ||
        value == 0x3041 || value == 0x3043 || value == 0x3045 || value == 0x3047 ||
        value == 0x3049 || value == 0x308E
    }

    static func isHan(_ value: UInt32) -> Bool {
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
