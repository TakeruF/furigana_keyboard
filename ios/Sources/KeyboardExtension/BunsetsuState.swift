import Foundation

struct CommittedBunsetsuSegment: Equatable {
    let reading: String
    let surface: String
    let rightID: Int
}

struct BunsetsuStateToken: Equatable {
    let generation: Int
    let remainingReading: String
    let activeReading: String
    let previousRightID: Int
    let previousContextSurface: String?
}

struct BunsetsuStateOption: Equatable {
    let surface: String
    let reading: String
    let rightID: Int
}

/// Context-preserving state for sequential bunsetsu conversion.
final class BunsetsuState {
    /// The untouched reading that started this conversion session.
    let reading: String

    private(set) var committedSegments: [CommittedBunsetsuSegment] = []
    private(set) var remainingSegmentCandidates: [ConversionSegment]
    private(set) var previousRightID = 0
    private(set) var previousContextSurface: String?
    private(set) var generation = 0
    private(set) var activeLength: Int

    private var remainingScalars: [Unicode.Scalar]
    private var conversionPaths: [[ConversionSegment]]

    init(
        reading: String,
        initialLength: Int,
        conversionPaths: [[ConversionSegment]] = []
    ) {
        self.reading = reading
        let scalars = Array(reading.unicodeScalars)
        let paths = conversionPaths.filter { Self.isCompletePath($0, length: scalars.count) }
        remainingScalars = scalars
        self.conversionPaths = paths
        remainingSegmentCandidates = paths.first ?? []
        activeLength = scalars.isEmpty
            ? 0
            : max(1, min(initialLength, scalars.count))
    }

    var committedSurface: String { committedSegments.map(\.surface).joined() }
    var offset: Int { committedSegments.reduce(0) { $0 + $1.reading.unicodeScalars.count } }
    var remainingCharacters: [Unicode.Scalar] { remainingScalars }
    var remainingReading: String { Self.string(from: remainingScalars[...]) }
    var activeReading: String { Self.string(from: remainingScalars.prefix(activeLength)) }
    var trailingReading: String { Self.string(from: remainingScalars.dropFirst(activeLength)) }
    var markedText: String { committedSurface + remainingReading }
    var canShrink: Bool { activeLength > 1 }
    var canExpand: Bool { activeLength < remainingScalars.count }
    var isComplete: Bool { remainingScalars.isEmpty }

    var activeRightID: Int {
        for path in conversionPaths {
            if let segment = path.last(where: { $0.end == activeLength }) { return segment.rightID }
        }
        if let segment = remainingSegmentCandidates.last(where: { $0.end == activeLength }) {
            return segment.rightID
        }
        return 15
    }

    func token() -> BunsetsuStateToken {
        BunsetsuStateToken(
            generation: generation,
            remainingReading: remainingReading,
            activeReading: activeReading,
            previousRightID: previousRightID,
            previousContextSurface: previousContextSurface
        )
    }

    func isCurrent(_ token: BunsetsuStateToken) -> Bool {
        token.generation == generation &&
            token.remainingReading == remainingReading &&
            token.activeReading == activeReading &&
            token.previousRightID == previousRightID &&
            token.previousContextSurface == previousContextSurface
    }

    func isCurrentCandidate(reading: String?, generation candidateGeneration: Int?) -> Bool {
        (candidateGeneration == nil || candidateGeneration == generation) &&
            (reading == nil || reading == activeReading)
    }

    func shrink() {
        guard canShrink else { return }
        activeLength -= 1
        mutate()
    }

    func expand() {
        guard canExpand else { return }
        activeLength += 1
        mutate()
    }

    /// Applies a full-suffix analysis only if it still belongs to this exact state.
    @discardableResult
    func applyAnalysis(
        _ conversions: [KanaKanjiConversion],
        token: BunsetsuStateToken,
        requestedBoundary: Int?
    ) -> Bool {
        guard isCurrent(token), !conversions.isEmpty else { return false }
        let paths = conversions.map(\.segments).filter { path in
            Self.isCompletePath(path, length: remainingScalars.count) &&
                (requestedBoundary == nil || path.contains { $0.end == requestedBoundary })
        }
        guard let first = paths.first else { return false }
        conversionPaths = paths
        remainingSegmentCandidates = first
        if let requestedBoundary {
            activeLength = requestedBoundary
        } else {
            activeLength = Self.naturalLeadingLength(first, totalLength: remainingScalars.count)
        }
        mutate()
        return true
    }

    func options() -> [BunsetsuStateOption] {
        var seen = Set<String>()
        var output: [BunsetsuStateOption] = []
        for path in conversionPaths {
            let prefix = path.prefix { $0.end <= activeLength }
            guard let last = prefix.last, last.end == activeLength else { continue }
            let surface = prefix.map(\.surface).joined()
            let key = "\(surface)\u{0}\(last.rightID)"
            guard seen.insert(key).inserted else { continue }
            output.append(BunsetsuStateOption(
                surface: surface,
                reading: activeReading,
                rightID: last.rightID
            ))
        }
        return output
    }

    /// Selects only the active prefix and retains the chosen path for its suffix.
    @discardableResult
    func select(surface: String, reading selectedReading: String? = nil, rightID: Int? = nil) -> Bool {
        guard !activeReading.isEmpty,
              selectedReading == nil || selectedReading == activeReading else { return false }

        let boundary = activeLength
        let matchingPath = conversionPaths.first { path in
            let prefix = path.prefix { $0.end <= boundary }
            guard let last = prefix.last, last.end == boundary else { return false }
            return prefix.map(\.surface).joined() == surface &&
                (rightID == nil || last.rightID == rightID)
        }
        let chosenPath = matchingPath ?? remainingSegmentCandidates
        let selectedRightID = rightID
            ?? chosenPath.last(where: { $0.end == boundary })?.rightID
            ?? activeRightID
        let selectedReadingValue = activeReading

        committedSegments.append(CommittedBunsetsuSegment(
            reading: selectedReadingValue,
            surface: surface,
            rightID: selectedRightID
        ))
        previousRightID = selectedRightID
        previousContextSurface = matchingPath.map { path in
            KanaKanjiConverter.contextSurfaceAfter(
                initialSurface: previousContextSurface,
                segments: Array(path.prefix { $0.end <= boundary })
            )
        } ?? nil
        remainingScalars.removeFirst(boundary)

        let rebased = Self.rebaseSuffix(chosenPath, boundary: boundary)
        remainingSegmentCandidates = rebased
        conversionPaths = conversionPaths.compactMap { path in
            let suffix = Self.rebaseSuffix(path, boundary: boundary)
            return suffix.isEmpty ? nil : suffix
        }
        if !rebased.isEmpty && !conversionPaths.contains(where: { Self.samePath($0, rebased) }) {
            conversionPaths.insert(rebased, at: 0)
        }
        activeLength = remainingScalars.isEmpty
            ? 0
            : Self.naturalLeadingLength(rebased, totalLength: remainingScalars.count)
        mutate()
        return true
    }

    func setSuggestedLength(_ length: Int) {
        guard !remainingScalars.isEmpty else { return }
        let next = max(1, min(length, remainingScalars.count))
        guard next != activeLength else { return }
        activeLength = next
        mutate()
    }

    /// Deletes only from the uncommitted suffix and keeps committed POS context.
    @discardableResult
    func deleteLastScalar() -> Bool {
        guard !remainingScalars.isEmpty else { return false }
        remainingScalars.removeLast()
        remainingSegmentCandidates = []
        conversionPaths = []
        activeLength = remainingScalars.isEmpty
            ? 0
            : max(1, min(activeLength, remainingScalars.count))
        mutate()
        return true
    }

    private func mutate() { generation += 1 }

    private static func naturalLeadingLength(
        _ segments: [ConversionSegment],
        totalLength: Int
    ) -> Int {
        guard totalLength > 0 else { return 0 }
        for (left, right) in zip(segments, segments.dropFirst()) {
            if closesBunsetsu(left.rightID) && startsContent(right.leftID) {
                return min(left.end, totalLength)
            }
        }
        // Empty/unknown analyses use the whole suffix, never a one-scalar reset.
        return totalLength
    }

    private static func rebaseSuffix(
        _ segments: [ConversionSegment],
        boundary: Int
    ) -> [ConversionSegment] {
        guard !segments.isEmpty, segments.contains(where: { $0.end == boundary }) else { return [] }
        return segments.filter { $0.start >= boundary }.map { segment in
            ConversionSegment(
                start: segment.start - boundary,
                end: segment.end - boundary,
                reading: segment.reading,
                surface: segment.surface,
                leftID: segment.leftID,
                rightID: segment.rightID,
                wordCost: segment.wordCost,
                isCopy: segment.isCopy
            )
        }
    }

    private static func isCompletePath(_ path: [ConversionSegment], length: Int) -> Bool {
        guard let first = path.first, let last = path.last,
              first.start == 0, last.end == length else { return false }
        return zip(path, path.dropFirst()).allSatisfy { $0.end == $1.start }
    }

    private static func samePath(_ left: [ConversionSegment], _ right: [ConversionSegment]) -> Bool {
        guard left.count == right.count else { return false }
        return zip(left, right).allSatisfy {
            $0.start == $1.start && $0.end == $1.end && $0.surface == $1.surface &&
                $0.leftID == $1.leftID && $0.rightID == $1.rightID
        }
    }

    private static func closesBunsetsu(_ posID: Int) -> Bool {
        posID == 5 || posID == 6 || posID == 11
    }

    private static func startsContent(_ posID: Int) -> Bool {
        [2, 3, 4, 7, 8, 9, 10, 12, 14, 15].contains(posID)
    }

    private static func string<S: Sequence>(from scalars: S) -> String where S.Element == Unicode.Scalar {
        var output = ""
        output.unicodeScalars.append(contentsOf: scalars)
        return output
    }
}
