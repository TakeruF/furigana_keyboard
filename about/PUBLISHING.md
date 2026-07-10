# Aboutサイトの公開・カスタムドメイン設定ガイド

この文書は、このリポジトリ内の `about/` を OpenAI Sites に公開し、
`keyboard.hanlu.app` で閲覧できるようにするための手順書です。

## 結論

- Android・iOSのソースとAboutサイトが同じリポジトリにあっても問題ありません。
- Webサイトとしてビルド・公開する対象は `about/` だけです。
- Sitesのプロジェクト情報は `about/.openai/hosting.json` にすでに保存されています。
- `keyboard.hanlu.app` はサブドメインなので、Sitesが発行する **CNAME** と、必要に応じて
  ドメイン所有確認用レコードをDNSに登録します。
- CNAMEの値や検証レコードは公開環境ごとに発行されます。推測して入力せず、Sitesが返した
  値をそのまま使用してください。

## 現在の状態

2026年7月11日の確認時点では、次の状態です。

| 項目 | 状態 |
| --- | --- |
| Sitesプロジェクト | 作成済み |
| Sites標準URL | Sitesの管理画面または公開結果で確認 |
| 公開バージョン | デプロイ済み |
| 閲覧権限 | 所有者のみ |
| `keyboard.hanlu.app` | 未登録 |

一般公開するには、サイトの閲覧権限を **public** に変更する必要があります。カスタムドメインを
設定しただけでは、所有者限定のアクセス設定は解除されません。

## 構成と役割

```text
repository-root/
├── app/                         # Androidアプリ
├── ios/                         # iOSアプリ
└── about/                       # 公開対象のAboutサイト
    ├── .openai/hosting.json     # Sitesプロジェクトとの紐付け
    ├── app/                     # ページ本体
    ├── package.json             # ビルド・テスト用コマンド
    └── PUBLISHING.md            # このガイド
```

`about/.openai/hosting.json` の `project_id` は既存サイトを識別する値です。新しいサイトを
作り直したり、この値を手作業で変更したりしないでください。また、公開用の環境変数や秘密値を
このファイルへ追加しないでください。

## 1. 公開前のローカル確認

リポジトリのルートから次を実行します。

```bash
cd about
npm ci
npm run lint
npm test
```

`npm test` はプロダクションビルドと、生成されたHTMLのテストを実行します。すべて成功してから
公開してください。

必要に応じて、ローカル表示も確認できます。

```bash
cd about
npm run dev
```

表示されたローカルURLをブラウザで開き、少なくとも次を確認します。

- `/ja`、`/zh`、`/en`、`/ko` が表示できる
- `/` から日本語ページへ移動する
- ダウンロード欄、機能紹介、About欄が正しく表示される
- 中国語では Noto Sans SC、韓国語では Noto Sans KR が適用される
- 画面幅を狭くしても文字やボタンがはみ出さない

## 2. ダウンロードリンク用の環境変数

Aboutサイトは次の環境変数を参照します。

| 変数名 | 用途 | 未設定時 |
| --- | --- | --- |
| `NEXT_PUBLIC_ANDROID_APK_URL` | APKまたは配布ページのURL | 「準備中」 |
| `NEXT_PUBLIC_APP_STORE_URL` | App Storeの製品URL | 「準備中」 |

URLがまだ決まっていなければ、未設定のまま公開できます。設定する場合は、Sitesの本番環境変数
として登録します。`.env` や `hosting.json` へ本番値をコミットしないでください。

Codexへ依頼する場合の例：

```text
aboutサイトのSites本番環境変数を更新して。
NEXT_PUBLIC_ANDROID_APK_URL=https://...
NEXT_PUBLIC_APP_STORE_URL=https://...
変更後、現在のaboutサイトを再公開して。
```

環境変数の更新は、次回のデプロイで反映されます。

## 3. Aboutサイトを公開する

### 推奨手順

Sitesへの保存・デプロイには、ローカルビルドだけでなくSites側のバージョン作成処理が必要です。
このリポジトリを開いたCodexへ、次のように依頼してください。

```text
about/ をビルド・検証し、既存のSitesプロジェクトへ新しいバージョンとして公開して。
公開後にデプロイ完了まで確認して。AndroidとiOSのディレクトリは公開対象に含めないで。
```

Codexは `about/.openai/hosting.json` の既存 `project_id` を使用します。新規サイトの作成を
依頼する必要はありません。

### 公開の内部的な流れ

1. `about/` の依存関係を固定されたロックファイルから復元する
2. lint、テスト、プロダクションビルドを実行する
3. 検証済みのソースだけをSitesのソースリポジトリへ送る
4. そのソースと同じコミットから公開用アーカイブを作る
5. Sitesに新しいバージョンを保存する
6. 保存済みバージョンを本番へデプロイする
7. デプロイが `succeeded` になるまで状態を確認する

GitHubの `main` ブランチへpushしただけではSitesの本番ページは自動更新されません。Sitesへの
バージョン保存とデプロイも必要です。

## 4. 一般公開へ切り替える

現在のサイトは所有者限定です。誰でも `keyboard.hanlu.app` を開けるようにするには、公開範囲を
明示的に変更します。

Codexへの依頼例：

```text
既存のFurigana KeyboardのSitesプロジェクトを、誰でも閲覧できるpublic設定に変更して。
変更後、現在のアクセス設定を確認して。
```

これはアクセス範囲を広げる操作です。実行前に、掲載内容、ダウンロード先、ソースコードへの
外部リンクに問題がないことを確認してください。

## 5. `keyboard.hanlu.app` をSitesへ登録する

サイトが本番デプロイ済みであることを確認してから、カスタムドメインを追加します。

Codexへの依頼例：

```text
既存のFurigana KeyboardのSitesプロジェクトに keyboard.hanlu.app をカスタムドメインとして追加して。
DNSに登録すべきCNAMEと検証レコードを、値を省略せず表で示して。まだDNS変更はしないで。
```

Sitesから、通常は次の情報が返ります。

| 種別 | 内容 |
| --- | --- |
| CNAME target | `keyboard.hanlu.app` の接続先 |
| Validation records | 所有権・証明書発行などに必要なDNSレコード |
| Status | `pending`、`active`、または `failed` |
| SSL status | HTTPS証明書の状態 |

ここで表示された値を保存し、次のDNS設定に使用します。

## 6. DNSへレコードを追加する

`hanlu.app` を管理しているDNSサービスの管理画面を開きます。Cloudflare、Route 53、
お名前.comなど、どのサービスでも基本的な考え方は同じです。

### CNAME

Sitesが返したCNAMEを、次の形で登録します。

| DNS項目 | 入力内容 |
| --- | --- |
| Type | `CNAME` |
| Name / Host | `keyboard` |
| Target / Value | Sitesが返した `cname_target` |
| TTL | `Auto` または既定値 |

DNSサービスによってはName欄へ完全な `keyboard.hanlu.app` を入力します。管理画面が
`hanlu.app` を自動補完する場合は `keyboard` だけを入力してください。

### 検証レコード

Sitesが `validation_records` を返した場合は、**すべて**追加します。

| DNS項目 | 入力内容 |
| --- | --- |
| Type | Sitesが返した `record_type` |
| Name / Host | Sitesが返した `name` |
| Target / Value | Sitesが返した `value` |
| TTL | `Auto` または既定値 |

レコード名や値の末尾にドットが付く場合、その扱いはDNSサービスによって異なります。管理画面が
末尾のドットを自動調整する場合は、その仕様に従ってください。

Cloudflareの「プロキシ」切り替えが表示される場合は、検証が完了するまでは **DNS only** を
選ぶのが安全です。`active` になった後も、Sitesから特別な指定がなければDNS onlyのまま運用
できます。

### 既存レコードとの競合

同じ `keyboard` という名前に既存のA、AAAA、CNAMEレコードがある場合、CNAMEと競合します。
古い接続先が不要か確認してから削除または置換してください。MXやTXTなど用途の異なるレコードは、
Sitesから削除指示がない限り勝手に消さないでください。

## 7. DNSとSSLの反映を確認する

DNSの反映には時間がかかることがあります。ローカルでは次のコマンドで確認できます。

```bash
dig CNAME keyboard.hanlu.app +short
dig TXT keyboard.hanlu.app +short
```

検証レコードが別名を指定している場合は、Sitesが返した完全なレコード名を `dig` に渡します。

DNS登録後、Codexへ状態更新を依頼します。

```text
keyboard.hanlu.app のカスタムドメイン状態を再確認して。
status、SSL status、last errorを教えて。activeになるまで必要な修正があれば特定して。
```

完了条件は次のとおりです。

- カスタムドメインの状態が `active`
- SSLの状態が正常
- `https://keyboard.hanlu.app` が証明書エラーなしで開く
- `/ja`、`/zh`、`/en`、`/ko` がカスタムドメイン上でも表示できる

コマンドでも最終確認できます。

```bash
curl -I https://keyboard.hanlu.app
curl -I https://keyboard.hanlu.app/ja
curl -I https://keyboard.hanlu.app/zh
curl -I https://keyboard.hanlu.app/en
curl -I https://keyboard.hanlu.app/ko
```

HTTP `200` または意図したリダイレクトが返り、TLSエラーがなければ接続は正常です。

## 8. 以後の更新公開

ページを修正した場合は、毎回次の順で更新します。

1. `about/` の変更をレビューする
2. `npm run lint` と `npm test` を実行する
3. 変更を適切な単位でコミットし、GitHubへpushする
4. 既存Sitesプロジェクトへ新しいバージョンを保存・デプロイする
5. Sites標準URLと `https://keyboard.hanlu.app` の両方を確認する

一度カスタムドメインが `active` になれば、通常は更新のたびにDNSを変更する必要はありません。

## 切り戻し

新しい公開内容に問題があった場合は、ソースを破壊的に巻き戻すのではなく、正常だったコミットの
内容を再び新しいSitesバージョンとして保存・デプロイします。

Codexへの依頼例：

```text
Aboutサイトの直前の正常版を確認し、その内容を新しいSitesバージョンとして再公開して。
現在のmainの未コミット変更は変更しないで。
```

カスタムドメイン自体を削除するのは、ドメインをSitesから完全に切り離したい場合だけです。
一時的なページ不具合への対応としてDNSレコードを消すと、復旧時に再検証が必要になる可能性が
あるため避けてください。

## トラブルシューティング

### `keyboard.hanlu.app` が名前解決できない

- CNAMEのNameが `keyboard` になっているか確認する
- TargetにSitesの `cname_target` を正確に入力したか確認する
- 値に `https://` やパスを付けていないか確認する
- DNS管理先が実際のネームサーバーと一致しているか確認する

### 状態が `pending` のまま

- Sitesが返した検証レコードをすべて登録したか確認する
- レコード名へ `hanlu.app` が二重付加されていないか確認する
- Cloudflareプロキシを一時的にDNS onlyへ変更する
- TTL分待ってから、Sites側の状態を再取得する

### 状態が `failed`

- Sitesの `last_error` を確認する
- 既存のA、AAAA、CNAMEとの競合を確認する
- CAAレコードで証明書発行元が制限されていないか確認する
- 推測でレコードを増やさず、エラーが要求している修正だけを行う

### ドメインは開くがログインを求められる

DNSではなくSitesのアクセス設定が原因です。アクセスモードを `public` に変更してください。

### 標準URLは更新されたがカスタムドメインは古い表示のまま

- ブラウザのキャッシュを無効にして再読み込みする
- CDNやDNSサービスで追加キャッシュを設定していないか確認する
- カスタムドメインが現在のSitesプロジェクトへ紐付いているか確認する

## 公開前チェックリスト

- [ ] `cd about && npm ci && npm run lint && npm test` が成功した
- [ ] 4言語のページを確認した
- [ ] 掲載内容と外部リンクを確認した
- [ ] APK・App Store URLの設定方針を確認した
- [ ] 既存のSitesプロジェクトを再利用した
- [ ] 新しいSitesバージョンのデプロイが成功した
- [ ] アクセス設定を `public` にした
- [ ] `keyboard.hanlu.app` をSitesへ追加した
- [ ] Sitesが返したCNAMEと検証レコードをDNSへ登録した
- [ ] カスタムドメインとSSLが `active` になった
- [ ] 4言語のURLをカスタムドメイン上で確認した
