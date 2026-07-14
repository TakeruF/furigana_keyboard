import Foundation

struct WordCandidate: Equatable {
    let surface: String
    let readings: [String]
}

/// Dictionary edge whose start/end are Unicode-scalar offsets into reading.
struct ConversionLexeme {
    let start: Int
    let end: Int
    let reading: String
    let surface: String
    let leftID: Int
    let rightID: Int
    let wordCost: Int
}

struct ConversionConnection {
    let rightID: Int
    let leftID: Int
    let cost: Int
}

struct ConversionSegment {
    let start: Int
    let end: Int
    let reading: String
    let surface: String
    let leftID: Int
    let rightID: Int
    let wordCost: Int
    let isCopy: Bool
}

struct KanaKanjiConversion {
    let surface: String
    let reading: String
    let cost: Int
    let segments: [ConversionSegment]
}

enum KanaKanjiConverter {
    private static let maxInputScalars = 48
    private static let maxLexemeScalars = 16
    private static let maxVocabularyPerReading = 12
    private static let maxOutput = 8
    private static let defaultConnectionCost = 3_000
    private static let copyWordCost = 4_000
    private static let katakanaFallbackBaseCost = 1_000
    private static let katakanaFallbackScalarCost = 500
    private static let maxKatakanaFallbackScalars = 8
    private static let katakanaCompoundJoinCost = 200
    private static let maxKatakanaCompoundPieces = 4
    private static let shortHanSequencePenalty = 900
    private static let loanwordFragmentPenalty = 1_600
    private static let loanwordScriptAlternationPenalty = 900

    private struct ConnectionKey: Hashable { let right: Int; let left: Int }
    private struct VocabularyKey: Hashable {
        let start: Int
        let end: Int
        let reading: [UInt32]
    }
    private struct StateKey: Hashable {
        let end: Int
        let right: Int
        let surface: [UInt32]
        let loanwordAlternations: Int
        let previousContextSurface: [UInt32]?
        let bunsetsuBoundaries: [Int]
    }
    private struct ResultKey: Hashable {
        let surface: [UInt32]
        let bunsetsuBoundaries: [Int]
    }
    private struct SyntheticEdgeKey: Hashable {
        let start: Int
        let end: Int
        let surface: [UInt32]
        let leftID: Int
        let rightID: Int
    }
    private struct Edge {
        let start: Int
        let end: Int
        let reading: String
        let surface: String
        let leftID: Int
        let rightID: Int
        let wordCost: Int
        let isCopy: Bool
        let isKatakana: Bool
    }
    private struct State {
        let end: Int
        let rightID: Int
        let surface: String
        let cost: Int64
        let copied: Int
        let loanwordAlternations: Int
        let previousContextSurface: String?
        let segments: [ConversionSegment]
    }
    private struct PathPenalty { let cost: Int; let alternations: Int }
    private struct ContextTransition { let cost: Int; let previousSurface: String? }
    private enum FragmentScript { case han, unconvertedKana, other }

    static func convert(
        reading: String,
        lexemes: [ConversionLexeme],
        connections: [ConversionConnection],
        limit: Int = 8,
        preserveSegmentations: Bool = false,
        initialRightID: Int = 0,
        initialContextSurface: String? = nil,
        requiredBoundary: Int? = nil,
        contextModel: ConversionContextModel = .empty,
        beamWidth: Int = 12,
        isCancelled: () -> Bool = { false }
    ) -> [KanaKanjiConversion] {
        if isCancelled() { return [] }
        let boundaries = ConversionText.scalarBoundaries(reading)
        let scalarCount = boundaries.count - 1
        guard scalarCount > 0, scalarCount <= maxInputScalars, limit > 0, beamWidth > 0 else {
            return []
        }
        if let requiredBoundary, !(1...scalarCount).contains(requiredBoundary) { return [] }

        var connectionCosts: [ConnectionKey: Int] = [:]
        for connection in connections {
            if isCancelled() { return [] }
            let key = ConnectionKey(right: connection.rightID, left: connection.leftID)
            let cost = clampedInputCost(connection.cost)
            connectionCosts[key] = min(connectionCosts[key] ?? Int.max, cost)
        }

        var usableLexemes: [ConversionLexeme] = []
        for lexeme in lexemes {
            if isCancelled() { return [] }
            guard lexeme.start >= 0,
                  lexeme.end > lexeme.start,
                  lexeme.end <= scalarCount,
                  ConversionText.scalarCount(lexeme.reading) <= maxLexemeScalars,
                  let token = ConversionText.scalarSubstring(
                    reading,
                    start: lexeme.start,
                    end: lexeme.end
                  ),
                  ConversionText.scalarEquals(token, lexeme.reading) else { continue }
            usableLexemes.append(ConversionLexeme(
                start: lexeme.start,
                end: lexeme.end,
                reading: lexeme.reading,
                surface: lexeme.surface,
                leftID: lexeme.leftID,
                rightID: lexeme.rightID,
                wordCost: clampedInputCost(lexeme.wordCost)
            ))
        }
        usableLexemes.sort(by: lexemeOrder)

        var vocabularyCounts: [VocabularyKey: Int] = [:]
        var dictionaryEdges: [Int: [ConversionLexeme]] = [:]
        for lexeme in usableLexemes {
            if isCancelled() { return [] }
            let key = VocabularyKey(
                start: lexeme.start,
                end: lexeme.end,
                reading: ConversionText.scalarValues(lexeme.reading)
            )
            let count = vocabularyCounts[key] ?? 0
            if count < maxVocabularyPerReading {
                dictionaryEdges[lexeme.start, default: []].append(lexeme)
                vocabularyCounts[key] = count + 1
            }
        }

        let katakanaPieces = usableLexemes.filter {
            ConversionText.isKatakanaTransliteration(reading: $0.reading, surface: $0.surface)
        }.map { lexeme in
            Edge(
                start: lexeme.start, end: lexeme.end,
                reading: lexeme.reading, surface: lexeme.surface,
                leftID: lexeme.leftID, rightID: lexeme.rightID,
                wordCost: lexeme.wordCost, isCopy: false, isKatakana: true
            )
        }
        let syntheticKatakanaEdges = katakanaEdges(
            reading: reading,
            boundaries: boundaries,
            pieces: katakanaPieces,
            lexemes: usableLexemes
        )
        let loanwordPositions = loanwordPositions(
            reading: reading,
            boundaries: boundaries
        )

        var states: [Int: [State]] = [
            0: [State(end: 0, rightID: initialRightID, surface: "", cost: 0, copied: 0,
                      loanwordAlternations: 0,
                      previousContextSurface: initialContextSurface, segments: [])]
        ]
        for start in 0..<scalarCount {
            if isCancelled() { return [] }
            let incoming = prune(
                states[start] ?? [],
                preserveSegmentations: preserveSegmentations,
                beamWidth: beamWidth
            )
            guard !incoming.isEmpty else { continue }
            let copyEnd = start + 1
            let copied = String(reading[boundaries[start]..<boundaries[copyEnd]])
            var edges = (dictionaryEdges[start] ?? []).map { lexeme in
                Edge(
                    start: lexeme.start,
                    end: lexeme.end,
                    reading: lexeme.reading,
                    surface: lexeme.surface,
                    leftID: lexeme.leftID,
                    rightID: lexeme.rightID,
                    wordCost: clampedInputCost(lexeme.wordCost),
                    isCopy: false,
                    isKatakana: ConversionText.isKatakanaTransliteration(
                        reading: lexeme.reading,
                        surface: lexeme.surface
                    )
                )
            }
            edges.append(contentsOf: syntheticKatakanaEdges[start] ?? [])
            edges.append(Edge(
                start: start,
                end: copyEnd,
                reading: copied,
                surface: copied,
                leftID: 15,
                rightID: 15,
                wordCost: copyWordCost,
                isCopy: true,
                isKatakana: false
            ))
            if let requiredBoundary, start < requiredBoundary {
                edges.removeAll { $0.end > requiredBoundary }
            }
            edges.sort(by: edgeOrder)

            for state in incoming {
                for edge in edges {
                    if isCancelled() { return [] }
                    let penalty = pathPenalty(
                        previous: state.segments.last,
                        edge: edge,
                        previousAlternations: state.loanwordAlternations,
                        loanwordPositions: loanwordPositions
                    )
                    let connection = connectionCosts[
                        ConnectionKey(right: state.rightID, left: edge.leftID)
                    ] ?? defaultConnectionCost
                    let context = contextTransition(
                        model: contextModel,
                        previousSurface: state.previousContextSurface,
                        edge: edge
                    )
                    let segment = ConversionSegment(
                        start: edge.start,
                        end: edge.end,
                        reading: edge.reading,
                        surface: edge.surface,
                        leftID: edge.leftID,
                        rightID: edge.rightID,
                        wordCost: edge.wordCost,
                        isCopy: edge.isCopy
                    )
                    states[edge.end, default: []].append(State(
                        end: edge.end,
                        rightID: edge.rightID,
                        surface: state.surface + edge.surface,
                        cost: state.cost + Int64(edge.wordCost) + Int64(connection)
                            + Int64(penalty.cost) + Int64(context.cost),
                        copied: state.copied + (edge.isCopy ? 1 : 0),
                        loanwordAlternations: penalty.alternations,
                        previousContextSurface: context.previousSurface,
                        segments: state.segments + [segment]
                    ))
                }
            }
        }

        if isCancelled() { return [] }
        let completed = prune(
            states[scalarCount] ?? [],
            preserveSegmentations: preserveSegmentations,
            beamWidth: beamWidth
        ).compactMap { state -> State? in
            guard state.segments.contains(where: { !$0.isCopy }) else { return nil }
            if let requiredBoundary,
               !state.segments.contains(where: { $0.end == requiredBoundary }) { return nil }
            let end = connectionCosts[ConnectionKey(right: state.rightID, left: 1)]
                ?? defaultConnectionCost
            return State(
                end: state.end,
                rightID: state.rightID,
                surface: state.surface,
                cost: state.cost + Int64(end),
                copied: state.copied,
                loanwordAlternations: state.loanwordAlternations,
                previousContextSurface: state.previousContextSurface,
                segments: state.segments
            )
        }.sorted(by: stateOrder)

        var seen = Set<ResultKey>()
        var output: [KanaKanjiConversion] = []
        for state in completed {
            if isCancelled() { return [] }
            let key = ResultKey(
                surface: ConversionText.scalarValues(state.surface),
                bunsetsuBoundaries: preserveSegmentations ? bunsetsuBoundaries(state.segments) : []
            )
            guard seen.insert(key).inserted else { continue }
            output.append(KanaKanjiConversion(
                surface: state.surface,
                reading: reading,
                cost: ConversionCost.clampInt32(state.cost),
                segments: state.segments
            ))
            if output.count >= min(limit, maxOutput) { break }
        }
        return output
    }

    /// Particles and auxiliaries preserve the preceding content surface, so
    /// 学校 + へ + 行きます is scored by the 学校→行きます model entry.
    private static func contextTransition(
        model: ConversionContextModel,
        previousSurface: String?,
        edge: Edge
    ) -> ContextTransition {
        if edge.isCopy { return ContextTransition(cost: 0, previousSurface: nil) }
        if edge.leftID == 5 || edge.leftID == 6 {
            return ContextTransition(cost: 0, previousSurface: previousSurface)
        }
        if [2, 3, 4, 7, 8, 9, 12].contains(edge.leftID) {
            return ContextTransition(
                cost: model.cost(previousSurface: previousSurface, nextSurface: edge.surface),
                previousSurface: edge.surface
            )
        }
        return ContextTransition(cost: 0, previousSurface: nil)
    }

    /// Carry the model's content-surface state across a confirmed bunsetsu.
    static func contextSurfaceAfter(
        initialSurface: String?,
        segments: [ConversionSegment]
    ) -> String? {
        segments.reduce(initialSurface) { previous, segment in
            if segment.isCopy { return nil }
            if segment.leftID == 5 || segment.leftID == 6 { return previous }
            if [2, 3, 4, 7, 8, 9, 12].contains(segment.leftID) {
                return segment.surface
            }
            return nil
        }
    }

    /// Join adjacent dictionary-backed katakana pieces into one scored edge,
    /// and keep the whole-input script candidate inside the same lattice.
    private static func katakanaEdges(
        reading: String,
        boundaries: [String.Index],
        pieces: [Edge],
        lexemes: [ConversionLexeme]
    ) -> [Int: [Edge]] {
        let scalarCount = boundaries.count - 1
        let piecesByStart = Dictionary(grouping: pieces, by: \.start)
        var output: [Int: [Edge]] = [:]
        var seen = Set<SyntheticEdgeKey>()

        func append(_ edge: Edge) {
            let key = SyntheticEdgeKey(
                start: edge.start,
                end: edge.end,
                surface: ConversionText.scalarValues(edge.surface),
                leftID: edge.leftID,
                rightID: edge.rightID
            )
            if seen.insert(key).inserted { output[edge.start, default: []].append(edge) }
        }

        func extend(first: Edge, last: Edge, surface: String, wordCost: Int64, count: Int) {
            if count >= 2 {
                append(Edge(
                    start: first.start,
                    end: last.end,
                    reading: String(reading[boundaries[first.start]..<boundaries[last.end]]),
                    surface: surface,
                    leftID: first.leftID,
                    rightID: last.rightID,
                    wordCost: ConversionCost.clampInt32(wordCost),
                    isCopy: false,
                    isKatakana: true
                ))
            }
            guard count < maxKatakanaCompoundPieces else { return }
            for next in piecesByStart[last.end] ?? [] where next.end - first.start <= maxLexemeScalars {
                extend(
                    first: first,
                    last: next,
                    surface: surface + next.surface,
                    wordCost: wordCost + Int64(next.wordCost) + Int64(katakanaCompoundJoinCost),
                    count: count + 1
                )
            }
        }

        for piece in pieces {
            extend(first: piece, last: piece, surface: piece.surface,
                   wordCost: Int64(piece.wordCost), count: 1)
        }
        for start in 0..<scalarCount {
            let finalEnd = min(scalarCount, start + maxKatakanaFallbackScalars)
            guard start + 2 <= finalEnd else { continue }
            for end in (start + 2)...finalEnd {
                let token = String(reading[boundaries[start]..<boundaries[end]])
                guard isCompletedHiraganaReading(token),
                      ConversionText.isLoanwordLikeReading(token),
                      !hasFunctionAffix(start: start, end: end, lexemes: lexemes)
                else { continue }
                append(Edge(
                    start: start,
                    end: end,
                    reading: token,
                    surface: ConversionText.toKatakana(token),
                    leftID: 3,
                    rightID: 3,
                    wordCost: katakanaFallbackBaseCost + katakanaFallbackScalarCost * (end - start),
                    isCopy: false,
                    isKatakana: true
                ))
            }
        }
        return output.mapValues { $0.sorted(by: edgeOrder) }
    }

    private static func hasFunctionAffix(
        start: Int,
        end: Int,
        lexemes: [ConversionLexeme]
    ) -> Bool {
        lexemes.contains { lexeme in
            lexeme.end - lexeme.start == 1 &&
            ConversionText.scalarEquals(lexeme.reading, lexeme.surface) &&
            (lexeme.leftID == 5 || lexeme.leftID == 6) &&
            ((lexeme.start == start && lexeme.end < end) ||
             (lexeme.end == end && lexeme.start > start))
        }
    }

    private static func loanwordPositions(
        reading: String,
        boundaries: [String.Index]
    ) -> [Bool] {
        var result = Array(repeating: false, count: boundaries.count - 1)
        var markerPositions: [Int] = []
        var smallTsuPositions: [Int] = []
        for position in 0..<(boundaries.count - 1) {
            let scalar = reading[boundaries[position]..<boundaries[position + 1]].unicodeScalars.first?.value
            if scalar == 0x3063 {
                smallTsuPositions.append(position)
            } else if scalar.map(ConversionText.isLoanwordMarker) == true {
                markerPositions.append(position)
            }
        }
        if smallTsuPositions.count >= 2 { markerPositions.append(contentsOf: smallTsuPositions) }
        for position in markerPositions {
            for nearby in max(0, position - 5)...min(result.count - 1, position + 5) {
                result[nearby] = true
            }
        }
        return result
    }

    private static func pathPenalty(
        previous: ConversionSegment?,
        edge: Edge,
        previousAlternations: Int,
        loanwordPositions: [Bool]
    ) -> PathPenalty {
        var cost = 0
        let edgeInLoanword = overlaps(start: edge.start, end: edge.end, positions: loanwordPositions)
        if let previous,
           edgeInLoanword,
           overlaps(start: previous.start, end: previous.end, positions: loanwordPositions),
           isShortHan(reading: previous.reading, surface: previous.surface),
           isShortHan(edge) {
            cost += shortHanSequencePenalty
        }

        let followsKatakanaUnit = previous.map {
            $0.end - $0.start >= 2 && ConversionText.isKatakanaTransliteration(
                reading: $0.reading,
                surface: $0.surface
            )
        } ?? false
        let shortUnconverted = edge.isCopy ||
            (ConversionText.isUnconvertedHiragana(reading: edge.reading, surface: edge.surface) &&
             !followsKatakanaUnit)
        if edgeInLoanword && !edge.isKatakana &&
            (isShortHan(edge) || (shortUnconverted && edge.end - edge.start <= 2)) {
            cost += loanwordFragmentPenalty
        }

        guard let previous,
              edgeInLoanword,
              overlaps(start: previous.start, end: previous.end, positions: loanwordPositions)
        else { return PathPenalty(cost: cost, alternations: 0) }
        let previousScript = fragmentScript(previous)
        let nextScript = fragmentScript(edge)
        guard previousScript != .other, nextScript != .other, previousScript != nextScript else {
            return PathPenalty(cost: cost, alternations: previousAlternations)
        }
        let alternations = previousAlternations + 1
        if alternations >= 2 { cost += loanwordScriptAlternationPenalty }
        return PathPenalty(cost: cost, alternations: alternations)
    }

    private static func fragmentScript(_ segment: ConversionSegment) -> FragmentScript {
        if ConversionText.isPureHan(segment.surface) { return .han }
        if segment.isCopy || ConversionText.isUnconvertedHiragana(
            reading: segment.reading,
            surface: segment.surface
        ) { return .unconvertedKana }
        return .other
    }

    private static func fragmentScript(_ edge: Edge) -> FragmentScript {
        if ConversionText.isPureHan(edge.surface) { return .han }
        if edge.isCopy || ConversionText.isUnconvertedHiragana(
            reading: edge.reading,
            surface: edge.surface
        ) { return .unconvertedKana }
        return .other
    }

    private static func isShortHan(reading: String, surface: String) -> Bool {
        ConversionText.scalarCount(reading) == 1 && ConversionText.isPureHan(surface)
    }

    private static func isShortHan(_ edge: Edge) -> Bool {
        isShortHan(reading: edge.reading, surface: edge.surface)
    }

    private static func overlaps(start: Int, end: Int, positions: [Bool]) -> Bool {
        guard start < positions.count else { return false }
        return positions[start..<min(end, positions.count)].contains(true)
    }

    private static func isCompletedHiraganaReading(_ value: String) -> Bool {
        !value.isEmpty && value.unicodeScalars.allSatisfy {
            (0x3041...0x3096).contains($0.value) || $0.value == 0x30FC
        }
    }

    /// Returns the leading boundary of the most plausible bunsetsu in scalar offsets.
    static func leadingBunsetsuLength(segments: [ConversionSegment], totalLength: Int) -> Int? {
        guard totalLength > 0 else { return nil }
        for (left, right) in zip(segments, segments.dropFirst()) {
            if closesBunsetsu(left.rightID) && startsContent(right.leftID) {
                return left.end < totalLength ? left.end : nil
            }
        }
        return nil
    }

    private static func clampedInputCost(_ value: Int) -> Int {
        ConversionCost.clampInt32(Int64(value))
    }

    private static func bunsetsuBoundaries(_ segments: [ConversionSegment]) -> [Int] {
        var output: [Int] = []
        for (left, right) in zip(segments, segments.dropFirst()) {
            if closesBunsetsu(left.rightID) && startsContent(right.leftID) {
                output.append(left.end)
            }
        }
        if let last = segments.last { output.append(last.end) }
        return output
    }

    private static func closesBunsetsu(_ posID: Int) -> Bool {
        posID == 5 || posID == 6 || posID == 11
    }

    private static func startsContent(_ posID: Int) -> Bool {
        [2, 3, 4, 7, 8, 9, 10, 12, 14, 15].contains(posID)
    }

    private static func prune(
        _ values: [State],
        preserveSegmentations: Bool,
        beamWidth: Int
    ) -> [State] {
        var best: [StateKey: State] = [:]
        values.forEach { state in
            let key = StateKey(
                end: state.end,
                right: state.rightID,
                surface: ConversionText.scalarValues(state.surface),
                loanwordAlternations: state.loanwordAlternations,
                previousContextSurface: state.previousContextSurface.map(ConversionText.scalarValues),
                bunsetsuBoundaries: preserveSegmentations ? bunsetsuBoundaries(state.segments) : []
            )
            if let current = best[key] {
                if stateOrder(state, current) { best[key] = state }
            } else {
                best[key] = state
            }
        }
        return Array(best.values.sorted(by: stateOrder).prefix(beamWidth))
    }

    private static func lexemeOrder(_ left: ConversionLexeme, _ right: ConversionLexeme) -> Bool {
        if left.start != right.start { return left.start < right.start }
        if left.end != right.end { return left.end < right.end }
        if left.wordCost != right.wordCost { return left.wordCost < right.wordCost }
        let surface = ConversionText.scalarOrder(left.surface, right.surface)
        if surface != .orderedSame { return surface == .orderedAscending }
        if left.leftID != right.leftID { return left.leftID < right.leftID }
        return left.rightID < right.rightID
    }

    private static func edgeOrder(_ left: Edge, _ right: Edge) -> Bool {
        if left.end != right.end { return left.end < right.end }
        if left.wordCost != right.wordCost { return left.wordCost < right.wordCost }
        let surface = ConversionText.scalarOrder(left.surface, right.surface)
        if surface != .orderedSame { return surface == .orderedAscending }
        if left.leftID != right.leftID { return left.leftID < right.leftID }
        if left.rightID != right.rightID { return left.rightID < right.rightID }
        return !left.isCopy && right.isCopy
    }

    private static func stateOrder(_ left: State, _ right: State) -> Bool {
        if left.cost != right.cost { return left.cost < right.cost }
        if left.copied != right.copied { return left.copied < right.copied }
        if left.segments.count != right.segments.count {
            return left.segments.count < right.segments.count
        }
        let surface = ConversionText.scalarOrder(left.surface, right.surface)
        if surface != .orderedSame { return surface == .orderedAscending }
        if left.rightID != right.rightID { return left.rightID < right.rightID }
        return intListOrder(bunsetsuBoundaries(left.segments), bunsetsuBoundaries(right.segments))
    }

    private static func intListOrder(_ left: [Int], _ right: [Int]) -> Bool {
        for (lhs, rhs) in zip(left, right) where lhs != rhs { return lhs < rhs }
        return left.count < right.count
    }
}
