package com.example.furiganakeyboard.view

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.furiganakeyboard.R
import com.example.furiganakeyboard.reading.RomajiConverter
import com.example.furiganakeyboard.settings.ReadingMode

/**
 * Horizontal, scrollable candidate bar. The first (best) candidate is shown in
 * a compact white pill with blue text, while later candidates remain flat.
 * Each chip shows the character large with its readings
 * underneath (kana / romaji / hidden). Tapping a chip fires [onCandidateSelected].
 */
class CandidateBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : HorizontalScrollView(context, attrs, defStyle) {

    /** Invoked with the candidate text when a chip is tapped. */
    var onCandidateSelected: ((CandidateUiModel) -> Unit)? = null

    /** Best candidate currently displayed, or null (used for auto-commit). */
    val firstCandidate: CandidateUiModel?
        get() = candidates.firstOrNull()

    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val pad = dp(2)
        setPadding(pad, pad, pad, pad)
    }

    private var candidates: List<CandidateUiModel> = emptyList()
    private var readingMode: ReadingMode = ReadingMode.KANA

    init {
        isFillViewport = true
        isHorizontalScrollBarEnabled = false
        addView(
            row,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
    }

    /** Replace the displayed candidates and rebuild the chips. */
    fun setCandidates(items: List<CandidateUiModel>) {
        candidates = items
        rebuild()
    }

    /** Change how readings are rendered; refreshes existing chips. */
    fun setReadingMode(mode: ReadingMode) {
        readingMode = mode
        rebuild()
    }

    fun clear() = setCandidates(emptyList())

    private fun rebuild() {
        row.removeAllViews()
        scrollX = 0
        candidates.forEachIndexed { index, candidate ->
            row.addView(makeChip(candidate, highlight = index == 0))
        }
    }

    /** Build a single tappable chip (character + reading). */
    private fun makeChip(candidate: CandidateUiModel, highlight: Boolean): View {
        val chip = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = ContextCompat.getDrawable(
                context,
                if (highlight) R.drawable.candidate_selected_background
                else R.drawable.candidate_background
            )
            val padH = dp(16)
            setPadding(padH, dp(3), padH, dp(3))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                Haptics.key(it)
                if (candidate.kind != CandidateKind.STATUS) onCandidateSelected?.invoke(candidate)
            }
        }
        chip.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        )

        // The first candidate is the only blue element in this row.
        chip.addView(TextView(context).apply {
            text = candidate.text
            textLocale = java.util.Locale.JAPAN // force Japanese glyph variants
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(
                color(if (highlight) R.color.kbd_accent else R.color.kbd_on_surface)
            )
        })

        // Reading (small), unless OFF.
        val reading = readingText(candidate)
        if (readingMode != ReadingMode.OFF && reading.isNotEmpty()) {
            chip.addView(TextView(context).apply {
                text = reading
                textLocale = java.util.Locale.JAPAN
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                gravity = Gravity.CENTER
                maxLines = 1
                setTextColor(color(R.color.kbd_on_surface_secondary))
            })
        }
        return chip
    }

    /**
     * Compose the reading line for a candidate in the current mode.
     * On-yomi appear in katakana, kun-yomi with okurigana in parentheses
     * (うつ(す)); at most [MAX_READINGS] readings are shown, then "…".
     */
    private fun readingText(candidate: CandidateUiModel): String {
        val raw = candidate.readings
        if (raw.isEmpty()) return ""
        val shown = raw.take(MAX_READINGS).map(::displayReading)
        val suffix = if (raw.size > MAX_READINGS) " …" else ""
        return when (readingMode) {
            ReadingMode.KANA -> shown.joinToString(" / ") + suffix
            ReadingMode.ROMAJI ->
                shown.joinToString(" / ") { RomajiConverter.toRomaji(it) } + suffix
            ReadingMode.OFF -> ""
        }
    }

    private fun displayReading(reading: String): String {
        val dot = reading.indexOf('.')
        return if (dot < 0) reading
        else reading.substring(0, dot) + "(" + reading.substring(dot + 1) + ")"
    }

    private fun color(resId: Int) = ContextCompat.getColor(context, resId)

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()

    companion object {
        // Cap readings per chip so the bar stays scannable.
        private const val MAX_READINGS = 3
    }
}
