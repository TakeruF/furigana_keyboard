import Foundation

/// Identifies the exact composition slice used by an asynchronous conversion request.
struct RomajiCursorRequest: Equatable {
    let reading: String
    let compositionRevision: Int
    let cursorRevision: Int
}

/// The resolved reading before the cursor and the suffix that conversion must not replace.
struct RomajiConversionSlice: Equatable {
    let request: RomajiCursorRequest
    let resolvedSuffix: String
    let unresolvedSuffix: String
    let unresolvedRaw: String
    let isWholeComposition: Bool
}

/// Adapter-only information for an unresolved romaji edit at an arbitrary scalar range.
struct RomajiPendingEdit: Equatable {
    let range: CompositionScalarRange
    let display: String
    let raw: String
}

/// Marked-text adapter around one authoritative `CompositionCursorState`.
///
/// This adapter owns no text, cursor, or revision counters. It only carries the raw romaji that
/// belongs to the state's explicit pending display range, and answers the UTF-16 selection that
/// `setMarkedText(_:selectedRange:)` needs. Unlike Android there is no selection arbitration:
/// the proxy places the caret inside the marked text exactly where it is told to.
final class RomajiCompositionCursor {
    let compositionState: CompositionCursorState
    private var pendingRaw = ""

    init(compositionState: CompositionCursorState = .create()) {
        self.compositionState = compositionState
    }

    var compositionRevision: Int { compositionState.compositionRevision }
    var cursorRevision: Int { compositionState.cursorRevision }
    var isActive: Bool { !compositionState.text.isEmpty }
    var displayText: String { compositionState.text }
    var cursorUTF16: Int { compositionState.cursorUTF16 }
    var canRequestDictionary: Bool { isActive && compositionState.canRequestDictionary }

    /// Where the caret goes inside the marked text.
    var markedTextSelectedRange: NSRange { NSRange(location: cursorUTF16, length: 0) }

    var pendingEdit: RomajiPendingEdit? {
        guard let range = compositionState.pendingRange,
              let display = compositionState.pendingDisplay else { return nil }
        return RomajiPendingEdit(range: range, display: display, raw: pendingRaw)
    }

    /// Input for a pending suffix. Interior edits go through `replacePendingRomaji` instead.
    func replace(display: String, resolvedPrefix: String, pendingRaw: String = "") {
        let result = compositionState.replaceComposition(
            displayText: display,
            resolvedPrefix: resolvedPrefix
        )
        precondition(result.isApplied, "Invalid romaji composition: \(result)")
        self.pendingRaw = compositionState.hasPendingInput ? pendingRaw : ""
    }

    /// Ends the lifecycle while retaining the same authoritative state and its revisions.
    func clear() {
        compositionState.clear()
        pendingRaw = ""
    }

    /// Inserts or refreshes pending romaji at the current cursor or range without rebuilding
    /// suffixes. Raw changes advance the composition revision even when the display is unchanged.
    @discardableResult
    func replacePendingRomaji(
        display: String,
        raw: String,
        expected: CompositionCursorRevision? = nil
    ) -> CompositionCursorMutation {
        let result = compositionState.replacePendingDisplay(
            display,
            logicalInputChanged: raw != pendingRaw,
            expected: expected
        )
        if result.isApplied { pendingRaw = compositionState.hasPendingInput ? raw : "" }
        return result
    }

    /// Replaces only the pending range and keeps its left and right resolved text exact.
    @discardableResult
    func resolvePendingRomaji(
        _ replacement: String,
        expected: CompositionCursorRevision? = nil
    ) -> CompositionCursorMutation {
        let result = compositionState.resolvePendingDisplay(replacement, expected: expected)
        if result.isApplied { pendingRaw = "" }
        return result
    }

    @discardableResult
    func insertResolvedAtCursor(
        _ value: String,
        expected: CompositionCursorRevision? = nil
    ) -> CompositionCursorMutation {
        compositionState.insertAtCursor(value, expected: expected)
    }

    @discardableResult
    func deleteGraphemeBeforeCursor(
        expected: CompositionCursorRevision? = nil
    ) -> CompositionCursorMutation {
        compositionState.deleteGraphemeBeforeCursor(expected: expected)
    }

    /// User-facing Space movement; `deltaInGraphemes` is never a scalar or UTF-16 delta.
    func moveCursorByGrapheme(_ deltaInGraphemes: Int) -> Bool {
        guard isActive, deltaInGraphemes != 0 else { return false }
        return compositionState.moveCursorByGrapheme(deltaInGraphemes).cursorChanged
    }

    /// No dictionary request is produced while any raw or pending romaji range exists.
    func conversionSlice() -> RomajiConversionSlice? {
        guard canRequestDictionary else { return nil }
        let slice = compositionState.slice()
        guard !slice.prefix.isEmpty else { return nil }
        return RomajiConversionSlice(
            request: RomajiCursorRequest(
                reading: slice.prefix,
                compositionRevision: compositionRevision,
                cursorRevision: cursorRevision
            ),
            resolvedSuffix: slice.suffix,
            unresolvedSuffix: "",
            unresolvedRaw: "",
            isWholeComposition: slice.suffix.isEmpty
        )
    }

    func isCurrent(_ request: RomajiCursorRequest) -> Bool {
        canRequestDictionary &&
            request.compositionRevision == compositionRevision &&
            request.cursorRevision == cursorRevision &&
            request.reading == compositionState.slice().prefix
    }
}
