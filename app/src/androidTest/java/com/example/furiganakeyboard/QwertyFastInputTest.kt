package com.example.furiganakeyboard

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.furiganakeyboard.view.QwertyPadView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QwertyFastInputTest {
    @Test
    fun overlappingKeyPressesAreAcceptedBeforeEitherFingerIsReleased() {
        var text = ""
        var textBeforeRelease = ""

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val pad = QwertyPadView(ApplicationProvider.getApplicationContext()).apply {
                onText = { text += it }
            }
            val a = pad.findButton("a")
            val s = pad.findButton("s")

            assertTrue(a.dispatch(MotionEvent.ACTION_DOWN))
            assertTrue(s.dispatch(MotionEvent.ACTION_DOWN))
            textBeforeRelease = text

            assertTrue(a.dispatch(MotionEvent.ACTION_UP))
            assertTrue(s.dispatch(MotionEvent.ACTION_UP))
        }

        assertEquals("as", textBeforeRelease)
        assertEquals("releasing keys must not type twice", "as", text)
    }

    private fun View.findButton(label: String): Button {
        if (this is Button && text.toString() == label) return this
        if (this is ViewGroup) {
            for (index in 0 until childCount) {
                runCatching { return getChildAt(index).findButton(label) }
            }
        }
        error("Key not found: $label")
    }

    private fun View.dispatch(action: Int): Boolean {
        val time = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(time, time, action, 1f, 1f, 0)
        return try {
            dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }
}
