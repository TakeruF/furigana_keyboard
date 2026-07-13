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
    private static let beamWidth = 12
    private static let maxOutput = 8
    private static let defaultConnectionCost = 3_000
    private static let copyWordCost = 4_000

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
        let bunsetsuBoundaries: [Int]
    }
    private struct ResultKey: Hashable {
        let surface: [UInt32]
        let bunsetsuBoundaries: [Int]
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
    }
    private struct State {
        let end: Int
        let rightID: Int
        let surface: String
        let cost: Int64
        let copied: Int
        let segments: [ConversionSegment]
    }

    static func convert(
        reading: String,
        lexemes: [ConversionLexeme],
        connections: [ConversionConnection],
        limit: Int = 8,
        preserveSegmentations: Bool = false
    ) -> [KanaKanjiConversion] {
        let boundaries = ConversionText.scalarBoundaries(reading)
        let scalarCount = boundaries.count - 1
        guard scalarCount > 0, scalarCount <= maxInputScalars, limit > 0 else { return [] }

        var connectionCosts: [ConnectionKey: Int] = [:]
        connections.forEach { connection in
            let key = ConnectionKey(right: connection.rightID, left: connection.leftID)
            let cost = clampedInputCost(connection.cost)
            connectionCosts[key] = min(connectionCosts[key] ?? Int.max, cost)
        }

        let usableLexemes = lexemes.compactMap { lexeme -> ConversionLexeme? in
            guard lexeme.start >= 0,
                  lexeme.end > lexeme.start,
                  lexeme.end <= scalarCount,
                  ConversionText.scalarCount(lexeme.reading) <= maxLexemeScalars,
                  let token = ConversionText.scalarSubstring(
                    reading,
                    start: lexeme.start,
                    end: lexeme.end
                  ),
                  ConversionText.scalarEquals(token, lexeme.reading) else { return nil }
            return ConversionLexeme(
                start: lexeme.start,
                end: lexeme.end,
                reading: lexeme.reading,
                surface: lexeme.surface,
                leftID: lexeme.leftID,
                rightID: lexeme.rightID,
                wordCost: clampedInputCost(lexeme.wordCost)
            )
        }.sorted(by: lexemeOrder)

        var vocabularyCounts: [VocabularyKey: Int] = [:]
        var dictionaryEdges: [Int: [ConversionLexeme]] = [:]
        for lexeme in usableLexemes {
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

        var states: [Int: [State]] = [
            0: [State(end: 0, rightID: 0, surface: "", cost: 0, copied: 0, segments: [])]
        ]
        for start in 0..<scalarCount {
            let incoming = prune(states[start] ?? [], preserveSegmentations: preserveSegmentations)
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
                    isCopy: false
                )
            }
            edges.append(Edge(
                start: start,
                end: copyEnd,
                reading: copied,
                surface: copied,
                leftID: 15,
                rightID: 15,
                wordCost: copyWordCost,
                isCopy: true
            ))
            edges.sort(by: edgeOrder)

            for state in incoming {
                for edge in edges {
                    let connection = connectionCosts[
                        ConnectionKey(right: state.rightID, left: edge.leftID)
                    ] ?? defaultConnectionCost
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
                        cost: state.cost + Int64(edge.wordCost) + Int64(connection),
                        copied: state.copied + (edge.isCopy ? 1 : 0),
                        segments: state.segments + [segment]
                    ))
                }
            }
        }

        let completed = prune(
            states[scalarCount] ?? [],
            preserveSegmentations: preserveSegmentations
        ).compactMap { state -> State? in
            guard state.segments.contains(where: { !$0.isCopy }) else { return nil }
            let end = connectionCosts[ConnectionKey(right: state.rightID, left: 1)]
                ?? defaultConnectionCost
            return State(
                end: state.end,
                rightID: state.rightID,
                surface: state.surface,
                cost: state.cost + Int64(end),
                copied: state.copied,
                segments: state.segments
            )
        }.sorted(by: stateOrder)

        var seen = Set<ResultKey>()
        var output: [KanaKanjiConversion] = []
        for state in completed {
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
        preserveSegmentations: Bool
    ) -> [State] {
        var best: [StateKey: State] = [:]
        values.forEach { state in
            let key = StateKey(
                end: state.end,
                right: state.rightID,
                surface: ConversionText.scalarValues(state.surface),
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
