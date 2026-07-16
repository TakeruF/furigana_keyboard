package com.example.furiganakeyboard.ime

/**
 * Pure romaji editing policy around an authoritative [RomajiCompositionCursor].
 *
 * The service owns Android rendering and candidate refresh. This class owns only the transformation
 * from incremental raw keys to a pending middle range or resolved kana, so right-side composition
 * text can never be rebuilt from an end-only buffer.
 */
internal class RomajiCompositionEditor(
    private val cursor: RomajiCompositionCursor,
) {
    fun append(rawUnit: String): CompositionCursorMutation {
        require(rawUnit.isNotEmpty()) { "rawUnit must not be empty" }
        return replaceRaw(cursor.pendingEdit?.raw.orEmpty() + rawUnit)
    }

    fun deleteBeforeCursor(): CompositionCursorMutation {
        val pending = cursor.pendingEdit
        return if (pending != null) {
            replaceRaw(RomajiKanaConverter.deleteLastUnit(pending.raw))
        } else {
            cursor.deleteGraphemeBeforeCursor()
        }
    }

    private fun replaceRaw(raw: String): CompositionCursorMutation {
        val pendingBefore = cursor.pendingEdit
        if (raw.isEmpty()) {
            return if (pendingBefore == null) {
                cursor.deleteGraphemeBeforeCursor()
            } else {
                cursor.resolvePendingRomaji("")
            }
        }

        val converted = RomajiKanaConverter.convert(raw)
        return when {
            converted.hasUnresolvedInput ->
                cursor.replacePendingRomaji(converted.displayText, raw)
            pendingBefore != null ->
                cursor.resolvePendingRomaji(converted.kana)
            else ->
                cursor.insertResolvedAtCursor(converted.kana)
        }
    }
}
