# iOS setup

The iOS project contains a small container app and a Japanese custom keyboard
extension. The extension bundles the same Zinnia model and reading database as
Android, performs recognition locally, and requests no open/network access.
The container app can download signed dictionary updates into an App Group;
the extension receives read-only access and keeps Full Access disabled.

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

Full Access should remain disabled. This initial iOS setup supports offline
single-character handwriting recognition and KANJIDIC2 readings. Android's
continuous composition and JMdict word-candidate pipeline are the next parity
milestone.
