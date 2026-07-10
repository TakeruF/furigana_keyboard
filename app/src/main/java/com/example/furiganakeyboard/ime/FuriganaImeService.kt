package com.example.furiganakeyboard.ime

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.content.res.Configuration
import android.annotation.SuppressLint
import java.util.Locale
import android.widget.Button
import android.widget.FrameLayout
import com.example.furiganakeyboard.R
import com.example.furiganakeyboard.reading.ReadingRepository
import com.example.furiganakeyboard.recognizer.InkRecognizer
import com.example.furiganakeyboard.recognizer.KanjiCandidateRanker
import com.example.furiganakeyboard.recognizer.ZinniaInkRecognizer
import com.example.furiganakeyboard.settings.KeyboardPrefs
import com.example.furiganakeyboard.settings.AppLocale
import com.example.furiganakeyboard.settings.ReadingMode
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
    private enum class Panel { HANDWRITING, SYMBOLS, ENGLISH, ROMAJI }

    private lateinit var prefs: KeyboardPrefs
    private lateinit var readings: ReadingRepository
    private lateinit var candidateBar: CandidateBarView
    private lateinit var handwritingView: HandwritingView
    private lateinit var handwritingPanel: View
    private lateinit var symbolPad: SymbolPadView
    private lateinit var englishPad: QwertyPadView
    private lateinit var romajiPad: QwertyPadView
    private lateinit var enterKey: Button

    private val composition = CompositionBuffer()
    private var recognizer: InkRecognizer? = null
    private var currentPanel = Panel.HANDWRITING
    private var appliedLocaleTag = ""
    private var latestCharacterAlternatives: List<String> = emptyList()
    private var wordRootBeforeLastCharacter = ""
    private var lastCharacterAlternatives: List<String> = emptyList()
    private var romajiRaw = ""

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
        romajiRaw = ""
        clearAlternativeContext()
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
        englishPad = QwertyPadView(this)
        romajiPad = QwertyPadView(this)
        for (pad in listOf<View>(symbolPad, englishPad, romajiPad)) {
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
        englishPad.onText = { commitDirect(it) }
        romajiPad.onText = { appendRomajiInput(it) }
        symbolPad.onDelete = { deleteBeforeCursor() }
        englishPad.onDelete = { deleteBeforeCursor() }
        romajiPad.onDelete = { deleteRomajiInput() }
        symbolPad.onEnter = { sendEnter() }
        englishPad.onEnter = { sendEnter() }
        romajiPad.onEnter = { sendEnter() }
        symbolPad.onBack = { switchPanel(Panel.HANDWRITING) }
        englishPad.onBack = { switchPanel(Panel.HANDWRITING) }
        romajiPad.onBack = { switchPanel(Panel.HANDWRITING) }
    }

    private fun switchPanel(panel: Panel) {
        if (panel != currentPanel) finishComposition()
        showPanel(panel)
    }

    private fun showPanel(panel: Panel) {
        currentPanel = panel
        handwritingPanel.visibility = if (panel == Panel.HANDWRITING) View.VISIBLE else View.GONE
        symbolPad.visibility = if (panel == Panel.SYMBOLS) View.VISIBLE else View.GONE
        englishPad.visibility = if (panel == Panel.ENGLISH) View.VISIBLE else View.GONE
        romajiPad.visibility = if (panel == Panel.ROMAJI) View.VISIBLE else View.GONE
    }

    private fun wireHandwriting() {
        handwritingView.onRecognize = { ink ->
            ensureRecognizer().recognize(ink) { values ->
                val rankedValues = KanjiCandidateRanker.rank(
                    values,
                    readings.kanjiPriorities(values.map { it.text })
                )
                val candidates = rankedValues.map { value ->
                    val resolved = readings.readingsFor(value.text)
                    CandidateUiModel(
                        text = value.text,
                        readings = if (resolved.isEmpty() && isHan(value.text)) {
                            listOf(getString(R.string.reading_unavailable))
                        } else resolved,
                        kind = CandidateKind.CHARACTER
                    )
                }
                if (candidates.isEmpty()) return@recognize
                if (prefs.autoCommit && !composition.isEmpty) {
                    stageRecognizedCharacter(candidates)
                } else {
                    latestCharacterAlternatives = candidates.map { it.text }
                    candidateBar.setCandidates(candidates)
                    handwritingView.markResultsDelivered()
                }
            }
        }

        handwritingView.onNewCharacterGate = gate@{
            if (!prefs.autoCommit) return@gate false
            val first = candidateBar.firstCandidate ?: return@gate false
            if (first.kind != CandidateKind.CHARACTER) return@gate false
            appendToComposition(
                first.text,
                latestCharacterAlternatives.ifEmpty { listOf(first.text) }
            )
            true
        }

        candidateBar.onCandidateSelected = { candidate ->
            when (candidate.kind) {
                CandidateKind.CHARACTER -> {
                    appendToComposition(candidate.text, listOf(candidate.text))
                    handwritingView.clear()
                }
                CandidateKind.WORD -> commitWordCandidate(candidate.text)
                CandidateKind.STATUS -> Unit
            }
        }
    }

    private fun appendToComposition(text: String, alternatives: List<String> = listOf(text)) {
        wordRootBeforeLastCharacter = composition.text
        lastCharacterAlternatives = alternatives.distinct()
        val value = composition.append(text)
        currentInputConnection?.setComposingText(value, 1)
        latestCharacterAlternatives = emptyList()
        candidateBar.clear()
        showWordSuggestions()
    }

    /**
     * Once at least one character is composing, the next recognized character
     * is staged automatically and the alternative cross-product is resolved
     * against JMdict. This makes two sequential handwritten characters behave
     * as a word without requiring a tap on the second character.
     */
    private fun stageRecognizedCharacter(characters: List<CandidateUiModel>) {
        val baseBeforeCurrent = composition.text
        val currentAlternatives = characters.map { it.text }.distinct()
        val possibleSurfaces = WordCandidateResolver.combine(
            root = if (lastCharacterAlternatives.isEmpty()) baseBeforeCurrent else wordRootBeforeLastCharacter,
            previousCharacters = lastCharacterAlternatives,
            currentCharacters = currentAlternatives
        )

        val topSurface = composition.append(currentAlternatives.first())
        currentInputConnection?.setComposingText(topSurface, 1)
        val resolved = WordCandidateResolver.resolve(
            surfaces = possibleSurfaces,
            exactReadings = readings::readingsFor,
            suggestions = readings::suggest
        ).map { CandidateUiModel(it.surface, it.readings, CandidateKind.WORD) }

        val fallback = CandidateUiModel(
            text = topSurface,
            readings = readings.readingsFor(topSurface).ifEmpty {
                listOf(getString(R.string.reading_not_in_dictionary))
            },
            kind = CandidateKind.WORD
        )
        candidateBar.setCandidates(
            (resolved + fallback).distinctBy { it.text }.take(MAX_WORD_CANDIDATES)
        )

        wordRootBeforeLastCharacter = baseBeforeCurrent
        lastCharacterAlternatives = currentAlternatives
        latestCharacterAlternatives = emptyList()
        handwritingView.clear()
        Haptics.selection(handwritingView)
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
        root.findViewById<Button>(R.id.keyKeyboardSwitch).onKey {
            finishComposition()
            getSystemService(InputMethodManager::class.java).showInputMethodPicker()
        }
        root.findViewById<Button>(R.id.keySymbol).onKey { switchPanel(Panel.SYMBOLS) }
        root.findViewById<Button>(R.id.keyEnglish).onKey { switchPanel(Panel.ENGLISH) }
        root.findViewById<Button>(R.id.keyRomaji).onKey { switchPanel(Panel.ROMAJI) }
        root.findViewById<Button>(R.id.keyRewrite).onKey {
            handwritingView.clear()
            showWordSuggestions()
        }
        root.findViewById<Button>(R.id.keyDelete)
            .setOnTouchListener(RepeatOnTouchListener { deleteBeforeCursor() })
        root.findViewById<Button>(R.id.keySpace).onKey { commitDirect(" ") }
        root.findViewById<Button>(R.id.keyComma).onKey { commitDirect("、") }
        root.findViewById<Button>(R.id.keyPeriod).onKey { commitDirect("。") }
        root.findViewById<Button>(R.id.keyQuestion).onKey { commitDirect("？") }
        root.findViewById<Button>(R.id.keyExclamation).onKey { commitDirect("！") }
        enterKey.onKey { sendEnter() }
    }

    private fun applyReadingMode(mode: ReadingMode) {
        candidateBar.setReadingMode(mode)
    }

    private fun updateEnterLabel(info: EditorInfo?) {
        val label = when (editorAction(info)) {
            EditorInfo.IME_ACTION_SEND -> getString(R.string.key_send)
            EditorInfo.IME_ACTION_SEARCH -> getString(R.string.key_search)
            else -> getString(R.string.key_enter)
        }
        enterKey.text = label
        symbolPad.setEnterLabel(label)
        englishPad.setEnterLabel(label)
        romajiPad.setEnterLabel(label)
    }

    private fun appendRomajiInput(text: String) {
        if (text.length == 1 && text[0].isLetter() && text[0].code < 128) {
            romajiRaw += text.lowercase()
            updateRomajiComposition()
        } else {
            commitDirect(text)
        }
    }

    private fun updateRomajiComposition() {
        val converted = RomajiKanaConverter.convert(romajiRaw)
        val display = converted.displayText
        composition.replace(display)
        clearAlternativeContext()
        currentInputConnection?.setComposingText(display, 1)

        if (converted.pending.isNotEmpty() || converted.kana.isEmpty()) {
            candidateBar.setCandidates(
                listOf(CandidateUiModel(display, kind = CandidateKind.WORD))
            )
            return
        }

        val dictionaryCandidates = readings.suggestByReading(
            converted.kana,
            MAX_WORD_CANDIDATES - 1
        ).map {
            CandidateUiModel(it.surface, it.readings, CandidateKind.WORD)
        }
        val kanaCandidate = CandidateUiModel(
            converted.kana,
            listOf(converted.kana),
            CandidateKind.WORD
        )
        candidateBar.setCandidates(
            (dictionaryCandidates + kanaCandidate).distinctBy { it.text }.take(MAX_WORD_CANDIDATES)
        )
    }

    private fun deleteRomajiInput() {
        if (romajiRaw.isEmpty()) {
            deleteBeforeCursor()
            return
        }
        romajiRaw = romajiRaw.dropLast(1)
        if (romajiRaw.isEmpty()) {
            composition.clear()
            currentInputConnection?.setComposingText("", 1)
            currentInputConnection?.finishComposingText()
            candidateBar.clear()
        } else {
            updateRomajiComposition()
        }
    }

    private fun commitDirect(text: String) {
        finishComposition()
        currentInputConnection?.commitText(text, 1)
    }

    private fun finishComposition(clearCandidates: Boolean = true) {
        if (!composition.isEmpty) currentInputConnection?.finishComposingText()
        composition.clear()
        romajiRaw = ""
        clearAlternativeContext()
        if (clearCandidates && this::candidateBar.isInitialized) candidateBar.clear()
    }

    private fun deleteBeforeCursor() {
        val connection = currentInputConnection ?: return
        if (!composition.isEmpty) {
            val remaining = composition.deleteLastCodePoint()
            clearAlternativeContext()
            if (remaining.isEmpty()) {
                connection.setComposingText("", 1)
                connection.finishComposingText()
            }
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

    private fun clearAlternativeContext() {
        latestCharacterAlternatives = emptyList()
        wordRootBeforeLastCharacter = ""
        lastCharacterAlternatives = emptyList()
    }

    override fun onDestroy() {
        recognizer?.close()
        recognizer = null
        readings.close()
        super.onDestroy()
    }

    companion object {
        private const val MAX_WORD_CANDIDATES = 8
    }
}
