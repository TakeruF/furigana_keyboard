package com.example.furiganakeyboard.conversion

/** Cross-platform integer scoring defined by docs/conversion-scoring.md. */
object ConversionCost {
    private const val UNKNOWN_KANJI_FREQUENCY = 3_000
    private const val KANJI_FREQUENCY_SCALE = 100
    private const val KANA_CONTENT_WORD_PENALTY = 500
    private val CONTENT_WORD_POS_IDS = setOf(3, 4, 7, 8, 9)

    fun adjustedWordCost(
        reading: String,
        surface: String,
        leftId: Int,
        rawWordCost: Int,
        frequencyByHanLiteral: Map<String, Int>,
    ): Int {
        val kanaContentPenalty = if (
            ConversionText.scalarEquals(surface, reading) && leftId in CONTENT_WORD_POS_IDS
        ) KANA_CONTENT_WORD_PENALTY else 0
        val hanLiterals = hanLiterals(surface).toList()
        val frequencyPenalty = if (hanLiterals.isEmpty()) {
            0L
        } else {
            hanLiterals.sumOf { literal ->
                val frequency = frequencyByHanLiteral[literal]
                    ?.takeIf { it > 0 }
                    ?: UNKNOWN_KANJI_FREQUENCY
                (minOf(frequency, UNKNOWN_KANJI_FREQUENCY) / KANJI_FREQUENCY_SCALE).toLong()
            } / hanLiterals.size
        }
        return clampInt32(
            rawWordCost.toLong() + kanaContentPenalty + frequencyPenalty -
                ConversionText.scalarCount(surface)
        )
    }

    fun hanLiterals(value: String): Sequence<String> = sequence {
        var offset = 0
        while (offset < value.length) {
            val codePoint = value.codePointAt(offset)
            if (isHan(codePoint)) yield(String(Character.toChars(codePoint)))
            offset += Character.charCount(codePoint)
        }
    }

    fun clampInt32(value: Long): Int =
        value.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

    private fun isHan(value: Int): Boolean = when (value) {
        in 0x2E80..0x2EFF,
        in 0x2F00..0x2FDF,
        0x3005,
        0x3007,
        in 0x3021..0x3029,
        in 0x3038..0x303B,
        in 0x3400..0x4DBF,
        in 0x4E00..0x9FFF,
        in 0xF900..0xFAFF,
        in 0x16FE2..0x16FE3,
        in 0x16FF0..0x16FF1,
        in 0x20000..0x2EE5F,
        in 0x2F800..0x2FA1F,
        in 0x30000..0x323AF -> true
        else -> false
    }
}

/** Unicode-scalar operations whose behavior must match Swift exactly. */
object ConversionText {
    fun scalarCount(value: String): Int = value.codePointCount(0, value.length)

    fun scalarEquals(left: String, right: String): Boolean = left == right

    fun compareScalars(left: String, right: String): Int {
        var leftOffset = 0
        var rightOffset = 0
        while (leftOffset < left.length && rightOffset < right.length) {
            val leftScalar = left.codePointAt(leftOffset)
            val rightScalar = right.codePointAt(rightOffset)
            if (leftScalar != rightScalar) return leftScalar.compareTo(rightScalar)
            leftOffset += Character.charCount(leftScalar)
            rightOffset += Character.charCount(rightScalar)
        }
        return (left.length - leftOffset).compareTo(right.length - rightOffset)
    }

    /** UTF-16 string indices for each Unicode-scalar boundary. */
    fun utf16Boundaries(value: String): IntArray {
        val result = IntArray(scalarCount(value) + 1)
        var offset = 0
        var index = 0
        while (offset < value.length) {
            result[index++] = offset
            offset += Character.charCount(value.codePointAt(offset))
        }
        result[index] = value.length
        return result
    }

    fun scalarSubstring(value: String, start: Int, end: Int): String {
        val boundaries = utf16Boundaries(value)
        require(start in 0..end && end < boundaries.size)
        return value.substring(boundaries[start], boundaries[end])
    }
}
