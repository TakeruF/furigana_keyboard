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
import com.example.furiganakeyboard.recognizer.PlusInkRecognizer
import com.example.furiganakeyboard.recognizer.SideBySideInkRecognizer
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
import com.example.furiganakeyboard.view.AccentStyle
import com.example.furiganakeyboard.view.KeyboardPanelContainer
import com.example.furiganakeyboard.view.KeySounds

/** Japanese handwriting IME with optional Plus recognition and bundled fallback. */
class FuriganaImeService : InputMethodService() {
    private enum class Panel { HANDWRITING, SYMBOLS, ENGLISH, ROMAJI }

    private lateinit var prefs: KeyboardPrefs
    private lateinit var candidatePipeline: CandidatePipeline
    private lateinit var candidateBar: CandidateBarView
    private lateinit var handwritingView: HandwritingView
    private lateinit var handwritingPanel: View
    private lateinit var panelContainer: KeyboardPanelContainer
    private var symbolPad: SymbolPadView? = null
    private var englishPad: QwertyPadView? = null
    private var romajiPad: QwertyPadView? = null
    private lateinit var enterKey: Button
    private lateinit var questionKey: Button
    private lateinit var symbolModeKey: Button
    private lateinit var englishModeKey: Button
    private lateinit var romajiModeKey: Button

    private val composition = CompositionBuffer()
    private var recognizer: InkRecognizer? = null
    private var recognizerUsesPlus = false
    private var currentPanel = Panel.HANDWRITING
    private var appliedLocaleTag = ""
    private var latestCharacterAlternatives: List<String> = emptyList()
    private var wordRootBeforeLastCharacter = ""
    private var lastCharacterAlternatives: List<String> = emptyList()
    private val romajiRaw = StringBuilder()
    private var currentEnterLabel = ""

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        prefs = KeyboardPrefs(this)
        appliedLocaleTag = prefs.localeTag
        candidatePipeline = CandidatePipeline({ ReadingRepository(this) })
        candidatePipeline.prewarm()
        Haptics.enabled = prefs.haptics
        Haptics.strength = prefs.hapticStrength
        KeySounds.enabled = prefs.keySound
        KeySounds.volumeStep = prefs.keySoundVolume
    }

    @SuppressLint("InflateParams")
    override fun onCreateInputView(): View {
        // A locale change creates a new root; lazily rebuild panels against that root.
        symbolPad = null
        englishPad = null
        romajiPad = null
        val root = layoutInflater.inflate(R.layout.keyboard_view, null)
        candidateBar = root.findViewById(R.id.candidateBar)
        handwritingView = root.findViewById(R.id.handwritingView)
        handwritingPanel = root.findViewById(R.id.handwritingPanel)
        panelContainer = root.findViewById(R.id.panelContainer)
        enterKey = root.findViewById(R.id.keyEnter)
        questionKey = root.findViewById(R.id.keyQuestion)
        symbolModeKey = root.findViewById(R.id.keySymbol)
        englishModeKey = root.findViewById(R.id.keyEnglish)
        romajiModeKey = root.findViewById(R.id.keyRomaji)
        applyAccentColor()
        applyLayoutPreferences()

        wireHandwriting()
        wireControlKeys(root)
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
        romajiRaw.setLength(0)
        clearAlternativeContext()
        candidatePipeline.invalidate()
        recognizer?.cancelPending()
        Haptics.enabled = prefs.haptics
        Haptics.strength = prefs.hapticStrength
        KeySounds.enabled = prefs.keySound
        KeySounds.volumeStep = prefs.keySoundVolume
        applyAccentColor()
        applyLayoutPreferences()
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
        val usePlus = prefs.plusRecognition
        recognizer?.let { existing ->
            if (recognizerUsesPlus == usePlus) return existing
            existing.close()
            recognizer = null
        }
        val baseRecognizer = if (usePlus) PlusInkRecognizer(this) else ZinniaInkRecognizer(this)
        val created = SideBySideInkRecognizer(baseRecognizer)
        return created.also { engine ->
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
            recognizerUsesPlus = usePlus
        }
    }

    private fun switchPanel(panel: Panel) {
        if (panel != currentPanel) finishComposition()
        showPanel(panel)
    }

    private fun showPanel(panel: Panel) {
        ensurePanel(panel)
        currentPanel = panel
        handwritingPanel.visibility = if (panel == Panel.HANDWRITING) View.VISIBLE else View.GONE
        symbolPad?.visibility = if (panel == Panel.SYMBOLS) View.VISIBLE else View.GONE
        englishPad?.visibility = if (panel == Panel.ENGLISH) View.VISIBLE else View.GONE
        romajiPad?.visibility = if (panel == Panel.ROMAJI) View.VISIBLE else View.GONE
        if (this::symbolModeKey.isInitialized) {
            symbolModeKey.isSelected = panel == Panel.SYMBOLS
            englishModeKey.isSelected = panel == Panel.ENGLISH
            romajiModeKey.isSelected = panel == Panel.ROMAJI
        }
    }

    /** Heavy key grids are created only when the user first opens that mode. */
    private fun ensurePanel(panel: Panel) {
        when (panel) {
            Panel.HANDWRITING -> Unit
            Panel.SYMBOLS -> if (symbolPad == null) {
                symbolPad = SymbolPadView(this).also { pad ->
                    addPanel(pad)
                    pad.onText = { commitDirect(it) }
                    pad.onDelete = { deleteBeforeCursor() }
                    pad.onEnter = { sendEnter() }
                    pad.onBack = { switchPanel(Panel.HANDWRITING) }
                    pad.setEnterLabel(currentEnterLabel)
                }
            }
            Panel.ENGLISH -> if (englishPad == null) {
                englishPad = QwertyPadView(this).also { pad ->
                    addPanel(pad)
                    pad.onText = { commitDirect(it) }
                    pad.onDelete = { deleteBeforeCursor() }
                    pad.onEnter = { sendEnter() }
                    pad.onBack = { switchPanel(Panel.HANDWRITING) }
                    pad.setEnterLabel(currentEnterLabel)
                }
            }
            Panel.ROMAJI -> if (romajiPad == null) {
                romajiPad = QwertyPadView(this, includeJapaneseLongVowelKey = true).also { pad ->
                    addPanel(pad)
                    pad.onText = { appendRomajiInput(it) }
                    pad.onDelete = { deleteRomajiInput() }
                    pad.onEnter = { sendEnter() }
                    pad.onBack = { switchPanel(Panel.HANDWRITING) }
                    pad.setEnterLabel(currentEnterLabel)
                }
            }
        }
    }

    private fun addPanel(panel: View) {
        panel.visibility = View.GONE
        panelContainer.addView(
            panel,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun wireHandwriting() {
        handwritingView.onRecognize = { ink ->
            ensureRecognizer().recognize(ink, composition.text.takeLast(MAX_RECOGNITION_CONTEXT)) { values ->
                if (values.isEmpty()) return@recognize
                val stageContext = if (prefs.autoCommit && !composition.isEmpty) {
                    HandwritingStageContext(
                        baseBeforeCurrent = composition.text,
                        wordRootBeforeLast = wordRootBeforeLastCharacter,
                        previousAlternatives = lastCharacterAlternatives
                    )
                } else null
                candidatePipeline.submitHandwriting(
                    values,
                    stageContext,
                    MAX_WORD_CANDIDATES,
                    ::applyHandwritingResult
                )
            }
        }

        handwritingView.onInkChanged = {
            recognizer?.cancelPending()
            candidatePipeline.invalidate()
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
                CandidateKind.CHARACTER -> commitCharacterCandidate(candidate.text)
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
        updateEnterLabel(currentInputEditorInfo)
        latestCharacterAlternatives = emptyList()
        showWordSuggestions()
    }

    private fun applyHandwritingResult(result: HandwritingPipelineResult) {
        when (result) {
            is HandwritingPipelineResult.Characters -> {
                val candidates = result.candidates.map { value ->
                    CandidateUiModel(
                        text = value.text,
                        readings = if (value.readings.isEmpty() && isHan(value.text)) {
                            listOf(getString(R.string.reading_unavailable))
                        } else value.readings,
                        kind = CandidateKind.CHARACTER
                    )
                }
                latestCharacterAlternatives = candidates.map { it.text }
                candidateBar.setCandidates(candidates)
                handwritingView.markResultsDelivered()
            }
            is HandwritingPipelineResult.Staged -> {
                val baseBeforeCurrent = composition.text
                composition.replace(result.topSurface)
                currentInputConnection?.setComposingText(result.topSurface, 1)
                updateEnterLabel(currentInputEditorInfo)
                val resolved = result.candidates.map {
                    CandidateUiModel(it.surface, it.readings, CandidateKind.WORD)
                }
                val fallback = CandidateUiModel(
                    result.topSurface,
                    listOf(getString(R.string.reading_not_in_dictionary)),
                    CandidateKind.WORD
                )
                candidateBar.setCandidates(
                    (resolved + fallback).distinctBy { it.text }.take(MAX_WORD_CANDIDATES)
                )
                wordRootBeforeLastCharacter = baseBeforeCurrent
                lastCharacterAlternatives = result.currentAlternatives
                latestCharacterAlternatives = emptyList()
                handwritingView.clear()
                Haptics.selection(handwritingView)
            }
        }
    }

    private fun showWordSuggestions() {
        if (composition.isEmpty) {
            candidatePipeline.invalidate()
            candidateBar.clear()
            return
        }
        val prefix = composition.text
        val fallback = CandidateUiModel(
            prefix,
            listOf(getString(R.string.reading_not_in_dictionary)),
            CandidateKind.WORD
        )
        candidateBar.setCandidates(listOf(fallback))
        candidatePipeline.submitSurface(prefix, MAX_WORD_CANDIDATES) { values ->
            val suggestions = values.map {
                CandidateUiModel(it.surface, it.readings, CandidateKind.WORD)
            }
            candidateBar.setCandidates(if (suggestions.isEmpty()) listOf(fallback) else suggestions)
        }
    }

    private fun commitWordCandidate(surface: String) {
        composition.replace(surface)
        currentInputConnection?.setComposingText(surface, 1)
        finishComposition()
        handwritingView.clear()
    }

    /** A deliberate candidate tap selects and confirms the character immediately. */
    private fun commitCharacterCandidate(text: String) {
        val surface = composition.append(text)
        currentInputConnection?.setComposingText(surface, 1)
        finishComposition()
        handwritingView.clear()
    }

    private fun showStatus(message: String) {
        candidateBar.setCandidates(listOf(CandidateUiModel(message, kind = CandidateKind.STATUS)))
    }

    private fun View.onKey(action: () -> Unit) = setOnClickListener {
        Haptics.key(it)
        action()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun wireControlKeys(root: View) {
        root.findViewById<View>(R.id.keyKeyboardSwitch).onKey {
            finishComposition()
            getSystemService(InputMethodManager::class.java).showInputMethodPicker()
        }
        root.findViewById<Button>(R.id.keySymbol).onKey { switchPanel(Panel.SYMBOLS) }
        root.findViewById<Button>(R.id.keyEnglish).onKey { switchPanel(Panel.ENGLISH) }
        root.findViewById<Button>(R.id.keyRomaji).onKey { switchPanel(Panel.ROMAJI) }
        root.findViewById<Button>(R.id.keyClearInk).onKey { clearHandwritingInput() }
        root.findViewById<Button>(R.id.keyDelete)
            .setOnTouchListener(RepeatOnTouchListener { deleteBeforeCursor() })
        root.findViewById<Button>(R.id.keySpace).onKey { commitDirect(" ") }
        root.findViewById<Button>(R.id.keyComma).onKey { commitDirect("、") }
        root.findViewById<Button>(R.id.keyPeriod).onKey { commitDirect("。") }
        questionKey.onKey { commitDirect("？") }
        enterKey.onKey { sendEnter() }
    }

    private fun clearHandwritingInput() {
        recognizer?.cancelPending()
        candidatePipeline.invalidate()
        handwritingView.clear()
        latestCharacterAlternatives = emptyList()
        if (composition.isEmpty) candidateBar.clear() else showWordSuggestions()
    }

    private fun applyReadingMode(mode: ReadingMode) {
        candidateBar.setReadingMode(mode)
    }

    private fun applyAccentColor() {
        if (!this::enterKey.isInitialized || !this::candidateBar.isInitialized) return
        val accent = prefs.accentColor
        AccentStyle.apply(enterKey, accent)
        candidateBar.setAccentColor(accent)
        symbolPad?.setAccentColor(accent)
        englishPad?.setAccentColor(accent)
        romajiPad?.setAccentColor(accent)
    }

    private fun applyLayoutPreferences() {
        if (!this::panelContainer.isInitialized || !this::candidateBar.isInitialized) return
        panelContainer.canvasScale = prefs.keyboardHeight.canvasScale
        candidateBar.setCandidateTextSize(prefs.candidateTextSize)
    }

    private fun updateEnterLabel(info: EditorInfo?) {
        val label = if (!composition.isEmpty) {
            getString(R.string.key_convert)
        } else {
            when (editorAction(info)) {
                EditorInfo.IME_ACTION_SEND -> getString(R.string.key_send)
                EditorInfo.IME_ACTION_SEARCH -> getString(R.string.key_search)
                else -> getString(R.string.key_enter)
            }
        }
        currentEnterLabel = label
        enterKey.text = label
        symbolPad?.setEnterLabel(label)
        englishPad?.setEnterLabel(label)
        romajiPad?.setEnterLabel(label)
    }

    private fun appendRomajiInput(text: String) {
        if (text.length == 1 && text[0].isLetter() && text[0].code < 128) {
            romajiRaw.append(text.lowercase())
            updateRomajiComposition()
        } else if (text == JAPANESE_LONG_VOWEL_MARK) {
            romajiRaw.append(text)
            updateRomajiComposition()
        } else {
            commitDirect(text)
        }
    }

    private fun updateRomajiComposition() {
        candidatePipeline.invalidate()
        val converted = RomajiKanaConverter.convert(romajiRaw.toString())
        val display = converted.displayText
        composition.replace(display)
        clearAlternativeContext()
        currentInputConnection?.setComposingText(display, 1)
        updateEnterLabel(currentInputEditorInfo)

        if (converted.hasUnresolvedInput || converted.kana.isEmpty()) {
            candidateBar.setCandidates(
                listOf(CandidateUiModel(display, kind = CandidateKind.WORD))
            )
            return
        }

        val kanaCandidate = CandidateUiModel(
            converted.kana,
            listOf(converted.kana),
            CandidateKind.WORD
        )
        candidateBar.setCandidates(listOf(kanaCandidate))
        candidatePipeline.submitRomaji(converted.kana, MAX_WORD_CANDIDATES - 1) { values ->
            val dictionaryCandidates = values.map {
                CandidateUiModel(it.surface, it.readings, CandidateKind.WORD)
            }
            candidateBar.setCandidates(
                (dictionaryCandidates + kanaCandidate).distinctBy { it.text }.take(MAX_WORD_CANDIDATES)
            )
        }
    }

    private fun deleteRomajiInput(): Boolean {
        if (romajiRaw.isEmpty()) {
            return deleteBeforeCursor()
        }
        val remainingRaw = RomajiKanaConverter.deleteLastUnit(romajiRaw.toString())
        romajiRaw.setLength(0)
        romajiRaw.append(remainingRaw)
        if (romajiRaw.isEmpty()) {
            candidatePipeline.invalidate()
            composition.clear()
            currentInputConnection?.setComposingText("", 1)
            currentInputConnection?.finishComposingText()
            candidateBar.clear()
            updateEnterLabel(currentInputEditorInfo)
        } else {
            updateRomajiComposition()
        }
        return true
    }

    private fun commitDirect(text: String) {
        finishComposition()
        currentInputConnection?.commitText(text, 1)
    }

    private fun finishComposition(clearCandidates: Boolean = true) {
        if (!composition.isEmpty) currentInputConnection?.finishComposingText()
        composition.clear()
        romajiRaw.setLength(0)
        clearAlternativeContext()
        candidatePipeline.invalidate()
        recognizer?.cancelPending()
        if (clearCandidates && this::candidateBar.isInitialized) candidateBar.clear()
        if (this::enterKey.isInitialized) updateEnterLabel(currentInputEditorInfo)
    }

    private fun deleteBeforeCursor(): Boolean {
        val connection = currentInputConnection ?: return false
        if (!composition.isEmpty) {
            val remaining = composition.deleteLastCodePoint()
            clearAlternativeContext()
            if (remaining.isEmpty()) {
                connection.setComposingText("", 1)
                connection.finishComposingText()
            }
            else connection.setComposingText(remaining, 1)
            updateEnterLabel(currentInputEditorInfo)
            showWordSuggestions()
            return true
        }
        val selected = connection.getSelectedText(0)
        if (!selected.isNullOrEmpty()) return connection.commitText("", 1)

        if (connection.getTextBeforeCursor(1, 0).isNullOrEmpty()) return false
        return connection.deleteSurroundingTextInCodePoints(1, 0)
    }

    private fun editorAction(info: EditorInfo?): Int {
        val editorInfo = info ?: return EditorInfo.IME_ACTION_NONE
        if (editorInfo.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) {
            return EditorInfo.IME_ACTION_NONE
        }
        return editorInfo.imeOptions and EditorInfo.IME_MASK_ACTION
    }

    private fun sendEnter() {
        if (!composition.isEmpty) {
            finishComposition()
            return
        }
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
        candidatePipeline.close()
        super.onDestroy()
    }

    companion object {
        private const val MAX_WORD_CANDIDATES = 8
        private const val MAX_RECOGNITION_CONTEXT = 20
        private const val JAPANESE_LONG_VOWEL_MARK = "ー"
    }
}
