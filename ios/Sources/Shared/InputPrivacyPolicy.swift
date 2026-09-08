import UIKit

/// What the keyboard is allowed to do for the editor it is attached to.
///
/// The keyboard has no learning store: nothing the user types is ever written to
/// disk. This policy makes the remaining promises explicit, so a password field
/// cannot reach the dictionary, cannot become recognition context, and cannot be
/// left behind in the in-memory candidate caches after the editor goes away.
///
/// iOS normally substitutes the system keyboard for secure text entry, so this is
/// a second guard rather than the only one. It still matters for editors that turn
/// secure entry on while a custom keyboard is already attached.
struct InputPrivacyPolicy: Equatable {
    /// Password-like editors, by `UITextInputTraits.isSecureTextEntry`.
    let isSensitiveField: Bool

    /// Dictionary suggestions, kana-kanji conversion, and reading inference.
    var allowsDictionaryLookup: Bool { !isSensitiveField }

    /// Feeding already-committed text back to the recognizer as composition context.
    var allowsRecognitionContext: Bool { !isSensitiveField }

    /// Holding a multi-character marked-text composition in the editor before committing.
    var allowsMarkedText: Bool { !isSensitiveField }

    static let unrestricted = InputPrivacyPolicy(isSensitiveField: false)

    init(isSensitiveField: Bool) {
        self.isSensitiveField = isSensitiveField
    }

    /// `UITextInputTraits` members are optional, so an unanswered trait means unrestricted.
    init(isSecureTextEntry: Bool?) {
        self.init(isSensitiveField: isSecureTextEntry == true)
    }

    init(proxy: UITextDocumentProxy?) {
        self.init(isSecureTextEntry: proxy?.isSecureTextEntry)
    }
}
