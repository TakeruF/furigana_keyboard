import AudioToolbox
import UIKit

extension AccentChoice {
    var color: UIColor {
        switch self {
        case .blue: .systemBlue
        case .green: .systemGreen
        case .orange: .systemOrange
        case .pink: .systemPink
        case .purple: .systemPurple
        }
    }
}

enum KeyboardFeedback {
    static func key(preferences: KeyboardPreferences) {
        if preferences.hapticsEnabled { UIImpactFeedbackGenerator(style: .light).impactOccurred(intensity: 0.45) }
        if preferences.keyClicksEnabled { AudioServicesPlaySystemSound(1104) }
    }
}

final class RepeatButton: UIButton {
    var repeatedAction: (() -> Void)?
    private var timer: Timer?

    override init(frame: CGRect) {
        super.init(frame: frame)
        addTarget(self, action: #selector(start), for: .touchDown)
        addTarget(self, action: #selector(stop), for: [.touchUpInside, .touchUpOutside, .touchCancel, .touchDragExit])
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
    @objc private func start() {
        repeatedAction?()
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 0.42, repeats: false) { [weak self] _ in
            self?.timer = Timer.scheduledTimer(withTimeInterval: 0.075, repeats: true) { [weak self] _ in self?.repeatedAction?() }
        }
    }
    @objc private func stop() { timer?.invalidate(); timer = nil }
    deinit { timer?.invalidate() }
}

final class CandidateBarView: UIView {
    var onSelect: ((KeyboardCandidate) -> Void)?
    private let scroll = UIScrollView()
    private let stack = UIStackView()
    private let status = UILabel()
    private var preferences = KeyboardPreferences()

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .secondarySystemBackground
        scroll.showsHorizontalScrollIndicator = false
        scroll.translatesAutoresizingMaskIntoConstraints = false
        addSubview(scroll)
        stack.axis = .horizontal; stack.spacing = 0; stack.translatesAutoresizingMaskIntoConstraints = false
        scroll.addSubview(stack)
        status.font = .preferredFont(forTextStyle: .footnote); status.textColor = .secondaryLabel
        status.textAlignment = .center
        NSLayoutConstraint.activate([
            scroll.leadingAnchor.constraint(equalTo: leadingAnchor), scroll.trailingAnchor.constraint(equalTo: trailingAnchor),
            scroll.topAnchor.constraint(equalTo: topAnchor), scroll.bottomAnchor.constraint(equalTo: bottomAnchor),
            stack.leadingAnchor.constraint(equalTo: scroll.contentLayoutGuide.leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: scroll.contentLayoutGuide.trailingAnchor),
            stack.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor),
            stack.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor),
            stack.heightAnchor.constraint(equalTo: scroll.frameLayoutGuide.heightAnchor)
        ])
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    func reloadPreferences() { preferences = KeyboardPreferences() }

    func show(status text: String) {
        clear(); status.text = text; stack.addArrangedSubview(status)
        status.widthAnchor.constraint(greaterThanOrEqualToConstant: 220).isActive = true
    }

    func show(_ candidates: [KeyboardCandidate]) {
        clear()
        guard !candidates.isEmpty else { return }
        for (index, candidate) in candidates.enumerated() {
            let button = UIButton(type: .system)
            button.tintColor = index == 0 ? preferences.accent.color : .label
            button.backgroundColor = .clear
            var configuration = UIButton.Configuration.plain()
            configuration.contentInsets = NSDirectionalEdgeInsets(top: 3, leading: 12, bottom: 3, trailing: 12)
            configuration.title = candidate.text
            configuration.titleTextAttributesTransformer = UIConfigurationTextAttributesTransformer { [preferences] incoming in
                var outgoing = incoming
                outgoing.font = UIFont.systemFont(ofSize: preferences.candidateTextSize.primary, weight: .semibold)
                outgoing.foregroundColor = index == 0 ? preferences.accent.color : .label
                return outgoing
            }
            let reading = readingText(candidate.readings)
            if !reading.isEmpty {
                configuration.subtitle = reading
                configuration.subtitleTextAttributesTransformer = UIConfigurationTextAttributesTransformer { incoming in
                    var outgoing = incoming
                    outgoing.font = UIFont.systemFont(ofSize: 10)
                    outgoing.foregroundColor = .secondaryLabel
                    return outgoing
                }
            }
            configuration.titleAlignment = .center
            button.configuration = configuration
            button.accessibilityLabel = reading.isEmpty ? candidate.text : "\(candidate.text), \(reading)"
            button.addAction(UIAction { [weak self] _ in self?.onSelect?(candidate) }, for: .touchUpInside)
            stack.addArrangedSubview(button)
            if index < candidates.count - 1 {
                let divider = UIView(); divider.backgroundColor = .separator
                divider.widthAnchor.constraint(equalToConstant: 0.5).isActive = true
                stack.addArrangedSubview(divider)
            }
        }
        scroll.setContentOffset(.zero, animated: false)
    }

    func clear() {
        stack.arrangedSubviews.forEach { stack.removeArrangedSubview($0); $0.removeFromSuperview() }
    }

    private func readingText(_ values: [String]) -> String {
        switch preferences.readingMode {
        case .hidden: return ""
        case .kana: return values.prefix(3).map(formatReading).joined(separator: " / ")
        case .romaji: return values.prefix(3).map { KanaRomanizer.convert(formatReading($0)) }.joined(separator: " / ")
        }
    }

    private func formatReading(_ value: String) -> String {
        guard let dot = value.firstIndex(of: ".") else { return value }
        return String(value[..<dot]) + "(" + String(value[value.index(after: dot)...]) + ")"
    }
}

final class QwertyPanelView: UIStackView {
    var onText: ((String) -> Void)?
    var onDelete: (() -> Void)?
    var onEnter: (() -> Void)?
    var onSymbols: (() -> Void)?
    var onSwitchLanguage: (() -> Void)?

    private let japanese: Bool
    private var shifted = false
    private var letterButtons: [UIButton] = []
    private var preferences = KeyboardPreferences()

    init(japanese: Bool) {
        self.japanese = japanese
        super.init(frame: .zero)
        axis = .vertical; spacing = 4; distribution = .fillEqually
        rebuild()
    }
    required init(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    func rebuild() {
        preferences = KeyboardPreferences()
        arrangedSubviews.forEach { removeArrangedSubview($0); $0.removeFromSuperview() }
        letterButtons.removeAll()
        if preferences.showNumberRow { addArrangedSubview(row(Array("1234567890").map(String.init))) }
        addArrangedSubview(row(Array("qwertyuiop").map(String.init), letters: true))
        let middle = japanese ? Array("asdfghjkl").map(String.init) + ["ー"] : Array("asdfghjkl").map(String.init)
        addArrangedSubview(row(middle, letters: true, sideInset: japanese ? 0 : 18))
        addArrangedSubview(shiftRow())
        addArrangedSubview(bottomRow())
        updateShift()
    }

    private func row(_ values: [String], letters: Bool = false, sideInset: CGFloat = 0) -> UIView {
        let stack = UIStackView(); stack.axis = .horizontal; stack.spacing = 4; stack.distribution = .fillEqually
        if sideInset > 0 { let spacer = UIView(); spacer.widthAnchor.constraint(equalToConstant: sideInset).isActive = true; stack.addArrangedSubview(spacer) }
        values.forEach { value in
            let button = key(value)
            if letters && value.first?.isLetter == true { letterButtons.append(button) }
            button.addAction(UIAction { [weak self] _ in
                guard let self else { return }
                KeyboardFeedback.key(preferences: self.preferences)
                let output = self.shifted ? value.uppercased() : value.lowercased()
                self.onText?(output)
                if self.shifted { self.shifted = false; self.updateShift() }
            }, for: .touchUpInside)
            stack.addArrangedSubview(button)
        }
        if sideInset > 0 { let spacer = UIView(); spacer.widthAnchor.constraint(equalToConstant: sideInset).isActive = true; stack.addArrangedSubview(spacer) }
        return stack
    }

    private func shiftRow() -> UIView {
        let stack = UIStackView(); stack.axis = .horizontal; stack.spacing = 4; stack.distribution = .fillEqually
        let shift = functionKey("⇧") { [weak self] in self?.shifted.toggle(); self?.updateShift() }
        shift.accessibilityLabel = "Shift"; stack.addArrangedSubview(shift)
        Array("zxcvbnm").map(String.init).forEach { value in
            let button = key(value); letterButtons.append(button)
            button.addAction(UIAction { [weak self] _ in
                guard let self else { return }
                KeyboardFeedback.key(preferences: self.preferences)
                self.onText?(self.shifted ? value.uppercased() : value)
                if self.shifted { self.shifted = false; self.updateShift() }
            }, for: .touchUpInside); stack.addArrangedSubview(button)
        }
        let delete = RepeatButton(type: .system); style(delete, function: true); delete.setTitle("⌫", for: .normal)
        delete.repeatedAction = { [weak self] in self?.onDelete?() }; stack.addArrangedSubview(delete)
        return stack
    }

    private func bottomRow() -> UIView {
        let stack = UIStackView(); stack.axis = .horizontal; stack.spacing = 4
        let switcher = functionKey(japanese ? "ABC" : "かな") { [weak self] in self?.onSwitchLanguage?() }
        let symbols = functionKey("123") { [weak self] in self?.onSymbols?() }
        let comma = key(japanese ? "、" : ","); comma.addAction(action(japanese ? "、" : ","), for: .touchUpInside)
        let space = key(AppStrings.text("space")); space.addAction(action(" "), for: .touchUpInside)
        let period = key(japanese ? "。" : "."); period.addAction(action(japanese ? "。" : "."), for: .touchUpInside)
        let enter = functionKey(AppStrings.text("return")) { [weak self] in self?.onEnter?() }
        enter.backgroundColor = preferences.accent.color; enter.setTitleColor(.white, for: .normal)
        [switcher, symbols, comma, space, period, enter].forEach { stack.addArrangedSubview($0) }
        switcher.widthAnchor.constraint(equalTo: space.widthAnchor, multiplier: 0.75).isActive = true
        symbols.widthAnchor.constraint(equalTo: space.widthAnchor, multiplier: 0.62).isActive = true
        comma.widthAnchor.constraint(equalTo: space.widthAnchor, multiplier: 0.5).isActive = true
        period.widthAnchor.constraint(equalTo: space.widthAnchor, multiplier: 0.5).isActive = true
        enter.widthAnchor.constraint(equalTo: space.widthAnchor, multiplier: 0.9).isActive = true
        return stack
    }

    private func action(_ value: String) -> UIAction { UIAction { [weak self] _ in
        guard let self else { return }; KeyboardFeedback.key(preferences: self.preferences); self.onText?(value)
    }}

    private func updateShift() { letterButtons.forEach { $0.setTitle(shifted ? $0.currentTitle?.uppercased() : $0.currentTitle?.lowercased(), for: .normal) } }
    private func key(_ title: String) -> UIButton { let value = UIButton(type: .system); style(value, function: false); value.setTitle(title, for: .normal); return value }
    private func functionKey(_ title: String, action: @escaping () -> Void) -> UIButton {
        let value = UIButton(type: .system); style(value, function: true); value.setTitle(title, for: .normal)
        value.addAction(UIAction { [weak self] _ in if let self { KeyboardFeedback.key(preferences: self.preferences) }; action() }, for: .touchUpInside)
        return value
    }
    private func style(_ button: UIButton, function: Bool) {
        button.backgroundColor = function ? .tertiarySystemFill : .secondarySystemBackground
        button.setTitleColor(.label, for: .normal); button.titleLabel?.font = .systemFont(ofSize: 17, weight: .medium)
        button.layer.cornerRadius = 6; button.layer.shadowOpacity = 0.08; button.layer.shadowRadius = 0.5; button.layer.shadowOffset = CGSize(width: 0, height: 1)
    }
}

final class SymbolPanelView: UIStackView {
    var onText: ((String) -> Void)?; var onDelete: (() -> Void)?; var onEnter: (() -> Void)?; var onBack: (() -> Void)?
    private var preferences = KeyboardPreferences()
    override init(frame: CGRect) {
        super.init(frame: frame); axis = .vertical; spacing = 4; distribution = .fillEqually
        [["@","1","2","3","⌫"],["-","4","5","6","/"],["(","7","8","9",")"],["戻る","。","0","、","改行"]].forEach { values in
            let row = UIStackView(); row.axis = .horizontal; row.spacing = 4; row.distribution = .fillEqually
            values.forEach { value in
                let button = UIButton(type: .system); button.setTitle(value, for: .normal); button.setTitleColor(.label, for: .normal)
                button.titleLabel?.font = .systemFont(ofSize: value.count > 1 ? 13 : 22, weight: .medium)
                button.backgroundColor = ["戻る","⌫","改行"].contains(value) ? .tertiarySystemFill : .secondarySystemBackground
                button.layer.cornerRadius = 6
                button.addAction(UIAction { [weak self] _ in
                    guard let self else { return }; KeyboardFeedback.key(preferences: preferences)
                    switch value { case "戻る": onBack?(); case "⌫": onDelete?(); case "改行": onEnter?(); default: onText?(value) }
                }, for: .touchUpInside); row.addArrangedSubview(button)
            }; addArrangedSubview(row)
        }
    }
    required init(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
}
