import Foundation

/// Conversion-key state shared by Space and Return on the Japanese romaji panel.
final class RomajiConversionState {
    enum Phase: Equatable {
        case empty
        case composing
        case selectingCandidate
    }

    enum Action: Equatable {
        case insertSpace
        case sendEditorAction
        case commitComposition
        case selectCandidate(Int)
        case commitCandidate(Int)
    }

    private(set) var phase: Phase = .empty
    private var selectedIndex = 0

    func compositionEdited(hasComposition: Bool) {
        phase = hasComposition ? .composing : .empty
        selectedIndex = 0
    }

    func clear() {
        compositionEdited(hasComposition: false)
    }

    func selectedCandidateIndex(candidateCount: Int) -> Int? {
        guard phase == .selectingCandidate, candidateCount > 0 else { return nil }
        return selectedIndex % candidateCount
    }

    func space(candidateCount: Int) -> Action {
        switch phase {
        case .empty:
            return .insertSpace
        case .composing:
            guard candidateCount > 0 else { return .commitComposition }
            phase = .selectingCandidate
            selectedIndex = 0
            return .selectCandidate(0)
        case .selectingCandidate:
            guard candidateCount > 0 else { return .commitComposition }
            selectedIndex = (selectedIndex + 1) % candidateCount
            return .selectCandidate(selectedIndex)
        }
    }

    func enter(candidateCount: Int) -> Action {
        switch phase {
        case .empty:
            return .sendEditorAction
        case .composing:
            return candidateCount > 0 ? .commitCandidate(0) : .commitComposition
        case .selectingCandidate:
            guard let index = selectedCandidateIndex(candidateCount: candidateCount) else {
                return .commitComposition
            }
            return .commitCandidate(index)
        }
    }
}
