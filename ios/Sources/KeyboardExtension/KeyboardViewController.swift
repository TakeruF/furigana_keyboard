import UIKit

final class KeyboardViewController: UIInputViewController {
    private let recognizer = OfflineRecognizer()
    private let candidateEngine = CandidateEngine()
    private let candidateBar = CandidateBarView()
    private let panelHost = UIView()
    private let canvas = HandwritingCanvasView()
    private let handwritingControls = UIStackView()

    private lazy var japanesePanel = QwertyPanelView(japanese: true)
    private lazy var englishPanel = QwertyPanelView(japanese: false)
    private lazy var symbolPanel = SymbolPanelView()
    private var activePanel: PreferredKeyboardPanel = .handwriting
    private var panelBeforeSymbols: PreferredKeyboardPanel = .handwriting
    private var heightConstraint: NSLayoutConstraint?
    private var preferences = KeyboardPreferences()

    private var composition = ""
    private var romajiRaw = ""
    private var handwritingBase: String?
    private var handwritingTop: KeyboardCandidate?
    private var currentCandidates: [KeyboardCandidate] = []
    private var bunsetsuState: BunsetsuState?
    private let romajiConversionState = RomajiConversionState()

    override func viewDidLoad() {
        super.viewDidLoad()
        buildInterface()
        wireActions()
        reloadPreferences()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        reloadPreferences()
    }

    override func viewWillLayoutSubviews() {
        super.viewWillLayoutSubviews()
        heightConstraint?.constant = preferences.keyboardHeight.points
    }

    private func buildInterface() {
        view.backgroundColor = .systemBackground
        let root = UIStackView(arrangedSubviews: [candidateBar, panelHost])
        root.axis = .vertical; root.spacing = 6; root.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(root)
        panelHost.translatesAutoresizingMaskIntoConstraints = false
        let height = view.heightAnchor.constraint(equalToConstant: preferences.keyboardHeight.points)
        height.priority = UILayoutPriority(999); height.isActive = true; heightConstraint = height
        NSLayoutConstraint.activate([
            root.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 5),
            root.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -5),
            root.topAnchor.constraint(equalTo: view.topAnchor, constant: 4),
            root.bottomAnchor.constraint(equalTo: view.bottomAnchor, constant: -5),
            candidateBar.heightAnchor.constraint(equalToConstant: 54)
        ])
    }

    private func wireActions() {
        candidateBar.onSelect = { [weak self] candidate in self?.select(candidate) }
        canvas.onInkChanged = { [weak self] strokes, size in self?.recognize(strokes: strokes, size: size) }
        canvas.onNewCharacterGate = { [weak self] in self?.acceptContinuousHandwriting() ?? false }

        japanesePanel.onText = { [weak self] in self?.handleJapaneseKey($0) }
        japanesePanel.onDelete = { [weak self] in self?.deleteBackward() }
        japanesePanel.onEnter = { [weak self] in self?.handleReturn() }
        japanesePanel.onSymbols = { [weak self] in self?.showSymbols() }
        japanesePanel.onSwitchLanguage = { [weak self] in self?.show(panel: .english) }

        englishPanel.onText = { [weak self] in self?.commitDirect($0) }
        englishPanel.onDelete = { [weak self] in self?.deleteBackward() }
        englishPanel.onEnter = { [weak self] in self?.handleReturn() }
        englishPanel.onSymbols = { [weak self] in self?.showSymbols() }
        englishPanel.onSwitchLanguage = { [weak self] in self?.show(panel: .japanese) }

        symbolPanel.onText = { [weak self] in self?.commitDirect($0) }
        symbolPanel.onDelete = { [weak self] in self?.deleteBackward() }
        symbolPanel.onEnter = { [weak self] in self?.handleReturn() }
        symbolPanel.onBack = { [weak self] in guard let self else { return }; self.show(panel: self.panelBeforeSymbols) }
    }

    private func reloadPreferences() {
        preferences = KeyboardPreferences()
        candidateBar.reloadPreferences()
        canvas.hapticsEnabled = preferences.hapticsEnabled
        japanesePanel.rebuild(); englishPanel.rebuild()
        heightConstraint?.constant = preferences.keyboardHeight.points
        if composition.isEmpty { show(panel: preferences.preferredPanel, commitCurrent: false) }
        else { candidateBar.show(currentCandidates) }
    }

    private func show(panel: PreferredKeyboardPanel, commitCurrent: Bool = true) {
        if commitCurrent { finishComposition() }
        activePanel = panel
        panelHost.subviews.forEach { $0.removeFromSuperview() }
        let displayedPanel: UIView
        switch panel {
        case .handwriting: displayedPanel = makeHandwritingPanel()
        case .japanese: displayedPanel = japanesePanel
        case .english: displayedPanel = englishPanel
        }
        displayedPanel.translatesAutoresizingMaskIntoConstraints = false; panelHost.addSubview(displayedPanel)
        NSLayoutConstraint.activate([
            displayedPanel.leadingAnchor.constraint(equalTo: panelHost.leadingAnchor),
            displayedPanel.trailingAnchor.constraint(equalTo: panelHost.trailingAnchor),
            displayedPanel.topAnchor.constraint(equalTo: panelHost.topAnchor),
            displayedPanel.bottomAnchor.constraint(equalTo: panelHost.bottomAnchor)
        ])
        if composition.isEmpty {
            candidateBar.show(status: panel == .handwriting ? AppStrings.text("write_hint") :
                                (panel == .english ? AppStrings.text("english") : AppStrings.text("japanese")))
        }
    }

    private func showSymbols() {
        finishComposition(); panelBeforeSymbols = activePanel
        panelHost.subviews.forEach { $0.removeFromSuperview() }
        symbolPanel.translatesAutoresizingMaskIntoConstraints = false; panelHost.addSubview(symbolPanel)
        NSLayoutConstraint.activate([
            symbolPanel.leadingAnchor.constraint(equalTo: panelHost.leadingAnchor), symbolPanel.trailingAnchor.constraint(equalTo: panelHost.trailingAnchor),
            symbolPanel.topAnchor.constraint(equalTo: panelHost.topAnchor), symbolPanel.bottomAnchor.constraint(equalTo: panelHost.bottomAnchor)
        ])
        candidateBar.show(status: AppStrings.text("symbols"))
    }

    private func makeHandwritingPanel() -> UIView {
        handwritingControls.arrangedSubviews.forEach { handwritingControls.removeArrangedSubview($0); $0.removeFromSuperview() }
        handwritingControls.axis = .vertical; handwritingControls.spacing = 4; handwritingControls.distribution = .fillEqually
        let globe = functionButton("🌐", accessibility: AppStrings.text("next_keyboard")) {}
        globe.addTarget(self, action: #selector(handleInputModeList(from:with:)), for: .allTouchEvents)
        let language = functionButton("かな", accessibility: AppStrings.text("japanese")) { [weak self] in self?.show(panel: .japanese) }
        handwritingControls.addArrangedSubview(controlRow(globe, language))
        handwritingControls.addArrangedSubview(repeatDeleteButton())
        handwritingControls.addArrangedSubview(controlRow(
            functionButton(AppStrings.text("space"), accessibility: AppStrings.text("space")) { [weak self] in self?.commitDirect(" ") },
            functionButton("123", accessibility: AppStrings.text("symbols")) { [weak self] in self?.showSymbols() }
        ))
        let enter = functionButton(AppStrings.text("return"), accessibility: AppStrings.text("return")) { [weak self] in self?.handleReturn() }
        enter.backgroundColor = preferences.accent.color; enter.setTitleColor(.white, for: .normal)
        handwritingControls.addArrangedSubview(enter)

        let row = UIStackView(arrangedSubviews: [canvas, handwritingControls])
        row.axis = .horizontal; row.spacing = 6
        handwritingControls.widthAnchor.constraint(equalToConstant: 124).isActive = true
        return row
    }

    private func controlRow(_ first: UIView, _ second: UIView) -> UIView {
        let row = UIStackView(arrangedSubviews: [first, second]); row.axis = .horizontal; row.spacing = 4; row.distribution = .fillEqually
        return row
    }

    private func functionButton(_ title: String, accessibility: String, action: @escaping () -> Void) -> UIButton {
        let button = UIButton(type: .system); button.setTitle(title, for: .normal); button.accessibilityLabel = accessibility
        button.titleLabel?.font = .systemFont(ofSize: title.count > 2 ? 12 : 17, weight: .semibold)
        button.backgroundColor = .tertiarySystemFill; button.setTitleColor(.label, for: .normal); button.layer.cornerRadius = 7
        button.addAction(UIAction { [weak self] _ in
            if let self { KeyboardFeedback.key(preferences: self.preferences) }; action()
        }, for: .touchUpInside)
        return button
    }

    private func repeatDeleteButton() -> UIButton {
        let button = RepeatButton(type: .system); button.setTitle("⌫", for: .normal); button.accessibilityLabel = "Delete"
        button.titleLabel?.font = .systemFont(ofSize: 19, weight: .semibold); button.backgroundColor = .tertiarySystemFill
        button.setTitleColor(.label, for: .normal); button.layer.cornerRadius = 7
        button.repeatedAction = { [weak self] in self?.deleteBackward() }
        return button
    }

    private func recognize(strokes: [[CGPoint]], size: CGSize) {
        if handwritingBase == nil { handwritingBase = composition }
        candidateBar.show(status: AppStrings.text("recognizing"))
        recognizer.recognize(strokes: strokes, canvasSize: size) { [weak self] recognized in
            guard let self else { return }
            guard !recognized.isEmpty else {
                candidateBar.show(status: recognizer.isReady ? AppStrings.text("no_candidates") : AppStrings.text("model_error")); return
            }
            candidateEngine.resolveHandwriting(base: handwritingBase ?? "", recognized: recognized) { [weak self] candidates in
                guard let self, !candidates.isEmpty else { return }
                currentCandidates = candidates; handwritingTop = candidates.first
                composition = candidates[0].text
                textDocumentProxy.setMarkedText(composition, selectedRange: NSRange(location: composition.utf16.count, length: 0))
                candidateBar.show(candidates); canvas.markResultsDelivered()
            }
        }
    }

    private func acceptContinuousHandwriting() -> Bool {
        guard preferences.continuousHandwriting, handwritingTop != nil else { return false }
        // Keep the accepted character in the same marked-text composition so
        // the next character can produce JMdict word completions.
        handwritingBase = composition; handwritingTop = nil
        candidateEngine.invalidate()
        return true
    }

    private func refreshCandidatesAfterHandwritingDeletion() {
        if composition.isEmpty {
            currentCandidates = []
            candidateBar.show(status: recognizer.isReady ? AppStrings.text("write_hint") : AppStrings.text("model_error"))
        } else {
            candidateEngine.suggestSurface(composition) { [weak self] candidates in
                guard let self else { return }
                self.currentCandidates = [KeyboardCandidate(self.composition, kind: .character)] + candidates
                self.candidateBar.show(self.currentCandidates)
            }
        }
    }

    private func handleJapaneseKey(_ value: String) {
        if value == " " {
            handleJapaneseSpace()
            return
        }
        if bunsetsuState != nil { finishComposition() }
        if value.count == 1, value.first?.isASCII == true, value.first?.isLetter == true {
            romajiRaw += value.lowercased(); updateRomaji(); return
        }
        if value == "ー" { romajiRaw += value; updateRomaji(); return }
        commitDirect(value)
    }

    private func updateRomaji() {
        candidateEngine.invalidate()
        let converted = RomajiKanaConverter.convert(romajiRaw)
        composition = converted.displayText
        romajiConversionState.compositionEdited(hasComposition: !composition.isEmpty)
        textDocumentProxy.setMarkedText(composition, selectedRange: NSRange(location: composition.utf16.count, length: 0))
        if converted.hasUnresolvedInput || converted.kana.isEmpty {
            currentCandidates = [KeyboardCandidate(composition)]
            showRomajiCandidates(); return
        }
        let scripts = [KeyboardCandidate(converted.kana, readings: [converted.kana]),
                       KeyboardCandidate(RomajiKanaConverter.toKatakana(converted.kana), readings: [converted.kana])]
        currentCandidates = scripts; showRomajiCandidates()
        candidateEngine.analyzeKana(converted.kana) { [weak self] analysis in
            guard let self else { return }
            if let conversion = analysis.conversions.first,
               conversion.segments.contains(where: { !$0.isCopy }),
               let initialLength = KanaKanjiConverter.leadingBunsetsuLength(
                   segments: conversion.segments,
                   totalLength: converted.kana.unicodeScalars.count
               ) {
                beginBunsetsu(
                    reading: converted.kana,
                    initialLength: initialLength,
                    conversions: analysis.conversions
                )
            } else {
                currentCandidates = analysis.candidates; showRomajiCandidates()
            }
        }
    }

    private func beginBunsetsu(
        reading: String,
        initialLength: Int,
        conversions: [KanaKanjiConversion]
    ) {
        let state = BunsetsuState(
            reading: reading,
            initialLength: initialLength,
            conversionPaths: conversions.map(\.segments)
        )
        bunsetsuState = state; romajiRaw = ""; composition = reading
        renderBunsetsu(state)
        loadBunsetsuCandidates(state, preserveBoundary: true)
    }

    private func renderBunsetsu(_ state: BunsetsuState) {
        composition = state.markedText
        textDocumentProxy.setMarkedText(composition, selectedRange: NSRange(location: composition.utf16.count, length: 0))
    }

    private func loadBunsetsuCandidates(_ state: BunsetsuState, preserveBoundary: Bool) {
        let token = state.token()
        guard !token.activeReading.isEmpty, !token.remainingReading.isEmpty else { return }
        showBunsetsuCandidates(state)
        let requiredBoundary = preserveBoundary ? state.activeLength : nil
        candidateEngine.analyzeKana(
            token.remainingReading,
            initialRightID: token.previousRightID,
            initialContextSurface: token.previousContextSurface,
            requiredBoundary: requiredBoundary
        ) { [weak self, weak state] analysis in
            guard let self, let state, bunsetsuState === state, state.isCurrent(token) else { return }
            if state.applyAnalysis(
                analysis.conversions,
                token: token,
                requestedBoundary: requiredBoundary
            ) {
                renderBunsetsu(state)
            }
            showBunsetsuCandidates(state)
        }
    }

    private func showBunsetsuCandidates(_ state: BunsetsuState) {
        let generation = state.generation
        var candidates = state.options().map { option in
            KeyboardCandidate(
                option.surface,
                readings: [option.reading],
                bunsetsuReading: option.reading,
                bunsetsuRightID: option.rightID,
                bunsetsuGeneration: generation
            )
        }
        let scripts = [
            state.activeReading,
            RomajiKanaConverter.toKatakana(state.activeReading)
        ]
        for script in scripts where !candidates.contains(where: { $0.text == script }) {
            candidates.append(KeyboardCandidate(
                script,
                readings: [state.activeReading],
                bunsetsuReading: state.activeReading,
                bunsetsuRightID: state.activeRightID,
                bunsetsuGeneration: generation
            ))
        }
        if state.canShrink {
            candidates.append(KeyboardCandidate(
                "←",
                readings: [AppStrings.text("segment_shrink")],
                kind: .segmentShrink,
                bunsetsuGeneration: generation
            ))
        }
        if state.canExpand {
            candidates.append(KeyboardCandidate(
                "→",
                readings: [AppStrings.text("segment_expand")],
                kind: .segmentExpand,
                bunsetsuGeneration: generation
            ))
        }
        currentCandidates = Array(candidates.prefix(10))
        showRomajiCandidates()
    }

    private func adjustBunsetsu(expand: Bool, candidate: KeyboardCandidate) {
        guard let state = bunsetsuState else { return }
        guard state.isCurrentCandidate(
            reading: state.activeReading,
            generation: candidate.bunsetsuGeneration
        ) else { return }
        if expand { state.expand() } else { state.shrink() }
        romajiConversionState.compositionEdited(hasComposition: true)
        renderBunsetsu(state)
        loadBunsetsuCandidates(state, preserveBoundary: true)
    }

    private func selectBunsetsu(_ candidate: KeyboardCandidate) {
        guard let state = bunsetsuState else { return }
        guard state.isCurrentCandidate(
            reading: candidate.bunsetsuReading,
            generation: candidate.bunsetsuGeneration
        ), state.select(
            surface: candidate.text,
            reading: candidate.bunsetsuReading,
            rightID: candidate.bunsetsuRightID
        ) else { return }
        if state.isComplete {
            textDocumentProxy.setMarkedText(state.committedSurface,
                                            selectedRange: NSRange(location: state.committedSurface.utf16.count, length: 0))
            textDocumentProxy.unmarkText(); resetComposition(clearCandidates: true)
            return
        }
        romajiConversionState.compositionEdited(hasComposition: true)
        renderBunsetsu(state)
        loadBunsetsuCandidates(state, preserveBoundary: false)
    }

    private func select(_ candidate: KeyboardCandidate) {
        guard candidate.kind != .status else { return }
        KeyboardFeedback.key(preferences: preferences)
        if candidate.kind == .segmentShrink { adjustBunsetsu(expand: false, candidate: candidate); return }
        if candidate.kind == .segmentExpand { adjustBunsetsu(expand: true, candidate: candidate); return }
        if bunsetsuState != nil { selectBunsetsu(candidate); return }
        if composition.isEmpty { textDocumentProxy.insertText(candidate.text) }
        else {
            textDocumentProxy.setMarkedText(candidate.text, selectedRange: NSRange(location: candidate.text.utf16.count, length: 0))
            textDocumentProxy.unmarkText()
        }
        canvas.clear(); resetComposition(clearCandidates: true); handwritingBase = nil; handwritingTop = nil
    }

    private func commitDirect(_ text: String) {
        finishComposition(); textDocumentProxy.insertText(text)
    }

    private func finishComposition() {
        if !composition.isEmpty { textDocumentProxy.unmarkText() }
        resetComposition(clearCandidates: true); canvas.clear(); handwritingBase = nil; handwritingTop = nil
    }

    private func resetComposition(clearCandidates: Bool) {
        composition = ""; romajiRaw = ""; currentCandidates = []; bunsetsuState = nil
        romajiConversionState.clear()
        recognizer.cancel(); candidateEngine.invalidate()
        if clearCandidates { candidateBar.clear() }
    }

    private func deleteBackward() {
        KeyboardFeedback.key(preferences: preferences)
        if activePanel == .handwriting, canvas.hasInk {
            recognizer.cancel(); candidateEngine.invalidate()
            if canvas.deleteLastCharacter() {
                handwritingTop = nil
                if canvas.hasInk {
                    candidateBar.show(status: AppStrings.text("recognizing"))
                } else {
                    let remaining = handwritingBase ?? composition
                    if remaining != composition {
                        composition = remaining
                        if remaining.isEmpty {
                            textDocumentProxy.setMarkedText("", selectedRange: NSRange(location: 0, length: 0))
                            textDocumentProxy.unmarkText()
                        } else {
                            textDocumentProxy.setMarkedText(
                                remaining,
                                selectedRange: NSRange(location: remaining.utf16.count, length: 0)
                            )
                        }
                    }
                    handwritingBase = composition
                    refreshCandidatesAfterHandwritingDeletion()
                }
                return
            }
        }
        if let state = bunsetsuState {
            candidateEngine.invalidate()
            guard state.deleteLastScalar() else { return }
            if state.isComplete {
                textDocumentProxy.setMarkedText(
                    state.committedSurface,
                    selectedRange: NSRange(location: state.committedSurface.utf16.count, length: 0)
                )
                textDocumentProxy.unmarkText()
                resetComposition(clearCandidates: true)
            } else {
                romajiConversionState.compositionEdited(hasComposition: true)
                renderBunsetsu(state)
                loadBunsetsuCandidates(state, preserveBoundary: true)
            }
            return
        }
        if !romajiRaw.isEmpty {
            romajiRaw = RomajiKanaConverter.deleteLastUnit(romajiRaw)
            if romajiRaw.isEmpty {
                textDocumentProxy.setMarkedText("", selectedRange: NSRange(location: 0, length: 0)); textDocumentProxy.unmarkText()
                resetComposition(clearCandidates: true)
            } else { updateRomaji() }
            return
        }
        if !composition.isEmpty {
            composition.removeLast()
            if composition.isEmpty {
                textDocumentProxy.setMarkedText("", selectedRange: NSRange(location: 0, length: 0)); textDocumentProxy.unmarkText()
                canvas.clear(); handwritingBase = nil; handwritingTop = nil
                resetComposition(clearCandidates: true)
            } else {
                textDocumentProxy.setMarkedText(composition, selectedRange: NSRange(location: composition.utf16.count, length: 0))
                canvas.clear(); handwritingBase = composition; handwritingTop = nil
                candidateEngine.suggestSurface(composition) { [weak self] candidates in self?.currentCandidates = candidates; self?.candidateBar.show(candidates) }
            }
            return
        }
        textDocumentProxy.deleteBackward()
    }

    private func handleReturn() {
        guard activePanel == .japanese else {
            if !composition.isEmpty { finishComposition() } else { textDocumentProxy.insertText("\n") }
            return
        }
        performRomajiAction(
            romajiConversionState.enter(candidateCount: selectableRomajiCandidates.count)
        )
    }

    private var selectableRomajiCandidates: [KeyboardCandidate] {
        currentCandidates.filter { $0.kind == .word }
    }

    private func showRomajiCandidates() {
        candidateBar.show(currentCandidates)
        candidateBar.setSelectedCandidateIndex(
            romajiConversionState.selectedCandidateIndex(
                candidateCount: selectableRomajiCandidates.count
            )
        )
    }

    private func handleJapaneseSpace() {
        performRomajiAction(
            romajiConversionState.space(candidateCount: selectableRomajiCandidates.count)
        )
    }

    private func performRomajiAction(_ action: RomajiConversionState.Action) {
        switch action {
        case .insertSpace:
            commitDirect(" ")
        case .sendEditorAction:
            textDocumentProxy.insertText("\n")
        case .commitComposition:
            finishComposition()
        case let .selectCandidate(index):
            candidateBar.setSelectedCandidateIndex(index)
        case let .commitCandidate(index):
            guard selectableRomajiCandidates.indices.contains(index) else {
                finishComposition()
                return
            }
            select(selectableRomajiCandidates[index])
        }
    }
}
