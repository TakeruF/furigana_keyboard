import Foundation

/// Recognizer that produced a piece of handwriting evidence.
///
/// There is no ML Kit case: Furigana Plus is Android-only, so every raw
/// candidate on iOS comes from the bundled Zinnia model.
enum RecognitionSource: Equatable {
    case zinnia
    case sideBySide
    case legacyNative

    /// Side-by-side candidates are composed, so they carry component evidence instead.
    var isRawRecognizer: Bool { self != .sideBySide }
}

/// Evidence for one character in a composed, side-by-side candidate.
struct RecognitionComponentEvidence: Equatable {
    let position: Int
    let text: String
    let shapeCost: Float
    let source: RecognitionSource
    let rawScore: Float?
    let rawRank: Int?
    let isRecognizerRawTop: Bool
}

/// Raw recognizer evidence retained after score normalization and reranking.
struct RecognitionEvidence: Equatable {
    let source: RecognitionSource
    let rawScore: Float?
    let rawRank: Int?
    let components: [RecognitionComponentEvidence]

    init(
        source: RecognitionSource,
        rawScore: Float? = nil,
        rawRank: Int? = nil,
        components: [RecognitionComponentEvidence] = []
    ) {
        self.source = source
        self.rawScore = rawScore
        self.rawRank = rawRank
        self.components = components
    }

    var isRecognizerRawTop: Bool {
        if source.isRawRecognizer { return rawRank == 0 }
        // A composed candidate is what the recognizer read when every half is its own raw top.
        return !components.isEmpty && components.allSatisfy(\.isRecognizerRawTop)
    }
}

/// The information a recognizer exposes before common-cost normalization.
struct RawRecognitionCandidate: Equatable {
    let text: String
    let score: Float?

    init(_ text: String, _ score: Float? = nil) {
        self.text = text
        self.score = score
    }
}

/// A normalized handwriting candidate. `shapeCost` is always lower-is-better.
///
/// The score-taking initializer is retained for the Zinnia bridging boundary. Its
/// value is raw higher-is-better evidence, so recognizer-facing code must normalize
/// a batch before comparing candidates.
struct RecognitionCandidate: Equatable {
    let text: String
    let reading: String?
    let shapeCost: Float
    let evidence: RecognitionEvidence

    init(text: String, reading: String? = nil, shapeCost: Float, evidence: RecognitionEvidence) {
        self.text = text
        self.reading = reading
        self.shapeCost = shapeCost
        self.evidence = evidence
    }

    init(text: String, reading: String? = nil, score: Float) {
        self.init(
            text: text,
            reading: reading,
            shapeCost: .nan,
            evidence: RecognitionEvidence(source: .legacyNative, rawScore: score)
        )
    }

    /// Compatibility view for callers that still think in higher-is-better scores.
    var score: Float { evidence.rawScore ?? -shapeCost }

    var isRecognizerRawTop: Bool { evidence.isRecognizerRawTop }

    func withReading(_ reading: String?) -> RecognitionCandidate {
        RecognitionCandidate(text: text, reading: reading, shapeCost: shapeCost, evidence: evidence)
    }
}
