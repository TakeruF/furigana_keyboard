export const locales = ["ja", "zh", "en", "ko"] as const;
export type Locale = (typeof locales)[number];

export const localeLabels: Record<Locale, { short: string; full: string }> = {
  ja: { short: "日", full: "日本語" },
  zh: { short: "中", full: "简体中文" },
  en: { short: "EN", full: "English" },
  ko: { short: "한", full: "한국어" },
};

export function isLocale(value: string): value is Locale {
  return locales.includes(value as Locale);
}

export const copy = {
  ja: {
    htmlLang: "ja",
    meta: {
      title: "Furigana Keyboard — 書く。読める。つながる。",
      description: "ふりがな付き候補で直感的に入力できる、プライバシーを重視した日本語キーボード。Android・iPhone・iPadに対応予定。",
      social: "書く。読める。つながる。ふりがな付き日本語キーボード。",
    },
    nav: { label: "メインナビゲーション", top: "Furigana Keyboard トップ", features: "特徴", download: "ダウンロード", about: "このアプリについて", language: "表示言語" },
    hero: { title: ["書く。読める。", "つながる。"], lead: ["ふりがな付き候補で、日本語入力をもっと直感的に。", "基本機能はオフラインでも利用できます。"], release: "リリース情報を見る", keyboardImage: "手書きキーボードのイメージ", writingHint: "ここに一文字を書く", keys: ["かな", "スペース", "⌫", "改行"], privateStatus: "基本機能はオフライン対応" },
    why: { heading: "日本語を、ためらわずに。", features: [
      { title: "書いて選ぶ", body: "かな・漢字を指先で書くと、認識候補をすぐに表示。キーボードを行き来せず入力できます。" },
      { title: "読みが見える", body: "候補にふりがなを添えて表示。形は分かるけれど読み方に迷う文字も、確かめながら選べます。" },
      { title: "プライバシーを重視", body: "入力文字や手書き内容を開発者のサーバーへ送信しません。基本機能はオフラインでも利用できます。" },
    ] },
    download: { heading: "あなたの端末へ。", intro: "各ストアでの公開後、このページから直接アクセスできます。", androidTitle: "Android版", androidBody: "オフライン手書き認識、ふりがな表示、連続入力に対応。", apk: "APKをダウンロード", iosTitle: "iOS版", iosBody: "現在プレビュー開発中。端末内の単文字認識と読み表示から対応。", appStore: "App Storeで見る", pending: "準備中", pendingLabel: "現在準備中です" },
    about: { heading: ["文字の形と、", "読みのあいだを結ぶ。"], lead: "Furigana Keyboardは、「書けるけれど読めない」「読みは分かるけれど変換しづらい」という小さな壁をなくすための、日本語キーボードです。", body: "入力内容や手書き履歴は保存しません。基本機能はオフラインでも利用でき、任意のPlus機能ではモデルの取得などに通信を使用します。", facts: ["JIS X 0208 漢字ラベル", "読みを収録した文字", "開発者による入力履歴の保存"] },
    legal: { terms: "利用規約", privacy: "プライバシーポリシー" },
    support: "サポート",
    footer: "手書きとふりがなで、読みやすい日本語を。",
  },
  zh: {
    htmlLang: "zh-CN",
    meta: {
      title: "Furigana Keyboard — 书写。读懂。连接。",
      description: "显示注音候选、重视隐私的日语键盘。计划支持 Android、iPhone 和 iPad。",
      social: "书写。读懂。连接。显示注音候选的日语键盘。",
    },
    nav: { label: "主导航", top: "Furigana Keyboard 首页", features: "特色", download: "下载", about: "关于", language: "显示语言" },
    hero: { title: ["书写。读懂。", "连接。"], lead: ["候选字附带假名读音，让日语输入更直观。", "基础功能也可离线使用。"], release: "查看发布信息", keyboardImage: "手写键盘示意图", writingHint: "在这里写一个字", keys: ["假名", "空格", "⌫", "换行"], privateStatus: "基础功能支持离线使用" },
    why: { heading: "输入日语，不再犹豫。", features: [
      { title: "手写选择", body: "用手指写下假名或汉字，即刻显示识别候选，无需来回切换键盘。" },
      { title: "读音可见", body: "候选字附带假名读音。即使只记得字形，也能确认读音后再选择。" },
      { title: "重视隐私", body: "输入文字和手写内容不会发送到开发者的服务器，基础功能也可离线使用。" },
    ] },
    download: { heading: "安装到你的设备。", intro: "应用正式发布后，可从本页面直接前往下载。", androidTitle: "Android 版", androidBody: "支持离线手写识别、注音显示和连续输入。", apk: "下载 APK", iosTitle: "iOS 版", iosBody: "目前处于预览开发阶段，MVP 支持设备端单字识别和读音显示。", appStore: "前往 App Store", pending: "准备中", pendingLabel: "目前正在准备中" },
    about: { heading: ["连接字形与", "读音之间。"], lead: "Furigana Keyboard 是一款日语键盘，旨在消除“会写却不会读”或“知道读音却难以转换”的小障碍。", body: "应用不会保存输入内容或手写记录。基础功能可离线使用；可选的 Plus 功能会联网下载模型等数据。", facts: ["JIS X 0208 汉字标签", "收录读音的字符", "开发者保存的输入记录"] },
    legal: { terms: "使用条款", privacy: "隐私政策" },
    support: "支持",
    footer: "通过手写与注音，让日语更易读。",
  },
  en: {
    htmlLang: "en",
    meta: {
      title: "Furigana Keyboard — Write. Read. Connect.",
      description: "A privacy-conscious Japanese keyboard with furigana on every candidate. Coming to Android, iPhone, and iPad.",
      social: "Write. Read. Connect. A Japanese keyboard with furigana candidates.",
    },
    nav: { label: "Main navigation", top: "Furigana Keyboard home", features: "Features", download: "Download", about: "About", language: "Display language" },
    hero: { title: ["Write. Read.", "Connect."], lead: ["Furigana on every candidate makes Japanese input more intuitive.", "Core features remain available offline."], release: "View release information", keyboardImage: "Handwriting keyboard preview", writingHint: "Write one character here", keys: ["Kana", "Space", "⌫", "Return"], privateStatus: "Core features work offline" },
    why: { heading: "Type Japanese with confidence.", features: [
      { title: "Write and choose", body: "Draw kana or kanji with your finger and see recognition candidates instantly, without switching keyboards." },
      { title: "See the reading", body: "Furigana appears beside each candidate, so you can confirm the reading even when you only remember the shape." },
      { title: "Privacy-conscious", body: "Typed text and handwriting are not sent to the developer’s servers, and core features remain available offline." },
    ] },
    download: { heading: "Bring it to your device.", intro: "Once each release is available, this page will link directly to it.", androidTitle: "Android", androidBody: "Offline handwriting recognition, furigana display, and continuous input.", apk: "Download APK", iosTitle: "iPhone & iPad", iosBody: "Now in preview development. The MVP supports on-device single-character recognition and readings.", appStore: "View on the App Store", pending: "Coming soon", pendingLabel: "This release is coming soon" },
    about: { heading: ["Connecting the shape", "of a character to its sound."], lead: "Furigana Keyboard is a Japanese keyboard designed to remove the small barriers between knowing how a character looks, knowing how it sounds, and getting it into a text field.", body: "The app does not retain typed text or handwriting history. Core features work offline; the optional Plus feature uses a connection for model downloads and related services.", facts: ["JIS X 0208 kanji labels", "characters with readings", "input history retained by the developer"] },
    legal: { terms: "Terms of Use", privacy: "Privacy Policy" },
    support: "Support",
    footer: "Handwriting and furigana for more readable Japanese.",
  },
  ko: {
    htmlLang: "ko",
    meta: {
      title: "Furigana Keyboard — 쓰고. 읽고. 이어지다.",
      description: "후리가나 후보를 표시하고 개인정보 보호를 중시하는 일본어 키보드. Android, iPhone, iPad 지원 예정.",
      social: "쓰고. 읽고. 이어지다. 후리가나 후보를 표시하는 일본어 키보드.",
    },
    nav: { label: "주요 탐색", top: "Furigana Keyboard 홈", features: "특징", download: "다운로드", about: "소개", language: "표시 언어" },
    hero: { title: ["쓰고. 읽고.", "이어지다."], lead: ["후리가나가 표시된 후보로 일본어 입력을 더 직관적으로.", "기본 기능은 오프라인에서도 사용할 수 있습니다."], release: "출시 정보 보기", keyboardImage: "필기 키보드 미리보기", writingHint: "여기에 한 글자를 쓰세요", keys: ["가나", "공백", "⌫", "줄바꿈"], privateStatus: "기본 기능은 오프라인 지원" },
    why: { heading: "일본어를 망설임 없이.", features: [
      { title: "쓰고 선택하기", body: "손가락으로 가나나 한자를 쓰면 인식 후보가 바로 표시되어 키보드를 오갈 필요가 없습니다." },
      { title: "읽는 법 확인하기", body: "후보에 후리가나가 함께 표시됩니다. 모양만 기억나는 글자도 읽는 법을 확인하고 선택할 수 있습니다." },
      { title: "개인정보 보호 중시", body: "입력 문자와 필기 내용은 개발자 서버로 전송되지 않으며 기본 기능은 오프라인에서도 사용할 수 있습니다." },
    ] },
    download: { heading: "내 기기에서 시작하세요.", intro: "각 스토어에 공개되면 이 페이지에서 바로 이동할 수 있습니다.", androidTitle: "Android 버전", androidBody: "오프라인 필기 인식, 후리가나 표시, 연속 입력을 지원합니다.", apk: "APK 다운로드", iosTitle: "iOS 버전", iosBody: "현재 프리뷰 개발 중이며 MVP는 기기 내 한 글자 인식과 읽기 표시를 지원합니다.", appStore: "App Store에서 보기", pending: "준비 중", pendingLabel: "현재 준비 중입니다" },
    about: { heading: ["글자의 모양과", "읽는 법을 잇다."], lead: "Furigana Keyboard는 ‘쓸 수 있지만 읽기 어렵다’거나 ‘읽는 법은 알지만 변환하기 어렵다’는 작은 장벽을 없애기 위한 일본어 키보드입니다.", body: "입력 내용과 필기 기록은 저장하지 않습니다. 기본 기능은 오프라인에서도 사용할 수 있으며 선택형 Plus 기능은 모델 다운로드 등에 통신을 사용합니다.", facts: ["JIS X 0208 한자 라벨", "읽기가 수록된 문자", "개발자가 저장하는 입력 기록"] },
    legal: { terms: "이용약관", privacy: "개인정보 처리방침" },
    support: "지원",
    footer: "필기와 후리가나로 더 읽기 쉬운 일본어를.",
  },
} as const;
