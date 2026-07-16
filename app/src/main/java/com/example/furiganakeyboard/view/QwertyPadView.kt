package com.example.furiganakeyboard.view

import android.content.Context
import android.annotation.SuppressLint
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import android.content.res.ColorStateList
import com.example.furiganakeyboard.R
import com.example.furiganakeyboard.settings.AccentColor
import com.example.furiganakeyboard.settings.KeyboardPrefs
import com.example.furiganakeyboard.view.KeyFactory.Kind

/**
 * ABC input panel: a simple QWERTY keyboard (optional number row + three letter
 * rows + control row) with a one-shot shift, replacing the old placeholder toast.
 */
@SuppressLint("ViewConstructor")
class QwertyPadView(
    context: Context,
    private val includeJapaneseLongVowelKey: Boolean = false,
    showNumberRow: Boolean = true
) : LinearLayout(context) {

    /** Input callbacks wired by the IME service. */
    var onText: ((String) -> Unit)? = null
    var onSpace: (() -> Unit)? = null
    /**
     * Incremental user-facing cursor steps. Each step means one grapheme; the consumer owns actual
     * text-boundary movement, while this View only converts drag pixels to integer steps.
     */
    var onCursorStep: ((deltaInGraphemes: Int) -> Unit)? = null
    /** Optional observability hooks invoked with the built-in cursor haptics. */
    var onCursorModeHaptic: (() -> Unit)? = null
    var onCursorStepHaptic: ((deltaInGraphemes: Int) -> Unit)? = null
    var onDelete: (() -> Boolean)? = null
    var onEnter: (() -> Unit)? = null
    var onBack: (() -> Unit)? = null

    private var shifted = false
    private val letterKeys = mutableListOf<Button>()
    private var shiftKey: Button? = null
    private var enterKey: Button? = null
    private val numberRow = buildCharRow("1234567890")

    init {
        orientation = VERTICAL
        if (!includeJapaneseLongVowelKey) {
            setPadding(0, KeyFactory.dp(context, 4), 0, 0)
        }
        addRow(numberRow)
        setNumberRowVisible(showNumberRow)
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

    /** Show or hide the dedicated number row without rebuilding the panel. */
    fun setNumberRowVisible(visible: Boolean) {
        numberRow.visibility = if (visible) View.VISIBLE else View.GONE
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
            val textSize = if (c.isDigit()) NUMBER_TEXT_SIZE_SP else LETTER_TEXT_SIZE_SP
            val key = KeyFactory.key(context, c.toString(), Kind.PLAIN, textSize) {
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
            val key = KeyFactory.key(context, c.toString(), Kind.PLAIN, LETTER_TEXT_SIZE_SP) {
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
    @SuppressLint("ClickableViewAccessibility")
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
        val space = KeyFactory.deferredKey(
            context,
            "",
            Kind.FUNCTION,
            13f,
        ) {
                onSpace?.invoke() ?: onText?.invoke(" ")
        }.apply {
            contentDescription = context.getString(R.string.key_space)
        }
        space.setOnTouchListener(SpaceCursorTouchListener(space))
        row.addView(space, KeyFactory.rowParams(context, 3.5f))
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

    /** MotionEvent adapter; all gesture decisions live in [SpaceCursorGesture]. */
    private inner class SpaceCursorTouchListener(
        private val key: Button,
    ) : OnTouchListener {
        private val gesture = SpaceCursorGesture(
            SpaceCursorGesture.Config(
                longPressTimeoutMillis = ViewConfiguration.getLongPressTimeout().toLong(),
                activationDistance = ViewConfiguration.get(context).scaledTouchSlop.toFloat(),
                cursorStepDistancePixels = KeyFactory.dp(context, CURSOR_STEP_DP).toFloat(),
            )
        )
        private var activePointerId = INVALID_POINTER_ID
        private val longPressRunnable = Runnable {
            dispatchEffects(gesture.onLongPressTimeout(SystemClock.uptimeMillis()))
        }

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            if (!view.isEnabled) {
                cancel(view)
                return false
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cancel(view)
                    activePointerId = event.getPointerId(event.actionIndex)
                    view.isPressed = true
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    dispatchEffects(
                        gesture.onDown(
                            x = event.getX(event.actionIndex),
                            timeMillis = event.eventTime,
                            insideKey = true,
                        )
                    )
                    view.postDelayed(longPressRunnable, gesture.config.longPressTimeoutMillis)
                }
                MotionEvent.ACTION_MOVE -> {
                    val index = event.findPointerIndex(activePointerId)
                    if (index < 0) {
                        cancel(view)
                    } else {
                        val inside = isInside(view, event.getX(index), event.getY(index))
                        view.isPressed = inside
                        dispatchEffects(
                            gesture.onMove(event.getX(index), event.eventTime, inside)
                        )
                    }
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    gesture.onPointerAdded()
                    cancel(view, resetState = false)
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    if (event.getPointerId(event.actionIndex) == activePointerId) {
                        cancel(view)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val index = event.findPointerIndex(activePointerId)
                    view.removeCallbacks(longPressRunnable)
                    view.isPressed = false
                    if (index >= 0) {
                        val inside = isInside(view, event.getX(index), event.getY(index))
                        dispatchEffects(
                            gesture.onUp(event.getX(index), event.eventTime, inside)
                        )
                    } else {
                        gesture.onCancel()
                    }
                    finish(view)
                }
                MotionEvent.ACTION_CANCEL -> cancel(view)
            }
            return true
        }

        private fun dispatchEffects(effects: List<SpaceCursorGesture.Effect>) {
            effects.forEach { effect ->
                when (effect) {
                    SpaceCursorGesture.Effect.Tap -> key.performClick()
                    SpaceCursorGesture.Effect.CursorModeStarted -> {
                        key.removeCallbacks(longPressRunnable)
                        Haptics.selection(key)
                        onCursorModeHaptic?.invoke()
                    }
                    is SpaceCursorGesture.Effect.CursorStep -> {
                        // The gesture emits abstract steps. A3 maps these to real grapheme bounds.
                        val deltaInGraphemes = effect.deltaInSteps
                        Haptics.tick(key)
                        onCursorStepHaptic?.invoke(deltaInGraphemes)
                        onCursorStep?.invoke(deltaInGraphemes)
                    }
                }
            }
        }

        private fun cancel(view: View, resetState: Boolean = true) {
            view.removeCallbacks(longPressRunnable)
            view.isPressed = false
            if (resetState) gesture.onCancel()
            finish(view)
        }

        private fun finish(view: View) {
            activePointerId = INVALID_POINTER_ID
            view.parent?.requestDisallowInterceptTouchEvent(false)
        }

        private fun isInside(view: View, x: Float, y: Float): Boolean =
            x >= 0f && x < view.width && y >= 0f && y < view.height
    }

    /** Invisible flexible gap used to inset the middle letter row. */
    private inner class Spacer : android.view.View(context)

    private companion object {
        const val LETTER_TEXT_SIZE_SP = 24f
        const val NUMBER_TEXT_SIZE_SP = 20f
        const val CURSOR_STEP_DP = 12
        const val INVALID_POINTER_ID = -1
    }
}
