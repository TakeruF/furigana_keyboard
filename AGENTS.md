# リポジトリ作業指針

このファイルは、このリポジトリで変更、ビルド、リリース作業を行うエージェント向けの共通指針です。ユーザーから明示的な指示がある場合は、その指示を優先してください。

## バージョン表記

Semantic Versioningを基準にし、バージョン本体は`MAJOR.MINOR.PATCH`形式で管理します。プレリリースは`1.0.0-rc.1`、`1.0.0-beta.1`のように表記します。

用途ごとの表記を混同しないでください。

| 用途 | 例 | 規則 |
| --- | --- | --- |
| Android `versionName` | `1.0.0-rc.1` | `v`を付けないSemantic Versioning形式 |
| iOS `MARKETING_VERSION` | `1.0.0` | `v`を付けない。App Storeで許容される形式に従う |
| Gitタグ／GitHub Release | `v1.0.0-rc.1` | バージョン本体との識別のため先頭に`v`を付ける |
| APKファイル | `1.0.0-rc.1.apk` | `v`なしのバージョン本体を使用する |
| OSSオブジェクトURL | `https://downloads.hanlu.app/furigana-keyboard/1.0.0-rc.1.apk` | `furigana-keyboard/`配下にAPK名と一致する不変URLを置く |

このプロジェクトではAPK名を`<versionName>.apk`に統一し、OSSでは`furigana-keyboard/`ディレクトリに配置します。既存の公開契約やユーザー指定と異なる命名へ変更するときは、互換性と移行方法を先に確認してください。

## ビルド番号

- Androidの更新判定には`versionName`ではなく`versionCode`を使用します。
- iOSの更新・アップロード判定には`CURRENT_PROJECT_VERSION`を使用します。
- `versionCode`と`CURRENT_PROJECT_VERSION`は、公開またはストアへアップロードするたびに必ず増やします。
- プレリリースから正式版へ移る場合も番号を増やします。例: RC.1が`versionCode = 2`なら、正式版は3以上にします。
- 使用済みのビルド番号を、ロールバックや再ビルドで再利用しません。
- 公開済み最大値を確認できないときは推測で決めず、公開先、ストア、過去のリリース記録を確認します。それでも確認できない場合はユーザーへ確認します。

## Android直接配布

- `latest.apk`のような上書き型URLは更新配布に使用しません。
- APKはバージョンごとの不変オブジェクトとして公開します。
- `latest.json`だけを可変ポインタとして使用し、`downloadUrl`、`versionCode`、`versionName`、`sha256`を記録します。
- 公開順序は、APKのアップロード、公開URLからの取得とSHA-256検証、`latest.json`のアップロード、Aboutサイトの公開、の順とします。
- APKと`latest.json`には匿名`GetObject`を許可し、外部ネットワークからHTTP 200になることを確認します。
- `latest.json`は短時間キャッシュ、バージョン固定APKは長期キャッシュを基本とします。
- 生成物の`android-update-dist/`や署名鍵をGitへコミットしません。

## リリース時の同期

リリースでは次の値を同じバージョンへ揃えます。

- `app/build.gradle.kts`の`versionName`
- APKファイル名とOSS URL
- `latest.json`の`versionName`と`downloadUrl`
- Aboutサイトの既定ダウンロードURL
- `RELEASE_NOTES/`のファイル名と見出し
- Gitタグ名（バージョン本体に`v`を付加）

文字列を複数箇所で更新した場合は、古いバージョン名や旧URLが残っていないことを`rg`で確認します。

## 公開前後の確認

1. 公開済みの最大ビルド番号を確認する。
2. バージョンとリリースノートを更新する。
3. テスト、Lint、リリースビルドを実行する。
4. APK内のパッケージ名、`versionCode`、`versionName`、署名を検証する。
5. 公開ツールでAPKと`latest.json`を生成し、SHA-256を照合する。
6. APKを先に公開し、外部から取得できることを確認する。
7. `latest.json`を公開し、旧版の番号を指定して更新ありと判定されることを確認する。
8. Aboutサイトを公開し、リンク先がHTTP 200になることを確認する。
9. 公開成功後に`v<version>`タグを作成する。タグは移動・再利用しない。

詳細なコマンドとプラットフォーム別チェック項目は`RELEASING.md`に従ってください。
