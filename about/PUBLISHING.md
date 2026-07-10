# Furigana KeyboardサイトをEdgeOneで公開する手順

この文書は、既にEdgeOneで運用している`hanlu.app`を変更せず、このリポジトリの
`about/`を別サイトとして`https://keyboard.hanlu.app`へ公開するための手順書です。

確認日: 2026-07-11

## 結論

推奨構成は次のとおりです。

```text
hanluリポジトリ
└─ 既存のEdgeOneプロジェクト
   ├─ hanlu.app
   └─ dict.hanlu.app

furigana keyboardリポジトリ
└─ 新しいEdgeOne Makersプロジェクト
   ├─ Root Directory: about
   └─ Custom Domain: keyboard.hanlu.app

hanlu.appの既存DNSゾーン
├─ @ / www / dict ...           # 変更しない
└─ keyboard CNAME               # 新しいレコードだけ追加
```

`hanlu.app`本体とFurigana Keyboardを別のEdgeOneプロジェクトにする理由は次のとおりです。

- 本体の認証、middleware、デプロイに影響を与えない
- Aboutサイトだけを独立して更新・切り戻しできる
- EdgeOneはGitリポジトリ内のRoot Directoryを指定できるため、`about/`だけをビルドできる
- `hanlu.app`のネームサーバーや既存ドメイン設定を変更せず、`keyboard`レコードだけ追加できる

`keyboard.hanlu.app`を既存のHanlu本体プロジェクトへ直接追加する方法は推奨しません。
現在のHanlu本体はホスト名によるルーティングとSupabase認証を持つため、専用のhost rewriteと
認証除外を実装しないままドメインを追加すると、Hanlu本体やログイン画面が表示される可能性が
あります。

## 現在のビルド構成

`about/`の既定ビルドはEdgeOne向けの標準Next.jsへ移行済みです。

- `npm run dev`: `next dev`
- `npm run build`: `next build`
- `npm run start`: `next start`
- ビルド出力: `.next/`
- 旧Sites用ビルド: `npm run build:sites`

EdgeOne MakersはNext.js 13.5以降のApp Router、SSR、SSG、React Server Componentsを
サポートし、標準設定は次のとおりです。

| 項目 | 設定値 |
| --- | --- |
| Framework Preset | `Next.js` |
| Root Directory | `about` |
| Install Command | `npm ci` |
| Build Command | `npm run build` |
| Output Directory | `.next` |
| Node.js | `22.17.1` |
| Production Branch | `main` |

公式資料:

- [EdgeOne Makers Build Guide](https://pages.edgeone.ai/document/build-guide)
- [EdgeOne Makers Next.js Guide](https://pages.edgeone.ai/document/framework-nextjs)

### EdgeOne対応済みの内容

- `package.json`の既定ビルドを`next build`へ変更
- Cloudflare/Vinext固有処理をEdgeOneの本番経路から分離
- `/ja`、`/zh`、`/en`、`/ko`を標準Next.jsで配信
- `/`から`/ja`への307リダイレクトをNext.jsページで実装
- HTMLテストを実際の`next start`サーバーに対する検証へ変更
- Turbopackのルートを`about/`へ固定

`.openai/hosting.json`は、旧Sitesプロジェクトをすぐ切り戻せるよう、EdgeOneの公開確認が終わる
までは削除しません。EdgeOneのAPIトークンや秘密値をこのファイルへ追加しないでください。

## 1. 公開前のローカル確認

リポジトリのルートから実行します。

```bash
cd about
npm ci
npm run lint
npm test
```

追加で標準Next.jsビルドを確認します。

```bash
cd about
npm run build
```

少なくとも次を確認してください。

- `/`が`/ja`へ移動する
- `/ja`、`/zh`、`/en`、`/ko`が表示できる
- 各ページのcanonical URLが`keyboard.hanlu.app`を指す
- 中国語にNoto Sans SC、韓国語にNoto Sans KRが適用される
- `support@hanlu.app`以外の不要な問い合わせ先が表示されない
- GitHubリポジトリ名やローカルパスがHTMLへ含まれない
- APK・App StoreのURLが未設定なら「準備中」になる

## 2. EdgeOne Makersで新しいプロジェクトを作る

EdgeOneコンソールで、Hanlu本体とは別のMakersプロジェクトを作成します。

1. EdgeOne Makersを開く
2. `Create Project`または`Import Git Repository`を選ぶ
3. GitHub連携からFurigana Keyboardのリポジトリを選ぶ
4. プロジェクト名を`furigana-keyboard-web`など、Hanlu本体と区別できる名前にする
5. Production Branchを`main`にする
6. Framework Presetを`Next.js`にする
7. Root Directoryを`about`にする
8. Node.jsを`22.17.1`にする
9. Install Commandを`npm ci`にする
10. Build Commandを`npm run build`にする
11. Output Directoryを`.next`にする
12. 最初のデプロイを実行する

EdgeOneはRoot Directoryをビルドコマンドの実行場所として扱います。したがって、Androidの
`app/`、iOSの`ios/`、署名情報などはAboutサイトのビルド対象になりません。

最初のデプロイではまだ`keyboard.hanlu.app`を付けず、EdgeOneが発行する標準ドメインで
4言語とリンクを確認してください。

## 3. 環境変数を設定する

Aboutサイトは次の公開環境変数を使用します。

| 変数 | 用途 | 未設定時 |
| --- | --- | --- |
| `NEXT_PUBLIC_ANDROID_APK_URL` | APKまたはAndroid配布ページ | 準備中 |
| `NEXT_PUBLIC_APP_STORE_URL` | App Store製品ページ | 準備中 |

EdgeOneのProject Settings → Environment Variablesで設定し、設定後に新しいデプロイを
実行します。EdgeOneでは環境変数の変更は過去のデプロイへ遡って反映されません。

### APKをAboutサイトへ直接含めない

現在の署名済みRelease APKは約46MBです。EdgeOne Makersには単一ファイル25MiBの上限が
あるため、APKを`about/public/`へコピーしてはいけません。

また、GitHub ReleasesのURLをそのまま使うと、利用者へリポジトリ名が見える場合があります。
リポジトリ情報を公開したくない場合は、次のいずれかを使います。

- Tencent Cloud COSなどのオブジェクトストレージ
- `downloads.hanlu.app`のような配布専用ドメイン
- Google Playの配布ページ

Aboutサイトには配布先URLだけを環境変数として渡します。APKの署名鍵、APIトークン、
ストレージ秘密鍵を`NEXT_PUBLIC_*`へ設定してはいけません。

## 4. `keyboard.hanlu.app`を追加する

標準ドメインでデプロイを確認した後、新しいFurigana Keyboardプロジェクトの
Domain Managementを開きます。

1. `Add Custom Domain`を押す
2. `keyboard.hanlu.app`を入力する
3. 関連付け先を`Production`にする
4. 所有権確認レコードが表示された場合は、その値を保存する
5. EdgeOneが指定したCNAME targetを保存する

EdgeOneはカスタムドメインをProductionまたはPreviewへ関連付けられます。Productionへ
関連付けると、その環境で最後に成功したデプロイが表示されます。

公式資料:

- [EdgeOne Makers Custom Domain](https://pages.edgeone.ai/document/custom-domain)

## 5. 既存の`hanlu.app` DNSへレコードを追加する

ここで行うのは`keyboard`サブドメインの追加だけです。

次のものは変更しません。

- `hanlu.app`のネームサーバー
- apex (`@`) レコード
- `www`、`dict`、メール用MX/TXTレコード
- Hanlu本体のEdgeOneプロジェクト
- Hanlu本体の証明書

### `hanlu.app`のDNSをEdgeOneで管理している場合

EdgeOneの既存`hanlu.app`サイトでDNS Recordsを開き、Makersの画面が指定したレコードを
追加します。コンソールにOne-click additionが表示される場合は、対象ホストが
`keyboard`であることを確認して使用します。

代表的な形は次のとおりですが、Targetは必ずコンソールに表示された実値を使ってください。

| 項目 | 値 |
| --- | --- |
| Type | `CNAME` |
| Host | `keyboard` |
| Target | EdgeOne Makersが指定した値 |
| TTL | Autoまたは既定値 |

所有権確認用TXTが指定された場合は、EdgeOneが表示したHostとValueをそのまま登録します。
同じ`keyboard`ホストにA、AAAA、CNAMEなどが既にある場合は競合するため、用途を確認してから
置き換えます。無関係なレコードは削除しません。

### DNSを外部事業者で管理している場合

現在の権威DNS事業者側で同じCNAME・検証レコードを追加します。EdgeOneを利用しているという
理由だけで、`hanlu.app`全体のネームサーバーを切り替える必要はありません。

## 6. 中国本土向けリージョンとICPを確認する

EdgeOne公式仕様では、プロジェクトの加速リージョンが中国本土を含む場合、カスタムドメインに
ICP登録が必要です。

- Hanlu本体で既に採用している加速リージョンを確認する
- `hanlu.app`のICP状態と、`keyboard.hanlu.app`を追加できる状態か確認する
- ICP対応が未完了なら、最初は中国本土を含まないGlobalリージョンで公開する
- リージョンを推測で変更せず、既存Hanlu運用と整合させる

中国本土の利用者へ提供する場合でも、規制対応が完了する前に本土リージョンを選択しないで
ください。

## 7. DNS・SSL・表示を確認する

DNS追加後に次を実行します。

```bash
dig CNAME keyboard.hanlu.app +short
dig TXT keyboard.hanlu.app +short
```

EdgeOneコンソールでは次を確認します。

- Domain statusがActive
- Production環境へ関連付いている
- SSL証明書が発行・適用済み
- Force HTTPSが有効
- 最後のProductionデプロイがSucceeded

URLも確認します。

```bash
curl -I https://keyboard.hanlu.app
curl -I https://keyboard.hanlu.app/ja
curl -I https://keyboard.hanlu.app/zh
curl -I https://keyboard.hanlu.app/en
curl -I https://keyboard.hanlu.app/ko
```

完了条件は次のとおりです。

- `/`が意図どおり`/ja`へ移動する
- 4言語すべてがHTTP 200で表示される
- TLS証明書エラーがない
- ページソースにEdgeOne Preview URLやローカルURLが残っていない
- AndroidとApp Storeのリンクが意図した配布先を指す
- `hanlu.app`と`dict.hanlu.app`が従来どおり動作する

## 8. 通常の更新手順

Git連携を使う場合、Production BranchへのpushでEdgeOneが自動デプロイします。

1. `about/`の変更をレビューする
2. `cd about && npm ci && npm run lint && npm test`を実行する
3. Previewデプロイで4言語を確認する
4. PRをmergeしてProduction Branchへ反映する
5. EdgeOneのProductionデプロイ成功を確認する
6. `keyboard.hanlu.app`を確認する

Root Directoryが`about`に設定されていても、EdgeOne側の変更検知設定によってはリポジトリ内の
別ディレクトリ変更でもデプロイされる場合があります。不要なデプロイが多い場合は、EdgeOneの
Build and Deployment設定で監視パス機能の有無を確認してください。

## 9. 切り戻し

ページに問題がある場合は、DNSを削除せず、EdgeOne Makersで直前の正常なProduction
Deploymentを再デプロイまたは再関連付けします。

DNSを消すとSSL・所有権確認をやり直す可能性があります。ドメインを別サービスへ移行する場合
以外は、切り戻しのためにCNAMEを削除しません。

旧Sites版は、EdgeOne版が安定するまで削除せず緊急時の比較対象として残します。ただし、
`keyboard.hanlu.app`を2つのホスティング先へ同時に関連付けることはできません。

## トラブルシューティング

### EdgeOneがリポジトリのルートでビルドしようとする

Project Settings → Build and Deployment ConfigurationでRoot Directoryを`about`にします。
ビルドログの作業ディレクトリと、読み込まれた`package.json`がAboutサイトのものか確認します。

### `vinext`やCloudflare Workerのエラーになる

まだSites向けビルドを実行しています。EdgeOne本番経路を標準`next build`へ切り替え、Presetを
Next.js、Output Directoryを`.next`にしてください。

### `keyboard.hanlu.app`でHanluのログイン画面が出る

カスタムドメインがHanlu本体のEdgeOneプロジェクトへ付いている可能性があります。
Furigana Keyboard用の別Makersプロジェクトへ付け直してください。

### カスタムドメインを追加できない

- 別のEdgeOne Pages/Makersプロジェクトに同じドメインが付いていないか確認する
- EdgeOneの別製品で`keyboard.hanlu.app`がAcceleration Domainになっていないか確認する
- 所有権確認レコードを確認する
- 中国本土を含むリージョンの場合はICP状態を確認する

### CNAMEが反映されない

- Hostが`keyboard`になっているか確認する
- Targetに`https://`やパスを付けていないか確認する
- 同一ホストのA、AAAA、CNAME、MX、TXT競合を確認する
- 実際の権威ネームサーバーがEdgeOneか外部DNSかを確認する
- TTLとDNSキャッシュの反映を待つ

### APKのデプロイで25MiB超過になる

APKをAboutサイト成果物から外します。現在のAPKは約46MBなので、オブジェクトストレージや
ストア配布へ移し、`NEXT_PUBLIC_ANDROID_APK_URL`には配布URLだけを設定します。

## 最終チェックリスト

- [ ] Hanlu本体とは別のEdgeOne Makersプロジェクトを作成した
- [ ] GitリポジトリのRoot Directoryを`about`にした
- [ ] Aboutサイトを標準Next.jsのEdgeOne対応ビルドへ変更した
- [ ] Node.js 22.17.1、`npm ci`、`npm run build`、`.next`を設定した
- [ ] 標準ドメインで4言語を確認した
- [ ] 公開環境変数を設定した
- [ ] APKをPages成果物へ含めていない
- [ ] `keyboard.hanlu.app`をProductionへ関連付けた
- [ ] 既存の`hanlu.app` DNSで`keyboard`レコードだけを追加した
- [ ] apex、www、dict、MX、ネームサーバーを変更していない
- [ ] リージョンとICP要件を確認した
- [ ] SSLとForce HTTPSを確認した
- [ ] `keyboard.hanlu.app`の4言語を確認した
- [ ] `hanlu.app`と`dict.hanlu.app`が従来どおり動作することを確認した
