# Furigana Handwriting Keyboard

An Android Japanese handwriting IME that recognizes characters and displays
furigana without a network connection. Recognition, KANJIDIC2 lookups, and
JMdict word suggestions are bundled in the APK, so the keyboard works on AOSP
devices without Google Play services and in regions where Google model
downloads are unavailable.

## Features

- Offline Zinnia recognition for the Tegaki Japanese label set: 6,356 JIS X
  0208 kanji labels plus hiragana and katakana.
- KANJIDIC2 snapshot with 13,108 characters and 40,510 Japanese on, kun,
  nanori, and radical-name readings.
- JMdict snapshot with 217,819 entries and 247,673 surface/reading pairs.
- KANJIDIC2 school/Jōyō grade and newspaper-frequency metadata gently
  re-ranks visually close kanji candidates toward commonly used characters.
- Unicode-code-point-safe composition, including supplementary-plane kanji.
- Word completion from the current handwritten composition. Exact JMdict
  readings are shown instead of guessed per-character concatenations. After
  two sequential characters, recognition alternatives are combined and
  matched against JMdict automatically.
- Kana, romaji, or hidden reading display.
- Japanese, Simplified Chinese, Korean, and English UI.
- Two-column card settings hub with focused pages for display, handwriting,
  haptics, language, system keyboard setup, privacy, terms, help, and licenses.
- Light/dark palettes, continuous handwriting, symbols, QWERTY, adaptive enter
  actions, haptics, and hold-to-delete.

## Offline and licensing

The production APK has no `INTERNET` permission and does not depend on ML Kit,
Firebase, Google Play services, or a model server.

- Zinnia runtime: New BSD License.
- Tegaki Japanese 0.3 model: LGPL 2.1.
- KANJIDIC2/JMdict and the generated `reading.db`: CC BY-SA 4.0, EDRDG.

Notices are bundled under `app/src/main/assets/licenses`; localized privacy
policies and terms are under `app/src/main/assets/legal`. They are accessible
from Settings → More. Exact source URLs, hashes, and the generation command are
recorded in `tools/reading-data-sources.txt`.

## Build

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
4. Write one character at a time. With continuous writing enabled, start the
   second character after the first character candidates appear.
5. The two characters are added as composing text and their recognition
   alternatives are matched against JMdict. Tap a word candidate to replace
   and commit it, or press space/enter to finish the current composition.

## Coverage boundary

Handwriting recognition covers the agreed JIS X 0208 set, not every Unicode
Han code point. Reading lookup covers KANJIDIC2 characters that have Japanese
reading information. JMdict word candidates exclude proper-name-only entries
and arbitrary out-of-dictionary inflections.
