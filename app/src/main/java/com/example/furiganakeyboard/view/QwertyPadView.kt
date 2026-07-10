package com.example.furiganakeyboard.view

import android.content.Context
import android.annotation.SuppressLint
import android.widget.Button
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.example.furiganakeyboard.R
import com.example.furiganakeyboard.view.KeyFactory.Kind

/**
 * ABC input panel: a simple QWERTY keyboard (number row + three letter rows +
 * control row) with a one-shot shift, replacing the old placeholder toast.
 */
class QwertyPadView(context: Context) : LinearLayout(context) {

    /** Input callbacks wired by the IME service. */
    var onText: ((String) -> Unit)? = null
    var onDelete: (() -> Unit)? = null
    var onEnter: (() -> Unit)? = null
    var onBack: (() -> Unit)? = null

    private var shifted = false
    private val letterKeys = mutableListOf<Button>()
    private var shiftKey: Button? = null
    private var enterKey: Button? = null

    init {
        orientation = VERTICAL
        addRow(buildCharRow("1234567890"))
        addRow(buildCharRow("qwertyuiop"))
        addRow(buildCharRow("asdfghjkl", sidePad = 0.5f))
        addRow(buildShiftRow("zxcvbnm"))
        addRow(buildBottomRow())
    }

    /** Update the enter key label (改行 / 送信 / 検索). */
    fun setEnterLabel(label: String) {
        enterKey?.text = label
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
            if (c.isLetter()) letterKeys.add(key)
            row.addView(key, KeyFactory.rowParams(context, 1f))
        }
        if (sidePad > 0) row.addView(Spacer(), KeyFactory.rowParams(context, sidePad))
        return row
    }

    /** ⇧ + letters + ⌫ row. */
    @SuppressLint("ClickableViewAccessibility")
    private fun buildShiftRow(chars: String): LinearLayout {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL }
        shiftKey = KeyFactory.key(context, "⇧", Kind.FUNCTION, 18f) { toggleShift() }
        row.addView(shiftKey, KeyFactory.rowParams(context, 1.5f))
        for (c in chars) {
            val key = KeyFactory.key(context, c.toString(), Kind.PLAIN) {
                commitLetter(c)
            }
            letterKeys.add(key)
            row.addView(key, KeyFactory.rowParams(context, 1f))
        }
        val delete = KeyFactory.key(context, context.getString(R.string.key_delete), Kind.FUNCTION, 18f)
        delete.setOnTouchListener(RepeatOnTouchListener { onDelete?.invoke() })
        row.addView(delete, KeyFactory.rowParams(context, 1.5f))
        return row
    }

    /** [手書き] [、] [space] [。] [改行] */
    private fun buildBottomRow(): LinearLayout {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL }
        row.addView(
            KeyFactory.key(context, context.getString(R.string.key_back_hw), Kind.FUNCTION, 13f) {
                onBack?.invoke()
            },
            KeyFactory.rowParams(context, 1.5f)
        )
        row.addView(
            KeyFactory.key(context, "、", Kind.PLAIN) { onText?.invoke("、") },
            KeyFactory.rowParams(context, 1f)
        )
        row.addView(
            KeyFactory.key(context, context.getString(R.string.key_space), Kind.FUNCTION, 13f) {
                onText?.invoke(" ")
            },
            KeyFactory.rowParams(context, 3.5f)
        )
        row.addView(
            KeyFactory.key(context, "。", Kind.PLAIN) { onText?.invoke("。") },
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

    /** Toggle shift: relabel letter keys and tint the shift key. */
    private fun toggleShift() {
        shifted = !shifted
        shiftKey?.isSelected = shifted
        for (key in letterKeys) {
            val t = key.text.toString()
            key.text = if (shifted) t.uppercase() else t.lowercase()
        }
        shiftKey?.setTextColor(
            ContextCompat.getColor(
                context,
                if (shifted) R.color.kbd_accent else R.color.kbd_on_surface
            )
        )
    }

    /** Invisible flexible gap used to inset the middle letter row. */
    private inner class Spacer : android.view.View(context)
}
