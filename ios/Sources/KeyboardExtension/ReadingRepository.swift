import Foundation
import SQLite3

private let sqliteTransient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

final class ReadingRepository {
    private var database: OpaquePointer?

    init?(bundle: Bundle = .main) {
        guard let path = bundle.path(forResource: "reading", ofType: "db"),
              sqlite3_open_v2(path, &database, SQLITE_OPEN_READONLY, nil) == SQLITE_OK else {
            if database != nil { sqlite3_close(database) }
            database = nil
            return nil
        }
    }

    deinit {
        sqlite3_close(database)
    }

    func primaryReading(for text: String) -> String? {
        guard let database else { return nil }
        let sql = "SELECT reading FROM kanji_reading WHERE literal = ? ORDER BY kind, position LIMIT 1"
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(database, sql, -1, &statement, nil) == SQLITE_OK else { return nil }
        defer { sqlite3_finalize(statement) }
        text.withCString { value in
            sqlite3_bind_text(statement, 1, value, -1, sqliteTransient)
        }
        guard sqlite3_step(statement) == SQLITE_ROW,
              let value = sqlite3_column_text(statement, 0) else { return nil }
        return String(cString: value)
    }
}
