package com.example.furiganakeyboard.view

import android.content.Context
import android.annotation.SuppressLint
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.example.furiganakeyboard.R
import com.example.furiganakeyboard.view.KeyFactory.Kind

/**
 * Symbol input panel (記号): a scrollable grid of Japanese and ASCII symbols
 * with a bottom control row, replacing the old placeholder toast.
 */
class SymbolPadView(context: Context) : LinearLayout(context) {

    /** Input callbacks wired by the IME service. */
    var onText: ((String) -> Unit)? = null
    var onDelete: (() -> Unit)? = null
    var onEnter: (() -> Unit)? = null
    var onBack: (() -> Unit)? = null

    private var enterKey: Button? = null

    init {
        orientation = VERTICAL
        buildGrid()
        buildBottomRow()
    }

    /** Update the enter key label (改行 / 送信 / 検索). */
    fun setEnterLabel(label: String) {
        enterKey?.text = label
    }

    private fun buildGrid() {
        val grid = GridLayout(context).apply { columnCount = COLUMNS }
        for (symbol in SYMBOLS) {
            val key = KeyFactory.key(context, symbol.toString(), Kind.PLAIN, 18f) {
                onText?.invoke(symbol.toString())
            }
            val lp = GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED),
                GridLayout.spec(GridLayout.UNDEFINED, 1f)
            ).apply {
                width = 0
                height = KeyFactory.dp(context, 48)
                val m = KeyFactory.dp(context, 2)
                setMargins(m, m, m, m)
            }
            grid.addView(key, lp)
        }
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(
                grid,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            )
        }
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildBottomRow() {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL }
        row.addView(
            KeyFactory.key(context, context.getString(R.string.key_back_hw), Kind.FUNCTION, 13f) {
                onBack?.invoke()
            },
            KeyFactory.rowParams(context, 1.5f)
        )
        row.addView(
            KeyFactory.key(context, context.getString(R.string.key_space), Kind.FUNCTION, 13f) {
                onText?.invoke(" ")
            },
            KeyFactory.rowParams(context, 3f)
        )
        // Delete repeats while held.
        val delete = KeyFactory.key(context, context.getString(R.string.key_delete), Kind.FUNCTION, 18f)
        delete.setOnTouchListener(RepeatOnTouchListener { onDelete?.invoke() })
        row.addView(delete, KeyFactory.rowParams(context, 1.5f))

        enterKey = KeyFactory.key(context, context.getString(R.string.key_enter), Kind.ACCENT, 13f) {
            onEnter?.invoke()
        }
        row.addView(enterKey, KeyFactory.rowParams(context, 2f))

        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, KeyFactory.dp(context, 52)))
    }

    companion object {
        private const val COLUMNS = 6

        // Numbers, Japanese symbols, then ASCII. One key per character.
        private val SYMBOLS: List<Char> = (
            "1234567890" +
                "、。，．・：；？！〜ー…「」『』（）〔〕［］｛｝〈〉《》【】" +
                "＋－±×÷＝≠＜＞％＃＆＊＠☆★○●◎◇◆□■△▲▽▼※→←↑↓♪￥" +
                "!?@#$%&*()-_=+/\\:;\"',.<>[]{}|^~`"
            ).toList()
    }
}
