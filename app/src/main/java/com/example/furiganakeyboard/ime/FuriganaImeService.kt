package com.example.furiganakeyboard.ime

import android.content.Intent
import android.content.Context
import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.content.res.Configuration
import android.annotation.SuppressLint
import java.util.Locale
import android.widget.Button
import android.widget.FrameLayout
import com.example.furiganakeyboard.R
import com.example.furiganakeyboard.reading.ReadingRepository
import com.example.furiganakeyboard.recognizer.InkRecognizer
import com.example.furiganakeyboard.recognizer.ZinniaInkRecognizer
import com.example.furiganakeyboard.settings.KeyboardPrefs
import com.example.furiganakeyboard.settings.AppLocale
import com.example.furiganakeyboard.settings.ReadingMode
import com.example.furiganakeyboard.settings.SettingsActivity
import com.example.furiganakeyboard.view.CandidateBarView
import com.example.furiganakeyboard.view.CandidateKind
import com.example.furiganakeyboard.view.CandidateUiModel
import com.example.furiganakeyboard.view.HandwritingView
import com.example.furiganakeyboard.view.Haptics
import com.example.furiganakeyboard.view.QwertyPadView
import com.example.furiganakeyboard.view.RepeatOnTouchListener
import com.example.furiganakeyboard.view.SymbolPadView

/** Japanese handwriting IME with fully bundled recognition and reading data. */
class FuriganaImeService : InputMethodService() {
    private enum class Panel { HANDWRITING, SYMBOLS, QWERTY }

    private lateinit var prefs: KeyboardPrefs
    private lateinit var readings: ReadingRepository
    private lateinit var candidateBar: CandidateBarView
    private lateinit var handwritingView: HandwritingView
    private lateinit var handwritingPanel: View
    private lateinit var symbolPad: SymbolPadView
    private lateinit var qwertyPad: QwertyPadView
    private lateinit var modeKey: Button
    private lateinit var enterKey: Button

    private val composition = CompositionBuffer()
    private var recognizer: InkRecognizer? = null
    private var currentPanel = Panel.HANDWRITING
    private var appliedLocaleTag = ""

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        prefs = KeyboardPrefs(this)
        appliedLocaleTag = prefs.localeTag
        readings = ReadingRepository(this)
        Haptics.enabled = prefs.haptics
    }

    @SuppressLint("InflateParams")
    override fun onCreateInputView(): View {
        val root = layoutInflater.inflate(R.layout.keyboard_view, null)
        candidateBar = root.findViewById(R.id.candidateBar)
        handwritingView = root.findViewById(R.id.handwritingView)
        handwritingPanel = root.findViewById(R.id.handwritingPanel)
        modeKey = root.findViewById(R.id.keyMode)
        enterKey = root.findViewById(R.id.keyEnter)

        wireHandwriting()
        wireControlKeys(root)
        buildExtraPanels(root.findViewById(R.id.panelContainer))
        applyReadingMode(prefs.readingMode)
        ensureRecognizer()
        return root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (applyChangedLocale()) {
            setInputView(onCreateInputView())
        }
        composition.clear()
        Haptics.enabled = prefs.haptics
        applyReadingMode(prefs.readingMode)
        updateEnterLabel(info)
        showPanel(Panel.HANDWRITING)
        handwritingView.clear()
        candidateBar.clear()
        ensureRecognizer()
    }

    @Suppress("DEPRECATION")
    private fun applyChangedLocale(): Boolean {
        val requested = prefs.localeTag
        if (requested == appliedLocaleTag) return false
        val configuration = Configuration(resources.configuration)
        val locale = if (requested.isEmpty()) {
            android.content.res.Resources.getSystem().configuration.locales[0]
        } else {
            Locale.forLanguageTag(requested)
        }
        configuration.setLocale(locale)
        configuration.setLayoutDirection(locale)
        resources.updateConfiguration(configuration, resources.displayMetrics)
        appliedLocaleTag = requested
        return true
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        finishComposition(clearCandidates = false)
        super.onFinishInputView(finishingInput)
    }

    private fun ensureRecognizer(): InkRecognizer {
        recognizer?.let { return it }
        return ZinniaInkRecognizer(this).also { engine ->
            engine.onStateChanged = { state ->
                if (this::candidateBar.isInitialized) {
                    when (state) {
                        InkRecognizer.State.PREPARING -> showStatus(getString(R.string.status_model_preparing))
                        InkRecognizer.State.ERROR -> showStatus(getString(R.string.status_model_error))
                        InkRecognizer.State.READY -> {
                            val current = candidateBar.firstCandidate
                            if (current?.kind == CandidateKind.STATUS) {
                                if (composition.isEmpty) candidateBar.clear() else showWordSuggestions()
                            }
                        }
                    }
                }
            }
            recognizer = engine
        }
    }

    private fun buildExtraPanels(container: FrameLayout) {
        symbolPad = SymbolPadView(this)
        qwertyPad = QwertyPadView(this)
        for (pad in listOf<View>(symbolPad, qwertyPad)) {
            pad.visibility = View.GONE
            container.addView(
                pad,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        symbolPad.onText = { commitDirect(it) }
        qwertyPad.onText = { commitDirect(it) }
        symbolPad.onDelete = { deleteBeforeCursor() }
        qwertyPad.onDelete = { deleteBeforeCursor() }
        symbolPad.onEnter = { sendEnter() }
        qwertyPad.onEnter = { sendEnter() }
        symbolPad.onBack = { switchPanel(Panel.HANDWRITING) }
        qwertyPad.onBack = { switchPanel(Panel.HANDWRITING) }
    }

    private fun switchPanel(panel: Panel) {
        if (panel != currentPanel) finishComposition()
        showPanel(panel)
    }

    private fun showPanel(panel: Panel) {
        currentPanel = panel
        handwritingPanel.visibility = if (panel == Panel.HANDWRITING) View.VISIBLE else View.GONE
        symbolPad.visibility = if (panel == Panel.SYMBOLS) View.VISIBLE else View.GONE
        qwertyPad.visibility = if (panel == Panel.QWERTY) View.VISIBLE else View.GONE
    }

    private fun wireHandwriting() {
        handwritingView.onRecognize = { ink ->
            ensureRecognizer().recognize(ink) { values ->
                val candidates = values.map { value ->
                    val resolved = readings.readingsFor(value)
                    CandidateUiModel(
                        text = value,
                        readings = if (resolved.isEmpty() && isHan(value)) {
                            listOf(getString(R.string.reading_unavailable))
                        } else resolved,
                        kind = CandidateKind.CHARACTER
                    )
                }
                candidateBar.setCandidates(candidates)
                if (candidates.isNotEmpty()) handwritingView.markResultsDelivered()
            }
        }

        handwritingView.onNewCharacterGate = gate@{
            if (!prefs.autoCommit) return@gate false
            val first = candidateBar.firstCandidate ?: return@gate false
            if (first.kind != CandidateKind.CHARACTER) return@gate false
            appendToComposition(first.text)
            true
        }

        candidateBar.onCandidateSelected = { candidate ->
            when (candidate.kind) {
                CandidateKind.CHARACTER -> {
                    appendToComposition(candidate.text)
                    handwritingView.clear()
                }
                CandidateKind.WORD -> commitWordCandidate(candidate.text)
                CandidateKind.STATUS -> Unit
            }
        }
    }

    private fun appendToComposition(text: String) {
        val value = composition.append(text)
        currentInputConnection?.setComposingText(value, 1)
        candidateBar.clear()
        showWordSuggestions()
    }

    private fun showWordSuggestions() {
        if (composition.isEmpty) {
            candidateBar.clear()
            return
        }
        val suggestions = readings.suggest(composition.text).map {
            CandidateUiModel(it.surface, it.readings, CandidateKind.WORD)
        }
        if (suggestions.isEmpty()) {
            candidateBar.setCandidates(
                listOf(
                    CandidateUiModel(
                        composition.text,
                        listOf(getString(R.string.reading_not_in_dictionary)),
                        CandidateKind.WORD
                    )
                )
            )
        } else {
            candidateBar.setCandidates(suggestions)
        }
    }

    private fun commitWordCandidate(surface: String) {
        composition.replace(surface)
        currentInputConnection?.setComposingText(surface, 1)
        finishComposition()
        handwritingView.clear()
    }

    private fun showStatus(message: String) {
        candidateBar.setCandidates(listOf(CandidateUiModel(message, kind = CandidateKind.STATUS)))
    }

    private fun Button.onKey(action: () -> Unit) = setOnClickListener {
        Haptics.key(it)
        action()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun wireControlKeys(root: View) {
        root.findViewById<Button>(R.id.keySymbol).onKey { switchPanel(Panel.SYMBOLS) }
        root.findViewById<Button>(R.id.keyAbc).onKey { switchPanel(Panel.QWERTY) }
        modeKey.onKey {
            val next = prefs.readingMode.next()
            prefs.readingMode = next
            applyReadingMode(next)
        }
        root.findViewById<Button>(R.id.keySettings).onKey {
            finishComposition()
            startActivity(
                Intent(this, SettingsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        root.findViewById<Button>(R.id.keyRewrite).onKey {
            handwritingView.clear()
            showWordSuggestions()
        }
        root.findViewById<Button>(R.id.keyDelete)
            .setOnTouchListener(RepeatOnTouchListener { deleteBeforeCursor() })
        root.findViewById<Button>(R.id.keySpace).onKey { commitDirect(" ") }
        enterKey.onKey { sendEnter() }
    }

    private fun applyReadingMode(mode: ReadingMode) {
        candidateBar.setReadingMode(mode)
        modeKey.setText(
            when (mode) {
                ReadingMode.KANA -> R.string.mode_kana
                ReadingMode.ROMAJI -> R.string.mode_romaji
                ReadingMode.OFF -> R.string.mode_off
            }
        )
    }

    private fun updateEnterLabel(info: EditorInfo?) {
        val label = when (editorAction(info)) {
            EditorInfo.IME_ACTION_SEND -> getString(R.string.key_send)
            EditorInfo.IME_ACTION_SEARCH -> getString(R.string.key_search)
            else -> getString(R.string.key_enter)
        }
        enterKey.text = label
        symbolPad.setEnterLabel(label)
        qwertyPad.setEnterLabel(label)
    }

    private fun commitDirect(text: String) {
        finishComposition()
        currentInputConnection?.commitText(text, 1)
    }

    private fun finishComposition(clearCandidates: Boolean = true) {
        if (!composition.isEmpty) currentInputConnection?.finishComposingText()
        composition.clear()
        if (clearCandidates && this::candidateBar.isInitialized) candidateBar.clear()
    }

    private fun deleteBeforeCursor() {
        val connection = currentInputConnection ?: return
        if (!composition.isEmpty) {
            val remaining = composition.deleteLastCodePoint()
            if (remaining.isEmpty()) connection.finishComposingText()
            else connection.setComposingText(remaining, 1)
            showWordSuggestions()
            return
        }
        val selected = connection.getSelectedText(0)
        if (selected.isNullOrEmpty()) connection.deleteSurroundingText(1, 0)
        else connection.commitText("", 1)
    }

    private fun editorAction(info: EditorInfo?): Int {
        val editorInfo = info ?: return EditorInfo.IME_ACTION_NONE
        if (editorInfo.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) {
            return EditorInfo.IME_ACTION_NONE
        }
        return editorInfo.imeOptions and EditorInfo.IME_MASK_ACTION
    }

    private fun sendEnter() {
        finishComposition()
        val connection = currentInputConnection ?: return
        val action = editorAction(currentInputEditorInfo)
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            connection.performEditorAction(action)
        } else {
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
    }

    private fun isHan(text: String): Boolean = text.isNotEmpty() &&
        Character.UnicodeScript.of(text.codePointAt(0)) == Character.UnicodeScript.HAN

    override fun onDestroy() {
        recognizer?.close()
        recognizer = null
        readings.close()
        super.onDestroy()
    }
}
