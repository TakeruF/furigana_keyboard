# Furigana Handwriting Keyboard

A Japanese handwriting keyboard that recognizes characters and displays
furigana. The production Android IME includes the full candidate pipeline and
optional Furigana Plus recognition; an iOS MVP provides a container app and custom
keyboard extension with offline single-character recognition and readings.
Bundled recognition remains available on both platforms without a network.

## Features

- Offline Zinnia recognition for the Tegaki Japanese label set: 6,356 JIS X
  0208 kanji labels plus hiragana and katakana.
- Furigana Plus on Android uses Google ML Kit's on-device Japanese model and
  automatically falls back to bundled Zinnia. It is enabled by default for all
  users and can be turned off under Handwriting settings if model delivery or
  recognition is unreliable in the user's region.
- KANJIDIC2 snapshot with 13,108 characters and 40,510 Japanese on, kun,
  nanori, and radical-name readings.
- JMdict snapshot with 217,819 entries and 247,673 surface/reading pairs.
- KANJIDIC2 school/Jōyō grade and newspaper-frequency metadata gently
  re-ranks visually close kanji candidates toward commonly used characters.
- Unicode-code-point-safe composition, including supplementary-plane kanji.
- Word completion from the current handwritten composition. Exact JMdict
  readings are shown instead of guessed per-character concatenations. One or
  two characters can be written on the same pad; side-by-side ink is segmented,
  recognized from left to right, and matched against JMdict automatically.
- Kana, romaji, or hidden reading display.
- Japanese, Simplified Chinese, Korean, and English UI.
- Two-column card settings hub with focused pages for display, handwriting,
  haptics, language, system keyboard setup, privacy, terms, help, and licenses.
- Light/dark palettes, continuous handwriting, symbols, QWERTY, adaptive enter
  actions, haptics, and hold-to-delete.

## On-device recognition and licensing

Recognition and dictionary lookup always run on device. Android requests the
`INTERNET` permission only for optional Furigana Plus model delivery and ML Kit
service data; handwriting and typed text are not sent. Turning Plus off keeps
the bundled recognizer fully usable offline. The first Plus activation downloads
an approximately 20 MB Japanese model.

- Zinnia runtime: New BSD License.
- Tegaki Japanese 0.3 model: LGPL 2.1.
- KANJIDIC2/JMdict and the generated `reading.db`: CC BY-SA 4.0, EDRDG.
- Google ML Kit Digital Ink Recognition: Google APIs/ML Kit terms.

Notices are bundled under `app/src/main/assets/licenses`; localized privacy
policies and terms are under `app/src/main/assets/legal`. They are accessible
from Settings → More. Exact source URLs, hashes, and the generation command are
recorded in `tools/reading-data-sources.txt`.

## Build

### Android

Requirements:

- JDK 17
- Android SDK 34
- Android NDK `27.2.12479018`
- CMake `3.22.1`

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest
```

The native library is built for `arm64-v8a`, `armeabi-v7a`, and `x86_64`, with
16 KB ELF page alignment. The current universal debug APK is about 33 MB.

### iOS MVP

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

The iOS MVP supports offline single-character handwriting recognition,
KANJIDIC2 reading display, candidate insertion, delete, space, return, and
keyboard switching. See `ios/README.md` for device signing and setup. Android's
continuous composition and JMdict word candidates are intentionally outside
the iOS MVP.

### About site

The public product page is under `about/`. APK and App Store actions remain in
a “coming soon” state until their environment variables are configured; no
page edit is needed when release URLs become available.

```bash
cd about
npm install
npm run dev
```

## Regenerating reading data

Download the three pinned inputs listed in `tools/reading-data-sources.txt`,
then run:

```bash
python3 tools/build_reading_db.py \
  --kanjidic /path/to/kanjidic2.xml.gz \
  --jmdict /path/to/JMdict_e.gz \
  --model-archive /path/to/tegaki-zinnia-japanese-0.3.zip \
  --output app/src/main/assets/reading.db
```

Generation fails if source coverage falls below the expected thresholds or if
any Han label in the bundled recognition model lacks a Japanese reading.

## Usage

1. Launch Furigana Keyboard.
2. Open **System keyboard** and enable the IME.
3. Choose Furigana Keyboard as the active input method.
4. Write one character, or write two characters side by side across the left
   and right halves of the handwriting pad.
5. Side-by-side characters are recognized from left to right and their combined
   alternatives are matched against JMdict. Tap a word candidate to replace and
   commit it, or press space/enter to finish the current composition.

## Coverage boundary

Handwriting recognition covers the agreed JIS X 0208 set, not every Unicode
Han code point. Reading lookup covers KANJIDIC2 characters that have Japanese
reading information. JMdict word candidates exclude proper-name-only entries
and arbitrary out-of-dictionary inflections.
