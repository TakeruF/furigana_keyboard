package com.example.furiganakeyboard.view

import android.content.Context
import android.annotation.SuppressLint
import android.widget.Button
import android.widget.LinearLayout
import com.example.furiganakeyboard.R
import com.example.furiganakeyboard.settings.AccentColor
import com.example.furiganakeyboard.settings.KeyboardPrefs
import com.example.furiganakeyboard.view.KeyFactory.Kind

/**
 * Simple 123 panel. The center three columns use the familiar calculator-style
 * number order; no flick alternatives or secondary symbol legends are shown.
 */
class SymbolPadView(context: Context) : LinearLayout(context) {

    /** Input callbacks wired by the IME service. */
    var onText: ((String) -> Unit)? = null
    var onDelete: (() -> Boolean)? = null
    var onEnter: (() -> Unit)? = null
    var onBack: (() -> Unit)? = null

    private var enterKey: Button? = null

    init {
        orientation = VERTICAL
        addRow(
            textKey("@", Kind.FUNCTION),
            numberKey("1"),
            numberKey("2"),
            numberKey("3"),
            deleteKey()
        )
        addRow(
            textKey("-", Kind.FUNCTION),
            numberKey("4"),
            numberKey("5"),
            numberKey("6"),
            textKey("/", Kind.FUNCTION)
        )
        addRow(
            textKey("(", Kind.FUNCTION),
            numberKey("7"),
            numberKey("8"),
            numberKey("9"),
            textKey(")", Kind.FUNCTION)
        )
        val enter = actionKey(context.getString(R.string.key_enter), 13f, Kind.ACCENT) {
            onEnter?.invoke()
        }
        enterKey = enter
        addRow(
            actionKey(context.getString(R.string.key_back_hw), 13f) { onBack?.invoke() },
            textKey("。"),
            numberKey("0"),
            textKey("、"),
            enter
        )
        setAccentColor(KeyboardPrefs(context).accentColor)
    }

    /** Update the enter key label (改行 / 送信 / 検索). */
    fun setEnterLabel(label: String) {
        enterKey?.text = label
    }

    fun setAccentColor(color: AccentColor) {
        enterKey?.let { AccentStyle.apply(it, color) }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun deleteKey(): Button {
        val delete = KeyFactory.key(context, context.getString(R.string.key_delete), Kind.FUNCTION, 18f)
        delete.setOnTouchListener(RepeatOnTouchListener { onDelete?.invoke() == true })
        return delete
    }

    private fun numberKey(value: String): Button =
        KeyFactory.key(context, value, Kind.PLAIN, 28f) { onText?.invoke(value) }

    private fun textKey(value: String, kind: Kind = Kind.PLAIN): Button =
        KeyFactory.key(context, value, kind, 22f) { onText?.invoke(value) }

    private fun actionKey(
        label: String,
        textSizeSp: Float,
        kind: Kind = Kind.FUNCTION,
        action: () -> Unit
    ): Button = KeyFactory.key(context, label, kind, textSizeSp, action)

    private fun addRow(vararg keys: Button) {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL }
        keys.forEachIndexed { index, key ->
            val weight = if (index == 0 || index == keys.lastIndex) SIDE_KEY_WEIGHT else 1f
            row.addView(key, KeyFactory.rowParams(context, weight))
        }
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    companion object {
        private const val SIDE_KEY_WEIGHT = 1.12f
    }
}
