import Foundation

struct RomajiConversion: Equatable {
    let kana: String
    let pending: String
    let hasAmbiguousTerminalN: Bool
    var displayText: String { kana + pending }
    var hasUnresolvedInput: Bool { !pending.isEmpty || hasAmbiguousTerminalN }
}

enum RomajiKanaConverter {
    private static let syllables: [String: String] = [
        "a":"あ", "i":"い", "u":"う", "e":"え", "o":"お",
        "ka":"か", "ki":"き", "ku":"く", "ke":"け", "ko":"こ",
        "ga":"が", "gi":"ぎ", "gu":"ぐ", "ge":"げ", "go":"ご",
        "sa":"さ", "shi":"し", "si":"し", "su":"す", "se":"せ", "so":"そ",
        "za":"ざ", "ji":"じ", "zi":"じ", "zu":"ず", "ze":"ぜ", "zo":"ぞ",
        "ta":"た", "chi":"ち", "ti":"ち", "tsu":"つ", "tu":"つ", "te":"て", "to":"と",
        "da":"だ", "di":"ぢ", "du":"づ", "de":"で", "do":"ど",
        "na":"な", "ni":"に", "nu":"ぬ", "ne":"ね", "no":"の",
        "ha":"は", "hi":"ひ", "fu":"ふ", "hu":"ふ", "he":"へ", "ho":"ほ",
        "ba":"ば", "bi":"び", "bu":"ぶ", "be":"べ", "bo":"ぼ",
        "pa":"ぱ", "pi":"ぴ", "pu":"ぷ", "pe":"ぺ", "po":"ぽ",
        "ma":"ま", "mi":"み", "mu":"む", "me":"め", "mo":"も",
        "ya":"や", "yu":"ゆ", "yo":"よ", "ra":"ら", "ri":"り", "ru":"る", "re":"れ", "ro":"ろ",
        "wa":"わ", "wi":"うぃ", "we":"うぇ", "wo":"を",
        "kya":"きゃ", "kyu":"きゅ", "kyo":"きょ", "gya":"ぎゃ", "gyu":"ぎゅ", "gyo":"ぎょ",
        "sha":"しゃ", "shu":"しゅ", "sho":"しょ", "sya":"しゃ", "syu":"しゅ", "syo":"しょ",
        "ja":"じゃ", "ju":"じゅ", "jo":"じょ", "jya":"じゃ", "jyu":"じゅ", "jyo":"じょ",
        "cha":"ちゃ", "chu":"ちゅ", "cho":"ちょ", "cya":"ちゃ", "cyu":"ちゅ", "cyo":"ちょ",
        "tya":"ちゃ", "tyu":"ちゅ", "tyo":"ちょ", "tcha":"っちゃ", "tchu":"っちゅ", "tcho":"っちょ",
        "nya":"にゃ", "nyu":"にゅ", "nyo":"にょ", "hya":"ひゃ", "hyu":"ひゅ", "hyo":"ひょ",
        "bya":"びゃ", "byu":"びゅ", "byo":"びょ", "pya":"ぴゃ", "pyu":"ぴゅ", "pyo":"ぴょ",
        "mya":"みゃ", "myu":"みゅ", "myo":"みょ", "rya":"りゃ", "ryu":"りゅ", "ryo":"りょ",
        "fa":"ふぁ", "fi":"ふぃ", "fe":"ふぇ", "fo":"ふぉ",
        "va":"ゔぁ", "vi":"ゔぃ", "vu":"ゔ", "ve":"ゔぇ", "vo":"ゔぉ",
        "she":"しぇ", "je":"じぇ", "che":"ちぇ",
        "tsa":"つぁ", "tsi":"つぃ", "tse":"つぇ", "tso":"つぉ",
        "dya":"ぢゃ", "dyu":"ぢゅ", "dyo":"ぢょ",
        "xya":"ゃ", "xyu":"ゅ", "xyo":"ょ", "lya":"ゃ", "lyu":"ゅ", "lyo":"ょ",
        "xa":"ぁ", "xi":"ぃ", "xu":"ぅ", "xe":"ぇ", "xo":"ぉ",
        "la":"ぁ", "li":"ぃ", "lu":"ぅ", "le":"ぇ", "lo":"ぉ",
        "xtsu":"っ", "ltsu":"っ", "xtu":"っ", "ltu":"っ"
    ]

    private static let maxSyllableLength = syllables.keys.map(\.count).max() ?? 4

    static func convert(_ rawInput: String) -> RomajiConversion {
        let input = Array(rawInput.lowercased())
        var kana = ""
        var index = 0
        while index < input.count {
            let current = input[index]
            if current == "ー" {
                kana.append(current); index += 1; continue
            }
            if index + 1 < input.count, current == input[index + 1], current != "n", isConsonant(current) {
                kana.append("っ"); index += 1; continue
            }
            if let match = longestMatch(input, from: index) {
                kana += match.kana; index += match.length; continue
            }
            if current == "n" {
                var runEnd = index
                while runEnd < input.count, input[runEnd] == "n" { runEnd += 1 }
                let count = runEnd - index
                if runEnd == input.count {
                    kana += String(repeating: "ん", count: (count + 1) / 2)
                    return RomajiConversion(kana: kana, pending: "", hasAmbiguousTerminalN: count % 2 != 0)
                }
                let next = input[index + 1]
                if next == "'" { kana.append("ん"); index += 2; continue }
                if count > 1 {
                    let after = input[runEnd]
                    if isVowel(after) || after == "y" {
                        kana += String(repeating: "ん", count: count / 2); index = runEnd - 1
                    } else {
                        kana += String(repeating: "ん", count: (count + 1) / 2); index = runEnd
                    }
                    continue
                }
                if isConsonant(next), next != "y" { kana.append("ん"); index += 1; continue }
            }
            return RomajiConversion(kana: kana, pending: String(input[index...]), hasAmbiguousTerminalN: false)
        }
        return RomajiConversion(kana: kana, pending: "", hasAmbiguousTerminalN: false)
    }

    static func toKatakana(_ hiragana: String) -> String {
        String(hiragana.unicodeScalars.map { scalar in
            if (0x3041...0x3096).contains(scalar.value), let converted = UnicodeScalar(scalar.value + 0x60) {
                return Character(String(converted))
            }
            return Character(String(scalar))
        })
    }

    static func deleteLastUnit(_ rawInput: String) -> String {
        guard !rawInput.isEmpty else { return rawInput }
        let converted = convert(rawInput)
        if !converted.pending.isEmpty || converted.kana.isEmpty { return String(rawInput.dropLast()) }
        let target = String(converted.kana.dropLast())
        for length in stride(from: rawInput.count - 1, through: 0, by: -1) {
            let prefix = String(rawInput.prefix(length))
            if convert(prefix).displayText == target { return prefix }
        }
        return canonicalRaw(for: target) ?? String(rawInput.dropLast())
    }

    private static func canonicalRaw(for kana: String) -> String? {
        guard !kana.isEmpty else { return "" }
        let characters = Array(kana)
        var memo: [Int: String] = [:]
        var canonical: [String: String] = [:]
        for (raw, visible) in syllables {
            if let current = canonical[visible] {
                if raw.count < current.count || (raw.count == current.count && raw < current) {
                    canonical[visible] = raw
                }
            } else {
                canonical[visible] = raw
            }
        }
        canonical["ん"] = "nn"; canonical["ー"] = "ー"
        func build(_ index: Int) -> String? {
            if index == characters.count { return "" }
            if let cached = memo[index] { return cached }
            var options: [String] = []
            for (visible, raw) in canonical {
                let glyphs = Array(visible)
                guard index + glyphs.count <= characters.count else { continue }
                let visibleSlice = Array(characters[index..<(index + glyphs.count)])
                guard visibleSlice == glyphs, let suffix = build(index + glyphs.count) else { continue }
                options.append(raw + suffix)
            }
            options.sort { $0.count == $1.count ? $0 < $1 : $0.count < $1.count }
            if let result = options.first { memo[index] = result }
            return options.first
        }
        guard let raw = build(0), convert(raw).displayText == kana else { return nil }
        return raw
    }

    private static func longestMatch(_ input: [Character], from start: Int) -> (kana: String, length: Int)? {
        let maximum = min(maxSyllableLength, input.count - start)
        guard maximum > 0 else { return nil }
        for length in stride(from: maximum, through: 1, by: -1) {
            let key = String(input[start..<(start + length)])
            if let kana = syllables[key] { return (kana, length) }
        }
        return nil
    }

    private static func isVowel(_ value: Character) -> Bool { "aiueo".contains(value) }
    private static func isConsonant(_ value: Character) -> Bool {
        value.isASCII && value.isLetter && !isVowel(value)
    }
}
