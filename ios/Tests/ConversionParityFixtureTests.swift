import XCTest
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

    func testSharedSentenceRegressionFixtureReportsBundledDatabaseQuality() throws {
        let fixture = try JSONDecoder().decode(
            SentenceFixture.self,
            from: Data(contentsOf: fixtureURL("sentence-conversion-regression.json"))
        )
        XCTAssertEqual(fixture.schemaVersion, 1)
        XCTAssertEqual(fixture.cases.count, 54)
        XCTAssertEqual(
            Dictionary(grouping: fixture.cases, by: \.category).mapValues(\.count),
            [
                "助詞・助動詞": 6,
                "同音異義語": 6,
                "活用": 6,
                "複文節": 6,
                "固有名詞": 6,
                "カタカナ外来語": 6,
                "未知語": 6,
                "数字": 6,
                "文節境界": 6
            ]
        )

        let repository = try bundledRepository()
        let knownFailureIDs = Set(fixture.baselineKnownFailureIDs)
        let outcomes = fixture.cases.map { entry in
            let data = repository.conversionData(for: entry.reading)
            let candidates = KanaKanjiConverter.convert(
                reading: entry.reading,
                lexemes: data.lexemes,
                connections: data.connections,
                limit: 8
            ).map(\.surface)
            return SentenceOutcome(
                entry: entry,
                candidates: candidates,
                baseline: knownFailureIDs.contains(entry.id) ? .knownFailure : .pass
            )
        }
        let report = SentenceRegressionReport(outcomes: outcomes)
        print(report.render())

        // Known failures stay exercised. Their resolution is reported as an
        // improvement; only a baseline passing case becoming a failure fails.
        XCTAssertTrue(report.regressions.isEmpty, report.regressionsMessage())
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
            let top1Correct = outcomes.filter { $0.entry.expectedTop1 != nil && $0.top1Matches }.count
            let top3Correct = outcomes.filter(\.top3Matches).count
            let forbidden = outcomes.reduce(0) { $0 + $1.forbiddenCount }
            let knownFailures = outcomes.filter { $0.baseline == .knownFailure }.count
            let unresolved = outcomes.filter { $0.baseline == .knownFailure && !$0.passes }.count
            let improvements = outcomes.filter(\.isImprovement).count
            var lines = [
                "sentence-conversion-regression: cases=\(outcomes.count)",
                "top-1 accuracy: \(top1Correct)/\(top1Denominator) (\(percent(top1Correct, top1Denominator)))",
                "top-3 containment: \(top3Correct)/\(outcomes.count) (\(percent(top3Correct, outcomes.count)))",
                "forbidden candidate count: \(forbidden)",
                "known failures: unresolved=\(unresolved)/\(knownFailures) improvements=\(improvements) regressions=\(regressions.count)"
            ]
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
