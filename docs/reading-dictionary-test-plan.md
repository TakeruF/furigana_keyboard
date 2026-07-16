# core/full reading dictionary test plan

## 1. 目的と合格条件

この計画は、`reading.db` を core 同梱 + full 配信へ移行するときに、辞書生成、
変換品質、Android/iOS の選択・更新、旧版からの移行を一つの検証契約として扱う。
対象は生成物そのものと配布・切替経路であり、テストを通すための期待値緩和は
合格とみなさない。

リリース候補は、次の条件をすべて満たす必要がある。

1. ネットワークが一度も利用できない新規インストールでも、同梱 core でかな漢字
   変換できる。
2. 検証済み full の取得後は full が選択され、Android/iOS の N-best の surface、
   integer cost、順位が完全一致する。
3. 署名、HTTPS、サイズ、SHA-256、SQLite `integrity_check`、schema、metadata の
   いずれかが不正なら active は変更されず、直前の有効な full または core が選ばれる。
4. 旧版の同梱完全DBまたは旧形式の active full を持つ利用者は、更新直後から core
   より full を優先し続ける。ネットワーク失敗時にも品質を落とさない。
5. 同一の固定入力、生成コード、SQLite toolchain から生成した core/full はバイト単位で
   再現し、生成 metadata と SHA-256 も一致する。
6. core は助詞・助動詞、基本活用、品質fixtureを成立させる語彙、口語縮約を保持し、
   size budget 内に収まる。full は core の候補とスコアを変更しない上位集合である。

## 2. テスト対象と信頼境界

| 層 | 主な対象 | 検証する境界 |
| --- | --- | --- |
| tools | `tools/build_reading_db.py`、core 選定、配布物生成 | 固定入力から決定的な core/full と署名対象 manifest を作る |
| shared fixtures | 語彙要件、自然文品質、exact N-best | OSや生成器から独立した期待品質と Android/iOS 共通順位 |
| Android | `ReadingDataStore`、`ReadingDataUpdater`、起動時初回取得 | core fallback、full 切替、旧DB移行、全失敗点での復帰 |
| iOS | `ReadingUpdateSupport`、親アプリ updater、App Group | 親アプリだけの取得、拡張からの読取り、原子的切替、旧DB移行 |
| release | 実サイズの core/full とパッケージ済みアプリ | 小型fixtureでは見えない容量、コピー、メモリ、署名、同梱物の誤り |

品質fixtureを生成器の入力に直接使って期待結果を作ってはならない。core の包含要件は、
人がレビューする語彙契約へ明示的に転記する。品質fixtureは生成後の独立した oracle として
実行し、循環した「テストに合わせた辞書」を防ぐ。

## 3. 追加・拡張する共有fixture

### 3.1 必須語彙fixture

新規 `fixtures/reading-core-required-lexemes.tsv` を追加する。最低限、次の列を持つ。

```text
id  reading  surface  left_id  right_id  max_word_cost  form_kind  required_profiles  reason
```

- `required_profiles` は常に `core,full` とする。full 専用の行は別fixtureに分離する。
- `left_id`、`right_id`、`word_cost`、`form_rank`、`source` を SQL で照合する。
- 同じ `(reading, surface, left_id, right_id)` が core/full にある場合、全スコア列は一致を
  必須とする。
- 助詞は少なくとも `は が を に で と の へ も や か ね よ`、助動詞は
  `た だ です ます ない たい` を固定する。
- 一段・五段・行く特殊・する・来る・形容詞について、基本形、て形、過去、否定、丁寧、
  条件、現在実装が生成する可能・易さの各形を代表語で固定する。
- 口語縮約は生成規則の種類ごとに固定する。少なくとも `ている→てる`、
  `でいる→でる` を含める。単なる文字列結合ではなく、元の動詞の surface/reading と
  POS 接続を保持していることを検証する。

最初に固定する縮約行の例:

| reading | 必須surface | 意図 |
| --- | --- | --- |
| `いってる` | `行ってる`、`言ってる` | 「行く」特殊活用と「言う」の同音候補を失わない |
| `よんでる` | `読んでる` | 撥音便 + `でいる→でる` |
| `かいてる` | `書いてる` | イ音便 + `ている→てる` |
| `みてる` | `見てる` | 一段活用 + `ている→てる` |
| `してる` | `してる` | する活用 |
| `きてる` | `来てる` | 来る特殊活用 |

### 3.2 自然文品質fixture

既存 `fixtures/sentence-conversion-regression.json` を自然言語上の品質契約として継続利用し、
profile 別の基準を持てる schema 2 へ計画的に移す。期待する日本語は core/full で共有し、
実測baselineだけを `core` と `full` に分ける。

追加する「口語縮約」カテゴリの最小ケースは次のとおり。

| id | reading | expectedTop1 | requiredNBest | forbiddenCandidates |
| --- | --- | --- | --- | --- |
| colloquial-01 | `がっこうにいってる` | `学校に行ってる` | 同左 | `学校に言ってる` |
| colloquial-02 | `そういってる` | `そう言ってる` | 同左 | `そう行ってる` |
| colloquial-03 | `ほんをよんでる` | `本を読んでる` | 同左 | `本を呼んでる` |
| colloquial-04 | `しゃしんをとってる` | `写真を撮ってる` | `写真を撮ってる` | `写真を取ってる` |
| colloquial-05 | `なにをみてるの` | `何を見てるの` | 同左 | なし |
| colloquial-06 | `あめがふってる` | `雨が降ってる` | 同左 | `飴が振ってる` |

単独入力 `いってる` は意味が曖昧なため、恣意的な Top-1 を自然文契約にしない。
`行ってる` と `言ってる` の双方を Top 8 に必須とし、exact順位は後述の parity snapshot
で固定する。これにより曖昧性を隠す期待値変更を避ける。

既存57件は削除せず、従来baselineで合格していたIDが core/full のどちらでも失敗したら
regression とする。full は少なくとも現行完全DBの全baseline-passを維持する。core は
同じ自然期待を評価し、core化のために `expectedTop1` を `null` にしたり、
`requiredNBest` を削ったりしてはならない。

### 3.3 exact N-best parity fixture

新規に次を用意する。

- `fixtures/conversion-nbest-core.json`
- `fixtures/conversion-nbest-full.json`
- `fixtures/context-conversion-nbest-core.json`
- `fixtures/context-conversion-nbest-full.json`

各fixtureは `profile`、`databaseSha256`、`schemaVersion`、`scoringVersion`、
`contextModelSha256`、生成元commitをヘッダに持ち、各入力について最大8件の
`surface` と符号付き整数 `cost` を順序込みで固定する。縮約6文と単独 `いってる` を
必須対象にする。

このsnapshotは自然文品質fixtureの代わりではない。Android/iOSの完全一致を検知する
機械的契約であり、snapshot更新後も自然文品質ゲートを独立に通す。

## 4. tools テスト

### 4.1 生成器unit test

`tools/tests/test_build_reading_db.py` を profile 対応へ拡張し、最小JMdict fixtureから
次を確認する。

1. core/full が同一schema、`conversion_pos`、`connection_cost` を持つ。
2. 辞書内容テーブルで、full が core 行の主キー単位の上位集合になっている。
   `metadata` のようにprofile固有値を持つテーブルは対象外とし、共通keyの値だけを比較する。
3. core/full 共通lexemeの `word_cost`、`form_rank`、`source`、左右POSが一致する。
4. 助詞・助動詞が候補数上限処理で脱落せず、歴史的表記より現代かな表記が低コストである。
5. 各活用クラスから `〜てる/〜でる` が決定的に生成され、`行ってる` と `言ってる` の
   片方を重複排除で落とさない。
6. 縮約の `form_rank` は基本形・標準的な活用より優先しないが、未知語経路より優先する。
7. core 選定の tie-break は明記した全列で安定し、入力XML順を逆転しても結果が同じになる。

### 4.2 実入力の再現性test

固定SHA-256の KANJIDIC/JMdict/JMnedict/model archive を使い、隔離した2ディレクトリで
core/full を各2回生成する。以下を比較する。

- DBファイルのバイト列とSHA-256
- `sqlite3 .dump` の正規化結果
- 全metadata行
- table/index/schema SQL
- tableごとの row count と主キー順content digest
- SQLite page size、page count、freelist count

SQLite CLI は生成器が要求する3.50.xをCI imageで固定する。異なるSQLite版でのバイト一致を
要求するのではなく、版が違えば生成を失敗させる。タイムスタンプ、絶対path、ランダム値を
metadataへ入れない。

metadataには少なくとも次を要求する。

```text
schema_version, profile, data_version, generator_version, sqlite_version,
kanjidic_date, kanjidic_sha256, jmdict_sha256, jmnedict_sha256,
model_archive_sha256, selection_policy_version, quality_contract_sha256,
conversion_lexemes, connection_costs, database_bytes
```

`quality_contract_sha256` はテストした語彙契約の識別子であり、生成器が品質期待値を読んで
順位を作ったことを意味しない。

### 4.3 profile構造・容量test

- `PRAGMA integrity_check` が両DBで厳密に `ok`。
- metadata schema/profile がファイル名と一致。
- coreの全必須語彙をfixtureでSQL照合。
- fullにcoreの全候補が同一スコアで存在。
- core/fullとも空DBや急激な行数減少を拒否する下限を持つ。
- coreの圧縮前サイズ、APK圧縮後サイズ、iOS app/appex内サイズを記録する。
- size上限は最初の承認済みcore測定値に余裕率を加えて固定し、上限を超えたら自動で
  引き上げず、行数・カテゴリ別寄与をレポートする。

### 4.4 manifest・署名test

`tools/tests/test_publish_reading_update.py` を追加し、一時生成したP-256鍵で以下を検証する。
秘密鍵と生成物はtemp directoryだけに置く。

- fullだけを配布対象として受け付け、`profile=core` を拒否する。
- manifestのcanonical JSON bytesに対するdetached署名を公開鍵で検証できる。
- manifestを1 byte変更、署名を変更、DBを1 byte変更した場合にそれぞれ失敗する。
- HTTPS以外、redirect先HTTP、過大size、schema/profile/dataVersion不整合を拒否する。
- manifestのsize/SHA-256/metadataが実DBと一致する。
- publisherがDB全体を一度にメモリへ読まず、streaming hash/copyすることを大容量fixtureで
  検証する。

## 5. Android テスト

### 5.1 JVM/Robolectric

`ReadingDataStoreTest` を次の状態表で拡張する。各行で選択ファイルのcanonical path、
profile、full versionを検証する。

| 状態 | 期待する選択 |
| --- | --- |
| 新規、ネットワーク未実行 | core |
| 新規、full取得成功 | full |
| valid active full + core | active full |
| corrupt/schema不一致 active full + valid legacy full | legacy full |
| corrupt active/legacy + core | core |
| 旧active記録にprofileなし + valid DB | 旧DBをfullとして選択 |
| unsafe filename/ディレクトリ外参照 | 拒否して次のvalid DBまたはcore |

core fixture DBを実際のrepositoryで開き、ネットワークmockを一切登録しない状態で、
最低限 `これはぺんです`、`ほんをよんでいる`、`がっこうにいってる` を変換する。
「ファイルが選べた」だけで初回オフライン合格にしない。

### 5.2 updater integration

HTTP transport、clock、filesystem activationを注入可能にし、MockWebServerまたはfixture
transportで次を実行する。

1. 正しい署名manifest + fullを取得し、download中はcore、検証・rename・active commit後は
   fullになる。
2. manifest取得不能、timeout、HTTPエラー、redirect、TLS/HTTPS違反。
3. 署名不正、未知format、minAppVersion不一致、schema不一致。
4. DBの短い/長いbody、SHA不一致、SQLite破損、metadata欠落、profile不一致。
5. write、`fsync`、rename、active preference commitの各失敗点。
6. 同一versionの壊れたtargetがある場合、検証済みstagingで回復する。
7. 2更新の競合時に、古いversionが新しいactiveを上書きしない。

各失敗ケースで次の4項目を必ずassertする。

- `active` が以前の値から変わらない。
- 新しいreaderは旧full、なければcoreを開ける。
- 旧DBを開いていたreaderは最後まで同じ内容を読める。
- `.tmp` は掃除されるがvalidな旧fullは削除されない。

### 5.3 旧版移行instrumentation

旧APK相当のfixture状態を作り、更新後コードを起動する連続テストを用意する。

- 旧版が同梱fullを `reading-v8.db` としてno-backup領域へinstall済み。
- 旧版がdownload済みfullとprofileなしpreferencesを持つ。
- 旧版fullをプロセスがread-onlyで開いている最中に移行する。
- 移行直後から機内モードで起動する。
- 移行元のhash不一致・schema不一致・コピー/rename失敗。

成功時はfullのSHA-256とN-best digestが移行前後で同一、失敗時は移行元を破壊せずcoreが
使えることを確認する。端末再起動相当のprocess再生成後も同じ選択になることを確認する。

IME serviceからupdater/network APIが呼ばれないこともテストする。初回取得をscheduleする
入口は親アプリのApplication/Activityだけとし、keyboard service起動テストではnetwork
transportの呼出し回数が0でなければ失敗させる。

## 6. iOS XCTest

### 6.1 shared selectionと初回オフライン

`ReadingUpdateSupportTests` に一時Bundleと一時App Group directoryを渡し、Androidと同じ
状態表を実行する。coreを `reading-core.db` としてbundleへ置き、次を追加する。

- active/legacy fullがない場合はcoreを選択。
- active JSONの欠落、途中書込み、unsafe filename、schema不一致、SQLite破損でcoreへ戻る。
- profileなしの旧activeはfullとして維持。
- valid legacy fullはactive JSONがなくてもcoreより優先。
- coreをproduction `ReadingRepository` で開き、オフライン3文を実変換。

### 6.2 親アプリupdater

`URLSessionConfiguration.protocolClasses`、App Group URL、Bundle、file operation、activationを
注入し、Android updater integrationと同じ成功・失敗matrixを実行する。大容量DBについては
RSSの不安定な閾値だけに依存せず、hasher/copy処理へ観測可能なchunk readerを注入して、
最大read requestが1 MiB以下、`Data(contentsOf:)` 相当の全読込みが0回であることをassertする。

原子的切替は次のbarrier testで確認する。

1. 拡張相当readerが旧fullを開く。
2. updaterをstaging検証後・active更新前で停止する。
3. 新規readerがまだ旧fullを選ぶことを確認する。
4. rename/replaceを行い、active JSON更新前後の各時点でDBが全体として開けることを確認する。
5. active JSON更新後の新規readerはnew full、既存readerはold fullを最後まで読める。

`replaceItemAt`、active JSONのatomic write、staging cleanupへ個別にfaultを注入し、どの失敗でも
旧fullまたはcoreを開けることを確認する。

### 6.3 旧完全DBとApp Group

- 旧appexにある既知SHA-256のfullを、一括メモリ読込みなしでApp Groupへ保存する。
- 保存途中の強制終了ではstagingだけが残り、次回起動で再試行できる。
- 保存済みlegacy fullがある場合は再コピーしない。
- 新版bundleの `reading.db` がcoreへ変わっていて既知hashと違う場合、legacy fullとして採用しない。
- 既存active fullまたは保存済みlegacy fullは、新版coreより常に優先する。
- App Group容量不足時は既存ファイルを削除せずcoreへ戻る。

親アプリtargetだけに `URLSession`、manifest URL、公開鍵、`ReadingDataUpdater` が含まれ、
extension targetには含まれないことを、生成済みXcode build settings/source listとバイナリ文字列の
両方でCI検査する。拡張の `RequestsOpenAccess` もfalseを固定する。

## 7. Android/iOS共通品質・parity実行

core/fullそれぞれについて、同一DB bytesと同一context modelを両OSへ渡す。各platform testは
fixtureごとに次のJSONLをartifactとして出力する。

```json
{"profile":"core","id":"colloquial-01","results":[{"surface":"学校に行ってる","cost":1234}]}
```

CIのcompare jobで、正規化せずbyte-for-byte比較する。比較対象はケース順、最大8件のsurface、
cost、順位、DB SHA-256、context model SHA-256、scoring versionである。Unicode normalizationを
テスト側で行うと実装差を隠すため禁止する。

さらに各OS内で以下を確認する。

- production beam幅12とreference beam幅64のTop-1が一致。
- limit 1の結果がlimit 8の先頭と一致。
- core/full共通lexemeだけで成立するケースは、両profileで同じcostと順位になる。
- fullで既存完全DBbaseline-passが0件もregressしない。
- coreのカテゴリ別Top-1/Top-3/N-bestは承認済みcore baselineを下回らない。
- forbidden候補数はbaselineより増えない。

## 8. 期待値変更禁止ルール

テスト失敗を理由に期待値を実装出力へコピーする変更は禁止する。具体的には次を禁止する。

- `expectedTop1` を `null` にする、Top-1期待をrequired N-bestへ降格する。
- `requiredNBest` やforbidden候補を削除する。
- 失敗IDを `baselineKnownFailureIds` に追加してgreenにする。
- core/full snapshotを無条件に上書きする。
- size上限、許容順位、メモリ上限を失敗した値の直上へ動かす。
- AndroidとiOSで別々の期待値を持つ。

期待値変更が正当になり得るのは、表記誤り、fixture schema変更、明示的な仕様変更のみとする。
その場合も次を必須にする。

1. 辞書・scoring実装PRとは分けるか、少なくとも独立commitにする。
2. 変更理由、旧結果、新結果、自然言語上の根拠、影響する全ケースをレビュー資料に記録する。
3. `baselineKnownFailureIds` は解消時に削除できるが、追加は品質劣化としてissueと承認を要する。
4. snapshot更新コマンドは通常はdiffを出すだけにし、`--accept` を明示した場合だけ書き込む。
5. snapshot更新前に自然文品質ゲートを実行し、baseline-pass regressionが0であることを確認する。
6. fixture変更を検知したCIは、変更前後のN-best、カテゴリ別指標、DB SHA-256をartifactに残す。

## 9. CIとリリースゲート

| 頻度 | 必須テスト | 備考 |
| --- | --- | --- |
| 全PR | tools unit、語彙fixture、Android JVM、iOS XCTest、core/full parity snapshot | 小型fixture中心、署名鍵は毎回temp生成 |
| 辞書/生成器変更PR | 実入力2回生成、実DB品質57+6件、profile上位集合、size report | source hash固定、生成物はartifactのみ |
| nightly | Android instrumentation、iOS simulator fault matrix、cross-job JSONL比較 | crash pointを分割実行 |
| release候補 | 実パッケージの初回機内モード、full取得、再起動、旧版からの上書き更新 | Android/iOS各1台以上の実機を含む |

推奨コマンドの入口は次に統一する。

```sh
python3 -m unittest discover -s tools/tests -v
./gradlew :app:testPlayDebugUnitTest :app:testDirectDebugUnitTest
./gradlew :app:connectedPlayDebugAndroidTest
cd ios && xcodegen generate
xcodebuild -project FuriganaKeyboard.xcodeproj -scheme FuriganaKeyboard \
  -configuration Debug -destination 'platform=iOS Simulator,id=<UDID>' \
  -derivedDataPath DerivedData/ReadingDictionaryTests CODE_SIGNING_ALLOWED=NO test
```

release候補では、パッケージから取り出したcoreと配布予定fullのSHA-256を、manifestおよび
生成metadataと照合する。ソースツリー上のDBを検査しただけでは合格にしない。

## 10. 実装順序

1. fixture schema、期待値変更規約、縮約6文と単独`いってる`を先にレビューする。
2. toolsに縮約生成unit testとcore/full上位集合・再現性testを追加し、失敗する状態を確認する。
3. core/full生成を実装し、語彙・構造・size testを通す。
4. Android/iOSのrepositoryをprofile指定で同じ共有fixtureへ接続する。
5. updaterへtransport/filesystem/activationのfault injection seamを追加し、成功・失敗matrixを実装する。
6. 旧版パッケージからの連続移行testを追加する。
7. cross-platform JSONL compareと実パッケージrelease gateをCIへ追加する。

この順序では、自然な期待値を実装前に固定する。実装結果に合わせて「いってる」等の期待を
後から弱める余地を作らず、coreサイズ最適化と品質維持を別々に判定できる。
