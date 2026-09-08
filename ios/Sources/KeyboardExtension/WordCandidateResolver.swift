import Foundation

/// A complete handwriting surface whose shape evidence must survive word resolution.
struct ShapedSurfaceCandidate: Equatable {
    let surface: String
    let shapeCost: Float
    let isRecognizerRawTop: Bool

    init(surface: String, shapeCost: Float, isRecognizerRawTop: Bool) {
        self.surface = surface
        self.shapeCost = shapeCost
        self.isRecognizerRawTop = isRecognizerRawTop
    }
}

/// A dictionary-enriched surface that still carries where its shape evidence came from.
struct ShapedWordCandidate: Equatable {
    let surface: String
    let readings: [String]
    let shapeCost: Float
    let isRecognizerRawTop: Bool
}

/// A ranked candidate with the lexical evidence that moved it made inspectable.
struct ResolvedWordCandidate: Equatable {
    let surface: String
    let readings: [String]
    /// The shape cost the recognizer produced, before dictionary evidence.
    let rankingShapeCost: Float
    /// The cost actually ordered on.
    let shapeCost: Float
    let isRecognizerRawTop: Bool
    let lexicalEvidence: SurfaceLexicalEvidence?
}

/// Ranks exact and prefix dictionary matches without losing what the recognizer read.
enum WordCandidateResolver {
    /// Carry recognition shape evidence through exact, unknown, and completion candidates.
    /// Base recognition surfaces are retained even when the dictionary has no reading.
    static func shapedCandidates(
        _ surfaces: [ShapedSurfaceCandidate],
        exactReadings: [String: [String]],
        suggestions: [String: [WordCandidate]]
    ) -> [ShapedWordCandidate] {
        let normalized = distinctSurfaces(surfaces)
        var order: [String] = []
        var output: [String: ShapedWordCandidate] = [:]
        for shaped in normalized {
            order.append(shaped.surface)
            output[shaped.surface] = ShapedWordCandidate(
                surface: shaped.surface,
                readings: exactReadings[shaped.surface] ?? [],
                shapeCost: shaped.shapeCost,
                isRecognizerRawTop: shaped.isRecognizerRawTop
            )
        }
        for shaped in normalized {
            for suggestion in suggestions[shaped.surface] ?? [] {
                guard suggestion.surface != shaped.surface,
                      output[suggestion.surface] == nil,
                      output.count < maximumSurfaces else { continue }
                order.append(suggestion.surface)
                // A completion inherits the shape evidence of the surface it extends, but it
                // is not what the recognizer read, so it can never claim the raw top.
                output[suggestion.surface] = ShapedWordCandidate(
                    surface: suggestion.surface,
                    readings: suggestion.readings,
                    shapeCost: shaped.shapeCost,
                    isRecognizerRawTop: false
                )
            }
        }
        return order.prefix(maximumSurfaces).compactMap { output[$0] }
    }

    /// Stable lower-is-better ranking with bounded lexical evidence and raw-top retention.
    static func resolveShaped(
        _ candidates: [ShapedWordCandidate],
        lexicalEvidence: [String: SurfaceLexicalEvidence],
        limit: Int
    ) -> [ResolvedWordCandidate] {
        var seen = Set<String>()
        let resolved = candidates
            .filter { !$0.surface.isEmpty && seen.insert($0.surface).inserted }
            .prefix(maximumSurfaces)
            .enumerated()
            .map { index, candidate -> (offset: Int, value: ResolvedWordCandidate) in
                // Shape costs arrive normalized. A cost outside the band would mean an
                // unnormalized batch reached this far, so treat it as the worst shape
                // evidence rather than trusting it or trapping inside the keyboard.
                let ranking = candidate.shapeCost.isFinite
                    ? min(max(candidate.shapeCost, 0), 1)
                    : 1
                let evidence = lexicalEvidence[candidate.surface]
                return (
                    index,
                    ResolvedWordCandidate(
                        surface: candidate.surface,
                        readings: candidate.readings,
                        rankingShapeCost: ranking,
                        shapeCost: evidence?.applyToShapeCost(ranking) ?? ranking,
                        isRecognizerRawTop: candidate.isRecognizerRawTop,
                        lexicalEvidence: evidence
                    )
                )
            }
            .sorted { lhs, rhs in
                if lhs.value.shapeCost != rhs.value.shapeCost {
                    return lhs.value.shapeCost < rhs.value.shapeCost
                }
                return lhs.offset < rhs.offset
            }
            .map(\.value)
        return takeRetainingRawTop(resolved, limit: min(max(limit, 0), maximumSurfaces)) {
            $0.isRecognizerRawTop
        }
    }

    private static func distinctSurfaces(
        _ surfaces: [ShapedSurfaceCandidate]
    ) -> [ShapedSurfaceCandidate] {
        var seen = Set<String>()
        return surfaces.filter { !$0.surface.isEmpty && seen.insert($0.surface).inserted }
            .prefix(maximumSurfaces)
            .map { $0 }
    }

    /// The character the recognizer actually read always keeps a slot, even when the
    /// dictionary would otherwise crowd it out of the visible list.
    private static func takeRetainingRawTop<T>(
        _ values: [T],
        limit: Int,
        isRawTop: (T) -> Bool
    ) -> [T] {
        guard limit > 0 else { return [] }
        var selected = Array(values.prefix(limit))
        guard let rawTop = values.first(where: isRawTop) else { return selected }
        if !selected.contains(where: isRawTop) {
            if selected.count == limit { selected.removeLast() }
            selected.append(rawTop)
        }
        return selected
    }

    private static let maximumSurfaces = SurfaceLexicalEvidenceBatch.maximumSurfaces
}
