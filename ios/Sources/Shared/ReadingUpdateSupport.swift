import CryptoKit
import Combine
import Foundation
import SQLite3

enum ReadingUpdateConfiguration {
    static let appGroup = "group.app.hanlu.furiganakeyboard"
    static let manifestURL = URL(
        string: "https://downloads.hanlu.app/furigana/manifest.json"
    )!
    static let supportedSchemaVersion = 7
    static let publicKeyDERBase64 =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE9fXKWi9gKlKzeFvoERpCuEm0cpuo7LZ8bhqU0ZDU8BV1naCjNzdHDg6uW04s4P0x1Q4yFKv+w7kLN6j0HKGhGQ=="
}

struct ReadingUpdateManifest: Decodable {
    let formatVersion: Int
    let dataVersion: Int
    let schemaVersion: Int
    let minAppVersion: Int
    let databaseUrl: URL
    let databaseSize: Int64
    let databaseSha256: String
    let dictionaryDate: String

    enum CodingKeys: String, CodingKey {
        case formatVersion, dataVersion, schemaVersion, minAppVersion
        case databaseUrl = "databaseUrl"
        case databaseSize, databaseSha256, dictionaryDate
    }
}

struct ActiveReadingData: Codable {
    let fileName: String
    let dataVersion: Int
    let dictionaryDate: String
}

enum ReadingDataLocation {
    private static let activeFileName = "active.json"

    static func databaseURL(bundle: Bundle = .main) -> URL? {
        if let directory = sharedDirectory(),
           let active = try? activeData(in: directory),
           safeFileName(active.fileName) {
            let candidate = directory.appendingPathComponent(active.fileName)
            if FileManager.default.fileExists(atPath: candidate.path) {
                return candidate
            }
        }
        return bundle.url(forResource: "reading", withExtension: "db")
    }

    static func sharedDirectory() -> URL? {
        guard let container = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: ReadingUpdateConfiguration.appGroup
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

    static func activate(_ active: ActiveReadingData, in directory: URL) throws {
        let data = try JSONEncoder().encode(active)
        try data.write(
            to: directory.appendingPathComponent(activeFileName),
            options: [.atomic, .completeFileProtectionUnlessOpen]
        )
    }

    static func removeInactive(in directory: URL, keeping fileName: String) {
        guard let files = try? FileManager.default.contentsOfDirectory(
            at: directory,
            includingPropertiesForKeys: nil
        ) else { return }
        for file in files where file.lastPathComponent != fileName &&
            file.lastPathComponent != activeFileName {
            try? FileManager.default.removeItem(at: file)
        }
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

@MainActor
final class ReadingDataUpdater: ObservableObject {
    @Published private(set) var status = "辞書更新を確認しています…"
    @Published private(set) var isUpdating = false

    func update() async {
        guard !isUpdating else { return }
        isUpdating = true
        defer { isUpdating = false }
        do {
            status = try await performUpdate()
        } catch {
            status = "辞書更新を確認できませんでした。同梱辞書を使用します。"
        }
    }

    private func performUpdate() async throws -> String {
        let manifestData = try await fetch(
            ReadingUpdateConfiguration.manifestURL,
            maximumSize: 32 * 1024
        )
        let signatureData = try await fetch(
            ReadingUpdateConfiguration.manifestURL.appendingPathExtension("sig"),
            maximumSize: 4 * 1024
        )
        try verify(manifestData: manifestData, signatureData: signatureData)
        let manifest = try JSONDecoder().decode(ReadingUpdateManifest.self, from: manifestData)
        guard manifest.formatVersion == 1,
              manifest.schemaVersion == ReadingUpdateConfiguration.supportedSchemaVersion,
              manifest.minAppVersion <= appVersion,
              manifest.databaseSize > 0,
              manifest.databaseSize <= 128 * 1024 * 1024,
              manifest.databaseUrl.scheme == "https" else {
            throw UpdateError.incompatible
        }
        guard let directory = ReadingDataLocation.sharedDirectory() else {
            throw UpdateError.sharedContainerUnavailable
        }
        if let active = try? ReadingDataLocation.activeData(in: directory),
           active.dataVersion >= manifest.dataVersion {
            return "辞書は最新です（\(active.dictionaryDate)）"
        }

        let (temporaryDownload, response) = try await URLSession.shared.download(
            from: manifest.databaseUrl
        )
        try requireOK(response)
        let values = try temporaryDownload.resourceValues(forKeys: [.fileSizeKey])
        guard Int64(values.fileSize ?? -1) == manifest.databaseSize else {
            throw UpdateError.sizeMismatch
        }
        let databaseData = try Data(contentsOf: temporaryDownload, options: .mappedIfSafe)
        guard SHA256.hash(data: databaseData).hex == manifest.databaseSha256.lowercased() else {
            throw UpdateError.hashMismatch
        }
        let fileName = "reading-\(manifest.dataVersion).db"
        let staging = directory.appendingPathComponent("\(fileName).tmp")
        let target = directory.appendingPathComponent(fileName)
        try? FileManager.default.removeItem(at: staging)
        try FileManager.default.copyItem(at: temporaryDownload, to: staging)
        try ReadingDatabaseValidator.validate(
            url: staging,
            expectedSchema: manifest.schemaVersion
        )
        try? FileManager.default.removeItem(at: target)
        try FileManager.default.moveItem(at: staging, to: target)
        try ReadingDataLocation.activate(
            ActiveReadingData(
                fileName: fileName,
                dataVersion: manifest.dataVersion,
                dictionaryDate: manifest.dictionaryDate
            ),
            in: directory
        )
        ReadingDataLocation.removeInactive(in: directory, keeping: fileName)
        return "辞書を更新しました（\(manifest.dictionaryDate)）"
    }

    private func fetch(_ url: URL, maximumSize: Int) async throws -> Data {
        let (data, response) = try await URLSession.shared.data(from: url)
        try requireOK(response)
        guard data.count <= maximumSize else { throw UpdateError.responseTooLarge }
        return data
    }

    private func requireOK(_ response: URLResponse) throws {
        guard let response = response as? HTTPURLResponse,
              response.statusCode == 200 else { throw UpdateError.httpFailure }
    }

    private func verify(manifestData: Data, signatureData: Data) throws {
        guard let keyData = Data(base64Encoded: ReadingUpdateConfiguration.publicKeyDERBase64),
              let encodedSignature = String(data: signatureData, encoding: .utf8),
              let signature = Data(base64Encoded: encodedSignature.trimmingCharacters(in: .whitespacesAndNewlines)) else {
            throw UpdateError.invalidSignature
        }
        let publicKey = try P256.Signing.PublicKey(derRepresentation: keyData)
        let ecdsaSignature = try P256.Signing.ECDSASignature(derRepresentation: signature)
        guard publicKey.isValidSignature(ecdsaSignature, for: manifestData) else {
            throw UpdateError.invalidSignature
        }
    }

    private var appVersion: Int {
        Int(Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "1") ?? 1
    }

    enum UpdateError: Error {
        case incompatible, sharedContainerUnavailable, sizeMismatch, hashMismatch
        case responseTooLarge, httpFailure, invalidSignature
    }
}

private extension SHA256.Digest {
    var hex: String { map { String(format: "%02x", $0) }.joined() }
}
