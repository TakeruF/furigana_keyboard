import Foundation
#if REPOSITORY_TEST_HOST
@testable import FuriganaKeyboard
#endif

/// Pure romaji editing policy around an authoritative `RomajiCompositionCursor`.
///
/// The keyboard controller owns rendering and candidate refresh. This type owns only the
/// transformation from incremental raw keys to a pending middle range or resolved kana, so
/// text right of the cursor can never be rebuilt from an end-only buffer.
struct RomajiCompositionEditor {
    private let cursor: RomajiCompositionCursor

    init(cursor: RomajiCompositionCursor) {
        self.cursor = cursor
    }

    func append(_ rawUnit: String) -> CompositionCursorMutation {
        precondition(!rawUnit.isEmpty, "rawUnit must not be empty")
        return replaceRaw((cursor.pendingEdit?.raw ?? "") + rawUnit)
    }

    func deleteBeforeCursor() -> CompositionCursorMutation {
        guard let pending = cursor.pendingEdit else { return cursor.deleteGraphemeBeforeCursor() }
        return replaceRaw(RomajiKanaConverter.deleteLastUnit(pending.raw))
    }

    private func replaceRaw(_ raw: String) -> CompositionCursorMutation {
        let hadPending = cursor.pendingEdit != nil
        guard !raw.isEmpty else {
            return hadPending
                ? cursor.resolvePendingRomaji("")
                : cursor.deleteGraphemeBeforeCursor()
        }
        let converted = RomajiKanaConverter.convert(raw)
        if converted.hasUnresolvedInput {
            return cursor.replacePendingRomaji(display: converted.displayText, raw: raw)
        }
        return hadPending
            ? cursor.resolvePendingRomaji(converted.kana)
            : cursor.insertResolvedAtCursor(converted.kana)
    }
}
