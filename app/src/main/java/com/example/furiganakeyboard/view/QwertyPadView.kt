package com.example.furiganakeyboard.view

import android.content.Context
import android.annotation.SuppressLint
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import android.content.res.ColorStateList
import com.example.furiganakeyboard.R
import com.example.furiganakeyboard.settings.AccentColor
import com.example.furiganakeyboard.settings.KeyboardPrefs
import com.example.furiganakeyboard.view.KeyFactory.Kind

/**
 * ABC input panel: a simple QWERTY keyboard (number row + three letter rows +
 * control row) with a one-shot shift, replacing the old placeholder toast.
 */
class QwertyPadView(
    context: Context,
    private val includeJapaneseLongVowelKey: Boolean = false
) : LinearLayout(context) {

    /** Input callbacks wired by the IME service. */
    var onText: ((String) -> Unit)? = null
    var onDelete: (() -> Boolean)? = null
    var onEnter: (() -> Unit)? = null
    var onBack: (() -> Unit)? = null

    private var shifted = false
    private val letterKeys = mutableListOf<Button>()
    private var shiftKey: Button? = null
    private var enterKey: Button? = null

    init {
        orientation = VERTICAL
        if (!includeJapaneseLongVowelKey) {
            setPadding(0, KeyFactory.dp(context, 4), 0, 0)
        }
        addRow(buildCharRow("1234567890"))
        addRow(buildCharRow("qwertyuiop"))
        addRow(
            if (includeJapaneseLongVowelKey) {
                buildCharRow("asdfghjklー")
            } else {
                buildCharRow("asdfghjkl", sidePad = 0.5f)
            }
        )
        addRow(buildShiftRow("zxcvbnm"))
        addRow(buildBottomRow())
        setAccentColor(KeyboardPrefs(context).accentColor)
    }

    /** Update the enter key label (改行 / 送信 / 検索). */
    fun setEnterLabel(label: String) {
        enterKey?.text = label
    }

    /** Refresh accent controls when settings changed while the IME was hidden. */
    fun setAccentColor(color: AccentColor) {
        enterKey?.let { AccentStyle.apply(it, color) }
        val dark = AccentStyle.isDark(context)
        shiftKey?.foregroundTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()),
            intArrayOf(color.accent(dark), ContextCompat.getColor(context, R.color.kbd_on_surface))
        )
    }

    private fun addRow(row: LinearLayout) {
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    /** Plain row of single-character keys, optionally inset on both sides. */
    private fun buildCharRow(chars: String, sidePad: Float = 0f): LinearLayout {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL }
        if (sidePad > 0) row.addView(Spacer(), KeyFactory.rowParams(context, sidePad))
        for (c in chars) {
            val key = KeyFactory.key(context, c.toString(), Kind.PLAIN) {
                commitLetter(c)
            }
            if (c.isLetter()) {
                letterKeys.add(key)
            }
            row.addView(key, KeyFactory.rowParams(context, 1f))
        }
        if (sidePad > 0) row.addView(Spacer(), KeyFactory.rowParams(context, sidePad))
        return row
    }

    /** Compact shift icon + letters + ⌫ row. */
    @SuppressLint("ClickableViewAccessibility")
    private fun buildShiftRow(chars: String): LinearLayout {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL }
        val shift = KeyFactory.key(context, "", Kind.FUNCTION) { toggleShift() }.apply {
            contentDescription = context.getString(R.string.key_shift)
            foreground = ContextCompat.getDrawable(context, R.drawable.ic_shift_state)
            foregroundGravity = Gravity.CENTER
        }
        shiftKey = shift
        row.addView(shift, KeyFactory.rowParams(context, 1.5f))
        for (c in chars) {
            val key = KeyFactory.key(context, c.toString(), Kind.PLAIN) {
                commitLetter(c)
            }
            letterKeys.add(key)
            row.addView(key, KeyFactory.rowParams(context, 1f))
        }
        val delete = KeyFactory.key(context, context.getString(R.string.key_delete), Kind.FUNCTION, 18f)
        delete.setOnTouchListener(RepeatOnTouchListener { onDelete?.invoke() == true })
        row.addView(delete, KeyFactory.rowParams(context, 1.5f))
        return row
    }

    /** English uses [,] / [.]; Japanese uses [、] / [。]. Space stays icon-free. */
    private fun buildBottomRow(): LinearLayout {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL }
        val comma = if (includeJapaneseLongVowelKey) "、" else ","
        val period = if (includeJapaneseLongVowelKey) "。" else "."
        row.addView(
            KeyFactory.key(context, context.getString(R.string.key_back_hw), Kind.FUNCTION, 13f) {
                onBack?.invoke()
            },
            KeyFactory.rowParams(context, 1.5f)
        )
        row.addView(
            KeyFactory.key(context, comma, Kind.PLAIN) { onText?.invoke(comma) },
            KeyFactory.rowParams(context, 1f)
        )
        row.addView(
            KeyFactory.key(
                context,
                "",
                Kind.FUNCTION,
                13f
            ) {
                onText?.invoke(" ")
            }.apply {
                contentDescription = context.getString(R.string.key_space)
            },
            KeyFactory.rowParams(context, 3.5f)
        )
        row.addView(
            KeyFactory.key(context, period, Kind.PLAIN) { onText?.invoke(period) },
            KeyFactory.rowParams(context, 1f)
        )
        enterKey = KeyFactory.key(context, context.getString(R.string.key_enter), Kind.ACCENT, 13f) {
            onEnter?.invoke()
        }
        row.addView(enterKey, KeyFactory.rowParams(context, 2f))
        return row
    }

    /** Commit a letter, applying (and then releasing) the one-shot shift. */
    private fun commitLetter(c: Char) {
        val out = if (shifted && c.isLetter()) c.uppercaseChar() else c
        onText?.invoke(out.toString())
        if (shifted) toggleShift()
    }

    /** Toggle shift: relabel letters and switch the arrow between outline/fill. */
    private fun toggleShift() {
        shifted = !shifted
        shiftKey?.isSelected = shifted
        for (key in letterKeys) {
            val t = key.text.toString()
            key.text = if (shifted) t.uppercase() else t.lowercase()
        }
    }

    /** Invisible flexible gap used to inset the middle letter row. */
    private inner class Spacer : android.view.View(context)
}
