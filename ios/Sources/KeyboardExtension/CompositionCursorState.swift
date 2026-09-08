import Foundation

/// Pair checked by asynchronous work and externally initiated state transitions.
struct CompositionCursorRevision: Equatable {
    let composition: Int
    let cursor: Int
}

/// Half-open Unicode-scalar range.
struct CompositionScalarRange: Equatable {
    let start: Int
    let end: Int

    init(start: Int, end: Int) {
        precondition(start >= 0 && end >= start, "Invalid scalar range [\(start), \(end))")
        self.start = start
        self.end = end
    }
}

/// Immutable view of the current pure composition state.
struct CompositionCursorSnapshot: Equatable {
    let text: String
    let cursorScalar: Int
    let cursorUTF16: Int
    /// Start of pending input, or the text end when everything is resolved.
    let resolvedScalarEnd: Int
    let resolvedUTF16End: Int
    let pendingRange: CompositionScalarRange?
    let revision: CompositionCursorRevision
}

/// Text around the cursor. `suffix` is always preserved by prefix replacement.
struct CompositionCursorSlice: Equatable {
    let prefix: String
    let suffix: String
    let resolvedSuffix: String
    /// Pending display text, which may sit in the middle rather than at the text end.
    let unresolvedSuffix: String
    let revision: CompositionCursorRevision
}

enum CompositionCursorRejection: Equatable {
    case staleRevision
    case scalarOutOfBounds
    case invalidUTF16Boundary
    case invalidGraphemeBoundary
    case reversedRange
    case unresolvedBoundary
    case pendingInputActive
    case pendingRangeMismatch
    case resolvedPrefixMismatch
}

enum CompositionCursorMutation: Equatable {
    case applied(CompositionCursorSnapshot, compositionChanged: Bool, cursorChanged: Bool)
    case rejected(CompositionCursorSnapshot, reason: CompositionCursorRejection)

    var snapshot: CompositionCursorSnapshot {
        switch self {
        case let .applied(snapshot, _, _): snapshot
        case let .rejected(snapshot, _): snapshot
        }
    }

    var isApplied: Bool {
        if case .applied = self { return true }
        return false
    }

    var compositionChanged: Bool {
        if case let .applied(_, changed, _) = self { return changed }
        return false
    }

    var cursorChanged: Bool {
        if case let .applied(_, _, changed) = self { return changed }
        return false
    }
}

/// Proxy-independent source of truth for composition text, cursor, and revisions.
///
/// All owned positions and editable ranges are Unicode-scalar offsets; UTF-16 offsets, which
/// `setMarkedText(_:selectedRange:)` needs, are produced only through explicit conversion.
/// Pending romaji is a half-open range rather than an assumed suffix, so resolved text can
/// exist on both sides of it without being rebuilt and duplicated.
///
/// Grapheme boundaries come from Swift's own `Character` segmentation, which is the
/// platform's extended-grapheme-cluster implementation.
final class CompositionCursorState {
    private(set) var text: String
    private(set) var cursorScalar: Int
    private(set) var pendingRange: CompositionScalarRange?
    private(set) var compositionRevision = 0
    private(set) var cursorRevision = 0

    private init(text: String, pendingRange: CompositionScalarRange?, cursorScalar: Int) {
        self.text = text
        self.pendingRange = pendingRange
        self.cursorScalar = cursorScalar
    }

    static func create(
        displayText: String = "",
        resolvedPrefix: String? = nil,
        cursorScalar: Int? = nil
    ) -> CompositionCursorState {
        let resolved = resolvedPrefix ?? displayText
        precondition(displayText.hasPrefix(resolved), "resolvedPrefix must be a displayText prefix")
        let resolvedEnd = Self.scalarCount(resolved)
        let length = Self.scalarCount(displayText)
        let pending = resolvedEnd == length
            ? nil
            : CompositionScalarRange(start: resolvedEnd, end: length)
        let initialCursor = cursorScalar ?? resolvedEnd
        precondition(
            initialCursor >= 0 && initialCursor <= resolvedEnd,
            "cursor must be inside the resolved prefix"
        )
        return CompositionCursorState(text: displayText, pendingRange: pending, cursorScalar: initialCursor)
    }

    var revision: CompositionCursorRevision {
        CompositionCursorRevision(composition: compositionRevision, cursor: cursorRevision)
    }

    var cursorUTF16: Int { utf16Offset(forScalar: cursorScalar) }

    /// Start of pending input, or the text end when everything is resolved.
    var resolvedScalarEnd: Int { pendingRange?.start ?? scalarLength }

    var resolvedUTF16End: Int { utf16Offset(forScalar: resolvedScalarEnd) }

    var scalarLength: Int { Self.scalarCount(text) }

    var hasPendingInput: Bool { pendingRange != nil }

    var canRequestDictionary: Bool { pendingRange == nil }

    var pendingDisplay: String? {
        pendingRange.map { scalarSubstring(from: $0.start, to: $0.end) }
    }

    func snapshot() -> CompositionCursorSnapshot {
        CompositionCursorSnapshot(
            text: text,
            cursorScalar: cursorScalar,
            cursorUTF16: cursorUTF16,
            resolvedScalarEnd: resolvedScalarEnd,
            resolvedUTF16End: resolvedUTF16End,
            pendingRange: pendingRange,
            revision: revision
        )
    }

    func slice() -> CompositionCursorSlice {
        let cursor = cursorScalar
        let pending = pendingRange
        let resolvedSuffix: String
        if let pending {
            if cursor <= pending.start {
                resolvedSuffix = scalarSubstring(from: cursor, to: pending.start)
            } else if cursor >= pending.end {
                resolvedSuffix = scalarSubstring(from: cursor, to: scalarLength)
            } else {
                resolvedSuffix = ""
            }
        } else {
            resolvedSuffix = scalarSubstring(from: cursor, to: scalarLength)
        }
        return CompositionCursorSlice(
            prefix: scalarSubstring(from: 0, to: cursor),
            suffix: scalarSubstring(from: cursor, to: scalarLength),
            resolvedSuffix: resolvedSuffix,
            unresolvedSuffix: pending.map { scalarSubstring(from: $0.start, to: $0.end) } ?? "",
            revision: revision
        )
    }

    /// Returns nil for out-of-range scalar offsets.
    func scalarToUTF16(_ scalarOffset: Int) -> Int? {
        guard scalarOffset >= 0, scalarOffset <= scalarLength else { return nil }
        return utf16Offset(forScalar: scalarOffset)
    }

    /// Returns nil for out-of-range offsets and offsets that split a surrogate pair.
    func utf16ToScalar(_ utf16Offset: Int) -> Int? {
        guard utf16Offset >= 0, utf16Offset <= text.utf16.count else { return nil }
        let utf16Index = text.utf16.index(text.utf16.startIndex, offsetBy: utf16Offset)
        guard let scalarIndex = utf16Index.samePosition(in: text.unicodeScalars) else { return nil }
        return text.unicodeScalars.distance(from: text.unicodeScalars.startIndex, to: scalarIndex)
    }

    func isGraphemeBoundary(_ scalarOffset: Int) -> Bool {
        graphemeScalarOffsets().contains(scalarOffset)
    }

    func isCurrent(_ expected: CompositionCursorRevision) -> Bool { expected == revision }

    // MARK: - Cursor movement

    /// Low-level scalar movement for conversion algorithms.
    @discardableResult
    func moveCursorToScalar(
        _ targetScalar: Int,
        expected: CompositionCursorRevision? = nil
    ) -> CompositionCursorMutation {
        if let stale = rejectIfStale(expected) { return stale }
        guard targetScalar >= 0, targetScalar <= scalarLength else {
            return rejected(.scalarOutOfBounds)
        }
        if pendingRange != nil, targetScalar != cursorScalar {
            return rejected(.pendingInputActive)
        }
        return applyCursor(targetScalar)
    }

    /// User-facing movement. It clamps at the text ends and never enters pending input.
    @discardableResult
    func moveCursorByGrapheme(
        _ delta: Int,
        expected: CompositionCursorRevision? = nil
    ) -> CompositionCursorMutation {
        if let stale = rejectIfStale(expected) { return stale }
        guard delta != 0 else { return appliedUnchanged() }
        guard pendingRange == nil else { return rejected(.pendingInputActive) }
        let boundaries = graphemeScalarOffsets()
        guard let currentIndex = boundaries.firstIndex(of: cursorScalar) else {
            return rejected(.invalidGraphemeBoundary)
        }
        let targetIndex = min(max(currentIndex + delta, 0), boundaries.count - 1)
        return applyCursor(boundaries[targetIndex])
    }

    // MARK: - Editing

    /// Inserts fully resolved text immediately before the cursor.
    @discardableResult
    func insertAtCursor(
        _ value: String,
        expected: CompositionCursorRevision? = nil
    ) -> CompositionCursorMutation {
        replaceScalarRange(from: cursorScalar, to: cursorScalar, with: value, expected: expected)
    }

    /// Deletes one complete user-perceived grapheme before the cursor.
    @discardableResult
    func deleteGraphemeBeforeCursor(
        expected: CompositionCursorRevision? = nil
    ) -> CompositionCursorMutation {
        if let stale = rejectIfStale(expected) { return stale }
        guard pendingRange == nil else { return rejected(.pendingInputActive) }
        guard cursorScalar > 0 else { return appliedUnchanged() }
        let boundaries = graphemeScalarOffsets()
        guard let cursorIndex = boundaries.firstIndex(of: cursorScalar), cursorIndex > 0 else {
            return rejected(.invalidGraphemeBoundary)
        }
        return replaceScalarRange(
            from: boundaries[cursorIndex - 1],
            to: cursorScalar,
            with: "",
            expected: expected
        )
    }

    /// Replaces `[composition start, cursor)` and preserves the exact right suffix.
    @discardableResult
    func replacePrefixAtCursor(
        _ replacement: String,
        expected: CompositionCursorRevision? = nil
    ) -> CompositionCursorMutation {
        replaceScalarRange(from: 0, to: cursorScalar, with: replacement, expected: expected)
    }

    /// Resolved range replacement. A range intersecting pending display is rejected.
    @discardableResult
    func replaceScalarRange(
        from startScalar: Int,
        to endScalar: Int,
        with replacement: String,
        expected: CompositionCursorRevision? = nil
    ) -> CompositionCursorMutation {
        if let stale = rejectIfStale(expected) { return stale }
        guard startScalar <= endScalar else { return rejected(.reversedRange) }
        guard startScalar >= 0, endScalar <= scalarLength else {
            return rejected(.scalarOutOfBounds)
        }
        if let pending = pendingRange, intersectsPending(startScalar, endScalar, pending) {
            return rejected(.unresolvedBoundary)
        }
        let replacementScalars = Self.scalarCount(replacement)
        let nextPending = pendingRange.map { range -> CompositionScalarRange in
            guard endScalar <= range.start else { return range }
            let delta = replacementScalars - (endScalar - startScalar)
            return CompositionScalarRange(start: range.start + delta, end: range.end + delta)
        }
        return replaceRange(
            from: startScalar,
            to: endScalar,
            with: replacement,
            nextPendingRange: nextPending,
            nextCursorScalar: startScalar + replacementScalars
        )
    }

    // MARK: - Pending romaji

    /// Inserts or updates pending display at its explicit range while preserving both sides.
    /// `logicalInputChanged` lets the adapter advance the composition revision when the raw
    /// romaji changed but rendered to the same display text.
    @discardableResult
    func replacePendingDisplay(
        _ display: String,
        logicalInputChanged: Bool = false,
        expected: CompositionCursorRevision? = nil
    ) -> CompositionCursorMutation {
        if let stale = rejectIfStale(expected) { return stale }
        let start = pendingRange?.start ?? cursorScalar
        let end = pendingRange?.end ?? cursorScalar
        let displayScalars = Self.scalarCount(display)
        let nextPending = display.isEmpty
            ? nil
            : CompositionScalarRange(start: start, end: start + displayScalars)
        return replaceRange(
            from: start,
            to: end,
            with: display,
            nextPendingRange: nextPending,
            nextCursorScalar: start + displayScalars,
            forceCompositionChanged: logicalInputChanged
        )
    }

    /// Resolves the pending range and preserves its exact left and right text.
    @discardableResult
    func resolvePendingDisplay(
        _ replacement: String,
        expected: CompositionCursorRevision? = nil
    ) -> CompositionCursorMutation {
        if let stale = rejectIfStale(expected) { return stale }
        guard let pending = pendingRange else { return rejected(.pendingRangeMismatch) }
        let replacementScalars = Self.scalarCount(replacement)
        return replaceRange(
            from: pending.start,
            to: pending.end,
            with: replacement,
            nextPendingRange: nil,
            nextCursorScalar: pending.start + replacementScalars
        )
    }

    // MARK: - Whole-composition replacement

    /// Describes an unresolved suffix through a resolved prefix.
    @discardableResult
    func replaceComposition(
        displayText: String,
        resolvedPrefix: String,
        cursorScalar: Int? = nil,
        expected: CompositionCursorRevision? = nil
    ) -> CompositionCursorMutation {
        if let stale = rejectIfStale(expected) { return stale }
        guard displayText.hasPrefix(resolvedPrefix) else { return rejected(.resolvedPrefixMismatch) }
        let resolvedEnd = Self.scalarCount(resolvedPrefix)
        let length = Self.scalarCount(displayText)
        let pending = resolvedEnd == length
            ? nil
            : CompositionScalarRange(start: resolvedEnd, end: length)
        return replaceComposition(
            displayText: displayText,
            cursorScalar: cursorScalar ?? resolvedEnd,
            pendingRange: pending,
            expected: expected
        )
    }

    /// General replacement with a pending range at any scalar position.
    @discardableResult
    func replaceComposition(
        displayText: String,
        cursorScalar: Int,
        pendingRange: CompositionScalarRange?,
        expected: CompositionCursorRevision? = nil,
        logicalInputChanged: Bool = false
    ) -> CompositionCursorMutation {
        if let stale = rejectIfStale(expected) { return stale }
        let length = Self.scalarCount(displayText)
        guard cursorScalar >= 0, cursorScalar <= length else { return rejected(.scalarOutOfBounds) }
        if let pendingRange,
           pendingRange.start == pendingRange.end ||
            pendingRange.end > length ||
            (cursorScalar > pendingRange.start && cursorScalar < pendingRange.end) {
            return rejected(.pendingRangeMismatch)
        }
        return applyText(
            displayText,
            nextPendingRange: pendingRange,
            nextCursorScalar: cursorScalar,
            forceCompositionChanged: logicalInputChanged
        )
    }

    @discardableResult
    func clear() -> CompositionCursorMutation {
        replaceComposition(displayText: "", cursorScalar: 0, pendingRange: nil)
    }

    // MARK: - Internals

    private func intersectsPending(_ start: Int, _ end: Int, _ pending: CompositionScalarRange) -> Bool {
        start == end
            ? start > pending.start && start < pending.end
            : start < pending.end && end > pending.start
    }

    private func replaceRange(
        from startScalar: Int,
        to endScalar: Int,
        with replacement: String,
        nextPendingRange: CompositionScalarRange?,
        nextCursorScalar: Int,
        forceCompositionChanged: Bool = false
    ) -> CompositionCursorMutation {
        let nextText = scalarSubstring(from: 0, to: startScalar) +
            replacement +
            scalarSubstring(from: endScalar, to: scalarLength)
        return applyText(
            nextText,
            nextPendingRange: nextPendingRange,
            nextCursorScalar: nextCursorScalar,
            forceCompositionChanged: forceCompositionChanged
        )
    }

    private func rejectIfStale(_ expected: CompositionCursorRevision?) -> CompositionCursorMutation? {
        guard let expected, !isCurrent(expected) else { return nil }
        return rejected(.staleRevision)
    }

    private func applyCursor(_ nextCursorScalar: Int) -> CompositionCursorMutation {
        let changed = nextCursorScalar != cursorScalar
        if changed {
            cursorScalar = nextCursorScalar
            cursorRevision += 1
        }
        return .applied(snapshot(), compositionChanged: false, cursorChanged: changed)
    }

    private func applyText(
        _ nextText: String,
        nextPendingRange: CompositionScalarRange?,
        nextCursorScalar: Int,
        forceCompositionChanged: Bool = false
    ) -> CompositionCursorMutation {
        let compositionChanged = forceCompositionChanged ||
            nextText != text || nextPendingRange != pendingRange
        let cursorChanged = nextCursorScalar != cursorScalar
        text = nextText
        pendingRange = nextPendingRange
        cursorScalar = nextCursorScalar
        cachedGraphemeOffsets = nil
        if compositionChanged { compositionRevision += 1 }
        if cursorChanged { cursorRevision += 1 }
        return .applied(snapshot(), compositionChanged: compositionChanged, cursorChanged: cursorChanged)
    }

    private func appliedUnchanged() -> CompositionCursorMutation {
        .applied(snapshot(), compositionChanged: false, cursorChanged: false)
    }

    private func rejected(_ reason: CompositionCursorRejection) -> CompositionCursorMutation {
        .rejected(snapshot(), reason: reason)
    }

    private var cachedGraphemeOffsets: [Int]?

    private func graphemeScalarOffsets() -> [Int] {
        if let cachedGraphemeOffsets { return cachedGraphemeOffsets }
        var offsets = [0]
        var scalars = 0
        for character in text {
            scalars += character.unicodeScalars.count
            offsets.append(scalars)
        }
        cachedGraphemeOffsets = offsets
        return offsets
    }

    private func scalarIndex(at offset: Int) -> String.Index {
        text.unicodeScalars.index(text.unicodeScalars.startIndex, offsetBy: offset)
    }

    private func scalarSubstring(from start: Int, to end: Int) -> String {
        String(text.unicodeScalars[scalarIndex(at: start)..<scalarIndex(at: end)])
    }

    private func utf16Offset(forScalar scalarOffset: Int) -> Int {
        text.utf16.distance(from: text.utf16.startIndex, to: scalarIndex(at: scalarOffset))
    }

    private static func scalarCount(_ value: String) -> Int { value.unicodeScalars.count }
}
