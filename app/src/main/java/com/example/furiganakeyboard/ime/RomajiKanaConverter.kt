package com.example.furiganakeyboard.ime

/** Incremental, offline romaji-to-hiragana conversion for the Japanese QWERTY panel. */
object RomajiKanaConverter {
    data class Result(val kana: String, val pending: String) {
        val displayText: String get() = kana + pending
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
    private val keysByLength = syllables.keys.sortedByDescending(String::length)

    fun convert(rawInput: String): Result {
        val input = rawInput.lowercase()
        val kana = StringBuilder()
        var index = 0
        while (index < input.length) {
            val current = input[index]

            if (index + 1 < input.length &&
                current == input[index + 1] &&
                current != 'n' && current.isConsonant()
            ) {
                kana.append('っ')
                index++
                continue
            }

            val matched = keysByLength.firstOrNull { key -> input.startsWith(key, index) }
            if (matched != null) {
                kana.append(syllables.getValue(matched))
                index += matched.length
                continue
            }

            if (current == 'n') {
                if (index + 1 == input.length) {
                    kana.append('ん')
                    index++
                    continue
                }
                val next = input[index + 1]
                if (next == '\'') {
                    kana.append('ん')
                    index += 2
                    continue
                }
                if (next == 'n') {
                    kana.append('ん')
                    index += if (index + 2 == input.length) 2 else 1
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

    private fun Char.isConsonant(): Boolean = this in 'a'..'z' && this !in "aiueo"
}
