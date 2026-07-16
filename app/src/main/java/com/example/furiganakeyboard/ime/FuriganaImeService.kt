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
import android.os.Build
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.UnderlineSpan
import java.util.Locale
import android.widget.Button
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnAttach
import com.example.furiganakeyboard.R
import com.example.furiganakeyboard.conversion.ConversionText
import com.example.furiganakeyboard.reading.ReadingRepository
import com.example.furiganakeyboard.reading.WordReadingCandidate
import com.example.furiganakeyboard.recognizer.InkRecognizer
import com.example.furiganakeyboard.recognizer.PlusInkRecognizer
import com.example.furiganakeyboard.recognizer.SideBySideInkRecognizer
import com.example.furiganakeyboard.recognizer.ZinniaInkRecognizer
import com.example.furiganakeyboard.settings.KeyboardPrefs
import com.example.furiganakeyboard.settings.JapaneseInputMode
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
import com.example.furiganakeyboard.update.ReadingDataUpdates

/** Japanese handwriting IME with optional Plus recognition and bundled fallback. */
class FuriganaImeService : InputMethodService(), RomajiCursorDeltaReceiver {
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
    private var latestCharacterCandidates: List<ResolvedCharacterCandidate> = emptyList()
    private var wordRootBeforeLastCharacter = ""
    private var lastCharacterAlternatives: List<String> = emptyList()
    private var lastCharacterCandidates: List<ResolvedCharacterCandidate> = emptyList()
    private var bunsetsuState: BunsetsuComposition? = null
    private val romajiConversionState = RomajiConversionState()
    private val romajiCursor = RomajiCompositionCursor()
    private val romajiEditor = RomajiCompositionEditor(romajiCursor)
    private var romajiCandidates: List<CandidateUiModel> = emptyList()
    private var romajiCandidateRequest: RomajiCursorRequest? = null
    private var currentEnterLabel = ""

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        ReadingDataUpdates.initialize(this)
        prefs = KeyboardPrefs(this)
        appliedLocaleTag = prefs.localeTag
        candidatePipeline = CandidatePipeline({ ReadingRepository(this) })
        candidatePipeline.prewarm()
        Haptics.enabled = prefs.haptics
        Haptics.strength = prefs.hapticStrength
        KeySounds.enabled = prefs.keySound
        KeySounds.volumeStep = prefs.keySoundVolume
        applyNavigationBarStyle()
    }

    @SuppressLint("InflateParams")
    override fun onCreateInputView(): View {
        applyNavigationBarStyle()
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
        applySystemKeyboardRaise(root)
        applyAccentColor()
        applyLayoutPreferences()

        wireHandwriting()
        wireControlKeys(root)
        applyReadingMode(prefs.readingMode)
        ensureRecognizer()
        return root
    }

    /**
     * Honors the bottom safe area supplied by gesture navigation and OEM
     * "raise keyboard" settings while keeping that area keyboard-colored.
     */
    private fun applySystemKeyboardRaise(root: View) {
        val initialLeft = root.paddingLeft
        val initialTop = root.paddingTop
        val initialRight = root.paddingRight
        val initialBottom = root.paddingBottom
        val bottomInsetTypes = WindowInsetsCompat.Type.navigationBars() or
            WindowInsetsCompat.Type.mandatorySystemGestures() or
            WindowInsetsCompat.Type.tappableElement()

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
            val systemInsets = windowInsets.getInsets(bottomInsetTypes)
            view.setPadding(
                initialLeft + systemInsets.left,
                initialTop,
                initialRight + systemInsets.right,
                initialBottom + systemInsets.bottom
            )
            windowInsets
        }
        root.doOnAttach { ViewCompat.requestApplyInsets(it) }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        applyNavigationBarStyle()
        if (applyChangedLocale()) {
            setInputView(onCreateInputView())
        }
        composition.clear()
        bunsetsuState = null
        romajiCursor.clear()
        romajiCandidateRequest = null
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
        showPanel(prefs.lastJapaneseInputMode.toPanel())
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

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart,
            oldSelEnd,
            newSelStart,
            newSelEnd,
            candidatesStart,
            candidatesEnd,
        )
        when (
            val action = romajiCursor.onUpdateSelection(
                newSelStart,
                newSelEnd,
                candidatesStart,
                candidatesEnd,
            )
        ) {
            RomajiSelectionAction.Ignored,
            RomajiSelectionAction.SelfUpdate -> Unit
            is RomajiSelectionAction.RestoreCursor -> restoreRomajiCursor(action.absoluteUtf16)
            is RomajiSelectionAction.CursorChanged -> {
                leaveBunsetsuModeForCursorMove()
                romajiConversionState.onCompositionEdited(hasComposition = true)
                refreshRomajiCandidatesForCursor()
            }
            RomajiSelectionAction.FinishComposition -> finishComposition()
        }
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
        when (panel) {
            Panel.HANDWRITING -> prefs.lastJapaneseInputMode = JapaneseInputMode.HANDWRITING
            Panel.ROMAJI -> prefs.lastJapaneseInputMode = JapaneseInputMode.ROMAJI
            Panel.SYMBOLS, Panel.ENGLISH -> Unit
        }
        showPanel(panel)
    }

    private fun JapaneseInputMode.toPanel(): Panel = when (this) {
        JapaneseInputMode.HANDWRITING -> Panel.HANDWRITING
        JapaneseInputMode.ROMAJI -> Panel.ROMAJI
    }

    private fun showPanel(panel: Panel) {
        ensurePanel(panel)
        currentPanel = panel
        // English input commits characters directly, so it has no conversion
        // candidates to show. Removing the bar also gives the ABC keys more room.
        candidateBar.visibility = if (panel == Panel.ENGLISH) View.GONE else View.VISIBLE
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
                englishPad = QwertyPadView(this, showNumberRow = prefs.showNumberRow).also { pad ->
                    addPanel(pad)
                    pad.onText = { commitDirect(it) }
                    pad.onDelete = { deleteBeforeCursor() }
                    pad.onEnter = { sendEnter() }
                    pad.onBack = { switchPanel(Panel.HANDWRITING) }
                    pad.setEnterLabel(currentEnterLabel)
                }
            }
            Panel.ROMAJI -> if (romajiPad == null) {
                romajiPad = QwertyPadView(
                    this,
                    includeJapaneseLongVowelKey = true,
                    showNumberRow = prefs.showNumberRow
                ).also { pad ->
                    addPanel(pad)
                    pad.onText = { appendRomajiInput(it) }
                    pad.onSpace = { handleRomajiSpace() }
                    pad.onCursorStep = { deltaInGraphemes ->
                        onSpaceCursorDelta(deltaInGraphemes)
                    }
                    pad.onDelete = { deleteRomajiInput() }
                    pad.onEnter = { handleRomajiEnter() }
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
                        previousAlternatives = lastCharacterAlternatives,
                        previousCandidates = lastCharacterCandidates,
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
                latestCharacterAlternatives.ifEmpty { listOf(first.text) },
                latestCharacterCandidates,
            )
            true
        }

        candidateBar.onCandidateSelected = { candidate ->
            when (candidate.kind) {
                CandidateKind.CHARACTER -> commitCharacterCandidate(candidate.text)
                CandidateKind.WORD -> if (currentPanel == Panel.ROMAJI && romajiCursor.isActive) {
                    commitRomajiWordCandidate(candidate.text)
                } else {
                    commitWordCandidate(candidate.text)
                }
                CandidateKind.BUNSETSU ->
                    commitBunsetsuCandidate(candidate)
                CandidateKind.STATUS -> Unit
                CandidateKind.SEGMENT_SHRINK ->
                    changeBunsetsuRange(expand = false, candidate.bunsetsuGeneration)
                CandidateKind.SEGMENT_EXPAND ->
                    changeBunsetsuRange(expand = true, candidate.bunsetsuGeneration)
            }
        }
    }

    private fun appendToComposition(
        text: String,
        alternatives: List<String> = listOf(text),
        candidates: List<ResolvedCharacterCandidate> = emptyList(),
    ) {
        wordRootBeforeLastCharacter = composition.text
        lastCharacterAlternatives = alternatives.distinct()
        lastCharacterCandidates = candidates
        val value = composition.append(text)
        currentInputConnection?.setComposingText(value, 1)
        updateEnterLabel(currentInputEditorInfo)
        latestCharacterAlternatives = emptyList()
        latestCharacterCandidates = emptyList()
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
                latestCharacterCandidates = result.candidates
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
                lastCharacterCandidates = result.currentCandidates
                latestCharacterAlternatives = emptyList()
                latestCharacterCandidates = emptyList()
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

    private fun commitRomajiWordCandidate(surface: String) {
        val request = romajiCandidateRequest ?: run {
            commitWordCandidate(surface)
            return
        }
        if (!romajiCursor.isCurrent(request)) return
        val slice = romajiCursor.conversionSlice()?.takeIf { it.request == request } ?: return
        val connection = currentInputConnection ?: return
        val remaining = slice.resolvedSuffix + slice.unresolvedSuffix

        connection.beginBatchEdit()
        try {
            // commitText replaces the whole composing span. Reinstall the untouched suffix as the
            // next composing span so a prefix conversion can never consume text right of cursor.
            connection.commitText(surface, 1)
            if (remaining.isNotEmpty()) {
                composition.replace(remaining)
                bunsetsuState = null
                romajiConversionState.onCompositionEdited(hasComposition = true)
                romajiCursor.clear()
                romajiCursor.replace(remaining, slice.resolvedSuffix, slice.unresolvedRaw)
                setRomajiComposingText(remaining)
            }
        } finally {
            connection.endBatchEdit()
        }

        if (remaining.isEmpty()) {
            finishComposition()
        } else {
            clearAlternativeContext()
            updateEnterLabel(currentInputEditorInfo)
            refreshRomajiCandidatesForCursor()
        }
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
        root.findViewById<Button>(R.id.keyDelete)
            .setOnTouchListener(RepeatOnTouchListener { deleteHandwritingOrText() })
        root.findViewById<Button>(R.id.keySpace).onKey { commitDirect(" ") }
        root.findViewById<Button>(R.id.keyComma).onKey { commitDirect("、") }
        root.findViewById<Button>(R.id.keyPeriod).onKey { commitDirect("。") }
        questionKey.onKey { commitDirect("？") }
        enterKey.onKey { sendEnter() }
    }

    private fun deleteHandwritingOrText(): Boolean {
        if (!handwritingView.deleteLastCharacter()) return deleteBeforeCursor()
        latestCharacterAlternatives = emptyList()
        if (composition.isEmpty) candidateBar.clear() else showWordSuggestions()
        return true
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
        englishPad?.setNumberRowVisible(prefs.showNumberRow)
        romajiPad?.setNumberRowVisible(prefs.showNumberRow)
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
        leaveBunsetsuModeForEditing()
        if (text.length == 1 && text[0].isLetter() && text[0].code < 128) {
            applyRomajiEdit(romajiEditor.append(text.lowercase()))
        } else if (text == JAPANESE_LONG_VOWEL_MARK) {
            applyRomajiEdit(romajiEditor.append(text))
        } else {
            commitDirect(text)
        }
    }

    private fun applyRomajiEdit(mutation: CompositionCursorMutation) {
        candidatePipeline.invalidate()
        if (mutation !is CompositionCursorMutation.Applied) return
        synchronizeRomajiComposition()
    }

    private fun synchronizeRomajiComposition() {
        val display = romajiCursor.displayText
        composition.replace(display)
        bunsetsuState = null
        romajiConversionState.onCompositionEdited(display.isNotEmpty())
        clearAlternativeContext()
        setRomajiComposingText(display)
        updateEnterLabel(currentInputEditorInfo)
        refreshRomajiCandidatesForCursor()
    }

    private fun setRomajiComposingText(value: CharSequence) {
        val connection = currentInputConnection ?: return
        romajiCursor.expectSelfSelection(romajiCursor.displayText.length)
        connection.setComposingText(value, 1)
        romajiCursor.absoluteCursorUtf16?.let { absoluteCursor ->
            if (romajiCursor.cursorUtf16 != composition.text.length) {
                restoreRomajiCursor(absoluteCursor)
            }
        }
    }

    private fun restoreRomajiCursor(absoluteUtf16: Int) {
        romajiCursor.expectSelfSelection(romajiCursor.cursorUtf16)
        currentInputConnection?.setSelection(absoluteUtf16, absoluteUtf16)
    }

    /** Space-drag emits user-visible grapheme steps, never pixels, scalars, or UTF-16 offsets. */
    override fun onSpaceCursorDelta(deltaInGraphemes: Int) {
        if (currentPanel != Panel.ROMAJI || !romajiCursor.isActive) return
        leaveBunsetsuModeForCursorMove()
        if (!romajiCursor.moveCursorByGrapheme(deltaInGraphemes)) return
        romajiConversionState.onCompositionEdited(hasComposition = true)
        val absoluteCursor = romajiCursor.absoluteCursorUtf16
        if (absoluteCursor == null) {
            setRomajiComposingText(composition.text)
        } else {
            restoreRomajiCursor(absoluteCursor)
        }
        refreshRomajiCandidatesForCursor()
    }

    private fun leaveBunsetsuModeForCursorMove() {
        if (bunsetsuState != null) leaveBunsetsuModeForEditing()
    }

    /**
     * End-of-composition requests keep the existing full/bunsetsu conversion policy. An interior
     * cursor submits only [composition start, cursor], leaving the suffix outside conversion.
     */
    private fun refreshRomajiCandidatesForCursor() {
        candidatePipeline.invalidate()
        val slice = romajiCursor.conversionSlice()
        if (slice == null) {
            romajiCandidateRequest = null
            showRomajiCandidates(
                listOf(CandidateUiModel(composition.text, kind = CandidateKind.WORD))
            )
            return
        }

        val reading = slice.request.reading
        romajiCandidateRequest = slice.request
        val scriptFallbacks = listOf(
            WordReadingCandidate(reading, listOf(reading)),
            WordReadingCandidate(RomajiKanaConverter.toKatakana(reading), listOf(reading)),
        ).distinctBy { it.surface }
        val scriptCandidates = scriptFallbacks.map { fallback ->
            CandidateUiModel(fallback.surface, fallback.readings, CandidateKind.WORD)
        }
        // While the dictionary lookup is pending, and when it has no match,
        // keep the plain-script fallback in the familiar hiragana | katakana order.
        showRomajiCandidates(scriptCandidates)
        // Preserve the existing hard contract: pending romaji never reaches dictionary lookup.
        if (slice.unresolvedSuffix.isNotEmpty()) return
        val analysisLimit = if (slice.isWholeComposition) {
            CandidatePipeline.MAX_ROMAJI_ANALYSIS_CANDIDATES
        } else {
            MAX_WORD_CANDIDATES
        }
        candidatePipeline.submitRomajiAnalysis(
            reading,
            analysisLimit,
        ) { result ->
            if (!romajiCursor.isCurrent(slice.request)) return@submitRomajiAnalysis
            val plan = if (slice.isWholeComposition) {
                BunsetsuComposition.plan(reading, result.conversions)
            } else {
                null
            }
            val wholeCandidates = if (slice.isWholeComposition) {
                WholeCompositionCandidatePolicy.build(
                    reading = reading,
                    dictionaryCandidates = result.candidates,
                    scriptFallbacks = scriptFallbacks,
                    limit = MAX_WORD_CANDIDATES,
                ).map { candidate ->
                    CandidateUiModel(candidate.surface, candidate.readings, CandidateKind.WORD)
                }
            } else {
                val exactCandidates = result.candidates.asSequence()
                    .filter { candidate -> candidate.readings.any { it == reading } }
                    .map { candidate ->
                        CandidateUiModel(candidate.surface, candidate.readings, CandidateKind.WORD)
                    }
                (exactCandidates + scriptCandidates.asSequence())
                    .distinctBy { it.text }
                    .take(MAX_WORD_CANDIDATES)
                    .toList()
            }
            if (plan == null) {
                bunsetsuState = null
                showRomajiCandidates(wholeCandidates)
            } else {
                enterBunsetsuConversion(reading, plan, wholeCandidates)
            }
        }
    }

    private fun enterBunsetsuConversion(
        reading: String,
        plan: BunsetsuConversionPlan,
        wholeCandidates: List<CandidateUiModel>,
    ) {
        val state = BunsetsuComposition.create(reading, plan)
        bunsetsuState = state
        composition.replace(reading)
        renderBunsetsuComposition(state)
        // At the terminal cursor, keep full-composition candidates visible. The retained state
        // exists so the segment arrows can still enter explicit bunsetsu range adjustment.
        state.setCandidates(wholeCandidates.map { it.text })
        showRomajiCandidates(wholeCandidates, state)
    }

    private fun showBunsetsuPlan(
        state: BunsetsuComposition,
        plan: BunsetsuConversionPlan,
    ) {
        val converted = roundRobinByReading(plan.candidates).map { option ->
            CandidateUiModel(
                text = option.surface,
                readings = listOf(option.reading),
                kind = CandidateKind.BUNSETSU,
                bunsetsuReading = option.reading,
                bunsetsuRightId = option.rightId,
                bunsetsuGeneration = state.generation,
            )
        }
        val scriptFallbacks = plan.candidates.distinctBy { it.reading }.flatMap { option ->
            val reading = option.reading
            listOf(
                CandidateUiModel(
                    reading,
                    listOf(reading),
                    CandidateKind.BUNSETSU,
                    bunsetsuReading = reading,
                    bunsetsuRightId = option.rightId,
                    bunsetsuGeneration = state.generation,
                ),
                CandidateUiModel(
                    RomajiKanaConverter.toKatakana(reading),
                    listOf(reading),
                    CandidateKind.BUNSETSU,
                    bunsetsuReading = reading,
                    bunsetsuRightId = option.rightId,
                    bunsetsuGeneration = state.generation,
                ),
            )
        }
        val candidates = (converted + scriptFallbacks)
            .distinctBy { Triple(it.text, it.bunsetsuReading, it.bunsetsuRightId) }
        state.setCandidates(candidates.map { it.text })
        showRomajiCandidates(candidates, state)
    }

    private fun roundRobinByReading(
        candidates: List<BunsetsuCandidateOption>,
    ): List<BunsetsuCandidateOption> {
        val groups = candidates.groupBy { it.reading }.values.map { it.toMutableList() }
        return buildList {
            while (groups.any { it.isNotEmpty() }) {
                groups.forEach { group -> if (group.isNotEmpty()) add(group.removeAt(0)) }
            }
        }
    }

    private fun changeBunsetsuRange(expand: Boolean, candidateGeneration: Long? = null) {
        val state = bunsetsuState ?: return
        if (!state.isCurrentCandidate(state.activeReading, candidateGeneration)) return
        val changed = if (expand) state.expand() else state.shrink()
        if (!changed) return
        composition.replace(state.composingText)
        romajiCursor.replace(state.composingText, state.composingText)
        romajiConversionState.onCompositionEdited(hasComposition = true)
        renderBunsetsuComposition(state)
        reanalyzeRemainingBunsetsu(
            state,
            requiredBoundary = ConversionText.scalarCount(state.activeReading),
        )
    }

    private fun reanalyzeRemainingBunsetsu(
        state: BunsetsuComposition,
        requiredBoundary: Int?,
    ) {
        candidatePipeline.invalidate()
        val token = state.analysisToken()
        val cursorRequest = romajiCursor.conversionSlice()?.request
            ?.takeIf { it.reading == token.remainingReading }
            ?: return
        if (token.activeReading.isEmpty() || token.remainingReading.isEmpty()) {
            romajiCandidates = emptyList()
            candidateBar.clear()
            return
        }
        val retainedCandidates = state.retainedOptions().map { option ->
            CandidateUiModel(
                text = option.surface,
                readings = listOf(option.reading),
                kind = CandidateKind.BUNSETSU,
                bunsetsuReading = option.reading,
                bunsetsuRightId = option.rightId,
                bunsetsuGeneration = state.generation,
            )
        }
        val immediateCandidates = (retainedCandidates + bunsetsuScriptCandidates(state))
            .distinctBy { Triple(it.text, it.bunsetsuReading, it.bunsetsuRightId) }
        state.setCandidates(immediateCandidates.map { it.text })
        showRomajiCandidates(immediateCandidates, state)
        candidatePipeline.submitRomajiAnalysis(
            kana = token.remainingReading,
            limit = MAX_WORD_CANDIDATES,
            initialRightId = token.previousRightId,
            initialContextSurface = token.previousContextSurface,
            requiredBoundary = requiredBoundary,
        ) { result ->
            if (bunsetsuState !== state ||
                !state.isCurrent(token) ||
                !romajiCursor.isCurrent(cursorRequest)
            ) return@submitRomajiAnalysis
            val plan = BunsetsuComposition.plan(
                reading = token.remainingReading,
                conversions = result.conversions,
                requestedBoundary = requiredBoundary,
                allowSingle = true,
            ) ?: return@submitRomajiAnalysis
            if (!state.applyPlan(plan, token)) return@submitRomajiAnalysis
            composition.replace(state.remainingReading)
            romajiCursor.replace(state.remainingReading, state.remainingReading)
            renderBunsetsuComposition(state)
            showBunsetsuPlan(state, plan)
        }
    }

    private fun withBunsetsuControls(
        state: BunsetsuComposition,
        candidates: List<CandidateUiModel>,
    ): List<CandidateUiModel> {
        val controls = buildList {
            if (state.canShrink) {
                add(
                    CandidateUiModel(
                        "←",
                        listOf(getString(R.string.segment_shrink)),
                        CandidateKind.SEGMENT_SHRINK,
                        bunsetsuGeneration = state.generation,
                    )
                )
            }
            if (state.canExpand) {
                add(
                    CandidateUiModel(
                        "→",
                        listOf(getString(R.string.segment_expand)),
                        CandidateKind.SEGMENT_EXPAND,
                        bunsetsuGeneration = state.generation,
                    )
                )
            }
        }
        return candidates.take((MAX_WORD_CANDIDATES - controls.size).coerceAtLeast(1)) + controls
    }

    /** Keep conversion selection separate from the candidate bar's scroll position. */
    private fun showRomajiCandidates(
        candidates: List<CandidateUiModel>,
        bunsetsu: BunsetsuComposition? = null,
    ) {
        val displayed = if (bunsetsu == null) {
            candidates.take(MAX_WORD_CANDIDATES)
        } else {
            withBunsetsuControls(bunsetsu, candidates)
        }
        romajiCandidates = displayed.filter {
            it.kind == CandidateKind.WORD || it.kind == CandidateKind.BUNSETSU
        }
        candidateBar.setCandidates(displayed)
        candidateBar.setSelectedCandidateIndex(
            romajiConversionState.selectedCandidateIndex(romajiCandidates.size)
        )
    }

    private fun handleRomajiSpace() {
        when (val action = romajiConversionState.onSpace(romajiCandidates.size)) {
            RomajiConversionState.KeyAction.InsertSpace -> commitDirect(" ")
            RomajiConversionState.KeyAction.SendEditorAction -> sendEnter()
            RomajiConversionState.KeyAction.CommitComposition -> finishComposition()
            is RomajiConversionState.KeyAction.SelectCandidate -> {
                candidateBar.setSelectedCandidateIndex(action.index)
            }
            is RomajiConversionState.KeyAction.CommitCandidate -> commitSelectedRomajiCandidate(action.index)
        }
    }

    private fun handleRomajiEnter() {
        when (val action = romajiConversionState.onEnter(romajiCandidates.size)) {
            RomajiConversionState.KeyAction.InsertSpace -> commitDirect(" ")
            RomajiConversionState.KeyAction.SendEditorAction -> sendEnter()
            RomajiConversionState.KeyAction.CommitComposition -> finishComposition()
            is RomajiConversionState.KeyAction.SelectCandidate -> {
                candidateBar.setSelectedCandidateIndex(action.index)
            }
            is RomajiConversionState.KeyAction.CommitCandidate -> commitSelectedRomajiCandidate(action.index)
        }
    }

    private fun commitSelectedRomajiCandidate(index: Int) {
        val candidate = romajiCandidates.getOrNull(index) ?: run {
            finishComposition()
            return
        }
        when (candidate.kind) {
            CandidateKind.WORD -> commitRomajiWordCandidate(candidate.text)
            CandidateKind.BUNSETSU -> commitBunsetsuCandidate(candidate)
            else -> finishComposition()
        }
    }

    private fun commitBunsetsuCandidate(candidate: CandidateUiModel) {
        val state = bunsetsuState ?: return
        val connection = currentInputConnection ?: return
        if (!state.isCurrentCandidate(candidate.bunsetsuReading, candidate.bunsetsuGeneration)) return
        val pathSurface = candidate.text.takeUnless {
            it == candidate.bunsetsuReading ||
                it == candidate.bunsetsuReading?.let(RomajiKanaConverter::toKatakana)
        }
        if (candidate.bunsetsuReading != null &&
            !state.selectActiveReading(
                candidate.bunsetsuReading,
                candidate.bunsetsuRightId,
                pathSurface,
            )
        ) return
        val result = state.commitActive(candidate.text, candidate.bunsetsuRightId)
        connection.beginBatchEdit()
        try {
            // commitText replaces only the current composing region. Re-create
            // the untouched suffix as the next composing region immediately.
            connection.commitText(result.committedText, 1)
            composition.replace(result.remainingText)
            if (result.remainingText.isEmpty()) {
                romajiCursor.clear()
                connection.finishComposingText()
            } else {
                romajiCursor.clear()
                romajiCursor.replace(result.remainingText, result.remainingText)
                setRomajiComposingText(styledBunsetsuText(state))
            }
        } finally {
            connection.endBatchEdit()
        }

        if (result.remainingText.isEmpty()) {
            bunsetsuState = null
            romajiConversionState.clear()
            romajiCandidates = emptyList()
            romajiCandidateRequest = null
            candidatePipeline.invalidate()
            candidateBar.clear()
        } else {
            romajiConversionState.onCompositionEdited(hasComposition = true)
            reanalyzeRemainingBunsetsu(state, requiredBoundary = null)
        }
        updateEnterLabel(currentInputEditorInfo)
    }

    private fun bunsetsuScriptCandidates(state: BunsetsuComposition): List<CandidateUiModel> = listOf(
        CandidateUiModel(
            state.activeReading,
            listOf(state.activeReading),
            CandidateKind.BUNSETSU,
            bunsetsuReading = state.activeReading,
            bunsetsuRightId = state.activeRightId,
            bunsetsuGeneration = state.generation,
        ),
        CandidateUiModel(
            RomajiKanaConverter.toKatakana(state.activeReading),
            listOf(state.activeReading),
            CandidateKind.BUNSETSU,
            bunsetsuReading = state.activeReading,
            bunsetsuRightId = state.activeRightId,
            bunsetsuGeneration = state.generation,
        ),
    ).distinctBy { it.text }

    private fun renderBunsetsuComposition(state: BunsetsuComposition) {
        setRomajiComposingText(styledBunsetsuText(state))
        updateEnterLabel(currentInputEditorInfo)
    }

    private fun styledBunsetsuText(state: BunsetsuComposition): CharSequence {
        val text = SpannableString(state.composingText)
        if (state.activeStart >= state.activeEnd || state.activeEnd > text.length) return text
        val accent = prefs.accentColor.accent(AccentStyle.isDark(this))
        val translucentAccent = (accent and 0x00ffffff) or (0x2f shl 24)
        text.setSpan(
            BackgroundColorSpan(translucentAccent),
            state.activeStart,
            state.activeEnd,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        text.setSpan(
            UnderlineSpan(),
            state.activeStart,
            state.activeEnd,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        return text
    }

    private fun leaveBunsetsuModeForEditing() {
        val state = bunsetsuState ?: return
        candidatePipeline.invalidate()
        composition.replace(state.composingText)
        bunsetsuState = null
    }

    private fun clearRomajiComposition() {
        candidatePipeline.invalidate()
        composition.clear()
        bunsetsuState = null
        romajiConversionState.clear()
        romajiCandidates = emptyList()
        romajiCandidateRequest = null
        romajiCursor.clear()
        currentInputConnection?.setComposingText("", 1)
        currentInputConnection?.finishComposingText()
        candidateBar.clear()
        updateEnterLabel(currentInputEditorInfo)
    }

    private fun deleteLastCodePoint(value: String): String {
        if (value.isEmpty()) return value
        return value.substring(0, value.offsetByCodePoints(value.length, -1))
    }

    private fun deleteRomajiInput(): Boolean {
        bunsetsuState?.let { state ->
            val remaining = state.deleteLastCodePoint()
            composition.replace(remaining)
            if (remaining.isEmpty()) {
                clearRomajiComposition()
            } else {
                romajiCursor.replace(remaining, remaining)
                romajiConversionState.onCompositionEdited(hasComposition = true)
                renderBunsetsuComposition(state)
                reanalyzeRemainingBunsetsu(
                    state,
                    requiredBoundary = ConversionText.scalarCount(state.activeReading),
                )
            }
            return true
        }
        if (!romajiCursor.isActive) return deleteBeforeCursor()

        val mutation = romajiEditor.deleteBeforeCursor()
        if (mutation !is CompositionCursorMutation.Applied) return true
        if (romajiCursor.displayText.isEmpty()) {
            clearRomajiComposition()
        } else if (mutation.compositionChanged || mutation.cursorChanged) {
            applyRomajiEdit(mutation)
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
        bunsetsuState = null
        romajiConversionState.clear()
        romajiCandidates = emptyList()
        romajiCandidateRequest = null
        romajiCursor.clear()
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
        latestCharacterCandidates = emptyList()
        wordRootBeforeLastCharacter = ""
        lastCharacterAlternatives = emptyList()
        lastCharacterCandidates = emptyList()
    }

    /** Keep the gesture/navigation area visually continuous with the keyboard surface. */
    private fun applyNavigationBarStyle() {
        val imeWindow = window?.window ?: return
        val darkMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        imeWindow.navigationBarColor = ContextCompat.getColor(this, R.color.kbd_background)
        WindowInsetsControllerCompat(imeWindow, imeWindow.decorView)
            .isAppearanceLightNavigationBars = !darkMode
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            imeWindow.isNavigationBarContrastEnforced = false
        }
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
