import Foundation
import SQLite3

/// Configuration shared by the app and the keyboard extension. It intentionally contains no
/// endpoint, public key, URLSession, or update implementation: the extension only opens files.
enum ReadingDataConfiguration {
    static let appGroup = "group.app.hanlu.furiganakeyboard"
    static let supportedSchemaVersion = 8
    static let fullProfile = "full"
}

struct ActiveReadingData: Codable {
    let fileName: String
    let dataVersion: Int
    let schemaVersion: Int
    let dictionaryDate: String
    /// Missing means a pre-core active record, which was necessarily a full dictionary.
    let profile: String?

    init(
        fileName: String,
        dataVersion: Int,
        schemaVersion: Int,
        dictionaryDate: String,
        profile: String? = nil
    ) {
        self.fileName = fileName
        self.dataVersion = dataVersion
        self.schemaVersion = schemaVersion
        self.dictionaryDate = dictionaryDate
        self.profile = profile
    }
}

enum ReadingDataLocation {
    private static let activeFileName = "active.json"
    static let legacyFullFileName = "legacy-full-v8.db"

    /// Used by the extension: an App Group full dictionary wins, then the bundled core.
    static func databaseURL(
        bundle: Bundle = .main,
        activeDirectory: URL? = sharedDirectory()
    ) -> URL? {
        activeDirectory.flatMap { activeFullDatabaseURL(in: $0) }
            ?? bundledCoreURL(bundle: bundle)
    }

    /// Compatibility spelling for callers and tests from the single-dictionary implementation.
    static func activeDatabaseURL(in directory: URL) -> URL? {
        activeFullDatabaseURL(in: directory)
    }

    static func activeFullDatabaseURL(in directory: URL) -> URL? {
        if let active = try? activeData(in: directory),
           active.profile == nil || active.profile == ReadingDataConfiguration.fullProfile,
           active.schemaVersion == ReadingDataConfiguration.supportedSchemaVersion,
           safeFileName(active.fileName) {
            let candidate = directory.appendingPathComponent(active.fileName)
            if (try? ReadingDatabaseValidator.validate(
                url: candidate,
                expectedSchema: active.schemaVersion
            )) != nil {
                return candidate
            }
        }
        let legacy = directory.appendingPathComponent(legacyFullFileName)
        guard (try? ReadingDatabaseValidator.validate(
            url: legacy,
            expectedSchema: ReadingDataConfiguration.supportedSchemaVersion
        )) != nil else { return nil }
        return legacy
    }

    static func sharedDirectory() -> URL? {
        guard let container = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: ReadingDataConfiguration.appGroup
        ) else { return nil }
        let directory = container.appendingPathComponent("ReadingUpdates", isDirectory: true)
        try? FileManager.default.createDirectory(
            at: directory,
            withIntermediateDirectories: true
        )
        return directory
    }

    static func activeData(in directory: URL) throws -> ActiveReadingData {
        let data = try Data(contentsOf: directory.appendingPathComponent(activeFileName))
        return try JSONDecoder().decode(ActiveReadingData.self, from: data)
    }

    /// The pointer is written only after the complete database has been verified and renamed.
    static func activate(_ active: ActiveReadingData, in directory: URL) throws {
        let data = try JSONEncoder().encode(active)
        try data.write(
            to: directory.appendingPathComponent(activeFileName),
            options: [.atomic, .completeFileProtectionUnlessOpen]
        )
    }

    /// Valid prior full dictionaries remain available; only interrupted staging files are removed.
    static func removeTemporaryFiles(in directory: URL) {
        guard let files = try? FileManager.default.contentsOfDirectory(
            at: directory,
            includingPropertiesForKeys: nil
        ) else { return }
        for file in files where file.lastPathComponent.hasSuffix(".tmp") ||
            file.lastPathComponent.contains(".staging-") {
            try? FileManager.default.removeItem(at: file)
        }
    }

    private static func bundledCoreURL(bundle: Bundle) -> URL? {
        // `reading.db` is the transitional core asset until the deterministic generator emits
        // `reading-core.db`. Keeping this fallback prevents an app update from losing offline use.
        bundle.url(forResource: "reading-core", withExtension: "db")
            ?? bundle.url(forResource: "reading", withExtension: "db")
    }

    private static func safeFileName(_ value: String) -> Bool {
        !value.isEmpty && !value.contains("..") && URL(fileURLWithPath: value).lastPathComponent == value
    }
}

enum ReadingDatabaseValidator {
    static func validate(url: URL, expectedSchema: Int) throws {
        var database: OpaquePointer?
        guard sqlite3_open_v2(url.path, &database, SQLITE_OPEN_READONLY, nil) == SQLITE_OK,
              let database else {
            if database != nil { sqlite3_close(database) }
            throw ValidationError.openFailed
        }
        defer { sqlite3_close(database) }
        guard scalar(database, sql: "PRAGMA integrity_check") == "ok" else {
            throw ValidationError.integrityFailed
        }
        guard scalar(
            database,
            sql: "SELECT value FROM metadata WHERE key='schema_version'"
        ) == String(expectedSchema) else {
            throw ValidationError.schemaMismatch
        }
    }

    private static func scalar(_ database: OpaquePointer, sql: String) -> String? {
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(database, sql, -1, &statement, nil) == SQLITE_OK else {
            return nil
        }
        defer { sqlite3_finalize(statement) }
        guard sqlite3_step(statement) == SQLITE_ROW,
              let value = sqlite3_column_text(statement, 0) else { return nil }
        return String(cString: value)
    }

    enum ValidationError: Error {
        case openFailed, integrityFailed, schemaMismatch
    }
}
