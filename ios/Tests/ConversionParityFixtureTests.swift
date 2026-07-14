import XCTest
import CryptoKit
@testable import FuriganaKeyboard

final class ConversionParityFixtureTests: XCTestCase {
    func testSharedCostFixtureMatchesSwiftIntegerCalculation() throws {
        let cases = try costCases()
        XCTAssertGreaterThanOrEqual(cases.filter { $0.kind == "actual" }.count, 50)

        for entry in cases {
            XCTAssertEqual(
                ConversionCost.adjustedWordCost(
                    reading: entry.reading,
                    surface: entry.surface,
                    leftID: entry.leftID,
                    rawWordCost: entry.rawWordCost,
                    frequencyByHanLiteral: entry.frequencies
                ),
                entry.adjustedWordCost,
                entry.description
            )
        }
    }

    func testSharedCostFixtureMatchesBundledRepository() throws {
        let repository = try bundledRepository()
        let actualCases = try costCases().filter { $0.kind == "actual" }

        for (reading, cases) in Dictionary(grouping: actualCases, by: \.reading) {
            let lexemes = repository.conversionData(for: reading).lexemes
            let scalarCount = reading.unicodeScalars.count
            for entry in cases {
                let matching = lexemes.filter { lexeme in
                    lexeme.start == 0 && lexeme.end == scalarCount &&
                    ConversionText.scalarEquals(lexeme.reading, reading) &&
                    ConversionText.scalarEquals(lexeme.surface, entry.surface) &&
                    lexeme.leftID == entry.leftID && lexeme.rightID == entry.rightID
                }
                XCTAssertEqual(matching.count, 1, entry.description)
                XCTAssertEqual(matching.first?.wordCost, entry.adjustedWordCost, entry.description)
            }
        }
    }

    func testSharedNBestFixtureMatchesSwiftConverterAndBundledDatabase() throws {
        let repository = try bundledRepository()
        let fixture = try JSONDecoder().decode(
            [NBestCase].self,
            from: Data(contentsOf: fixtureURL("conversion-nbest.json"))
        )

        for entry in fixture {
            let data = repository.conversionData(for: entry.reading)
            let actual = KanaKanjiConverter.convert(
                reading: entry.reading,
                lexemes: data.lexemes,
                connections: data.connections,
                limit: 8
            ).map { RankedResult(surface: $0.surface, cost: $0.cost) }
            XCTAssertEqual(actual, entry.results, entry.reading)
        }
    }

    func testSharedContextModelAndNBestFixtureMatchSwiftRuntime() throws {
        let fixture = try JSONDecoder().decode(
            ContextFixture.self,
            from: Data(contentsOf: fixtureURL("context-conversion-nbest.json"))
        )
        XCTAssertEqual(fixture.schemaVersion, 1)
        let modelData = try Data(contentsOf: fixtureURL("context-model.bin"))
        let model = try ConversionContextModel(data: modelData)
        XCTAssertEqual(model.metadata.formatVersion, fixture.model.formatVersion)
        XCTAssertEqual(model.metadata.modelVersion, fixture.model.modelVersion)
        XCTAssertEqual(model.metadata.unigramCount, fixture.model.unigramCount)
        XCTAssertEqual(model.metadata.bigramCount, fixture.model.bigramCount)
        XCTAssertEqual(model.metadata.sourceSha256, fixture.model.sourceSha256)
        XCTAssertEqual(modelData.count, fixture.model.modelBytes)
        XCTAssertEqual(
            SHA256.hash(data: modelData).map { String(format: "%02x", $0) }.joined(),
            fixture.model.modelSha256
        )
        XCTAssertTrue(model.hasBigram(previousSurface: "学校", nextSurface: "行きます"))
        XCTAssertTrue(model.hasBigram(previousSurface: "写真", nextSurface: "撮って"))
        XCTAssertTrue(model.hasBigram(previousSurface: "変換", nextSurface: "精度"))
        XCTAssertTrue(model.hasBigram(previousSurface: "お茶", nextSurface: "飲み"))
        XCTAssertEqual(model.cost(previousSurface: "未登録", nextSurface: "未知語"), 0)

        let repository = try bundledRepository()
        for entry in fixture.cases {
            let data = repository.conversionData(for: entry.reading)
            let oneCandidate = KanaKanjiConverter.convert(
                reading: entry.reading,
                lexemes: data.lexemes,
                connections: data.connections,
                limit: 1,
                contextModel: model,
                beamWidth: 12
            )
            let production = KanaKanjiConverter.convert(
                reading: entry.reading,
                lexemes: data.lexemes,
                connections: data.connections,
                limit: 8,
                contextModel: model,
                beamWidth: 12
            ).map { RankedResult(surface: $0.surface, cost: $0.cost) }
            let wideReference = KanaKanjiConverter.convert(
                reading: entry.reading,
                lexemes: data.lexemes,
                connections: data.connections,
                limit: 8,
                contextModel: model,
                beamWidth: 64
            ).map { RankedResult(surface: $0.surface, cost: $0.cost) }
            XCTAssertEqual(production, entry.results, entry.reading)
            XCTAssertEqual(oneCandidate.first?.surface, entry.results.first?.surface, entry.reading)
            XCTAssertEqual(wideReference, production, entry.reading)
        }

        let suffixReading = "いきます"
        let suffixData = repository.conversionData(for: suffixReading)
        let contextualSuffix = KanaKanjiConverter.convert(
            reading: suffixReading,
            lexemes: suffixData.lexemes,
            connections: suffixData.connections,
            limit: 8,
            initialRightID: 5,
            initialContextSurface: "学校",
            contextModel: model
        )
        XCTAssertEqual(contextualSuffix.first?.surface, "行きます", "confirmed bunsetsu context")
    }

    func testSharedSentenceRegressionFixtureReportsBundledDatabaseQuality() throws {
        let fixture = try JSONDecoder().decode(
            SentenceFixture.self,
            from: Data(contentsOf: fixtureURL("sentence-conversion-regression.json"))
        )
        XCTAssertEqual(fixture.schemaVersion, 1)
        XCTAssertEqual(fixture.cases.count, 57)
        XCTAssertEqual(
            Dictionary(grouping: fixture.cases, by: \.category).mapValues(\.count),
            [
                "助詞・助動詞": 6,
                "同音異義語": 6,
                "活用": 6,
                "複文節": 6,
                "固有名詞": 6,
                "カタカナ外来語": 9,
                "未知語": 6,
                "数字": 6,
                "文節境界": 6
            ]
        )

        let repository = try bundledRepository()
        let knownFailureIDs = Set(fixture.baselineKnownFailureIDs)
        let ranked = fixture.cases.map { entry -> (SentenceCase, [String], [String]) in
            let data = repository.conversionData(for: entry.reading)
            let baseline = KanaKanjiConverter.convert(
                reading: entry.reading,
                lexemes: data.lexemes,
                connections: data.connections,
                limit: 8
            ).map(\.surface)
            let contextual = KanaKanjiConverter.convert(
                reading: entry.reading,
                lexemes: data.lexemes,
                connections: data.connections,
                limit: 8,
                contextModel: repository.contextModel
            ).map(\.surface)
            return (entry, baseline, contextual)
        }
        let baselineOutcomes = ranked.map { entry, baseline, _ in
            return SentenceOutcome(
                entry: entry,
                candidates: baseline,
                baseline: knownFailureIDs.contains(entry.id) ? .knownFailure : .pass
            )
        }
        let outcomes = ranked.map { entry, _, contextual in
            SentenceOutcome(
                entry: entry,
                candidates: contextual,
                baseline: knownFailureIDs.contains(entry.id) ? .knownFailure : .pass
            )
        }
        let baselineReport = SentenceRegressionReport(outcomes: baselineOutcomes)
        let report = SentenceRegressionReport(outcomes: outcomes)
        var parityPayload = ""
        for (entry, _, contextual) in ranked {
            parityPayload += entry.id + "\u{0}"
            for candidate in contextual { parityPayload += candidate + "\u{0}" }
            parityPayload += "\u{1}"
        }
        let parityDigest = SHA256.hash(data: Data(parityPayload.utf8))
            .map { String(format: "%02x", $0) }
            .joined()
        print("context-nbest-sha256=\(parityDigest)")
        print("baseline\n\(baselineReport.render())context\n\(report.render())")

        XCTAssertGreaterThan(report.top1Correct, baselineReport.top1Correct, report.render())
        for (category, baselineTop1) in baselineReport.top1ByCategory {
            XCTAssertGreaterThanOrEqual(
                report.top1ByCategory[category] ?? -1,
                baselineTop1,
                "category[\(category)] top-1 regressed"
            )
        }
        for (category, baselineTop3) in baselineReport.top3ByCategory {
            XCTAssertGreaterThanOrEqual(
                report.top3ByCategory[category] ?? -1,
                baselineTop3,
                "category[\(category)] top-3 regressed"
            )
        }
        for (category, baselineNBest) in baselineReport.nBestByCategory {
            XCTAssertGreaterThanOrEqual(
                report.nBestByCategory[category] ?? -1,
                baselineNBest,
                "category[\(category)] N-best regressed"
            )
        }
        XCTAssertLessThanOrEqual(report.forbiddenCount, baselineReport.forbiddenCount)
        XCTAssertTrue(report.regressions.isEmpty, report.regressionsMessage())
    }

    func testContextConversionP95Report() throws {
        let repository = try bundledRepository()
        let readings = [
            "がっこうへいきます",
            "しゃしんをとってください",
            "へんかんせいどをたしかめる",
            "おちゃをのみたい"
        ]
        let prepared = Dictionary(uniqueKeysWithValues: readings.map { reading in
            (reading, repository.conversionData(for: reading))
        })

        func convertPrepared(_ reading: String, contextual: Bool) {
            let data = prepared[reading]!
            _ = KanaKanjiConverter.convert(
                reading: reading,
                lexemes: data.lexemes,
                connections: data.connections,
                contextModel: contextual ? repository.contextModel : .empty
            )
        }
        func convertWithLookup(_ reading: String, contextual: Bool) {
            let data = repository.conversionData(for: reading)
            _ = KanaKanjiConverter.convert(
                reading: reading,
                lexemes: data.lexemes,
                connections: data.connections,
                contextModel: contextual ? repository.contextModel : .empty
            )
        }

        for _ in 0..<4 {
            for reading in readings {
                convertPrepared(reading, contextual: false)
                convertPrepared(reading, contextual: true)
                convertWithLookup(reading, contextual: false)
                convertWithLookup(reading, contextual: true)
            }
        }
        var baselineEngine: [Double] = []
        var contextEngine: [Double] = []
        for index in 0..<40 {
            let reading = readings[index % readings.count]
            if index.isMultiple(of: 2) {
                baselineEngine.append(timing { convertPrepared(reading, contextual: false) })
                contextEngine.append(timing { convertPrepared(reading, contextual: true) })
            } else {
                contextEngine.append(timing { convertPrepared(reading, contextual: true) })
                baselineEngine.append(timing { convertPrepared(reading, contextual: false) })
            }
        }
        var baselineConversion: [Double] = []
        var contextConversion: [Double] = []
        for index in 0..<20 {
            let reading = readings[index % readings.count]
            if index.isMultiple(of: 2) {
                baselineConversion.append(timing { convertWithLookup(reading, contextual: false) })
                contextConversion.append(timing { convertWithLookup(reading, contextual: true) })
            } else {
                contextConversion.append(timing { convertWithLookup(reading, contextual: true) })
                baselineConversion.append(timing { convertWithLookup(reading, contextual: false) })
            }
        }
        let baselineEngineP95 = percentile95(baselineEngine)
        let contextEngineP95 = percentile95(contextEngine)
        let baselineConversionP50 = percentile(baselineConversion, fraction: 0.50)
        let contextConversionP50 = percentile(contextConversion, fraction: 0.50)
        let baselineConversionP95 = percentile95(baselineConversion)
        let contextConversionP95 = percentile95(contextConversion)
        let modelBytes = try Data(contentsOf: fixtureURL("context-model.bin")).count
        print(
            String(
                format: "context-performance: baseline_conversion_p50_ms=%.3f " +
                    "context_conversion_p50_ms=%.3f " +
                    "baseline_conversion_p95_ms=%.3f " +
                    "context_conversion_p95_ms=%.3f context_conversion_delta_p95_ms=%.3f " +
                    "baseline_engine_p95_ms=%.3f context_engine_p95_ms=%.3f " +
                    "context_engine_delta_p95_ms=%.3f " +
                    "context_model_bytes=%d",
                locale: Locale(identifier: "en_US_POSIX"),
                baselineConversionP50,
                contextConversionP50,
                baselineConversionP95,
                contextConversionP95,
                contextConversionP95 - baselineConversionP95,
                baselineEngineP95,
                contextEngineP95,
                contextEngineP95 - baselineEngineP95,
                modelBytes
            )
        )
        XCTAssertLessThanOrEqual(contextConversionP95, baselineConversionP95 * 1.25 + 1)
        XCTAssertLessThanOrEqual(contextEngineP95, baselineEngineP95 * 1.25 + 1)
    }

    private func bundledRepository() throws -> ReadingRepository {
        let bundleURL = Bundle.main.builtInPlugInsURL?
            .appendingPathComponent("FuriganaKeyboardExtension.appex")
        guard let bundleURL,
              let bundle = Bundle(url: bundleURL),
              let repository = ReadingRepository(bundle: bundle) else {
            throw XCTSkip("Embedded keyboard resources are unavailable in this test host")
        }
        return repository
    }

    private func costCases() throws -> [CostCase] {
        let value = try String(contentsOf: fixtureURL("conversion-cost.tsv"), encoding: .utf8)
        return try value.split(whereSeparator: \.isNewline).dropFirst().map { line in
            let fields = String(line).components(separatedBy: "\t")
            guard fields.count == 8,
                  let leftID = Int(fields[3]),
                  let rightID = Int(fields[4]),
                  let rawWordCost = Int(fields[5]),
                  let adjustedWordCost = Int(fields[7]) else {
                throw FixtureError.invalidLine(String(line))
            }
            return CostCase(
                kind: fields[0],
                reading: fields[1],
                surface: fields[2],
                leftID: leftID,
                rightID: rightID,
                rawWordCost: rawWordCost,
                frequencies: try parseFrequencies(fields[6]),
                adjustedWordCost: adjustedWordCost
            )
        }
    }

    private func parseFrequencies(_ value: String) throws -> [String: Int] {
        guard value != "-" else { return [:] }
        var output: [String: Int] = [:]
        for item in value.split(separator: ",") {
            let fields = item.split(separator: "=", maxSplits: 1).map(String.init)
            guard fields.count == 2 else { throw FixtureError.invalidFrequency(String(item)) }
            if fields[1] == "?" { continue }
            guard let codePoint = UInt32(fields[0].dropFirst(2), radix: 16),
                  let scalar = Unicode.Scalar(codePoint),
                  let frequency = Int(fields[1]) else {
                throw FixtureError.invalidFrequency(String(item))
            }
            output[String(scalar)] = frequency
        }
        return output
    }

    private func timing(operation: () -> Void) -> Double {
        let start = DispatchTime.now().uptimeNanoseconds
        operation()
        let elapsed = DispatchTime.now().uptimeNanoseconds - start
        return Double(elapsed) / 1_000_000
    }

    private func percentile95(_ values: [Double]) -> Double {
        percentile(values, fraction: 0.95)
    }

    private func percentile(_ values: [Double], fraction: Double) -> Double {
        let sorted = values.sorted()
        return sorted[Int(Double(sorted.count - 1) * fraction)]
    }

    private func fixtureURL(_ name: String) -> URL {
        if let bundled = Bundle(for: Self.self).url(forResource: name, withExtension: nil) {
            return bundled
        }
        return URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .appendingPathComponent("../../fixtures")
            .standardizedFileURL
            .appendingPathComponent(name)
    }

    private struct CostCase {
        let kind: String
        let reading: String
        let surface: String
        let leftID: Int
        let rightID: Int
        let rawWordCost: Int
        let frequencies: [String: Int]
        let adjustedWordCost: Int

        var description: String { "\(reading)/\(surface)/\(leftID)/\(rightID)" }
    }

    private struct NBestCase: Decodable {
        let reading: String
        let results: [RankedResult]
    }

    private struct ContextFixture: Decodable {
        let schemaVersion: Int
        let model: ContextModelFixture
        let cases: [NBestCase]
    }

    private struct ContextModelFixture: Decodable {
        let formatVersion: Int
        let modelVersion: Int
        let unigramCount: Int
        let bigramCount: Int
        let sourceSha256: String
        let modelSha256: String
        let modelBytes: Int
    }

    private struct RankedResult: Codable, Equatable {
        let surface: String
        let cost: Int
    }

    private struct SentenceFixture: Decodable {
        let schemaVersion: Int
        let baselineKnownFailureIDs: [String]
        let cases: [SentenceCase]

        private enum CodingKeys: String, CodingKey {
            case schemaVersion
            case baselineKnownFailureIDs = "baselineKnownFailureIds"
            case cases
        }
    }

    private struct SentenceCase: Decodable {
        let id: String
        let category: String
        let reading: String
        let expectedTop1: String?
        let requiredNBest: [String]
        let forbiddenCandidates: [String]
    }

    private enum SentenceBaseline: Equatable {
        case pass
        case knownFailure
    }

    private struct SentenceOutcome {
        let entry: SentenceCase
        let candidates: [String]
        let baseline: SentenceBaseline

        var top1Matches: Bool {
            entry.expectedTop1 == nil || candidates.first == entry.expectedTop1
        }

        var nBestMatches: Bool {
            entry.requiredNBest.allSatisfy(candidates.contains)
        }

        var top3Matches: Bool {
            let top3 = candidates.prefix(3)
            return entry.requiredNBest.allSatisfy(top3.contains)
        }

        var forbiddenCount: Int {
            candidates.filter(entry.forbiddenCandidates.contains).count
        }

        var passes: Bool {
            top1Matches && nBestMatches && forbiddenCount == 0
        }

        var isRegression: Bool {
            baseline == .pass && !passes
        }

        var isImprovement: Bool {
            baseline == .knownFailure && passes
        }
    }

    private struct SentenceRegressionReport {
        let outcomes: [SentenceOutcome]

        var top1Correct: Int {
            outcomes.filter { $0.entry.expectedTop1 != nil && $0.top1Matches }.count
        }

        var top1ByCategory: [String: Int] {
            Dictionary(grouping: outcomes, by: { $0.entry.category }).mapValues { values in
                values.filter { $0.entry.expectedTop1 != nil && $0.top1Matches }.count
            }
        }

        var top3ByCategory: [String: Int] {
            Dictionary(grouping: outcomes, by: { $0.entry.category }).mapValues { values in
                values.filter(\.top3Matches).count
            }
        }

        var nBestByCategory: [String: Int] {
            Dictionary(grouping: outcomes, by: { $0.entry.category }).mapValues { values in
                values.filter(\.nBestMatches).count
            }
        }

        var forbiddenCount: Int {
            outcomes.reduce(0) { $0 + $1.forbiddenCount }
        }

        var regressions: [SentenceOutcome] {
            outcomes.filter(\.isRegression)
        }

        func regressionsMessage() -> String {
            var output = render()
            if !regressions.isEmpty {
                output += "\nRegression IDs: " + regressions.map(\.entry.id).joined(separator: ", ")
            }
            return output
        }

        func render() -> String {
            let top1Denominator = outcomes.filter { $0.entry.expectedTop1 != nil }.count
            let correctTop1 = top1Correct
            let top3Correct = outcomes.filter(\.top3Matches).count
            let forbidden = forbiddenCount
            let knownFailures = outcomes.filter { $0.baseline == .knownFailure }.count
            let unresolved = outcomes.filter { $0.baseline == .knownFailure && !$0.passes }.count
            let improvements = outcomes.filter(\.isImprovement).count
            var lines = [
                "sentence-conversion-regression: cases=\(outcomes.count)",
                "top-1 accuracy: \(correctTop1)/\(top1Denominator) (\(percent(correctTop1, top1Denominator)))",
                "top-3 containment: \(top3Correct)/\(outcomes.count) (\(percent(top3Correct, outcomes.count)))",
                "forbidden candidate count: \(forbidden)",
                "known failures: unresolved=\(unresolved)/\(knownFailures) improvements=\(improvements) regressions=\(regressions.count)"
            ]
            let categories = Dictionary(grouping: outcomes, by: { $0.entry.category })
            for category in categories.keys.sorted() {
                let categoryOutcomes = categories[category] ?? []
                let top1Denominator = categoryOutcomes.filter { $0.entry.expectedTop1 != nil }.count
                let top1 = categoryOutcomes.filter { $0.entry.expectedTop1 != nil && $0.top1Matches }.count
                lines.append(
                    "category[\(category)]: top1=\(top1)/\(top1Denominator) " +
                    "top3=\(categoryOutcomes.filter(\.top3Matches).count)/\(categoryOutcomes.count) " +
                    "nbest=\(categoryOutcomes.filter(\.nBestMatches).count)/\(categoryOutcomes.count) " +
                    "pass=\(categoryOutcomes.filter(\.passes).count)/\(categoryOutcomes.count) " +
                    "forbidden=\(categoryOutcomes.reduce(0) { $0 + $1.forbiddenCount })"
                )
            }
            for outcome in outcomes where !outcome.passes {
                lines.append("  \(outcome.entry.id) [\(outcome.baseline == .knownFailure ? "known_failure" : "pass")] reading=\(outcome.entry.reading) expectedTop1=\(outcome.entry.expectedTop1 ?? "-") required=\(outcome.entry.requiredNBest) actual=\(outcome.candidates)")
            }
            return lines.joined(separator: "\n")
        }

        private func percent(_ numerator: Int, _ denominator: Int) -> String {
            guard denominator > 0 else { return "n/a" }
            return String(format: "%.1f%%", locale: Locale(identifier: "en_US_POSIX"), Double(numerator) * 100 / Double(denominator))
        }
    }

    private enum FixtureError: Error {
        case invalidLine(String)
        case invalidFrequency(String)
    }
}
