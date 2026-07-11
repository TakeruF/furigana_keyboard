import Foundation

/// Shared app/extension localization. This table intentionally follows the
/// in-app language preference instead of the process-wide iOS locale.
enum AppStrings {
    static var language: AppLanguage {
        let selected = KeyboardPreferences().language
        if selected != .automatic { return selected }
        let code = Locale.preferredLanguages.first?.lowercased() ?? "en"
        if code.hasPrefix("ja") { return .japanese }
        if code.hasPrefix("zh") { return .simplifiedChinese }
        if code.hasPrefix("ko") { return .korean }
        return .english
    }

    static func text(_ key: String) -> String {
        let values = table[key] ?? [:]
        return values[language] ?? values[.english] ?? key
    }

    private static let table: [String: [AppLanguage: String]] = [
        "write_hint": [.japanese: "一文字または二文字を書いてください", .english: "Write one or two characters", .simplifiedChinese: "请手写一个或两个字符", .korean: "한두 글자를 써 주세요"],
        "recognizing": [.japanese: "認識中…", .english: "Recognizing…", .simplifiedChinese: "正在识别…", .korean: "인식 중…"],
        "no_candidates": [.japanese: "候補がありません", .english: "No candidates", .simplifiedChinese: "没有候选项", .korean: "후보 없음"],
        "model_error": [.japanese: "オフラインモデルを読み込めません", .english: "Offline model unavailable", .simplifiedChinese: "无法加载离线模型", .korean: "오프라인 모델을 불러올 수 없음"],
        "clear": [.japanese: "消去", .english: "Clear", .simplifiedChinese: "清除", .korean: "지우기"],
        "space": [.japanese: "空白", .english: "space", .simplifiedChinese: "空格", .korean: "공백"],
        "return": [.japanese: "改行", .english: "return", .simplifiedChinese: "换行", .korean: "줄바꿈"],
        "convert": [.japanese: "変換", .english: "convert", .simplifiedChinese: "转换", .korean: "변환"],
        "handwriting": [.japanese: "手書き", .english: "Handwriting", .simplifiedChinese: "手写", .korean: "필기"],
        "japanese": [.japanese: "日本語", .english: "Japanese", .simplifiedChinese: "日语", .korean: "일본어"],
        "english": [.japanese: "英字", .english: "English", .simplifiedChinese: "英文", .korean: "영문"],
        "symbols": [.japanese: "記号", .english: "Symbols", .simplifiedChinese: "符号", .korean: "기호"],
        "settings": [.japanese: "設定", .english: "Settings", .simplifiedChinese: "设置", .korean: "설정"],
        "setup_title": [.japanese: "設定を完了する", .english: "Finish setup", .simplifiedChinese: "完成设置", .korean: "설정 완료"],
        "privacy": [.japanese: "プライバシー", .english: "Privacy", .simplifiedChinese: "隐私", .korean: "개인정보 보호"],
        "dictionary": [.japanese: "読み辞書", .english: "Reading dictionary", .simplifiedChinese: "读音词典", .korean: "읽기 사전"],
        "display": [.japanese: "表示", .english: "Display", .simplifiedChinese: "显示", .korean: "표시"],
        "input": [.japanese: "入力", .english: "Input", .simplifiedChinese: "输入", .korean: "입력"],
        "effects": [.japanese: "操作感", .english: "Feedback", .simplifiedChinese: "反馈", .korean: "피드백"],
        "about": [.japanese: "このアプリについて", .english: "About", .simplifiedChinese: "关于", .korean: "정보"]
        ,"open_settings": [.japanese: "設定アプリを開く", .english: "Open Settings", .simplifiedChinese: "打开设置", .korean: "설정 열기"]
        ,"step1_title": [.japanese: "設定アプリを開く", .english: "Open Settings", .simplifiedChinese: "打开设置", .korean: "설정 열기"]
        ,"step1_detail": [.japanese: "一般 → キーボード → キーボードの順に進みます", .english: "Go to General → Keyboard → Keyboards", .simplifiedChinese: "前往通用 → 键盘 → 键盘", .korean: "일반 → 키보드 → 키보드로 이동하세요"]
        ,"step2_title": [.japanese: "キーボードを追加", .english: "Add the keyboard", .simplifiedChinese: "添加键盘", .korean: "키보드 추가"]
        ,"step2_detail": [.japanese: "「新しいキーボードを追加」からFurigana Keyboardを選びます", .english: "Choose Furigana Keyboard under Add New Keyboard", .simplifiedChinese: "在“添加新键盘”中选择 Furigana Keyboard", .korean: "새로운 키보드 추가에서 Furigana Keyboard를 선택하세요"]
        ,"step3_title": [.japanese: "地球儀キーで切り替える", .english: "Switch with the globe key", .simplifiedChinese: "用地球键切换", .korean: "지구본 키로 전환"]
        ,"step3_detail": [.japanese: "入力中に地球儀キーを長押しして選択します", .english: "Touch and hold the globe key while typing", .simplifiedChinese: "输入时长按地球键并选择", .korean: "입력 중 지구본 키를 길게 누르세요"]
        ,"home_headline": [.japanese: "手書きでも、キー入力でも。\n読みを見ながら日本語入力。", .english: "Write it or type it.\nEnter Japanese with readings.", .simplifiedChinese: "手写或键入。\n看着读音输入日语。", .korean: "필기하거나 키로 입력하세요.\n읽기를 보며 일본어를 입력하세요."]
        ,"home_subtitle": [.japanese: "Android版と同じオフライン辞書を使う、日本語キーボードです。", .english: "A Japanese keyboard using the same offline dictionary as Android.", .simplifiedChinese: "使用与 Android 版相同离线词典的日语键盘。", .korean: "Android 버전과 같은 오프라인 사전을 사용하는 일본어 키보드입니다."]
        ,"full_access_title": [.japanese: "フルアクセスは不要", .english: "Full Access not required", .simplifiedChinese: "无需完全访问", .korean: "전체 접근 불필요"]
        ,"full_access_detail": [.japanese: "キーボードは通信せず、署名済み辞書の更新だけを親アプリが取得します。", .english: "The keyboard never connects to the network; only the app fetches signed dictionary updates.", .simplifiedChinese: "键盘不联网；只有主应用获取已签名的词典更新。", .korean: "키보드는 통신하지 않으며 앱만 서명된 사전 업데이트를 받습니다."]
        ,"offline_note": [.japanese: "手書き認識、読み表示、ローマ字かな漢字変換は端末内で処理されます。キーボードのフルアクセスは不要です。", .english: "Handwriting, readings, and romaji conversion run on device. Full Access is not required.", .simplifiedChinese: "手写识别、读音和罗马字转换均在设备上运行，无需完全访问。", .korean: "필기 인식, 읽기, 로마자 변환은 기기에서 실행되며 전체 접근이 필요 없습니다."]
        ,"check_update": [.japanese: "辞書更新を確認", .english: "Check for updates", .simplifiedChinese: "检查词典更新", .korean: "사전 업데이트 확인"]
        ,"initial_panel": [.japanese: "起動時の入力面", .english: "Initial input panel", .simplifiedChinese: "初始输入面板", .korean: "초기 입력 패널"]
        ,"continuous": [.japanese: "連続手書き", .english: "Continuous handwriting", .simplifiedChinese: "连续手写", .korean: "연속 필기"]
        ,"number_row": [.japanese: "数字行を表示", .english: "Show number row", .simplifiedChinese: "显示数字行", .korean: "숫자 행 표시"]
        ,"reading_display": [.japanese: "読み表示", .english: "Reading display", .simplifiedChinese: "读音显示", .korean: "읽기 표시"]
        ,"keyboard_height": [.japanese: "キーボードの高さ", .english: "Keyboard height", .simplifiedChinese: "键盘高度", .korean: "키보드 높이"]
        ,"candidate_size": [.japanese: "候補文字サイズ", .english: "Candidate text size", .simplifiedChinese: "候选文字大小", .korean: "후보 글자 크기"]
        ,"accent_color": [.japanese: "アクセントカラー", .english: "Accent color", .simplifiedChinese: "强调色", .korean: "강조 색상"]
        ,"haptics": [.japanese: "触覚フィードバック", .english: "Haptic feedback", .simplifiedChinese: "触觉反馈", .korean: "햅틱 피드백"]
        ,"key_clicks": [.japanese: "キークリック音", .english: "Key click sounds", .simplifiedChinese: "按键音", .korean: "키 클릭 소리"]
        ,"language": [.japanese: "言語", .english: "Language", .simplifiedChinese: "语言", .korean: "언어"]
        ,"privacy_policy": [.japanese: "プライバシーポリシー", .english: "Privacy Policy", .simplifiedChinese: "隐私政策", .korean: "개인정보 처리방침"]
        ,"terms": [.japanese: "利用規約", .english: "Terms of Use", .simplifiedChinese: "使用条款", .korean: "이용 약관"]
        ,"licenses": [.japanese: "オープンソースライセンス", .english: "Open-source licenses", .simplifiedChinese: "开源许可证", .korean: "오픈 소스 라이선스"]
        ,"version": [.japanese: "バージョン", .english: "Version", .simplifiedChinese: "版本", .korean: "버전"]
        ,"support": [.japanese: "サポート", .english: "Support", .simplifiedChinese: "支持", .korean: "지원"]
        ,"website": [.japanese: "公式サイト", .english: "Website", .simplifiedChinese: "官方网站", .korean: "웹사이트"]
        ,"update_checking": [.japanese: "辞書更新を確認しています…", .english: "Checking dictionary updates…", .simplifiedChinese: "正在检查词典更新…", .korean: "사전 업데이트 확인 중…"]
        ,"update_failed": [.japanese: "辞書更新を確認できませんでした。同梱辞書を使用します。", .english: "Could not check for updates. Using the bundled dictionary.", .simplifiedChinese: "无法检查更新，将使用内置词典。", .korean: "업데이트를 확인하지 못했습니다. 내장 사전을 사용합니다."]
        ,"update_current": [.japanese: "辞書は最新です", .english: "Dictionary is up to date", .simplifiedChinese: "词典已是最新", .korean: "사전이 최신입니다"]
        ,"update_complete": [.japanese: "辞書を更新しました", .english: "Dictionary updated", .simplifiedChinese: "词典已更新", .korean: "사전을 업데이트했습니다"]
        ,"feature_hw_title": [.japanese: "1〜2文字手書き", .english: "1–2 character writing", .simplifiedChinese: "手写1至2个字符", .korean: "1~2자 필기"]
        ,"feature_hw_detail": [.japanese: "連続入力と単語候補", .english: "Continuous input and words", .simplifiedChinese: "连续输入和词语候选", .korean: "연속 입력과 단어 후보"]
        ,"feature_conversion_title": [.japanese: "かな漢字変換", .english: "Kana-kanji conversion", .simplifiedChinese: "假名汉字转换", .korean: "가나 한자 변환"]
        ,"feature_conversion_detail": [.japanese: "ローマ字からオフライン変換", .english: "Offline conversion from romaji", .simplifiedChinese: "从罗马字离线转换", .korean: "로마자 오프라인 변환"]
        ,"feature_panels_title": [.japanese: "4つの入力面", .english: "Four input panels", .simplifiedChinese: "四种输入面板", .korean: "네 가지 입력 패널"]
        ,"feature_panels_detail": [.japanese: "手書き・かな・英字・記号", .english: "Writing, Japanese, ABC, symbols", .simplifiedChinese: "手写、日语、英文、符号", .korean: "필기, 일본어, 영문, 기호"]
        ,"feature_readings_title": [.japanese: "ふりがな表示", .english: "Reading display", .simplifiedChinese: "注音显示", .korean: "읽기 표시"]
        ,"feature_readings_detail": [.japanese: "かな・ローマ字・非表示", .english: "Kana, romaji, or hidden", .simplifiedChinese: "假名、罗马字或隐藏", .korean: "가나, 로마자 또는 숨김"]
        ,"automatic": [.japanese: "自動", .english: "Automatic", .simplifiedChinese: "自动", .korean: "자동"]
        ,"kana": [.japanese: "かな", .english: "Kana", .simplifiedChinese: "假名", .korean: "가나"]
        ,"hidden": [.japanese: "非表示", .english: "Hidden", .simplifiedChinese: "隐藏", .korean: "숨김"]
        ,"compact": [.japanese: "コンパクト", .english: "Compact", .simplifiedChinese: "紧凑", .korean: "컴팩트"]
        ,"standard": [.japanese: "標準", .english: "Standard", .simplifiedChinese: "标准", .korean: "표준"]
        ,"large": [.japanese: "大きい", .english: "Large", .simplifiedChinese: "大", .korean: "크게"]
        ,"small": [.japanese: "小", .english: "Small", .simplifiedChinese: "小", .korean: "작게"]
        ,"segment_shrink": [.japanese: "文節を縮める", .english: "Shorten segment", .simplifiedChinese: "缩短分段", .korean: "문절 줄이기"]
        ,"segment_expand": [.japanese: "文節を伸ばす", .english: "Extend segment", .simplifiedChinese: "扩展分段", .korean: "문절 늘리기"]
        ,"next_keyboard": [.japanese: "次のキーボード", .english: "Next keyboard", .simplifiedChinese: "下一个键盘", .korean: "다음 키보드"]
        ,"document_unavailable": [.japanese: "文書を読み込めませんでした。", .english: "The document could not be loaded.", .simplifiedChinese: "无法加载文档。", .korean: "문서를 불러올 수 없습니다."]
        ,"accent_blue": [.japanese: "ブルー", .english: "Blue", .simplifiedChinese: "蓝色", .korean: "파란색"]
        ,"accent_green": [.japanese: "グリーン", .english: "Green", .simplifiedChinese: "绿色", .korean: "초록색"]
        ,"accent_orange": [.japanese: "オレンジ", .english: "Orange", .simplifiedChinese: "橙色", .korean: "주황색"]
        ,"accent_pink": [.japanese: "ピンク", .english: "Pink", .simplifiedChinese: "粉色", .korean: "분홍색"]
        ,"accent_purple": [.japanese: "パープル", .english: "Purple", .simplifiedChinese: "紫色", .korean: "보라색"]
    ]
}
