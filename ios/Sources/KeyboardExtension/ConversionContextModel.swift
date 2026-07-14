import Foundation

struct ConversionContextMetadata: Equatable {
    let formatVersion: Int
    let modelVersion: Int
    let unigramCount: Int
    let bigramCount: Int
    let sourceSha256: String

    var entryCount: Int { unigramCount + bigramCount }
}

/// Immutable generated costs decoded from the same FKCTX bytes used by Android.
struct ConversionContextModel {
    private struct SurfaceKey: Hashable { let scalars: [UInt32] }
    private struct BigramKey: Hashable {
        let previous: SurfaceKey
        let next: SurfaceKey
    }

    let metadata: ConversionContextMetadata
    private let unigramCosts: [SurfaceKey: Int]
    private let bigramCosts: [BigramKey: Int]

    static let empty = ConversionContextModel(
        metadata: ConversionContextMetadata(
            formatVersion: 0,
            modelVersion: 0,
            unigramCount: 0,
            bigramCount: 0,
            sourceSha256: String(repeating: "0", count: 64)
        ),
        unigramCosts: [:],
        bigramCosts: [:]
    )

    /// Exact surface bigram first; an unknown pair backs off to next-surface unigram.
    func cost(previousSurface: String?, nextSurface: String) -> Int {
        let next = SurfaceKey(scalars: ConversionText.scalarValues(nextSurface))
        if let previousSurface {
            let previous = SurfaceKey(scalars: ConversionText.scalarValues(previousSurface))
            if let exact = bigramCosts[BigramKey(previous: previous, next: next)] {
                return exact
            }
        }
        return unigramCosts[next] ?? 0
    }

    func hasBigram(previousSurface: String, nextSurface: String) -> Bool {
        bigramCosts[BigramKey(
            previous: SurfaceKey(scalars: ConversionText.scalarValues(previousSurface)),
            next: SurfaceKey(scalars: ConversionText.scalarValues(nextSurface))
        )] != nil
    }

    init(data: Data) throws {
        var reader = ContextModelReader(data: data)
        guard try reader.readBytes(count: 8) == Array("FKCTX001".utf8) else {
            throw ConversionContextModelError.invalidMagic
        }
        let formatVersion = try reader.readUInt16()
        guard formatVersion == 1 else {
            throw ConversionContextModelError.unsupportedFormat(formatVersion)
        }
        let modelVersion = try reader.readUInt16()
        guard modelVersion > 0 else { throw ConversionContextModelError.invalidVersion }
        let unigramCount = try reader.readCount()
        let bigramCount = try reader.readCount()
        guard unigramCount <= 1_000_000,
              bigramCount <= 1_000_000,
              unigramCount + bigramCount <= 1_000_000 else {
            throw ConversionContextModelError.tooManyEntries
        }
        let sourceSha256 = try reader.readBytes(count: 32)
            .map { String(format: "%02x", $0) }
            .joined()

        var unigrams: [SurfaceKey: Int] = [:]
        unigrams.reserveCapacity(unigramCount)
        for _ in 0..<unigramCount {
            let key = SurfaceKey(scalars: ConversionText.scalarValues(try reader.readString()))
            guard unigrams.updateValue(try reader.readInt32(), forKey: key) == nil else {
                throw ConversionContextModelError.duplicateEntry
            }
        }
        var bigrams: [BigramKey: Int] = [:]
        bigrams.reserveCapacity(bigramCount)
        for _ in 0..<bigramCount {
            let previous = SurfaceKey(scalars: ConversionText.scalarValues(try reader.readString()))
            let next = SurfaceKey(scalars: ConversionText.scalarValues(try reader.readString()))
            let key = BigramKey(previous: previous, next: next)
            guard bigrams.updateValue(try reader.readInt32(), forKey: key) == nil else {
                throw ConversionContextModelError.duplicateEntry
            }
        }
        guard reader.isAtEnd else { throw ConversionContextModelError.trailingBytes }
        metadata = ConversionContextMetadata(
            formatVersion: formatVersion,
            modelVersion: modelVersion,
            unigramCount: unigramCount,
            bigramCount: bigramCount,
            sourceSha256: sourceSha256
        )
        unigramCosts = unigrams
        bigramCosts = bigrams
    }

    private init(
        metadata: ConversionContextMetadata,
        unigramCosts: [SurfaceKey: Int],
        bigramCosts: [BigramKey: Int]
    ) {
        self.metadata = metadata
        self.unigramCosts = unigramCosts
        self.bigramCosts = bigramCosts
    }
}

enum ConversionContextModelError: Error, Equatable {
    case truncated
    case invalidMagic
    case unsupportedFormat(Int)
    case invalidVersion
    case tooManyEntries
    case invalidString
    case duplicateEntry
    case trailingBytes
}

private struct ContextModelReader {
    private let bytes: [UInt8]
    private var offset = 0

    init(data: Data) { bytes = Array(data) }
    var isAtEnd: Bool { offset == bytes.count }

    mutating func readUInt16() throws -> Int {
        let value = try readBytes(count: 2)
        return Int(value[0]) | Int(value[1]) << 8
    }

    mutating func readCount() throws -> Int {
        let value = try readUInt32()
        guard value <= UInt32(Int32.max) else { throw ConversionContextModelError.tooManyEntries }
        return Int(value)
    }

    mutating func readUInt32() throws -> UInt32 {
        let value = try readBytes(count: 4)
        return UInt32(value[0]) |
            UInt32(value[1]) << 8 |
            UInt32(value[2]) << 16 |
            UInt32(value[3]) << 24
    }

    mutating func readInt32() throws -> Int {
        Int(Int32(bitPattern: try readUInt32()))
    }

    mutating func readString() throws -> String {
        let length = try readUInt16()
        guard length > 0 else { throw ConversionContextModelError.invalidString }
        let encoded = try readBytes(count: length)
        guard let value = String(bytes: encoded, encoding: .utf8) else {
            throw ConversionContextModelError.invalidString
        }
        return value
    }

    mutating func readBytes(count: Int) throws -> [UInt8] {
        guard count >= 0, offset <= bytes.count - count else {
            throw ConversionContextModelError.truncated
        }
        defer { offset += count }
        return Array(bytes[offset..<(offset + count)])
    }
}
