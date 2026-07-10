import UIKit

final class KeyboardViewController: UIInputViewController {
    private let recognizer = OfflineRecognizer()
    private let candidateStack = UIStackView()
    private let candidateScroll = UIScrollView()
    private let canvas = HandwritingCanvasView()
    private let statusLabel = UILabel()
    private let controls = UIStackView()

    override func viewDidLoad() {
        super.viewDidLoad()
        buildInterface()
        canvas.onInkChanged = { [weak self] strokes, size in
            self?.recognize(strokes: strokes, size: size)
        }
        showInitialState()
    }

    override func viewWillLayoutSubviews() {
        super.viewWillLayoutSubviews()
        nextKeyboardButton?.isHidden = !needsInputModeSwitchKey
    }

    private weak var nextKeyboardButton: UIButton?

    private func buildInterface() {
        view.backgroundColor = .systemBackground
        let root = UIStackView()
        root.axis = .vertical
        root.spacing = 8
        root.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(root)

        candidateStack.axis = .horizontal
        candidateStack.spacing = 6
        candidateStack.alignment = .fill
        candidateScroll.showsHorizontalScrollIndicator = false
        candidateScroll.addSubview(candidateStack)
        candidateStack.translatesAutoresizingMaskIntoConstraints = false
        root.addArrangedSubview(candidateScroll)

        statusLabel.font = .preferredFont(forTextStyle: .footnote)
        statusLabel.textColor = .secondaryLabel
        statusLabel.textAlignment = .center
        candidateStack.addArrangedSubview(statusLabel)

        let writingRow = UIStackView(arrangedSubviews: [canvas, controls])
        writingRow.axis = .horizontal
        writingRow.spacing = 8
        controls.axis = .vertical
        controls.spacing = 6
        controls.distribution = .fillEqually
        root.addArrangedSubview(writingRow)

        let next = keyButton(title: "🌐", accessibility: "次のキーボード") { [weak self] in
            self?.advanceToNextInputMode()
        }
        nextKeyboardButton = next
        controls.addArrangedSubview(next)
        controls.addArrangedSubview(keyButton(title: "消去", accessibility: "手書きを消去") { [weak self] in
            self?.clearInk()
        })
        controls.addArrangedSubview(keyButton(title: "⌫", accessibility: "一文字削除") { [weak self] in
            self?.textDocumentProxy.deleteBackward()
        })
        controls.addArrangedSubview(keyButton(title: "空白", accessibility: "空白") { [weak self] in
            self?.textDocumentProxy.insertText(" ")
        })
        controls.addArrangedSubview(keyButton(title: "改行", accessibility: "改行") { [weak self] in
            self?.textDocumentProxy.insertText("\n")
        })

        NSLayoutConstraint.activate([
            root.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 8),
            root.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -8),
            root.topAnchor.constraint(equalTo: view.topAnchor, constant: 7),
            root.bottomAnchor.constraint(equalTo: view.bottomAnchor, constant: -7),
            candidateScroll.heightAnchor.constraint(equalToConstant: 48),
            candidateStack.leadingAnchor.constraint(equalTo: candidateScroll.contentLayoutGuide.leadingAnchor),
            candidateStack.trailingAnchor.constraint(equalTo: candidateScroll.contentLayoutGuide.trailingAnchor),
            candidateStack.topAnchor.constraint(equalTo: candidateScroll.contentLayoutGuide.topAnchor),
            candidateStack.bottomAnchor.constraint(equalTo: candidateScroll.contentLayoutGuide.bottomAnchor),
            candidateStack.heightAnchor.constraint(equalTo: candidateScroll.frameLayoutGuide.heightAnchor),
            controls.widthAnchor.constraint(equalToConstant: 68),
            view.heightAnchor.constraint(equalToConstant: 286)
        ])
    }

    private func keyButton(title: String, accessibility: String, action: @escaping () -> Void) -> UIButton {
        let button = UIButton(type: .system)
        button.setTitle(title, for: .normal)
        button.titleLabel?.font = .systemFont(ofSize: title.count > 1 ? 13 : 20, weight: .semibold)
        button.backgroundColor = .tertiarySystemFill
        button.layer.cornerRadius = 10
        button.accessibilityLabel = accessibility
        button.addAction(UIAction { _ in action() }, for: .touchUpInside)
        return button
    }

    private func recognize(strokes: [[CGPoint]], size: CGSize) {
        statusLabel.text = "認識中…"
        recognizer.recognize(strokes: strokes, canvasSize: size) { [weak self] candidates in
            self?.show(candidates: candidates)
        }
    }

    private func show(candidates: [RecognitionCandidate]) {
        candidateStack.arrangedSubviews.forEach { view in
            candidateStack.removeArrangedSubview(view)
            view.removeFromSuperview()
        }
        guard !candidates.isEmpty else {
            statusLabel.text = recognizer.isReady ? "候補がありません" : "オフラインモデルを読み込めません"
            candidateStack.addArrangedSubview(statusLabel)
            return
        }
        for candidate in candidates {
            let title = candidate.reading.map { "\(candidate.text)  \($0)" } ?? candidate.text
            let button = keyButton(title: title, accessibility: "\(candidate.text)、\(candidate.reading ?? "読みなし")") { [weak self] in
                self?.textDocumentProxy.insertText(candidate.text)
                self?.clearInk()
            }
            button.contentEdgeInsets = UIEdgeInsets(top: 6, left: 14, bottom: 6, right: 14)
            button.titleLabel?.font = .systemFont(ofSize: 17, weight: .medium)
            candidateStack.addArrangedSubview(button)
        }
    }

    private func clearInk() {
        recognizer.cancel()
        canvas.clear()
        showInitialState()
    }

    private func showInitialState() {
        show(candidates: [])
        statusLabel.text = recognizer.isReady ? "一文字を書いてください" : "オフラインモデルを読み込めません"
    }
}

