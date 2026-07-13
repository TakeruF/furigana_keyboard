package com.example.furiganakeyboard.view

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
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
import com.example.furiganakeyboard.settings.AccentColor
import com.example.furiganakeyboard.settings.CandidateTextSize
import kotlin.math.ceil

/**
 * Horizontal, scrollable candidate bar. The first (best) candidate is shown in
 * with accent-colored text, while all candidate backgrounds remain flat.
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
    private var accentColor: Int = color(R.color.kbd_accent)
    private var candidateTextSize: CandidateTextSize = CandidateTextSize.STANDARD

    init {
        isFillViewport = true
        isHorizontalScrollBarEnabled = false
        addView(
            row,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
    }

    /** Replace the displayed candidates, reusing the existing chip views. */
    fun setCandidates(items: List<CandidateUiModel>) {
        val bounded = items.take(MAX_CANDIDATES)
        if (bounded == candidates) return
        candidates = bounded
        bindCandidates(resetScroll = true)
    }

    /** Change how readings are rendered; refreshes existing chips. */
    fun setReadingMode(mode: ReadingMode) {
        if (readingMode == mode) return
        readingMode = mode
        bindCandidates(resetScroll = false)
    }

    /** Apply the user-selected accent to the leading candidate. */
    fun setAccentColor(value: AccentColor) {
        val resolved = value.accent(AccentStyle.isDark(context))
        if (accentColor == resolved) return
        accentColor = resolved
        bindCandidates(resetScroll = false)
    }

    fun setCandidateTextSize(value: CandidateTextSize) {
        if (candidateTextSize == value) return
        candidateTextSize = value
        bindCandidates(resetScroll = false)
    }

    fun clear() = setCandidates(emptyList())

    private fun bindCandidates(resetScroll: Boolean) {
        while (row.childCount < candidates.size) {
            row.addView(CandidateChipView(context))
        }
        for (index in 0 until row.childCount) {
            val chip = row.getChildAt(index) as CandidateChipView
            if (index < candidates.size) {
                chip.visibility = View.VISIBLE
                chip.bind(
                    candidates[index],
                    highlight = index == 0,
                    showDivider = index < candidates.lastIndex
                )
            } else {
                chip.visibility = View.GONE
                chip.unbind()
            }
        }
        if (resetScroll) scrollTo(0, 0)
    }

    /** Rebindable candidate chip; at most [MAX_CANDIDATES] instances are allocated. */
    private inner class CandidateChipView(context: Context) : LinearLayout(context) {
        private var candidate: CandidateUiModel? = null
        private val primary = TextView(context).apply {
            textLocale = java.util.Locale.JAPAN
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        private val reading = TextView(context).apply {
            textLocale = java.util.Locale.JAPAN
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(color(R.color.kbd_on_surface_secondary))
        }

        init {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(3), 0, dp(3))
            minimumWidth = 0
            minimumHeight = dp(48)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            addView(primary)
            addView(reading)
            setOnClickListener {
                candidate?.takeIf { it.kind != CandidateKind.STATUS }?.let { selected ->
                    Haptics.key(it)
                    onCandidateSelected?.invoke(selected)
                }
            }
        }

        fun bind(value: CandidateUiModel, highlight: Boolean, showDivider: Boolean) {
            candidate = value
            background = ContextCompat.getDrawable(context, R.drawable.candidate_background)
            foreground = if (showDivider) {
                ContextCompat.getDrawable(context, R.drawable.candidate_divider)
            } else null
            primary.text = value.text
            primary.setTextSize(TypedValue.COMPLEX_UNIT_SP, candidateTextSize.primarySp)
            reading.setTextSize(TypedValue.COMPLEX_UNIT_SP, candidateTextSize.readingSp)
            primary.setTextColor(
                if (highlight) accentColor else color(R.color.kbd_on_surface)
            )
            val readingText = readingText(value)
            reading.text = readingText
            reading.visibility = if (readingMode == ReadingMode.OFF || readingText.isEmpty()) {
                View.GONE
            } else View.VISIBLE
            updateCandidateWidth(value.text, readingText)
            isClickable = value.kind != CandidateKind.STATUS
            isFocusable = isClickable
            contentDescription = buildString {
                append(value.text)
                if (readingText.isNotEmpty()) append(", ").append(readingText)
            }
        }

        fun unbind() {
            candidate = null
            primary.text = ""
            reading.text = ""
            foreground = null
            isClickable = false
            isFocusable = false
            contentDescription = null
        }

        private fun updateCandidateWidth(primaryText: String, readingText: String) {
            val primaryWidth = primary.paint.measureText(primaryText)
            val readingWidth = if (reading.visibility == View.VISIBLE) {
                reading.paint.measureText(readingText)
            } else {
                0f
            }
            val longestPaint = if (readingWidth > primaryWidth) reading.paint else primary.paint
            val contentWidth = maxOf(primaryWidth, readingWidth)
            val singleSpace = longestPaint.measureText(" ")
            layoutParams = (layoutParams as LinearLayout.LayoutParams).apply {
                width = ceil(contentWidth + singleSpace * 2).toInt()
            }
        }
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
        private const val MAX_CANDIDATES = 10
        // Cap readings per chip so the bar stays scannable.
        private const val MAX_READINGS = 3
    }
}
