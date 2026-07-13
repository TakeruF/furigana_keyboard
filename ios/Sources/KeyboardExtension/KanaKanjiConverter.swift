import Foundation

struct WordCandidate: Equatable {
    let surface: String
    let readings: [String]
}
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
    private struct ConnectionKey: Hashable { let right: Int; let left: Int }
    private struct StateKey: Hashable { let end: Int; let right: Int; let surface: String }
    private struct State {
        let end: Int
        let rightID: Int
        let surface: String
        let cost: Int
        let copied: Int
        let segments: [ConversionSegment]
    }

    static func convert(
        reading: String,
        lexemes: [ConversionLexeme],
        connections: [ConversionConnection],
        limit: Int = 8
    ) -> [KanaKanjiConversion] {
        let characters = Array(reading)
        guard !characters.isEmpty, characters.count <= 48, limit > 0 else { return [] }
        var connectionCosts: [ConnectionKey: Int] = [:]
        connections.forEach { connectionCosts[ConnectionKey(right: $0.rightID, left: $0.leftID)] = $0.cost }
        let grouped = Dictionary(grouping: lexemes.filter {
            $0.start >= 0 && $0.end <= characters.count && $0.start < $0.end &&
            String(characters[$0.start..<$0.end]) == $0.reading
        }, by: \.start)
        var states: [Int: [State]] = [0: [State(end: 0, rightID: 0, surface: "", cost: 0, copied: 0, segments: [])]]

        for start in 0..<characters.count {
            let incoming = prune(states[start] ?? [])
            guard !incoming.isEmpty else { continue }
            var edges = grouped[start] ?? []
            let copied = String(characters[start])
            edges.append(ConversionLexeme(start: start, end: start + 1, reading: copied,
                                          surface: copied, leftID: 15, rightID: 15, wordCost: 4_000))
            edges.sort { ($0.wordCost, $0.surface, $0.rightID) < ($1.wordCost, $1.surface, $1.rightID) }
            for state in incoming {
                for edge in edges {
                    let isCopy = edge.leftID == 15 && edge.rightID == 15 && edge.surface == edge.reading
                    let connection = connectionCosts[ConnectionKey(right: state.rightID, left: edge.leftID)] ?? 3_000
                    let segment = ConversionSegment(start: edge.start, end: edge.end, reading: edge.reading,
                                                    surface: edge.surface, leftID: edge.leftID,
                                                    rightID: edge.rightID, wordCost: edge.wordCost, isCopy: isCopy)
                    states[edge.end, default: []].append(State(
                        end: edge.end, rightID: edge.rightID, surface: state.surface + edge.surface,
                        cost: state.cost + edge.wordCost + connection,
                        copied: state.copied + (isCopy ? 1 : 0), segments: state.segments + [segment]
                    ))
                }
            }
        }

        let completed = prune(states[characters.count] ?? []).compactMap { state -> State? in
            guard state.segments.contains(where: { !$0.isCopy }) else { return nil }
            let end = connectionCosts[ConnectionKey(right: state.rightID, left: 1)] ?? 3_000
            return State(end: state.end, rightID: state.rightID, surface: state.surface,
                         cost: state.cost + end, copied: state.copied, segments: state.segments)
        }.sorted(by: stateOrder)

        var seen = Set<String>()
        return completed.compactMap { state in
            guard seen.insert(state.surface).inserted else { return nil }
            return KanaKanjiConversion(surface: state.surface, reading: reading,
                                       cost: state.cost, segments: state.segments)
        }.prefix(min(limit, 8)).map { $0 }
    }

    /// Returns the leading boundary of the most plausible bunsetsu, rather than
    /// exposing the converter's first (and often shortest) dictionary token.
    static func leadingBunsetsuLength(segments: [ConversionSegment], totalLength: Int) -> Int? {
        guard totalLength > 0 else { return nil }
        for (left, right) in zip(segments, segments.dropFirst()) {
            if closesBunsetsu(left.rightID) && startsContent(right.leftID) {
                return left.end < totalLength ? left.end : nil
            }
        }
        return nil
    }

    private static func closesBunsetsu(_ posID: Int) -> Bool {
        posID == 5 || posID == 6 || posID == 11 // particle, auxiliary, suffix
    }

    private static func startsContent(_ posID: Int) -> Bool {
        // pronoun, noun, proper noun, verb, adjective, adverb, prefix,
        // expression, other, or an unknown copied character
        [2, 3, 4, 7, 8, 9, 10, 12, 14, 15].contains(posID)
    }

    private static func prune(_ values: [State]) -> [State] {
        var best: [StateKey: State] = [:]
        values.forEach { state in
            let key = StateKey(end: state.end, right: state.rightID, surface: state.surface)
            if let current = best[key] {
                if stateOrder(state, current) { best[key] = state }
            } else { best[key] = state }
        }
        return best.values.sorted(by: stateOrder).prefix(12).map { $0 }
    }

    private static func stateOrder(_ lhs: State, _ rhs: State) -> Bool {
        if lhs.cost != rhs.cost { return lhs.cost < rhs.cost }
        if lhs.copied != rhs.copied { return lhs.copied < rhs.copied }
        if lhs.surface != rhs.surface { return lhs.surface < rhs.surface }
        return lhs.segments.count < rhs.segments.count
    }
}
