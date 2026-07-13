# リリース手順

この文書は、Android（Google Play／直接配布）、iOS、読み辞書、Aboutサイトを公開する際のチェックリストです。アプリのバージョン番号と読み辞書のデータバージョンは別物として管理します。

## 1. 共通の公開前確認

1. 公開済みの最新番号を確認する。
   - Android Play版: Google Play Consoleの最新`versionCode`
   - Android直接配布版: 公開中の`latest.json`の`versionCode`
   - iOS: App Store Connectの最新build番号
   - 読み辞書: 公開中の`furigana/manifest.json`の`dataVersion`
2. 公開先を確認できない場合は、前回のリリース記録から確認する。推測で番号を決めない。
3. `git status --short`に意図しない変更がないことを確認する。
4. リリースノートを`RELEASE_NOTES/`へ追加し、ユーザー向けの変更、既知の制約、対応OSを記載する。
5. GitHub ActionsのCIが全ジョブ成功していることを確認する。
6. 法務文書、第三者ライセンス、iOS Privacy Manifestが成果物に含まれることを確認する。

## 2. バージョン番号の規則

| 対象 | 変更場所 | 規則 |
| --- | --- | --- |
| Android `versionCode` | `app/build.gradle.kts` | Play版・直接配布版を問わず、過去に公開した最大値より必ず大きくする。一度使った値はロールバックでも再利用しない。 |
| Android `versionName` | `app/build.gradle.kts` | ユーザー向けバージョン。例: `1.0.0-beta.7`、`1.0.0`。Play版と直接配布版で同じソースを公開する場合は同じ値にする。 |
| iOS `CURRENT_PROJECT_VERSION` | `ios/project.yml` | App Store Connectで過去にアップロードしたbuild番号より必ず大きくする。再アップロードや修正版も増やす。 |
| iOS `MARKETING_VERSION` | `ios/project.yml` | App Store上のユーザー向けバージョン。例: `1.0.0`。親アプリとキーボード拡張には同じ値が自動適用される。 |
| 読み辞書 `dataVersion` | `publish_reading_update.py --version` | アプリのバージョンとは独立した単調増加整数。日付形式を使う場合も、同日再公開では前の値より増やす。 |

Gitタグや`versionName`だけを変更しても更新判定には使われません。Androidは`versionCode`、iOSはbuild番号、読み辞書は`dataVersion`で新旧を判定します。

## 3. Androidアプリ

### 3.1 番号と署名設定

1. `app/build.gradle.kts`の`versionCode`と`versionName`を更新する。
2. `key.properties`が存在し、公開済みアプリと同じアップロード／署名鍵を指していることを確認する。秘密値や鍵ファイルはコミットしない。
3. Play版と直接配布版は同じ`defaultConfig`を使用するため、同じビルドでは同じ番号になる。

リリースビルドは`key.properties`がない場合に失敗するよう設定されています。鍵を変更すると直接配布版のアプリ内更新で署名検証に失敗するため、意図しないローテーションは禁止です。

### 3.2 検証とビルド

```bash
./gradlew \
  :app:testPlayDebugUnitTest \
  :app:testDirectDebugUnitTest \
  :app:lintPlayDebug \
  :app:lintDirectDebug

./gradlew :app:bundlePlayRelease :app:assembleDirectRelease
```

最低限、Android 7（API 24）、最新Android、32-bit ARM、64-bit ARMで次を確認します。

- IMEの有効化・選択
- 手書き認識、連続入力、2文字認識
- ローマ字入力、かな漢字変換、文節確定
- オフライン時の同梱認識
- Plus認識のモデル取得とZinniaへのフォールバック
- 設定、法務文書、ライセンス、サポートメール
- Play版に外部APK更新機能がなく、直接配布版だけに存在すること

### 3.3 Google Play版

1. `app/build/outputs/bundle/playRelease/app-play-release.aab`を内部テストへアップロードする。
2. Play Consoleが表示する`versionCode`と`versionName`が設定値と一致することを確認する。
3. 自動テストと端末確認後、段階公開する。
4. 問題があっても古い`versionCode`を再利用せず、修正版はさらに大きい値で作る。

### 3.4 直接配布版

Gradleが生成したAPKを移動・改名する前に、次を実行します。公開ツールは同じディレクトリの`output-metadata.json`を読み、引数とAPKの実際の番号が違う場合は失敗します。

```bash
python3 tools/publish_android_update.py \
  --apk app/build/outputs/apk/direct/release/app-direct-release.apk \
  --version-code <app.build.gradle.ktsと同じ値> \
  --version-name <app.build.gradle.ktsと同じ値> \
  --release-notes "ユーザー向けリリースノート"
```

1. `android-update-dist/furigana-keyboard/<versionName>.apk`を同じオブジェクトパスへ先にアップロードする。
2. 公開URLからAPKを取得でき、SHA-256が`latest.json`と一致することを確認する。
3. `latest.json`を最後にアップロードする。
4. 旧版の直接配布APKから更新通知、ダウンロード、署名検証、Androidインストーラ起動まで確認する。

`latest.json`だけを先に公開してはいけません。ロールバックが必要な場合も、署名済みの修正版を新しい`versionCode`で公開します。

OSSでは、バケット一覧を公開する必要はありませんが、上記2オブジェクトへの匿名`GetObject`を許可します。`latest.json`の`Content-Type`は`application/json`にし、差し替えが端末へ早く反映されるよう短いキャッシュ時間（例: `Cache-Control: public, max-age=300`）を設定します。APKは長期キャッシュして構いません。

アップロード後は、外部ネットワークから次を実行します。1つ目は公開マニフェストの形式と現在のビルドに対する更新有無、2つ目はAPK本体の取得とSHA-256まで確認します。どちらも終了コード0になってから公開完了とします。

```bash
python3 tools/check_android_update.py \
  --current-version-code <確認元APKのversionCode>

python3 tools/check_android_update.py --download-apk
```

`HTTP 403`の場合はアプリの問題ではなく、OSSのオブジェクトACL／バケットポリシーが匿名`GetObject`を許可していません。`latest.json`と参照先APKを公開読取可能にしてから再実行します。`latest.json`が古いままキャッシュされないこと、APKより先に公開されていないことも確認します。

## 4. iOSアプリ

1. `ios/project.yml`の`MARKETING_VERSION`と`CURRENT_PROJECT_VERSION`を更新する。
2. Apple Developer Team、親アプリと拡張のBundle ID、App Groupが本番値であることを確認する。
3. Xcodeプロジェクトを再生成する。

```bash
cd ios
xcodegen generate
xcodebuild \
  -project FuriganaKeyboard.xcodeproj \
  -scheme FuriganaKeyboard \
  -configuration Release \
  -destination 'generic/platform=iOS' \
  archive \
  -archivePath DerivedData/FuriganaKeyboard.xcarchive
```

Archiveの親アプリと`FuriganaKeyboardExtension.appex`について、次を確認します。

- `CFBundleShortVersionString`と`CFBundleVersion`が`project.yml`と一致
- 親アプリと拡張のバージョンが一致
- 両方に`PrivacyInfo.xcprivacy`が存在
- 親アプリに法務文書と第三者ライセンスが存在
- `RequestsOpenAccess`が`false`
- 実機でフルアクセスなしの入力、App Groupからの辞書読込、キーボード切替が動作

TestFlightで確認してからApp Store審査へ提出します。同じマーケティングバージョンを再提出する場合でも、`CURRENT_PROJECT_VERSION`は増やします。

## 5. 読み辞書

読み辞書だけを更新するときはアプリの`versionCode`／build番号を変更しません。スキーマを変更する場合は、先に対応アプリを公開して普及を確認します。

```bash
python3 tools/publish_reading_update.py \
  --database app/src/main/assets/reading.db \
  --version <公開済みdataVersionより大きい値> \
  --database-url https://downloads.hanlu.app/furigana/reading-<version>.db \
  --private-key .secrets/reading-update-private.pem
```

データベースと署名を先にアップロードし、`manifest.json`を最後に公開します。秘密鍵を失うとアプリ更新なしでは鍵を変更できないため、安全な別保管先へバックアップします。スキーマ更新時の詳細な順序とロールバックはREADMEの「Schema rollout and rollback」に従います。

## 6. Aboutサイト

```bash
cd about
npm ci
npm audit --omit=dev
npm test
npm run lint
```

公開URLの環境変数、プライバシーポリシー、利用規約、ダウンロードリンクを確認し、`about/PUBLISHING.md`の手順で公開します。

## 7. 公開完了

1. ストア／配布URLから実際の成果物を取得してバージョンを再確認する。
2. 更新経路と新規インストールの両方を確認する。
3. 公開したコミットへ`v<versionName>`タグを付ける。タグは成果物の公開成功後に付け、移動・再利用しない。
4. 公開番号、URL、SHA-256、公開日時、既知の問題をリリース記録へ残す。
5. 段階公開中はクラッシュ、更新失敗、認識モデル取得失敗、サポート問い合わせを監視する。
