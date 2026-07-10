import SwiftUI

@main
struct FuriganaKeyboardApp: App {
    var body: some Scene {
        WindowGroup {
            SetupView()
        }
    }
}

private struct SetupView: View {
    private let steps = [
        ("1", "設定を開く", "一般 → キーボード → キーボードの順に進みます"),
        ("2", "キーボードを追加", "「新しいキーボードを追加」からFurigana Keyboardを選びます"),
        ("3", "入力中に切り替える", "地球儀キーを長押ししてFurigana Keyboardを選びます")
    ]

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    hero
                    VStack(spacing: 12) {
                        ForEach(steps, id: \.0) { step in
                            stepCard(number: step.0, title: step.1, detail: step.2)
                        }
                    }
                    privacyCard
                    Text("iOS版は初期プレビューです。手書き認識は端末内で実行され、入力内容を外部へ送信しません。")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .padding(.horizontal, 4)
                }
                .padding(20)
            }
            .background(Color(uiColor: .systemGroupedBackground))
            .navigationTitle("Furigana Keyboard")
            .navigationBarTitleDisplayMode(.inline)
        }
    }

    private var hero: some View {
        VStack(alignment: .leading, spacing: 12) {
            ZStack {
                RoundedRectangle(cornerRadius: 22)
                    .fill(Color.accentColor.gradient)
                    .frame(width: 72, height: 72)
                Text("振")
                    .font(.system(size: 38, weight: .bold, design: .rounded))
                    .foregroundStyle(.white)
            }
            Text("手書きで選び、\n読みを見ながら入力。")
                .font(.system(size: 31, weight: .bold, design: .rounded))
                .fixedSize(horizontal: false, vertical: true)
            Text("オフライン日本語手書きキーボードをiPhoneとiPadで利用できます。")
                .font(.body)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.vertical, 8)
    }

    private func stepCard(number: String, title: String, detail: String) -> some View {
        HStack(alignment: .top, spacing: 14) {
            Text(number)
                .font(.headline)
                .foregroundStyle(Color.accentColor)
                .frame(width: 34, height: 34)
                .background(Color.accentColor.opacity(0.12), in: Circle())
            VStack(alignment: .leading, spacing: 4) {
                Text(title).font(.headline)
                Text(detail).font(.subheadline).foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
        }
        .padding(16)
        .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 18))
    }

    private var privacyCard: some View {
        HStack(alignment: .top, spacing: 14) {
            Image(systemName: "lock.shield.fill")
                .font(.title2)
                .foregroundStyle(.green)
            VStack(alignment: .leading, spacing: 5) {
                Text("フルアクセスは不要").font(.headline)
                Text("認識モデルと読み辞書はキーボードに同梱されています。ネットワーク接続を許可せず利用できます。")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(17)
        .background(Color.green.opacity(0.09), in: RoundedRectangle(cornerRadius: 18))
    }
}

