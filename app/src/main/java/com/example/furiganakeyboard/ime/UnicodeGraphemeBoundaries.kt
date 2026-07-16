package com.example.furiganakeyboard.ime

/**
 * Small Android-independent extended-grapheme boundary helper.
 *
 * It covers combining marks, variation selectors, emoji modifiers, regional-indicator pairs,
 * Hangul syllable composition, and ZWJ sequences. Returned offsets are Unicode-scalar offsets;
 * UTF-16 conversion remains the responsibility of [CompositionCursorState].
 */
internal object UnicodeGraphemeBoundaries {
    fun scalarOffsets(value: String): IntArray {
        if (value.isEmpty()) return intArrayOf(0)

        val codePoints = value.codePoints().toArray()
        val boundaries = ArrayList<Int>(codePoints.size + 1)
        boundaries += 0
        var regionalIndicatorRun = if (isRegionalIndicator(codePoints[0])) 1 else 0
        for (index in 1 until codePoints.size) {
            val previous = codePoints[index - 1]
            val current = codePoints[index]
            val shouldBreak = shouldBreak(previous, current, regionalIndicatorRun)
            if (shouldBreak) boundaries += index
            regionalIndicatorRun = if (isRegionalIndicator(current)) {
                if (isRegionalIndicator(previous) && !shouldBreak) regionalIndicatorRun + 1 else 1
            } else {
                0
            }
        }
        boundaries += codePoints.size
        return boundaries.toIntArray()
    }

    private fun shouldBreak(previous: Int, current: Int, regionalIndicatorRun: Int): Boolean {
        if (previous == CR && current == LF) return false
        if (isControl(previous) || isControl(current)) return true
        if (isHangulContinuation(previous, current)) return false
        if (isExtend(current) || current == ZWJ) return false
        if (previous == ZWJ) return false
        if (isRegionalIndicator(previous) && isRegionalIndicator(current)) {
            return regionalIndicatorRun % 2 == 0
        }
        return true
    }

    private fun isControl(codePoint: Int): Boolean {
        if (codePoint == ZWJ || isExtend(codePoint)) return false
        return when (Character.getType(codePoint)) {
            Character.CONTROL.toInt(),
            Character.FORMAT.toInt(),
            Character.LINE_SEPARATOR.toInt(),
            Character.PARAGRAPH_SEPARATOR.toInt(),
            Character.SURROGATE.toInt(),
            -> true
            else -> false
        }
    }

    private fun isExtend(codePoint: Int): Boolean = when {
        Character.getType(codePoint) in setOf(
            Character.NON_SPACING_MARK.toInt(),
            Character.COMBINING_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt(),
        ) -> true
        codePoint in 0xFE00..0xFE0F -> true
        codePoint in 0xE0100..0xE01EF -> true
        codePoint in 0x1F3FB..0x1F3FF -> true
        codePoint in 0xE0020..0xE007F -> true
        else -> false
    }

    private fun isHangulContinuation(previous: Int, current: Int): Boolean {
        val previousClass = hangulClass(previous)
        val currentClass = hangulClass(current)
        return when (previousClass) {
            HangulClass.L -> currentClass in setOf(
                HangulClass.L,
                HangulClass.V,
                HangulClass.LV,
                HangulClass.LVT,
            )
            HangulClass.LV,
            HangulClass.V,
            -> currentClass == HangulClass.V || currentClass == HangulClass.T
            HangulClass.LVT,
            HangulClass.T,
            -> currentClass == HangulClass.T
            null -> false
        }
    }

    private fun hangulClass(codePoint: Int): HangulClass? = when {
        codePoint in 0x1100..0x115F || codePoint in 0xA960..0xA97C -> HangulClass.L
        codePoint in 0x1160..0x11A7 || codePoint in 0xD7B0..0xD7C6 -> HangulClass.V
        codePoint in 0x11A8..0x11FF || codePoint in 0xD7CB..0xD7FB -> HangulClass.T
        codePoint in 0xAC00..0xD7A3 -> {
            if ((codePoint - 0xAC00) % 28 == 0) HangulClass.LV else HangulClass.LVT
        }
        else -> null
    }

    private fun isRegionalIndicator(codePoint: Int): Boolean = codePoint in 0x1F1E6..0x1F1FF

    private enum class HangulClass { L, V, T, LV, LVT }

    private const val CR = 0x000D
    private const val LF = 0x000A
    private const val ZWJ = 0x200D
}
