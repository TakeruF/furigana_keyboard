# iOS setup

The iOS project contains a settings/onboarding app and a Japanese custom
keyboard extension. The extension bundles the same Zinnia model and schema-8
reading/conversion database as Android, performs recognition locally, and
requests no open/network access. The container app verifies and downloads
signed dictionary updates into the App Group; the keyboard always retains its
bundled offline fallback and keeps Full Access disabled.

## Feature parity

- Continuous one-character handwriting and spatially segmented two-character
  handwriting, with common-use kanji ranking.
- Zinnia scores are normalized into the same lower-is-better shape-cost band as
  Android before anything is compared, so side-by-side halves rank against each
  other and the common-use bias cannot cross a clear difference in shape
  evidence. The character the recognizer was most sure about always keeps a
  candidate slot.
- KANJIDIC2 readings plus JMdict/JMnedict exact matches and word completions.
  Recognized surfaces and their completions are ranked together, with exact
  dictionary hits earning a small bounded discount, so a known word can outrank
  a shape the recognizer was unsure about. Android additionally gives proper and
  place names their own smaller discount; on iOS that branch is gated on the
  database having an index led by `conversion_lexeme.surface`, because on the
  shipped schema-8 database it costs a 679k-row scan (about 130 ms warm, 650 ms
  cold, against 5 ms for the indexed branches) on every recognized stroke. The
  gate lifts by itself when such an index ships.
- Offline romaji-to-kana input and lattice-based kana-kanji conversion using
  the same conversion lexemes and part-of-speech connection costs as Android,
  including sequential bunsetsu selection and shrink/expand controls.
- The romaji composition is tracked around an explicit cursor. Holding the space
  key and dragging enters cursor mode and moves by one user-perceived character
  per step; typing inserts at the cursor and deletion removes one whole grapheme,
  so a supplementary-plane kanji, a combining dakuten, or an emoji sequence is
  never split. An interior cursor converts only the text left of it and
  reinstalls the untouched right side as the next composition. Marked text can
  carry the caret directly on iOS, so unlike Android there is no selection
  round trip to arbitrate. If the user taps inside the marked text the caret
  returns to the tracked position on the next keystroke; the composition text
  itself is never rebuilt from it.
- Japanese QWERTY, English QWERTY, symbol, and handwriting panels.
- Kana, romaji, or hidden readings; configurable candidate size, keyboard
  height, accent color, number row, continuous input, haptics, and key clicks.
- Light/dark appearance, press-and-hold delete, and iPhone/iPad layouts.

Furigana Plus is Android-only because it uses Google ML Kit's Android model
delivery. iOS uses the bundled Zinnia recognizer and remains fully offline.

## Password fields

Nothing typed is ever written to disk. `InputPrivacyPolicy` makes the remaining
promise explicit: for an editor reporting `isSecureTextEntry`, the keyboard
performs no dictionary lookup, no reading inference, and holds no marked-text
composition, so each character commits on its own and the Japanese panel behaves
like the plain ABC panel. Handwriting still works, with candidates coming from
ink recognition alone. iOS normally substitutes the system keyboard for secure
text entry, so this is a second guard rather than the only one. Candidates are
dropped with the editor instead of being carried to the next one.

## Generate and build

Requirements: Xcode 16 or newer and XcodeGen.

```bash
cd ios
xcodegen generate
xcodebuild \
  -project FuriganaKeyboard.xcodeproj \
  -scheme FuriganaKeyboard \
  -sdk iphonesimulator \
  -configuration Debug \
  CODE_SIGNING_ALLOWED=NO build
```

For a device/App Store build, set your Apple Developer team and replace the
example bundle identifiers in `project.yml` with identifiers owned by that
team. Enable the App Groups capability for both targets and register
`group.app.hanlu.furiganakeyboard` (or change the identifier consistently in
both entitlements and `ReadingUpdateSupport.swift`). Regenerate the project
after changing `project.yml`.

## Enable on a device

1. Run the `FuriganaKeyboard` scheme on an iPhone or iPad.
2. Open Settings → General → Keyboard → Keyboards.
3. Choose Add New Keyboard and select Furigana Keyboard.
4. Switch keyboards with the globe key while editing text.

Full Access should remain disabled. Open the container app's Settings tab to
choose the initial panel, reading style, layout size, accent, feedback, and
continuous-handwriting behavior; changes are shared with the extension.
