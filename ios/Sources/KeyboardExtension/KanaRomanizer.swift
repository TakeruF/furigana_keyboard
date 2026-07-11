import Foundation

enum KanaRomanizer {
    private static let pairs: [(String, String)] = [
        ("きゃ","kya"),("きゅ","kyu"),("きょ","kyo"),("しゃ","sha"),("しゅ","shu"),("しょ","sho"),
        ("ちゃ","cha"),("ちゅ","chu"),("ちょ","cho"),("にゃ","nya"),("にゅ","nyu"),("にょ","nyo"),
        ("ひゃ","hya"),("ひゅ","hyu"),("ひょ","hyo"),("みゃ","mya"),("みゅ","myu"),("みょ","myo"),
        ("りゃ","rya"),("りゅ","ryu"),("りょ","ryo"),("ぎゃ","gya"),("ぎゅ","gyu"),("ぎょ","gyo"),
        ("じゃ","ja"),("じゅ","ju"),("じょ","jo"),("びゃ","bya"),("びゅ","byu"),("びょ","byo"),
        ("ぴゃ","pya"),("ぴゅ","pyu"),("ぴょ","pyo")
    ]
    private static let singles: [Character: String] = Dictionary(uniqueKeysWithValues: zip(
        Array("あいうえおかきくけこがぎぐげござじずぜぞさしすせそたちつてとだぢづでどなにぬねのはひふへほばびぶべぼぱぴぷぺぽまみむめもやゆよらりるれろわをんぁぃぅぇぉゃゅょゔ"),
        ["a","i","u","e","o","ka","ki","ku","ke","ko","ga","gi","gu","ge","go","za","ji","zu","ze","zo","sa","shi","su","se","so","ta","chi","tsu","te","to","da","ji","zu","de","do","na","ni","nu","ne","no","ha","hi","fu","he","ho","ba","bi","bu","be","bo","pa","pi","pu","pe","po","ma","mi","mu","me","mo","ya","yu","yo","ra","ri","ru","re","ro","wa","o","n","a","i","u","e","o","ya","yu","yo","vu"]
    ))

    static func convert(_ value: String) -> String {
        var source = RomajiKanaConverter.toHiragana(value)
        var output = ""
        while !source.isEmpty {
            if source.first == "っ" {
                source.removeFirst()
                let next = romanPrefix(source)
                output += next.first.map(String.init) ?? ""
                continue
            }
            if let pair = pairs.first(where: { source.hasPrefix($0.0) }) {
                output += pair.1; source.removeFirst(pair.0.count); continue
            }
            let character = source.removeFirst()
            output += singles[character] ?? String(character)
        }
        return output
    }

    private static func romanPrefix(_ source: String) -> String {
        pairs.first(where: { source.hasPrefix($0.0) })?.1 ?? source.first.flatMap { singles[$0] } ?? ""
    }
}

private extension RomajiKanaConverter {
    static func toHiragana(_ value: String) -> String {
        String(value.unicodeScalars.map { scalar in
            if (0x30a1...0x30f6).contains(scalar.value), let converted = UnicodeScalar(scalar.value - 0x60) {
                return Character(String(converted))
            }
            return Character(String(scalar))
        })
    }
}
