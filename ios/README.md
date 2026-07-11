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
- KANJIDIC2 readings plus JMdict/JMnedict exact matches and word completions.
- Offline romaji-to-kana input and lattice-based kana-kanji conversion using
  the same conversion lexemes and part-of-speech connection costs as Android,
  including sequential bunsetsu selection and shrink/expand controls.
- Japanese QWERTY, English QWERTY, symbol, and handwriting panels.
- Kana, romaji, or hidden readings; configurable candidate size, keyboard
  height, accent color, number row, continuous input, haptics, and key clicks.
- Light/dark appearance, press-and-hold delete, and iPhone/iPad layouts.

Furigana Plus is Android-only because it uses Google ML Kit's Android model
delivery. iOS uses the bundled Zinnia recognizer and remains fully offline.

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
