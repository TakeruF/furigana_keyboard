import Foundation

enum ReadingDisplayMode: String, CaseIterable, Identifiable {
    case kana, romaji, hidden
    var id: String { rawValue }
}
enum PreferredKeyboardPanel: String, CaseIterable, Identifiable {
    case handwriting, japanese, english
    var id: String { rawValue }
}

enum KeyboardHeight: String, CaseIterable, Identifiable {
    case compact, standard, tall
    var id: String { rawValue }

    var points: CGFloat {
        switch self {
        case .compact: 260
        case .standard: 292
        case .tall: 326
        }
    }
}

enum CandidateTextSize: String, CaseIterable, Identifiable {
    case compact, standard, large
    var id: String { rawValue }

    var primary: CGFloat {
        switch self {
        case .compact: 20
        case .standard: 24
        case .large: 28
        }
    }
}

enum AccentChoice: String, CaseIterable, Identifiable {
    case blue, green, orange, pink, purple
    var id: String { rawValue }
}

enum AppLanguage: String, CaseIterable, Identifiable {
    case automatic, japanese, english, simplifiedChinese, korean
    var id: String { rawValue }
}

/// Shared App Group preferences. The ordinary defaults fallback keeps previews and
/// unsigned simulator builds functional when the App Group container is unavailable.
struct KeyboardPreferences {
    static let appGroup = "group.app.hanlu.furiganakeyboard"
    private let defaults: UserDefaults

    init(defaults: UserDefaults? = UserDefaults(suiteName: appGroup)) {
        self.defaults = defaults ?? .standard
    }

    var readingMode: ReadingDisplayMode {
        get { enumValue("readingMode", fallback: .kana) }
        nonmutating set { defaults.set(newValue.rawValue, forKey: "readingMode") }
    }

    var preferredPanel: PreferredKeyboardPanel {
        get { enumValue("preferredPanel", fallback: .handwriting) }
        nonmutating set { defaults.set(newValue.rawValue, forKey: "preferredPanel") }
    }

    var keyboardHeight: KeyboardHeight {
        get { enumValue("keyboardHeight", fallback: .standard) }
        nonmutating set { defaults.set(newValue.rawValue, forKey: "keyboardHeight") }
    }

    var candidateTextSize: CandidateTextSize {
        get { enumValue("candidateTextSize", fallback: .standard) }
        nonmutating set { defaults.set(newValue.rawValue, forKey: "candidateTextSize") }
    }

    var accent: AccentChoice {
        get { enumValue("accent", fallback: .blue) }
        nonmutating set { defaults.set(newValue.rawValue, forKey: "accent") }
    }

    var language: AppLanguage {
        get { enumValue("language", fallback: .automatic) }
        nonmutating set { defaults.set(newValue.rawValue, forKey: "language") }
    }

    var hapticsEnabled: Bool {
        get { defaults.object(forKey: "hapticsEnabled") as? Bool ?? true }
        nonmutating set { defaults.set(newValue, forKey: "hapticsEnabled") }
    }

    var keyClicksEnabled: Bool {
        get { defaults.object(forKey: "keyClicksEnabled") as? Bool ?? false }
        nonmutating set { defaults.set(newValue, forKey: "keyClicksEnabled") }
    }

    var showNumberRow: Bool {
        get { defaults.object(forKey: "showNumberRow") as? Bool ?? true }
        nonmutating set { defaults.set(newValue, forKey: "showNumberRow") }
    }

    var continuousHandwriting: Bool {
        get { defaults.object(forKey: "continuousHandwriting") as? Bool ?? true }
        nonmutating set { defaults.set(newValue, forKey: "continuousHandwriting") }
    }

    private func enumValue<T: RawRepresentable>(_ key: String, fallback: T) -> T where T.RawValue == String {
        defaults.string(forKey: key).flatMap(T.init(rawValue:)) ?? fallback
    }
}
