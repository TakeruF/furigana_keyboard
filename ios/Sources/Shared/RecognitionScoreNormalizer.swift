import Foundation

/// Maps recognizer-specific output to the shared lower-is-better `[0, 1]` shape-cost band.
///
/// Native scores are anchored at best = 0 without stretching a narrow score range.
/// If any native score is NaN, infinite, or absent, the entire batch falls back to a
/// rank mapping that advances by 0.1 per unique candidate. Equal finite native scores
/// therefore have equal cost instead of inventing a shape difference.
enum RecognitionScoreNormalizer {
    static func normalize(
        _ rawCandidates: [RawRecognitionCandidate],
        source: RecognitionSource
    ) -> [RecognitionCandidate] {
        precondition(
            source != .sideBySide,
            "Side-by-side candidates must retain component evidence"
        )

        // Exact duplicates use their first recognizer occurrence. The retained rank is
        // deliberately the pre-deduplication rank so the source output remains auditable.
        var seen = Set<String>()
        let unique = rawCandidates.enumerated().filter { seen.insert($0.element.text).inserted }
        guard !unique.isEmpty else { return [] }

        let scores = unique.compactMap { $0.element.score }
        let nativeScoresAreUsable = scores.count == unique.count && scores.allSatisfy(\.isFinite)
        let bestNativeScore = nativeScoresAreUsable ? scores.max() ?? 0 : 0
        let worstNativeScore = nativeScoresAreUsable ? scores.min() ?? 0 : 0
        let nativeScale = max(bestNativeScore - worstNativeScore, minimumNativeScoreRange)

        return unique.enumerated().map { uniqueRank, indexed in
            let rankCost = min(Float(uniqueRank) * rankCostStep, maximumShapeCost)
            let shapeCost: Float
            if !nativeScoresAreUsable {
                shapeCost = rankCost
            } else if bestNativeScore == worstNativeScore {
                shapeCost = 0
            } else {
                let score = indexed.element.score ?? worstNativeScore
                shapeCost = min(max((bestNativeScore - score) / nativeScale, 0), maximumShapeCost)
            }
            return RecognitionCandidate(
                text: indexed.element.text,
                shapeCost: shapeCost,
                evidence: RecognitionEvidence(
                    source: source,
                    rawScore: indexed.element.score,
                    rawRank: indexed.offset
                )
            )
        }
        .sorted { lhs, rhs in
            if lhs.shapeCost != rhs.shapeCost { return lhs.shapeCost < rhs.shapeCost }
            return (lhs.evidence.rawRank ?? 0) < (rhs.evidence.rawRank ?? 0)
        }
    }

    /// Normalize bridged candidates unless they already carry a finite common cost.
    static func normalizeIfNeeded(
        _ candidates: [RecognitionCandidate],
        source: RecognitionSource = .legacyNative
    ) -> [RecognitionCandidate] {
        let alreadyNormalized = candidates.allSatisfy {
            $0.shapeCost.isFinite && $0.shapeCost >= 0 && $0.shapeCost <= maximumShapeCost
        }
        if alreadyNormalized { return candidates }
        return normalize(
            candidates.map { RawRecognitionCandidate($0.text, $0.evidence.rawScore) },
            source: source
        )
    }

    private static let rankCostStep: Float = 0.1
    private static let minimumNativeScoreRange: Float = 1
    private static let maximumShapeCost: Float = 1
}
