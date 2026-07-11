import Foundation
import SQLite3
#if REPOSITORY_TEST_HOST
@testable import FuriganaKeyboard
#endif

private let sqliteTransient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

final class ReadingRepository {
    private var database: OpaquePointer?
    private lazy var connections: [ConversionConnection] = loadConnections()

    init?(bundle: Bundle = .main) {
        guard let url = ReadingDataLocation.databaseURL(bundle: bundle),
              sqlite3_open_v2(url.path, &database, SQLITE_OPEN_READONLY, nil) == SQLITE_OK else {
            if database != nil { sqlite3_close(database) }
            database = nil
            return nil
        }
    }

    deinit {
        sqlite3_close(database)
    }

    func primaryReading(for text: String) -> String? { readings(for: text).first }

    func readings(for text: String, limit: Int = 4) -> [String] {
        guard let database, !text.isEmpty else { return [] }
        let sql: String
        if text.count == 1 {
            sql = "SELECT reading FROM kanji_reading WHERE literal = ? ORDER BY kind, position LIMIT ?"
        } else {
            sql = "SELECT reading FROM word_reading WHERE surface = ? ORDER BY reading_priority, reading_position, form_rank, priority, reading LIMIT ?"
        }
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(database, sql, -1, &statement, nil) == SQLITE_OK else { return [] }
        defer { sqlite3_finalize(statement) }
        bind(text, at: 1, to: statement)
        _ = sqlite3_bind_int(statement, 2, Int32(limit))
        var output: [String] = []
        while sqlite3_step(statement) == SQLITE_ROW, let value = sqlite3_column_text(statement, 0) {
            output.append(String(cString: value))
        }
        return output
    }

    func priorities(for literals: [String]) -> [String: (grade: Int, frequency: Int)] {
        guard let database else { return [:] }
        var output: [String: (Int, Int)] = [:]
        let sql = "SELECT grade, frequency FROM kanji_priority WHERE literal = ?"
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(database, sql, -1, &statement, nil) == SQLITE_OK else { return output }
        defer { sqlite3_finalize(statement) }
        for literal in literals {
            sqlite3_reset(statement); sqlite3_clear_bindings(statement)
            bind(literal, at: 1, to: statement)
            if sqlite3_step(statement) == SQLITE_ROW {
                output[literal] = (Int(sqlite3_column_int(statement, 0)), Int(sqlite3_column_int(statement, 1)))
            }
        }
        return output
    }

    func suggestions(surfacePrefix: String, limit: Int = 8) -> [WordCandidate] {
        queryWords(
            sql: "SELECT surface, reading FROM word_reading WHERE surface >= ? AND surface < ? ORDER BY surface = ? DESC, form_rank, priority, surface, reading LIMIT ?",
            values: [surfacePrefix, surfacePrefix + "\u{10ffff}", surfacePrefix], limit: limit
        )
    }

    func suggestions(readingPrefix: String, limit: Int = 8) -> [WordCandidate] {
        queryWords(
            sql: "SELECT surface, reading FROM word_reading WHERE reading >= ? AND reading < ? ORDER BY reading = ? DESC, form_rank, priority, surface LIMIT ?",
            values: [readingPrefix, readingPrefix + "\u{10ffff}", readingPrefix], limit: limit
        )
    }

    func conversionData(for reading: String) -> (lexemes: [ConversionLexeme], connections: [ConversionConnection]) {
        guard let database else { return ([], []) }
        let characters = Array(reading)
        guard characters.count <= 48 else { return ([], connections) }
        let sql = "SELECT surface, left_id, right_id, word_cost FROM conversion_lexeme WHERE reading = ? ORDER BY word_cost, form_rank, surface LIMIT 12"
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(database, sql, -1, &statement, nil) == SQLITE_OK else { return ([], connections) }
        defer { sqlite3_finalize(statement) }
        var output: [ConversionLexeme] = []
        for start in characters.indices {
            for end in (start + 1)...min(characters.count, start + 16) {
                let token = String(characters[start..<end])
                sqlite3_reset(statement); sqlite3_clear_bindings(statement)
                bind(token, at: 1, to: statement)
                while sqlite3_step(statement) == SQLITE_ROW {
                    guard let value = sqlite3_column_text(statement, 0) else { continue }
                    output.append(ConversionLexeme(
                        start: start, end: end, reading: token, surface: String(cString: value),
                        leftID: Int(sqlite3_column_int(statement, 1)), rightID: Int(sqlite3_column_int(statement, 2)),
                        wordCost: Int(sqlite3_column_int(statement, 3))
                    ))
                }
            }
        }
        return (output, connections)
    }

    private func loadConnections() -> [ConversionConnection] {
        guard let database else { return [] }
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(database, "SELECT previous_right_id, next_left_id, cost FROM connection_cost", -1, &statement, nil) == SQLITE_OK else { return [] }
        defer { sqlite3_finalize(statement) }
        var output: [ConversionConnection] = []
        while sqlite3_step(statement) == SQLITE_ROW {
            output.append(ConversionConnection(rightID: Int(sqlite3_column_int(statement, 0)),
                                               leftID: Int(sqlite3_column_int(statement, 1)),
                                               cost: Int(sqlite3_column_int(statement, 2))))
        }
        return output
    }

    private func queryWords(sql: String, values: [String], limit: Int) -> [WordCandidate] {
        guard let database, !values.first!.isEmpty else { return [] }
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(database, sql, -1, &statement, nil) == SQLITE_OK else { return [] }
        defer { sqlite3_finalize(statement) }
        for (index, value) in values.enumerated() { bind(value, at: Int32(index + 1), to: statement) }
        _ = sqlite3_bind_int(statement, Int32(values.count + 1), Int32(limit * 3))
        var order: [String] = []
        var grouped: [String: [String]] = [:]
        while sqlite3_step(statement) == SQLITE_ROW,
              let surfaceValue = sqlite3_column_text(statement, 0),
              let readingValue = sqlite3_column_text(statement, 1) {
            let surface = String(cString: surfaceValue), reading = String(cString: readingValue)
            if grouped[surface] == nil { order.append(surface) }
            if !(grouped[surface] ?? []).contains(reading) { grouped[surface, default: []].append(reading) }
        }
        return order.prefix(limit).map { WordCandidate(surface: $0, readings: grouped[$0] ?? []) }
    }

    private func bind(_ value: String, at index: Int32, to statement: OpaquePointer?) {
        _ = value.withCString { sqlite3_bind_text(statement, index, $0, -1, sqliteTransient) }
    }
}
