package com.example.furiganakeyboard.ime

/** Incremental, offline romaji-to-hiragana conversion for the Japanese QWERTY panel. */
object RomajiKanaConverter {
    data class Result(
        val kana: String,
        val pending: String,
        val hasAmbiguousTerminalN: Boolean = false
    ) {
        val displayText: String get() = kana + pending
        val hasUnresolvedInput: Boolean get() = pending.isNotEmpty() || hasAmbiguousTerminalN
    }

    private val syllables = mapOf(
        "a" to "あ", "i" to "い", "u" to "う", "e" to "え", "o" to "お",
        "ka" to "か", "ki" to "き", "ku" to "く", "ke" to "け", "ko" to "こ",
        "ga" to "が", "gi" to "ぎ", "gu" to "ぐ", "ge" to "げ", "go" to "ご",
        "sa" to "さ", "shi" to "し", "si" to "し", "su" to "す", "se" to "せ", "so" to "そ",
        "za" to "ざ", "ji" to "じ", "zi" to "じ", "zu" to "ず", "ze" to "ぜ", "zo" to "ぞ",
        "ta" to "た", "chi" to "ち", "ti" to "ち", "tsu" to "つ", "tu" to "つ", "te" to "て", "to" to "と",
        "da" to "だ", "di" to "ぢ", "du" to "づ", "de" to "で", "do" to "ど",
        "na" to "な", "ni" to "に", "nu" to "ぬ", "ne" to "ね", "no" to "の",
        "ha" to "は", "hi" to "ひ", "fu" to "ふ", "hu" to "ふ", "he" to "へ", "ho" to "ほ",
        "ba" to "ば", "bi" to "び", "bu" to "ぶ", "be" to "べ", "bo" to "ぼ",
        "pa" to "ぱ", "pi" to "ぴ", "pu" to "ぷ", "pe" to "ぺ", "po" to "ぽ",
        "ma" to "ま", "mi" to "み", "mu" to "む", "me" to "め", "mo" to "も",
        "ya" to "や", "yu" to "ゆ", "yo" to "よ",
        "ra" to "ら", "ri" to "り", "ru" to "る", "re" to "れ", "ro" to "ろ",
        "wa" to "わ", "wi" to "うぃ", "we" to "うぇ", "wo" to "を",
        "kya" to "きゃ", "kyu" to "きゅ", "kyo" to "きょ",
        "gya" to "ぎゃ", "gyu" to "ぎゅ", "gyo" to "ぎょ",
        "sha" to "しゃ", "shu" to "しゅ", "sho" to "しょ",
        "sya" to "しゃ", "syu" to "しゅ", "syo" to "しょ",
        "ja" to "じゃ", "ju" to "じゅ", "jo" to "じょ",
        "jya" to "じゃ", "jyu" to "じゅ", "jyo" to "じょ",
        "cha" to "ちゃ", "chu" to "ちゅ", "cho" to "ちょ",
        "cya" to "ちゃ", "cyu" to "ちゅ", "cyo" to "ちょ",
        "tya" to "ちゃ", "tyu" to "ちゅ", "tyo" to "ちょ",
        "tcha" to "っちゃ", "tchu" to "っちゅ", "tcho" to "っちょ",
        "nya" to "にゃ", "nyu" to "にゅ", "nyo" to "にょ",
        "hya" to "ひゃ", "hyu" to "ひゅ", "hyo" to "ひょ",
        "bya" to "びゃ", "byu" to "びゅ", "byo" to "びょ",
        "pya" to "ぴゃ", "pyu" to "ぴゅ", "pyo" to "ぴょ",
        "mya" to "みゃ", "myu" to "みゅ", "myo" to "みょ",
        "rya" to "りゃ", "ryu" to "りゅ", "ryo" to "りょ",
        "fa" to "ふぁ", "fi" to "ふぃ", "fe" to "ふぇ", "fo" to "ふぉ",
        "va" to "ゔぁ", "vi" to "ゔぃ", "vu" to "ゔ", "ve" to "ゔぇ", "vo" to "ゔぉ",
        "she" to "しぇ", "je" to "じぇ", "che" to "ちぇ",
        "tsa" to "つぁ", "tsi" to "つぃ", "tse" to "つぇ", "tso" to "つぉ",
        "dya" to "ぢゃ", "dyu" to "ぢゅ", "dyo" to "ぢょ",
        "xya" to "ゃ", "xyu" to "ゅ", "xyo" to "ょ",
        "lya" to "ゃ", "lyu" to "ゅ", "lyo" to "ょ",
        "xa" to "ぁ", "xi" to "ぃ", "xu" to "ぅ", "xe" to "ぇ", "xo" to "ぉ",
        "la" to "ぁ", "li" to "ぃ", "lu" to "ぅ", "le" to "ぇ", "lo" to "ぉ",
        "xtsu" to "っ", "ltsu" to "っ", "xtu" to "っ", "ltu" to "っ"
    )
    private class TrieNode {
        val children = HashMap<Char, TrieNode>()
        var kana: String? = null
    }

    private val syllableTrie = TrieNode().also { root ->
        syllables.forEach { (romaji, kana) ->
            var node = root
            romaji.forEach { character ->
                node = node.children.getOrPut(character, ::TrieNode)
            }
            node.kana = kana
        }
    }

    /** Short canonical spelling for rebuilding a visible kana prefix on delete. */
    private val canonicalRomajiByKana: Map<String, String> = buildMap {
        syllables.forEach { (romaji, kana) ->
            val current = get(kana)
            if (current == null || romaji.length < current.length) put(kana, romaji)
        }
        put("ん", "nn")
        put(JAPANESE_LONG_VOWEL_MARK.toString(), JAPANESE_LONG_VOWEL_MARK.toString())
    }

    fun convert(rawInput: String): Result {
        val input = rawInput.lowercase()
        val kana = StringBuilder()
        var index = 0
        while (index < input.length) {
            val current = input[index]

            if (current == JAPANESE_LONG_VOWEL_MARK) {
                kana.append(current)
                index++
                continue
            }

            if (index + 1 < input.length &&
                current == input[index + 1] &&
                current != 'n' && current.isConsonant()
            ) {
                kana.append('っ')
                index++
                continue
            }

            val matched = longestMatch(input, index)
            if (matched != null) {
                kana.append(matched.first)
                index += matched.second
                continue
            }

            if (current == 'n') {
                val runEnd = input.indexOfFirstFrom(index) { it != 'n' }
                val runLength = runEnd - index
                if (runEnd == input.length) {
                    repeat((runLength + 1) / 2) { kana.append('ん') }
                    return Result(
                        kana.toString(),
                        "",
                        hasAmbiguousTerminalN = runLength % 2 != 0
                    )
                }
                val next = input[index + 1]
                if (next == '\'') {
                    kana.append('ん')
                    index += 2
                    continue
                }
                if (runLength > 1) {
                    val afterRun = input[runEnd]
                    if (afterRun.isVowel() || afterRun == 'y') {
                        repeat(runLength / 2) { kana.append('ん') }
                        index = runEnd - 1
                    } else {
                        repeat((runLength + 1) / 2) { kana.append('ん') }
                        index = runEnd
                    }
                    continue
                }
                if (next.isConsonant() && next != 'y') {
                    kana.append('ん')
                    index++
                    continue
                }
            }

            val pending = input.substring(index)
            return Result(kana.toString(), pending)
        }
        return Result(kana.toString(), "")
    }

    private fun String.indexOfFirstFrom(startIndex: Int, predicate: (Char) -> Boolean): Int {
        for (index in startIndex until length) {
            if (predicate(this[index])) return index
        }
        return length
    }

    private fun Char.isVowel(): Boolean = this == 'a' || this == 'i' || this == 'u' ||
        this == 'e' || this == 'o'

    /** Full-width katakana fallback for a completed hiragana composition. */
    fun toKatakana(hiragana: String): String = buildString(hiragana.length) {
        hiragana.forEach { character ->
            append(
                if (character in '\u3041'..'\u3096') character + HIRAGANA_KATAKANA_OFFSET
                else character
            )
        }
    }

    /**
     * Removes one visible input unit. Completed kana are removed as a whole
     * syllable ("sa" -> ""), while an incomplete romaji suffix still steps
     * back one key at a time ("sh" -> "s").
     */
    fun deleteLastUnit(rawInput: String): String {
        if (rawInput.isEmpty()) return rawInput
        val converted = convert(rawInput)
        if (converted.pending.isNotEmpty() || converted.kana.isEmpty()) {
            return rawInput.dropLast(1)
        }

        val targetKana = converted.kana.dropLastCodePoint()
        for (prefixLength in rawInput.lastIndex downTo 0) {
            val prefix = rawInput.substring(0, prefixLength)
            if (convert(prefix).displayText == targetKana) return prefix
        }

        // A multi-kana syllable can have no raw prefix for its visible base:
        // "kyu" -> "きゅ", but "ky" is not "き". Rebuild the remaining
        // visible kana with canonical spellings ("き" -> "ki"). This also
        // covers the internal small-tsu case ("tta" -> "っ").
        canonicalRawForKana(targetKana)?.let { return it }
        return rawInput.dropLast(1)
    }

    private fun canonicalRawForKana(kana: String): String? {
        if (kana.isEmpty()) return ""
        val memo = HashMap<Int, String?>()

        fun buildFrom(index: Int): String? {
            if (index == kana.length) return ""
            if (memo.containsKey(index)) return memo[index]
            val best = canonicalRomajiByKana.entries
                .asSequence()
                .filter { (visible, _) -> kana.startsWith(visible, index) }
                .mapNotNull { (visible, romaji) ->
                    buildFrom(index + visible.length)?.let { suffix -> romaji + suffix }
                }
                .minWithOrNull(compareBy<String> { it.length }.thenBy { it })
            memo[index] = best
            return best
        }

        return buildFrom(0)?.takeIf { convert(it).displayText == kana }
    }

    /** Longest-prefix lookup is bounded by the longest supported syllable. */
    private fun longestMatch(input: String, start: Int): Pair<String, Int>? {
        var node = syllableTrie
        var index = start
        var bestKana: String? = null
        var bestLength = 0
        while (index < input.length) {
            node = node.children[input[index]] ?: break
            index++
            node.kana?.let {
                bestKana = it
                bestLength = index - start
            }
        }
        return bestKana?.let { it to bestLength }
    }

    private fun Char.isConsonant(): Boolean = this in 'a'..'z' && this !in "aiueo"

    private fun String.dropLastCodePoint(): String {
        if (isEmpty()) return this
        return substring(0, offsetByCodePoints(length, -1))
    }

    private const val JAPANESE_LONG_VOWEL_MARK = 'ー'
    private const val HIRAGANA_KATAKANA_OFFSET = 0x60
}
