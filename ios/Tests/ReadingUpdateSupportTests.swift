import CryptoKit
import Foundation
import SQLite3
import XCTest
@testable import FuriganaKeyboard

final class ReadingUpdateSupportTests: XCTestCase {
    private var root: URL!
    private var activeDirectory: URL!
    private var bundle: Bundle!
    private var bundledDatabase: URL!

    override func setUpWithError() throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        activeDirectory = root.appendingPathComponent("active", isDirectory: true)
        let bundleDirectory = root.appendingPathComponent("Test.bundle", isDirectory: true)
        try FileManager.default.createDirectory(at: activeDirectory, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: bundleDirectory, withIntermediateDirectories: true)
        let info = try PropertyListSerialization.data(
            fromPropertyList: ["CFBundleIdentifier": "test.reading.bundle"],
            format: .xml,
            options: 0
        )
        try info.write(to: bundleDirectory.appendingPathComponent("Info.plist"))
        bundledDatabase = bundleDirectory.appendingPathComponent("reading.db")
        try createDatabase(at: bundledDatabase, schema: 8)
        bundle = try XCTUnwrap(Bundle(url: bundleDirectory))
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: root)
    }

    func testActiveV7FallsBackToBundledV8() throws {
        let active = activeDirectory.appendingPathComponent("active-v7.db")
        try createDatabase(at: active, schema: 7)
        try activate(fileName: active.lastPathComponent, schema: 7)
        XCTAssertEqual(selectedDatabase(), bundledDatabase)
    }

    func testLegacyActiveWithoutSchemaFallsBackToBundledV8() throws {
        let active = activeDirectory.appendingPathComponent("legacy.db")
        try createDatabase(at: active, schema: 7)
        let legacy = [
            "fileName": active.lastPathComponent,
            "dataVersion": 99,
            "dictionaryDate": "2026-07-01"
        ] as [String: Any]
        let data = try JSONSerialization.data(withJSONObject: legacy)
        try data.write(to: activeDirectory.appendingPathComponent("active.json"))
        XCTAssertEqual(selectedDatabase(), bundledDatabase)
    }

    func testCorruptActiveFallsBackToBundledV8() throws {
        let active = activeDirectory.appendingPathComponent("corrupt.db")
        try Data("not sqlite".utf8).write(to: active)
        try activate(fileName: active.lastPathComponent, schema: 8)
        XCTAssertEqual(selectedDatabase(), bundledDatabase)
    }

    func testValidActiveV8IsSelected() throws {
        let active = activeDirectory.appendingPathComponent("active-v8.db")
        try createDatabase(at: active, schema: 8)
        try activate(fileName: active.lastPathComponent, schema: 8)
        XCTAssertEqual(selectedDatabase(), active)
    }

    func testV7ManifestIsIncompatibleAndV8ManifestIsAccepted() throws {
        let data = Data([0])
        XCTAssertThrowsError(try manifest(schema: 7, data: data).validate(appVersion: 1))
        XCTAssertNoThrow(try manifest(schema: 8, data: data).validate(appVersion: 1))
    }

    func testHashSizeAndIntegrityMismatchesAreRejected() throws {
        let valid = activeDirectory.appendingPathComponent("package.db")
        try createDatabase(at: valid, schema: 8)
        let data = try Data(contentsOf: valid)
        let validManifest = manifest(schema: 8, data: data)
        XCTAssertNoThrow(
            try ReadingUpdatePackageValidator.validate(data: data, at: valid, manifest: validManifest)
        )

        let wrongSize = manifest(schema: 8, data: data, size: Int64(data.count + 1))
        XCTAssertThrowsError(
            try ReadingUpdatePackageValidator.validate(data: data, at: valid, manifest: wrongSize)
        )

        let wrongHash = manifest(schema: 8, data: data, hash: String(repeating: "0", count: 64))
        XCTAssertThrowsError(
            try ReadingUpdatePackageValidator.validate(data: data, at: valid, manifest: wrongHash)
        )

        let corrupt = activeDirectory.appendingPathComponent("corrupt-package.db")
        let corruptData = Data("not sqlite".utf8)
        try corruptData.write(to: corrupt)
        XCTAssertThrowsError(
            try ReadingUpdatePackageValidator.validate(
                data: corruptData,
                at: corrupt,
                manifest: manifest(schema: 8, data: corruptData)
            )
        )
    }

    func testInvalidSignatureIsRejectedAndValidSignatureIsAccepted() throws {
        let key = P256.Signing.PrivateKey()
        let manifestData = Data("manifest".utf8)
        let signature = try key.signature(for: manifestData).derRepresentation
        let encoded = Data((signature.base64EncodedString() + "\n").utf8)
        let publicKey = key.publicKey.derRepresentation.base64EncodedString()

        XCTAssertNoThrow(
            try ReadingManifestSignatureVerifier.verify(
                manifestData: manifestData,
                signatureData: encoded,
                publicKeyDERBase64: publicKey
            )
        )
        XCTAssertThrowsError(
            try ReadingManifestSignatureVerifier.verify(
                manifestData: Data("changed".utf8),
                signatureData: encoded,
                publicKeyDERBase64: publicKey
            )
        )
    }

    func testFailedPackageValidationKeepsPreviousActiveDatabase() throws {
        let active = activeDirectory.appendingPathComponent("previous.db")
        try createDatabase(at: active, schema: 8)
        try activate(fileName: active.lastPathComponent, schema: 8)
        let failedDownload = activeDirectory.appendingPathComponent("failed.tmp")
        let failedData = Data("bad".utf8)
        try failedData.write(to: failedDownload)

        XCTAssertThrowsError(
            try ReadingUpdatePackageValidator.validate(
                data: failedData,
                at: failedDownload,
                manifest: manifest(schema: 8, data: failedData)
            )
        )
        XCTAssertEqual(selectedDatabase(), active)
    }

    private func selectedDatabase() -> URL? {
        ReadingDataLocation.databaseURL(bundle: bundle, activeDirectory: activeDirectory)
    }

    private func activate(fileName: String, schema: Int) throws {
        try ReadingDataLocation.activate(
            ActiveReadingData(
                fileName: fileName,
                dataVersion: 99,
                schemaVersion: schema,
                dictionaryDate: "2026-07-11"
            ),
            in: activeDirectory
        )
    }

    private func manifest(
        schema: Int,
        data: Data,
        size: Int64? = nil,
        hash: String? = nil
    ) -> ReadingUpdateManifest {
        ReadingUpdateManifest(
            formatVersion: 1,
            dataVersion: 100,
            schemaVersion: schema,
            minAppVersion: 1,
            databaseUrl: URL(string: "https://example.com/reading.db")!,
            databaseSize: size ?? Int64(data.count),
            databaseSha256: hash ?? SHA256.hash(data: data).map {
                String(format: "%02x", $0)
            }.joined(),
            dictionaryDate: "2026-07-11"
        )
    }

    private func createDatabase(at url: URL, schema: Int) throws {
        var database: OpaquePointer?
        guard sqlite3_open(url.path, &database) == SQLITE_OK, let database else {
            throw TestError.sqlite
        }
        defer { sqlite3_close(database) }
        guard sqlite3_exec(
            database,
            "CREATE TABLE metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
            nil,
            nil,
            nil
        ) == SQLITE_OK,
        sqlite3_exec(
            database,
            "INSERT INTO metadata VALUES ('schema_version', '\(schema)')",
            nil,
            nil,
            nil
        ) == SQLITE_OK else {
            throw TestError.sqlite
        }
    }

    private enum TestError: Error { case sqlite }
}
