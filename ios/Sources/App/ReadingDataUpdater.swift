import Combine
import CryptoKit
import Foundation

/// This type is compiled into the parent app target only. The keyboard extension has no network
/// update code and only consumes the atomically switched App Group files.
enum ReadingUpdateConfiguration {
    static let manifestURL = URL(string: "https://downloads.hanlu.app/furigana/manifest.json")!
    static let publicKeyDERBase64 =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE9fXKWi9gKlKzeFvoERpCuEm0cpuo7LZ8bhqU0ZDU8BV1naCjNzdHDg6uW04s4P0x1Q4yFKv+w7kLN6j0HKGhGQ=="
    static let legacyBundledFullSha256 =
        "991a13b8552748ea2c35fb229446809869a0ceee14ba0107a65351c8527efbc2"
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
}

extension ReadingUpdateManifest {
    func validate(appVersion: Int) throws {
        guard formatVersion == 1,
              schemaVersion == ReadingDataConfiguration.supportedSchemaVersion,
              minAppVersion <= appVersion,
              databaseSize > 0,
              databaseSize <= 128 * 1024 * 1024,
              databaseUrl.scheme == "https",
              databaseSha256.lowercased().range(
                of: "^[0-9a-f]{64}$", options: .regularExpression
              ) != nil else {
            throw ReadingUpdateValidationError.incompatible
        }
    }
}

enum ReadingUpdateValidationError: Error {
    case incompatible, sizeMismatch, hashMismatch, invalidSignature
}

enum ReadingManifestSignatureVerifier {
    static func verify(
        manifestData: Data,
        signatureData: Data,
        publicKeyDERBase64: String = ReadingUpdateConfiguration.publicKeyDERBase64
    ) throws {
        guard let keyData = Data(base64Encoded: publicKeyDERBase64),
              let encodedSignature = String(data: signatureData, encoding: .utf8),
              let signature = Data(
                base64Encoded: encodedSignature.trimmingCharacters(in: .whitespacesAndNewlines)
              ) else {
            throw ReadingUpdateValidationError.invalidSignature
        }
        let publicKey = try P256.Signing.PublicKey(derRepresentation: keyData)
        let ecdsaSignature = try P256.Signing.ECDSASignature(derRepresentation: signature)
        guard publicKey.isValidSignature(ecdsaSignature, for: manifestData) else {
            throw ReadingUpdateValidationError.invalidSignature
        }
    }
}

enum ReadingFileHasher {
    static func sha256(url: URL) throws -> String {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        var hasher = SHA256()
        while let chunk = try handle.read(upToCount: 1_024 * 1_024), !chunk.isEmpty {
            hasher.update(data: chunk)
        }
        return hasher.finalize().hex
    }
}

enum ReadingFileSynchronizer {
    static func synchronize(url: URL) throws {
        let handle = try FileHandle(forWritingTo: url)
        defer { try? handle.close() }
        try handle.synchronize()
    }
}

enum ReadingUpdatePackageValidator {
    static func validate(url: URL, manifest: ReadingUpdateManifest) throws {
        let values = try url.resourceValues(forKeys: [.fileSizeKey])
        guard Int64(values.fileSize ?? -1) == manifest.databaseSize else {
            throw ReadingUpdateValidationError.sizeMismatch
        }
        guard try ReadingFileHasher.sha256(url: url) == manifest.databaseSha256.lowercased() else {
            throw ReadingUpdateValidationError.hashMismatch
        }
        try ReadingDatabaseValidator.validate(url: url, expectedSchema: manifest.schemaVersion)
    }
}

enum LegacyFullDictionaryMigration {
    /// Preserves a pre-core extension-bundled full DB without ever loading it into process memory.
    @discardableResult
    static func preserve(
        source: URL,
        in directory: URL,
        expectedSha256: String
    ) throws -> URL? {
        let target = directory.appendingPathComponent(ReadingDataLocation.legacyFullFileName)
        if FileManager.default.fileExists(atPath: target.path) {
            if try ReadingFileHasher.sha256(url: target) == expectedSha256,
               (try? ReadingDatabaseValidator.validate(
                url: target,
                expectedSchema: ReadingDataConfiguration.supportedSchemaVersion
               )) != nil {
                return target
            }
            try FileManager.default.removeItem(at: target)
        }
        guard try ReadingFileHasher.sha256(url: source) == expectedSha256 else {
            return nil
        }
        guard (try? ReadingDatabaseValidator.validate(
            url: source,
            expectedSchema: ReadingDataConfiguration.supportedSchemaVersion
        )) != nil else { return nil }
        let staging = directory.appendingPathComponent("legacy-full.staging-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: staging) }
        try FileManager.default.copyItem(at: source, to: staging)
        try ReadingFileSynchronizer.synchronize(url: staging)
        try FileManager.default.moveItem(at: staging, to: target)
        return target
    }

    static func bundledFullURL(appBundle: Bundle = .main) -> URL? {
        guard let plugins = appBundle.builtInPlugInsURL,
              let entries = try? FileManager.default.contentsOfDirectory(
                at: plugins, includingPropertiesForKeys: nil
              ) else { return nil }
        return entries.lazy
            .filter { $0.pathExtension == "appex" }
            .compactMap { Bundle(url: $0) }
            .compactMap { $0.url(forResource: "reading", withExtension: "db") }
            .first
    }
}

@MainActor
final class ReadingDataUpdater: ObservableObject {
    @Published private(set) var status = AppStrings.text("update_checking")
    @Published private(set) var isUpdating = false

    func update() async {
        guard !isUpdating else { return }
        isUpdating = true
        defer { isUpdating = false }
        do {
            status = try await performUpdate()
        } catch {
            status = AppStrings.text("update_failed")
        }
    }

    private func performUpdate() async throws -> String {
        let manifestData = try await fetch(ReadingUpdateConfiguration.manifestURL, maximumSize: 32 * 1024)
        let signatureData = try await fetch(
            ReadingUpdateConfiguration.manifestURL.appendingPathExtension("sig"), maximumSize: 4 * 1024
        )
        try ReadingManifestSignatureVerifier.verify(manifestData: manifestData, signatureData: signatureData)
        let manifest = try JSONDecoder().decode(ReadingUpdateManifest.self, from: manifestData)
        try manifest.validate(appVersion: appVersion)
        guard let directory = ReadingDataLocation.sharedDirectory() else {
            throw UpdateError.sharedContainerUnavailable
        }
        preserveBundledLegacyFull(in: directory)
        if let active = try? ReadingDataLocation.activeData(in: directory),
           active.profile == nil || active.profile == ReadingDataConfiguration.fullProfile,
           active.schemaVersion == ReadingDataConfiguration.supportedSchemaVersion,
           ReadingDataLocation.activeDatabaseURL(in: directory) != nil,
           active.dataVersion >= manifest.dataVersion {
            return "\(AppStrings.text("update_current"))（\(active.dictionaryDate)）"
        }

        let (downloaded, response) = try await URLSession.shared.download(from: manifest.databaseUrl)
        try requireOK(response)
        let target = try stageValidateAndInstall(downloaded: downloaded, manifest: manifest, directory: directory)
        try ReadingDataLocation.activate(
            ActiveReadingData(
                fileName: target.lastPathComponent,
                dataVersion: manifest.dataVersion,
                schemaVersion: manifest.schemaVersion,
                dictionaryDate: manifest.dictionaryDate,
                profile: ReadingDataConfiguration.fullProfile
            ),
            in: directory
        )
        ReadingDataLocation.removeTemporaryFiles(in: directory)
        return "\(AppStrings.text("update_complete"))（\(manifest.dictionaryDate)）"
    }

    private func preserveBundledLegacyFull(in directory: URL) {
        guard let source = LegacyFullDictionaryMigration.bundledFullURL() else { return }
        _ = try? LegacyFullDictionaryMigration.preserve(
            source: source,
            in: directory,
            expectedSha256: ReadingUpdateConfiguration.legacyBundledFullSha256
        )
    }

    /// All large-file work stays on disk. The active pointer is untouched until this returns.
    private func stageValidateAndInstall(
        downloaded: URL,
        manifest: ReadingUpdateManifest,
        directory: URL
    ) throws -> URL {
        let fileName = "full-\(manifest.dataVersion).db"
        let target = directory.appendingPathComponent(fileName)
        if FileManager.default.fileExists(atPath: target.path) {
            // A previous interrupted download can leave this immutable name behind.  Keep a
            // valid one, but do not let a corrupt same-version file prevent recovery.
            if (try? ReadingUpdatePackageValidator.validate(url: target, manifest: manifest)) != nil {
                return target
            }
        }
        let staging = directory.appendingPathComponent("\(fileName).staging-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: staging) }
        try FileManager.default.moveItem(at: downloaded, to: staging)
        try ReadingFileSynchronizer.synchronize(url: staging)
        try ReadingUpdatePackageValidator.validate(url: staging, manifest: manifest)
        if FileManager.default.fileExists(atPath: target.path) {
            // Both URLs are in the App Group directory.  Replacement is atomic, so an already
            // running extension keeps its old file descriptor and a new reader sees a whole DB.
            _ = try FileManager.default.replaceItemAt(target, withItemAt: staging)
        } else {
            try FileManager.default.moveItem(at: staging, to: target)
        }
        return target
    }

    private func fetch(_ url: URL, maximumSize: Int) async throws -> Data {
        let (data, response) = try await URLSession.shared.data(from: url)
        try requireOK(response)
        guard data.count <= maximumSize else { throw UpdateError.responseTooLarge }
        return data
    }

    private func requireOK(_ response: URLResponse) throws {
        guard let response = response as? HTTPURLResponse, response.statusCode == 200 else {
            throw UpdateError.httpFailure
        }
    }

    private var appVersion: Int {
        Int(Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "1") ?? 1
    }

    enum UpdateError: Error {
        case sharedContainerUnavailable, responseTooLarge, httpFailure
    }
}

private extension SHA256.Digest {
    var hex: String { map { String(format: "%02x", $0) }.joined() }
}
