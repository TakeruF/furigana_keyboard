import SwiftUI
import UIKit

@main
struct FuriganaKeyboardApp: App {
    var body: some Scene { WindowGroup { RootView() } }
}

@MainActor
private final class SettingsModel: ObservableObject {
    @Published var readingMode: ReadingDisplayMode { didSet { preferences.readingMode = readingMode } }
    @Published var preferredPanel: PreferredKeyboardPanel { didSet { preferences.preferredPanel = preferredPanel } }
    @Published var keyboardHeight: KeyboardHeight { didSet { preferences.keyboardHeight = keyboardHeight } }
    @Published var candidateTextSize: CandidateTextSize { didSet { preferences.candidateTextSize = candidateTextSize } }
    @Published var accent: AccentChoice { didSet { preferences.accent = accent } }
    @Published var language: AppLanguage { didSet { preferences.language = language } }
    @Published var hapticsEnabled: Bool { didSet { preferences.hapticsEnabled = hapticsEnabled } }
    @Published var keyClicksEnabled: Bool { didSet { preferences.keyClicksEnabled = keyClicksEnabled } }
    @Published var showNumberRow: Bool { didSet { preferences.showNumberRow = showNumberRow } }
    @Published var continuousHandwriting: Bool { didSet { preferences.continuousHandwriting = continuousHandwriting } }
    private var preferences: KeyboardPreferences

    init() {
        let values = KeyboardPreferences(); preferences = values
        readingMode = values.readingMode; preferredPanel = values.preferredPanel
        keyboardHeight = values.keyboardHeight; candidateTextSize = values.candidateTextSize
        accent = values.accent; language = values.language
        hapticsEnabled = values.hapticsEnabled; keyClicksEnabled = values.keyClicksEnabled
        showNumberRow = values.showNumberRow; continuousHandwriting = values.continuousHandwriting
    }
}

private struct RootView: View {
    @StateObject private var settings = SettingsModel()
    var body: some View {
        TabView {
            NavigationStack { HomeView() }
                .tabItem { Label(AppStrings.text("setup_title"), systemImage: "keyboard") }
            NavigationStack { SettingsView(model: settings) }
                .tabItem { Label(AppStrings.text("settings"), systemImage: "gearshape.fill") }
        }
        .tint(settings.accent.swiftUIColor)
    }
}

private struct HomeView: View {
    @StateObject private var readingUpdater = ReadingDataUpdater()
    private let steps = [
        ("1", AppStrings.text("step1_title"), AppStrings.text("step1_detail")),
        ("2", AppStrings.text("step2_title"), AppStrings.text("step2_detail")),
        ("3", AppStrings.text("step3_title"), AppStrings.text("step3_detail"))
    ]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                hero
                Button { UIApplication.shared.open(URL(string: UIApplication.openSettingsURLString)!) } label: {
                    Label(AppStrings.text("open_settings"), systemImage: "arrow.up.forward.app.fill")
                        .frame(maxWidth: .infinity).padding(.vertical, 5)
                }.buttonStyle(.borderedProminent)
                VStack(spacing: 10) {
                    ForEach(steps, id: \.0) { step in stepCard(number: step.0, title: step.1, detail: step.2) }
                }
                featureGrid
                dictionaryCard
                privacyCard
                Text(AppStrings.text("offline_note"))
                    .font(.footnote).foregroundStyle(.secondary)
            }.padding(20)
        }
        .background(Color(uiColor: .systemGroupedBackground))
        .navigationTitle("Furigana Keyboard").navigationBarTitleDisplayMode(.inline)
        .task { await readingUpdater.update() }
    }

    private var hero: some View {
        VStack(alignment: .leading, spacing: 11) {
            ZStack {
                RoundedRectangle(cornerRadius: 20).fill(Color.accentColor.gradient).frame(width: 70, height: 70)
                Text("振").font(.system(size: 37, weight: .bold, design: .rounded)).foregroundStyle(.white)
            }
            Text(AppStrings.text("home_headline"))
                .font(.system(size: 29, weight: .bold, design: .rounded))
            Text(AppStrings.text("home_subtitle"))
                .foregroundStyle(.secondary)
        }.padding(.vertical, 6)
    }

    private var featureGrid: some View {
        LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 10) {
            feature("pencil.and.scribble", AppStrings.text("feature_hw_title"), AppStrings.text("feature_hw_detail"))
            feature("character.cursor.ibeam", AppStrings.text("feature_conversion_title"), AppStrings.text("feature_conversion_detail"))
            feature("textformat.abc", AppStrings.text("feature_panels_title"), AppStrings.text("feature_panels_detail"))
            feature("eye", AppStrings.text("feature_readings_title"), AppStrings.text("feature_readings_detail"))
        }
    }

    private func feature(_ icon: String, _ title: String, _ detail: String) -> some View {
        VStack(alignment: .leading, spacing: 7) {
            Image(systemName: icon).foregroundStyle(Color.accentColor).font(.title3)
            Text(title).font(.subheadline.weight(.semibold)); Text(detail).font(.caption).foregroundStyle(.secondary)
        }.frame(maxWidth: .infinity, minHeight: 86, alignment: .topLeading).padding(14).card()
    }

    private func stepCard(number: String, title: String, detail: String) -> some View {
        HStack(alignment: .top, spacing: 13) {
            Text(number).font(.headline).foregroundStyle(Color.accentColor).frame(width: 32, height: 32)
                .background(Color.accentColor.opacity(0.12), in: Circle())
            VStack(alignment: .leading, spacing: 3) { Text(title).font(.headline); Text(detail).font(.subheadline).foregroundStyle(.secondary) }
            Spacer(minLength: 0)
        }.padding(15).card()
    }

    private var dictionaryCard: some View {
        VStack(alignment: .leading, spacing: 9) {
            HStack { Label(AppStrings.text("dictionary"), systemImage: "books.vertical.fill").font(.headline); Spacer(); if readingUpdater.isUpdating { ProgressView() } }
            Text(readingUpdater.status).font(.subheadline).foregroundStyle(.secondary)
            Button(AppStrings.text("check_update")) { Task { await readingUpdater.update() } }.buttonStyle(.bordered).disabled(readingUpdater.isUpdating)
        }.padding(16).card()
    }

    private var privacyCard: some View {
        HStack(alignment: .top, spacing: 13) {
            Image(systemName: "lock.shield.fill").font(.title2).foregroundStyle(.green)
            VStack(alignment: .leading, spacing: 4) { Text(AppStrings.text("full_access_title")).font(.headline); Text(AppStrings.text("full_access_detail")).font(.subheadline).foregroundStyle(.secondary) }
        }.padding(16).background(Color.green.opacity(0.09), in: RoundedRectangle(cornerRadius: 17))
    }
}

private struct SettingsView: View {
    @ObservedObject var model: SettingsModel
    var body: some View {
        List {
            Section(AppStrings.text("input")) {
                Picker(AppStrings.text("initial_panel"), selection: $model.preferredPanel) {
                    Text(AppStrings.text("handwriting")).tag(PreferredKeyboardPanel.handwriting)
                    Text(AppStrings.text("japanese")).tag(PreferredKeyboardPanel.japanese)
                    Text(AppStrings.text("english")).tag(PreferredKeyboardPanel.english)
                }
                Toggle(AppStrings.text("continuous"), isOn: $model.continuousHandwriting)
                Toggle(AppStrings.text("number_row"), isOn: $model.showNumberRow)
            }
            Section(AppStrings.text("display")) {
                Picker(AppStrings.text("reading_display"), selection: $model.readingMode) {
                    Text(AppStrings.text("kana")).tag(ReadingDisplayMode.kana); Text("Romaji").tag(ReadingDisplayMode.romaji); Text(AppStrings.text("hidden")).tag(ReadingDisplayMode.hidden)
                }
                Picker(AppStrings.text("keyboard_height"), selection: $model.keyboardHeight) {
                    Text(AppStrings.text("compact")).tag(KeyboardHeight.compact); Text(AppStrings.text("standard")).tag(KeyboardHeight.standard); Text(AppStrings.text("large")).tag(KeyboardHeight.tall)
                }
                Picker(AppStrings.text("candidate_size"), selection: $model.candidateTextSize) {
                    Text(AppStrings.text("small")).tag(CandidateTextSize.compact); Text(AppStrings.text("standard")).tag(CandidateTextSize.standard); Text(AppStrings.text("large")).tag(CandidateTextSize.large)
                }
                Picker(AppStrings.text("accent_color"), selection: $model.accent) {
                    ForEach(AccentChoice.allCases) { value in Label(value.label, systemImage: "circle.fill").foregroundStyle(value.swiftUIColor).tag(value) }
                }
            }
            Section(AppStrings.text("effects")) {
                Toggle(AppStrings.text("haptics"), isOn: $model.hapticsEnabled)
                Toggle(AppStrings.text("key_clicks"), isOn: $model.keyClicksEnabled)
            }
            Section(AppStrings.text("language")) {
                Picker(AppStrings.text("language"), selection: $model.language) {
                    Text(AppStrings.text("automatic")).tag(AppLanguage.automatic); Text("日本語").tag(AppLanguage.japanese); Text("English").tag(AppLanguage.english)
                    Text("简体中文").tag(AppLanguage.simplifiedChinese); Text("한국어").tag(AppLanguage.korean)
                }
            }
            Section(AppStrings.text("privacy")) {
                NavigationLink(AppStrings.text("privacy_policy")) { LegalView(title: AppStrings.text("privacy_policy"), resource: legalResource("privacy-policy")) }
                NavigationLink(AppStrings.text("terms")) { LegalView(title: AppStrings.text("terms"), resource: legalResource("terms")) }
                NavigationLink(AppStrings.text("licenses")) { LicensesView() }
            }
            Section(AppStrings.text("about")) {
                LabeledContent(AppStrings.text("version"), value: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "—")
                Link(AppStrings.text("support"), destination: URL(string: "mailto:support@hanlu.app")!)
                Link(AppStrings.text("website"), destination: URL(string: "https://hanlu.app")!)
            }
        }.navigationTitle(AppStrings.text("settings"))
    }

    private func legalResource(_ base: String) -> String {
        switch AppStrings.language {
        case .japanese: return base + "-ja"
        case .simplifiedChinese: return base + "-zh-CN"
        case .korean: return base + "-ko"
        case .english, .automatic: return base + "-en"
        }
    }
}

private struct LegalView: View {
    let title: String; let resource: String
    var body: some View { ScrollView { Text(content).frame(maxWidth: .infinity, alignment: .leading).padding() }.navigationTitle(title).navigationBarTitleDisplayMode(.inline) }
    private var content: String {
        guard let url = Bundle.main.url(forResource: resource, withExtension: "txt"), let value = try? String(contentsOf: url, encoding: .utf8) else { return AppStrings.text("document_unavailable") }
        return value
    }
}

private struct LicensesView: View {
    private let files = ["THIRD-PARTY-NOTICES", "ZINNIA-BSD", "TEGAKI-MODEL-LGPL-2.1", "EDRDG-CC-BY-SA-4.0", "APACHE-2.0"]
    var body: some View {
        List(files, id: \.self) { file in NavigationLink(file) { LegalView(title: file, resource: file) } }
            .navigationTitle(AppStrings.text("licenses"))
    }
}

private extension View {
    func card() -> some View { background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 17)) }
}

private extension AccentChoice {
    var swiftUIColor: Color {
        switch self { case .blue: .blue; case .green: .green; case .orange: .orange; case .pink: .pink; case .purple: .purple }
    }
    var label: String { AppStrings.text("accent_\(rawValue)") }
}
