package com.example.furiganakeyboard.reading

/**
 * Kana (hiragana AND katakana) -> Hepburn-style romaji converter.
 *
 * Handles: basic syllables, youon (きゃ/シャ/…), sokuon (small っ/ッ doubles the
 * next consonant), moraic ん -> "n", and the katakana long-vowel mark ー
 * (doubles the previous vowel: ショー -> "shoo"). Katakana is normalized to
 * hiragana before conversion, so on-yomi readings work directly.
 *
 * Examples: しゃ -> "sha", シャ -> "sha", うつす -> "utsusu", ショー -> "shoo".
 */
object RomajiConverter {

    // Youon combinations (base kana + small ゃ/ゅ/ょ) must be checked first.
    private val digraphs: Map<String, String> = mapOf(
        "きゃ" to "kya", "きゅ" to "kyu", "きょ" to "kyo",
        "しゃ" to "sha", "しゅ" to "shu", "しょ" to "sho",
        "ちゃ" to "cha", "ちゅ" to "chu", "ちょ" to "cho",
        "にゃ" to "nya", "にゅ" to "nyu", "にょ" to "nyo",
        "ひゃ" to "hya", "ひゅ" to "hyu", "ひょ" to "hyo",
        "みゃ" to "mya", "みゅ" to "myu", "みょ" to "myo",
        "りゃ" to "rya", "りゅ" to "ryu", "りょ" to "ryo",
        "ぎゃ" to "gya", "ぎゅ" to "gyu", "ぎょ" to "gyo",
        "じゃ" to "ja", "じゅ" to "ju", "じょ" to "jo",
        "びゃ" to "bya", "びゅ" to "byu", "びょ" to "byo",
        "ぴゃ" to "pya", "ぴゅ" to "pyu", "ぴょ" to "pyo"
    )

    // Single-kana readings.
    private val monographs: Map<Char, String> = mapOf(
        'あ' to "a", 'い' to "i", 'う' to "u", 'え' to "e", 'お' to "o",
        'か' to "ka", 'き' to "ki", 'く' to "ku", 'け' to "ke", 'こ' to "ko",
        'さ' to "sa", 'し' to "shi", 'す' to "su", 'せ' to "se", 'そ' to "so",
        'た' to "ta", 'ち' to "chi", 'つ' to "tsu", 'て' to "te", 'と' to "to",
        'な' to "na", 'に' to "ni", 'ぬ' to "nu", 'ね' to "ne", 'の' to "no",
        'は' to "ha", 'ひ' to "hi", 'ふ' to "fu", 'へ' to "he", 'ほ' to "ho",
        'ま' to "ma", 'み' to "mi", 'む' to "mu", 'め' to "me", 'も' to "mo",
        'や' to "ya", 'ゆ' to "yu", 'よ' to "yo",
        'ら' to "ra", 'り' to "ri", 'る' to "ru", 'れ' to "re", 'ろ' to "ro",
        'わ' to "wa", 'を' to "o", 'ん' to "n",
        'が' to "ga", 'ぎ' to "gi", 'ぐ' to "gu", 'げ' to "ge", 'ご' to "go",
        'ざ' to "za", 'じ' to "ji", 'ず' to "zu", 'ぜ' to "ze", 'ぞ' to "zo",
        'だ' to "da", 'ぢ' to "ji", 'づ' to "zu", 'で' to "de", 'ど' to "do",
        'ば' to "ba", 'び' to "bi", 'ぶ' to "bu", 'べ' to "be", 'ぼ' to "bo",
        'ぱ' to "pa", 'ぴ' to "pi", 'ぷ' to "pu", 'ぺ' to "pe", 'ぽ' to "po",
        // Small vowels as a fallback (rarely needed standalone).
        'ぁ' to "a", 'ぃ' to "i", 'ぅ' to "u", 'ぇ' to "e", 'ぉ' to "o",
        'ゔ' to "vu"
    )

    /** Convert a kana string (hiragana or katakana) to romaji. */
    fun toRomaji(reading: String): String {
        val kana = toHiragana(reading)
        val sb = StringBuilder()
        var i = 0
        var pendingSokuon = false // set by small っ, doubles next consonant
        while (i < kana.length) {
            // Long-vowel mark (ー): repeat the previous vowel.
            if (kana[i] == 'ー') {
                sb.lastOrNull()?.let { if (it in "aiueo") sb.append(it) }
                i++
                continue
            }
            // Small tsu: mark that the next consonant should be doubled.
            if (kana[i] == 'っ') {
                pendingSokuon = true
                i++
                continue
            }
            // Try a two-character digraph first.
            var syllable: String? = null
            if (i + 1 < kana.length) {
                syllable = digraphs[kana.substring(i, i + 2)]
                if (syllable != null) i += 2
            }
            if (syllable == null) {
                syllable = monographs[kana[i]]?.also { i++ }
            }
            if (syllable == null) {
                // Unknown character: emit as-is and move on.
                sb.append(kana[i])
                i++
                pendingSokuon = false
                continue
            }
            if (pendingSokuon) {
                // Double the leading consonant (e.g. っか -> kka).
                val first = syllable.firstOrNull()
                if (first != null && first !in "aiueo") sb.append(first)
                pendingSokuon = false
            }
            sb.append(syllable)
        }
        return sb.toString()
    }

    /** Normalize katakana (ァ..ヶ) to hiragana; other characters pass through. */
    private fun toHiragana(s: String): String = buildString {
        for (c in s) {
            if (c in 'ァ'..'ヶ') append(c - KATAKANA_OFFSET) else append(c)
        }
    }

    // Unicode distance between the katakana and hiragana blocks.
    private const val KATAKANA_OFFSET = 0x60
}
