import Foundation

/// Why a candidate is eligible at the terminal edge of a composition.
enum WholeCompositionCandidateKind: Equatable {
    /// A conversion whose reading consumes the complete composition.
    case wholeConversion
    /// A completion whose reading strictly extends the complete composition.
    case prediction
    /// Plain hiragana/katakana supplied by the caller as a last-resort choice.
    case scriptFallback
}

/// A terminal-composition candidate with its presentation policy made explicit.
struct WholeCompositionCandidate: Equatable {
    let surface: String
    let readings: [String]
    let kind: WholeCompositionCandidateKind
}

/// Pure ordering and eligibility policy for candidates shown at the composition end.
///
/// Dictionary/converter order is preserved within each category. Exact whole-reading candidates
/// precede predictions, and script fallbacks come last. A candidate supported only by a strict
/// reading prefix is excluded: terminal conversion must never turn a partial lexeme such as the
/// left side of `word + particle` into an ordinary whole-composition candidate.
enum WholeCompositionCandidatePolicy {
    /// `dictionaryCandidates` must retain converter/repository order and be over-fetched beyond
    /// the display capacity. This function owns `limit`, so rejected prefix-only entries cannot
    /// consume slots that should be available to later whole candidates or fallbacks.
    static func build(
        reading: String,
        dictionaryCandidates: [WordCandidate],
        scriptFallbacks: [WordCandidate],
        limit: Int
    ) -> [WholeCompositionCandidate] {
        guard !reading.isEmpty, limit > 0 else { return [] }

        let classified = dictionaryCandidates.compactMap { candidate -> WholeCompositionCandidate? in
            guard !candidate.surface.isEmpty else { return nil }
            let kind: WholeCompositionCandidateKind
            if candidate.readings.contains(reading) {
                kind = .wholeConversion
            } else if candidate.readings.contains(where: { $0.isStrictExtension(of: reading) }) {
                kind = .prediction
            } else {
                return nil
            }
            return WholeCompositionCandidate(
                surface: candidate.surface,
                readings: candidate.readings,
                kind: kind
            )
        }
        let ordered = classified.filter { $0.kind == .wholeConversion } +
            classified.filter { $0.kind == .prediction } +
            scriptFallbacks.filter { !$0.surface.isEmpty }.map {
                WholeCompositionCandidate(surface: $0.surface, readings: $0.readings, kind: .scriptFallback)
            }

        var seen = Set<String>()
        return ordered.filter { seen.insert($0.surface).inserted }.prefix(limit).map { $0 }
    }
}

private extension String {
    func isStrictExtension(of prefix: String) -> Bool {
        hasPrefix(prefix) && unicodeScalars.count > prefix.unicodeScalars.count
    }
}
