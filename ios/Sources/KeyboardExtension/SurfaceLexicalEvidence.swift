import Foundation

/// Dictionary evidence for one exact handwriting surface.
///
/// `costAdjustment` shares the shape cost's lower-is-better direction. It is deliberately
/// non-positive: dictionary hits receive a small bounded discount while an unknown surface
/// stays neutral instead of receiving a large penalty.
struct SurfaceLexicalEvidence: Equatable {
    let surface: String
    let exact: Bool
    let properName: Bool
    let placeName: Bool
    let costAdjustment: Float

    var unknown: Bool { !exact }
    var named: Bool { properName || placeName }

    init(surface: String, exact: Bool, properName: Bool, placeName: Bool, costAdjustment: Float) {
        precondition(!surface.isEmpty, "Evidence requires a non-empty surface")
        precondition(
            costAdjustment.isFinite &&
                costAdjustment >= Self.minimumCostAdjustment && costAdjustment <= 0,
            "Lexical cost adjustment must be in [\(Self.minimumCostAdjustment), 0]"
        )
        self.surface = surface
        self.exact = exact
        self.properName = properName
        self.placeName = placeName
        self.costAdjustment = costAdjustment
    }

    /// Apply this bounded evidence to the shape cost's finite `[0, 1]` band.
    func applyToShapeCost(_ shapeCost: Float) -> Float {
        precondition(
            shapeCost.isFinite && shapeCost >= 0 && shapeCost <= 1,
            "Shape cost must be finite and in [0, 1]"
        )
        return min(max(shapeCost + costAdjustment, 0), 1)
    }

    static let minimumCostAdjustment: Float = -0.08
    private static let properNameCostAdjustment: Float = -0.04

    static func match(
        surface: String,
        exact: Bool,
        properName: Bool,
        placeName: Bool
    ) -> SurfaceLexicalEvidence {
        let adjustment: Float
        if properName || placeName {
            adjustment = properNameCostAdjustment
        } else if exact {
            adjustment = minimumCostAdjustment
        } else {
            adjustment = 0
        }
        return SurfaceLexicalEvidence(
            surface: surface,
            exact: exact,
            properName: properName,
            placeName: placeName,
            costAdjustment: adjustment
        )
    }

    static func unknown(_ surface: String) -> SurfaceLexicalEvidence {
        match(surface: surface, exact: false, properName: false, placeName: false)
    }
}

/// Shared input normalization so every evidence reader answers the same bounded batch.
enum SurfaceLexicalEvidenceBatch {
    static let maximumSurfaces = 24

    static func normalize(_ surfaces: [String]) -> [String] {
        var seen = Set<String>()
        return surfaces.filter { !$0.isEmpty && seen.insert($0).inserted }
            .prefix(maximumSurfaces)
            .map { $0 }
    }

    static func unknown(_ surfaces: [String]) -> [String: SurfaceLexicalEvidence] {
        normalize(surfaces).reduce(into: [:]) { output, surface in
            output[surface] = SurfaceLexicalEvidence.unknown(surface)
        }
    }
}
