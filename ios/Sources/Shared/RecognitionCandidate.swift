import Foundation

struct RecognitionCandidate: Equatable {
    let text: String
    let reading: String?
    let score: Float
}

