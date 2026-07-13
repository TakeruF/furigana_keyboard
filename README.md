# Furigana Handwriting Keyboard

A Japanese handwriting keyboard that recognizes characters and displays
furigana. Android and iOS both provide the full offline input flow: continuous
handwriting, side-by-side recognition, word candidates, Japanese and English
QWERTY panels, symbols, and romaji kana-kanji conversion. Android additionally
offers optional Furigana Plus recognition through Google ML Kit. Bundled
recognition remains available on both platforms without a network.

## Features

- Offline Zinnia recognition for the Tegaki Japanese label set: 6,356 JIS X
  0208 kanji labels plus hiragana and katakana.
- Furigana Plus on Android uses Google ML Kit's on-device Japanese model and
  automatically falls back to bundled Zinnia. It is enabled by default for all
  users and can be turned off under Handwriting settings if model delivery or
  recognition is unreliable in the user's region.
- KANJIDIC2 snapshot with 13,108 characters and 40,510 Japanese on, kun,
  nanori, and radical-name readings.
- JMdict snapshot with 217,819 entries and 247,673 surface/reading pairs,
  plus 213,359 place and railway-station pairs from JMnedict.
- KANJIDIC2 school/Jōyō grade and newspaper-frequency metadata gently
  re-ranks visually close kanji candidates toward commonly used characters.
- Unicode-code-point-safe composition, including supplementary-plane kanji.
- Word completion from the current handwritten composition. Exact dictionary
  readings are shown instead of guessed per-character concatenations. One or
  two characters can be written on the same pad; side-by-side ink is segmented,
  recognized from left to right, and matched against the on-device dictionaries
  automatically.
- Offline kana-kanji sentence conversion for completed romaji input. The
  converter ranks up to eight candidates locally and keeps hiragana and
  katakana fallbacks available without sending the composition off device.
- Kana, romaji, or hidden reading display.
- Japanese, Simplified Chinese, Korean, and English UI.
- Settings for display, handwriting, haptics, language, system keyboard setup,
  haptics, language, system keyboard setup, privacy, terms, help, and licenses.
- Light/dark palettes, continuous handwriting, symbols, QWERTY, adaptive enter
  actions, haptics, and hold-to-delete.

## On-device recognition and licensing

Recognition and dictionary lookup always run on device. Android requests the
`INTERNET` permission for signed reading-dictionary updates and optional
Furigana Plus model delivery; handwriting and typed text are not sent. The
bundled recognizer and dictionary remain usable offline. The first Plus
activation downloads an approximately 20 MB Japanese model.

- Zinnia runtime: New BSD License.
- Tegaki Japanese 0.3 model: LGPL 2.1.
- KANJIDIC2/JMdict/JMnedict and the generated `reading.db`: CC BY-SA 4.0,
  EDRDG.
- Google ML Kit Digital Ink Recognition: Google APIs/ML Kit terms.

Notices are bundled under `app/src/main/assets/licenses`; localized privacy
policies and terms are under `app/src/main/assets/legal`. They are accessible
from Settings → More. Exact source URLs, hashes, and the generation command are
recorded in `tools/reading-data-sources.txt`.

## Build

### Android

Requirements:

- JDK 17
- Android SDK 35 (Build Tools 35.0.0)
- Android NDK `27.2.12479018`
- CMake `3.22.1`

```bash
./gradlew :app:assemblePlayDebug :app:assembleDirectDebug
./gradlew :app:testPlayDebugUnitTest :app:testDirectDebugUnitTest
./gradlew :app:connectedPlayDebugAndroidTest
```

Android has two distribution flavors. `play` uses Google Play In-App Updates
and contains no external APK updater. `direct` checks
`https://downloads.hanlu.app/latest.json`, downloads the listed APK inside the
app, verifies its SHA-256 digest and signing certificate, and then opens the
Android installer after the user confirms. Build release artifacts with
`:app:bundlePlayRelease` for Google Play and `:app:assembleDirectRelease` for
the download site.

The native library is built for `arm64-v8a`, `armeabi-v7a`, and `x86_64`, with
16 KB ELF page alignment. The current universal debug APK is about 33 MB.

### iOS

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

The iOS keyboard supports offline one- and two-character handwriting,
continuous composition, KANJIDIC2/JMdict/JMnedict candidates, QWERTY romaji
kana-kanji conversion, English and symbol panels, shared display/input
preferences, haptics, key clicks, and signed dictionary updates. Full Access
remains disabled. See `ios/README.md` for device signing and setup.

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

Download the four pinned inputs listed in `tools/reading-data-sources.txt`,
install the SQLite 3.50 command-line tool used to finalize the Android-compatible
file format, then run:

```bash
python3 tools/build_reading_db.py \
  --kanjidic /path/to/kanjidic2.xml.gz \
  --jmdict /path/to/JMdict_e.gz \
  --jmnedict /path/to/JMnedict.xml.gz \
  --model-archive /path/to/tegaki-zinnia-japanese-0.3.zip \
  --output app/src/main/assets/reading.db
```

Generation fails if source coverage falls below the expected thresholds or if
any Han label in the bundled recognition model lacks a Japanese reading.

### Kana-kanji conversion design

Schema 8 adds `conversion_lexeme`, the fixed 0–15 `conversion_pos` IDs, and a
16×16 `connection_cost` matrix. Input code-point boundaries form lattice
vertices; dictionary lexemes and one-code-point kana-copy alternatives form
edges. A deterministic N-best dynamic program ranks paths by word and POS
connection cost with beam width 12. Inputs are limited to 48 code points,
dictionary edges to 16 code points, stored vocabulary to 12 entries per
reading, and output to eight unique surfaces. Longer input bypasses dictionary
conversion and retains the existing hiragana/katakana candidates.

`conversion_lexeme` is a `WITHOUT ROWID` table whose composite primary key
starts with `reading`, so SQLite uses the table's primary-key B-tree as the
reading index without a second, size-heavy copy. The generated database must
remain below 128 MiB. Conversion runs on the candidate worker, is cancelled by
newer input generations, and caches only dictionary results in memory; input
text is not logged, analyzed, or persisted.

## Publishing reading-data updates

Reading data is distributed as static files; Supabase or another online
database is not required. Upload the generated files to HTTPS object storage
served at `downloads.hanlu.app/furigana/`. Android checks once per day while
connected. On iOS, the container app checks at launch and when the user taps
the update button; the keyboard extension remains offline and reads the
verified database through its App Group.

The current update schema is **8**. Both clients record the schema alongside
the active data version and only reuse a downloaded database when its recorded
schema, SQLite metadata, and integrity check all match schema 8. After an app
upgrade, legacy `active` data without a schema, schema 7 data, and corrupt data
are ignored automatically in favor of the bundled schema 8 database.

The local ECDSA signing key is stored at
`.secrets/reading-update-private.pem` and is intentionally ignored by Git.
Back it up in a secure password manager or secret store: losing it requires an
app release to rotate the embedded public key. Never upload or commit it.

For each release, increase `--version` monotonically and use the final public
database URL. The publishing command rejects every database except schema 8
and verifies that the emitted manifest schema matches the database metadata:

```bash
python3 tools/publish_reading_update.py \
  --database app/src/main/assets/reading.db \
  --version 20260711 \
  --database-url https://downloads.hanlu.app/furigana/reading-20260711.db \
  --private-key .secrets/reading-update-private.pem
```

Upload all three files from `reading-update-dist/`:

- `manifest.json` → `/furigana/manifest.json`
- `manifest.json.sig` → `/furigana/manifest.json.sig`
- `reading-<version>.db` → the URL recorded in the manifest

The clients reject unsigned manifests, non-HTTPS database URLs, incompatible
schemas, oversized files, hash mismatches, and corrupt SQLite databases. A
failed update leaves the active or bundled database untouched.

### Schema 8 rollout and rollback

Release in this order:

1. Publish Android and iOS app versions that bundle schema 8 and understand
   schema 8 manifests. Both remain fully usable offline from the bundled DB.
2. Confirm the compatible app versions have reached the required adoption
   threshold while the public manifest still points to schema 7.
3. Upload the schema 8 database and signature, then publish the schema 8
   manifest last. Older apps reject it instead of opening an incompatible DB.

Before step 3, retain the last signed schema 7 database, manifest, and detached
signature. To roll back, republish those exact v7 artifacts atomically (database
and signature first, manifest last), then verify the public manifest and a
schema-7 client. Compatible apps safely ignore an active v7 download after the
upgrade and fall back to their bundled v8 database; do not delete the bundled
v8 asset. Actual upload requires the offline signing key and distribution
permissions and is intentionally separate from completing this code change.

Keep the private signing key outside the repository and never paste it into
terminal output, manifests, issue reports, or commits. Review the generated
manifest and database filename, then upload the database and detached
signature first and `manifest.json` last. Publishing the manifest last keeps
clients from observing a release before all referenced files are available.

## Publishing direct Android updates

アプリ、iOS、読み辞書、サイトを含む公開前チェックとバージョン番号の規則は
[`RELEASING.md`](./RELEASING.md)を先に確認してください。

First increase `versionCode` and `versionName` in `app/build.gradle.kts`, then
build the direct APK. Prepare the versioned APK and discovery manifest with:

```bash
./gradlew :app:assembleDirectRelease
python3 tools/publish_android_update.py \
  --apk app/build/outputs/apk/direct/release/app-direct-release.apk \
  --version-code 12 \
  --version-name 1.2.0 \
  --release-notes "Recognition improvements"
```

Upload both files from `android-update-dist/` without changing their names:

- `furigana-keyboard/<versionName>.apk` → `https://downloads.hanlu.app/furigana-keyboard/<versionName>.apk`
- `latest.json` → `https://downloads.hanlu.app/latest.json`

Upload the APK first and `latest.json` last so clients are never directed to
an APK that is not available yet. `versionCode` must increase for every
release; the app uses it, rather than `versionName`, to decide whether an
update is newer.

Verify the public endpoint after uploading. Add `--download-apk` for the final
release check, which downloads the referenced APK and compares its SHA-256:

```bash
python3 tools/check_android_update.py --current-version-code 11 --download-apk
```

## Usage

1. Launch Furigana Keyboard.
2. Open **System keyboard** and enable the IME.
3. Choose Furigana Keyboard as the active input method.
4. Write one character, or write two characters side by side across the left
   and right halves of the handwriting pad.
5. Side-by-side characters are recognized from left to right and their combined
   alternatives are matched against JMdict and JMnedict. Tap a word candidate
   to replace and commit it, or press space/enter to finish the current
   composition.

## Coverage boundary

Handwriting recognition covers the agreed JIS X 0208 set, not every Unicode
Han code point. Reading lookup covers KANJIDIC2 characters that have Japanese
reading information. Word candidates cover JMdict vocabulary plus place and
railway station names from JMnedict; other proper-name-only entries and
arbitrary out-of-dictionary inflections remain outside the supported scope.
