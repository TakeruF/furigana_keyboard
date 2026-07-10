package com.example.furiganakeyboard.view

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View

/**
 * Touch listener that fires [action] immediately on press and then repeats it
 * while the key is held (used for the delete key, like real keyboards).
 * Do not also set an OnClickListener on the same view.
 */
class RepeatOnTouchListener(
    private val initialDelayMs: Long = 400,
    private val intervalMs: Long = 60,
    private val action: () -> Boolean
) : View.OnTouchListener {

    private val handler = Handler(Looper.getMainLooper())
    private var heldView: View? = null
    private val repeater = object : Runnable {
        override fun run() {
            val view = heldView ?: return
            if (action()) {
                Haptics.tick(view) // subtle feedback only when something was deleted
                handler.postDelayed(this, intervalMs)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                v.isPressed = true
                heldView = v
                if (action()) {
                    Haptics.key(v)
                    handler.postDelayed(repeater, initialDelayMs)
                }
            }
            MotionEvent.ACTION_UP -> {
                v.isPressed = false
                heldView = null
                handler.removeCallbacks(repeater)
                v.performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                v.isPressed = false
                heldView = null
                handler.removeCallbacks(repeater)
            }
        }
        return true
    }
}
