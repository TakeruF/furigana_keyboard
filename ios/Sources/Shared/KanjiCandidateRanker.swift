import Foundation

/// KANJIDIC2 school grade and newspaper frequency for one character.
struct KanjiUsagePriority: Equatable {
    let grade: Int
    let frequency: Int

    init(grade: Int, frequency: Int) {
        self.grade = grade
        self.frequency = frequency
    }

    var isJoyo: Bool { (1...6).contains(grade) || grade == 8 }
    var isJinmeiyo: Bool { grade == 9 || grade == 10 }
}

/// Applies a bounded common-use bias without crossing a clear shape-cost gap.
enum KanjiCandidateRanker {
    static func rank(
        _ candidates: [RecognitionCandidate],
        priorities: [String: KanjiUsagePriority]
    ) -> [RecognitionCandidate] {
        let normalized = RecognitionScoreNormalizer.normalizeIfNeeded(candidates)
        // Non-Han slots never move, so usage data cannot exchange kana and kanji.
        let hanSlots = normalized.indices.filter { isHan(normalized[$0].text) }
        guard hanSlots.count >= 2 else { return normalized }

        let shapeOrdered = hanSlots.map { normalized[$0] }.enumerated().sorted { lhs, rhs in
            if lhs.element.shapeCost != rhs.element.shapeCost {
                return lhs.element.shapeCost < rhs.element.shapeCost
            }
            return lhs.offset < rhs.offset
        }

        // A cluster boundary is absolute: usage bias is only applied within it.
        var clusters: [[(offset: Int, element: RecognitionCandidate)]] = []
        for candidate in shapeOrdered {
            if let previous = clusters.last?.last?.element,
               candidate.element.shapeCost - previous.shapeCost < clearShapeGap {
                clusters[clusters.count - 1].append(candidate)
            } else {
                clusters.append([candidate])
            }
        }

        let rankedHan = clusters.flatMap { cluster in
            cluster.sorted { lhs, rhs in
                let biased = lhs.element.shapeCost - usageDiscount(priorities[lhs.element.text])
                let other = rhs.element.shapeCost - usageDiscount(priorities[rhs.element.text])
                if biased != other { return biased < other }
                if lhs.element.shapeCost != rhs.element.shapeCost {
                    return lhs.element.shapeCost < rhs.element.shapeCost
                }
                return lhs.offset < rhs.offset
            }.map(\.element)
        }

        var output = normalized
        for (index, slot) in hanSlots.enumerated() { output[slot] = rankedHan[index] }
        return output
    }

    private static func usageDiscount(_ priority: KanjiUsagePriority?) -> Float {
        maximumUsageDiscount * (usageWeight(priority) / maximumUsageWeight)
    }

    private static func usageWeight(_ priority: KanjiUsagePriority?) -> Float {
        guard let priority else { return 0 }
        let category: Float
        if priority.isJoyo {
            category = joyoWeight
        } else if priority.isJinmeiyo {
            category = jinmeiyoWeight
        } else {
            category = 0
        }
        let frequency: Float
        switch priority.frequency {
        case 1...500: frequency = veryCommonWeight
        case 501...1_000: frequency = commonWeight
        case 1_001...2_500: frequency = frequencyWeight
        default: frequency = 0
        }
        return category + frequency
    }

    /// Han by code-point block. The recognizer only ever emits JIS X 0208 labels, but the
    /// wider blocks keep supplementary-plane text out of the kana slots as well.
    private static func isHan(_ text: String) -> Bool {
        guard let scalar = text.unicodeScalars.first else { return false }
        return hanRanges.contains { $0.contains(scalar.value) }
    }

    private static let hanRanges: [ClosedRange<UInt32>] = [
        0x2E80...0x2EF3,   // CJK Radicals Supplement
        0x2F00...0x2FD5,   // Kangxi Radicals
        0x3005...0x3005,   // 々
        0x3007...0x3007,   // 〇
        0x3021...0x3029,   // Hangzhou numerals
        0x3038...0x303B,
        0x3400...0x4DBF,   // Extension A
        0x4E00...0x9FFF,   // CJK Unified Ideographs
        0xF900...0xFAD9,   // Compatibility Ideographs
        0x20000...0x2A6DF, // Extension B
        0x2A700...0x2EBEF, // Extensions C-F
        0x2F800...0x2FA1D, // Compatibility Supplement
        0x30000...0x323AF  // Extensions G-H
    ]

    static let clearShapeGap: Float = 0.2
    // Kept below clearShapeGap as a second guard against crossing shape evidence.
    private static let maximumUsageDiscount: Float = 0.12
    private static let joyoWeight: Float = 1.0
    private static let jinmeiyoWeight: Float = 0.2
    private static let veryCommonWeight: Float = 0.7
    private static let commonWeight: Float = 0.45
    private static let frequencyWeight: Float = 0.2
    private static let maximumUsageWeight: Float = joyoWeight + veryCommonWeight
}
