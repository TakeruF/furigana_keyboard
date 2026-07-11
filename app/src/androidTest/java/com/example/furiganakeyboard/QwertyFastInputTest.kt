package com.example.furiganakeyboard

import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ContextThemeWrapper
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.content.ContextCompat
import com.example.furiganakeyboard.view.QwertyPadView
import com.example.furiganakeyboard.view.KeyboardPanelContainer
import com.example.furiganakeyboard.view.SymbolPadView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QwertyFastInputTest {
    @Test
    fun materialSettingsIconsLoadAtTheApprovedSize() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val expected = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            24f,
            context.resources.displayMetrics
        ).toInt()
        listOf(
            R.drawable.ic_settings_reading,
            R.drawable.ic_settings_layout,
            R.drawable.ic_settings_handwriting,
            R.drawable.ic_settings_effects,
            R.drawable.ic_settings_language,
            R.drawable.ic_settings_keyboard,
            R.drawable.ic_settings_privacy,
            R.drawable.ic_settings_legal,
            R.drawable.ic_settings_info,
            R.drawable.ic_settings_help,
            R.drawable.ic_arrow_back,
            R.drawable.ic_chevron_right,
            R.drawable.ic_check
        ).forEach { drawableRes ->
            val drawable = ContextCompat.getDrawable(context, drawableRes)
            assertNotNull(drawable)
            assertEquals(expected, drawable?.intrinsicWidth)
            assertEquals(expected, drawable?.intrinsicHeight)
        }
    }

    @Test
    fun handwritingCanvasHeightIsHalfTheAvailableWidth() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val panel = KeyboardPanelContainer(context)
            val width = 400
            val fixedHeight = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                52f,
                context.resources.displayMetrics
            ).toInt()

            panel.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1_000, View.MeasureSpec.AT_MOST)
            )

            assertEquals(width / 2 + fixedHeight, panel.measuredHeight)

            panel.canvasScale = 0.82f
            panel.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1_000, View.MeasureSpec.AT_MOST)
            )
            assertEquals((width / 2f * 0.82f).toInt() + fixedHeight, panel.measuredHeight)
        }
    }

    @Test
    fun generatedKeyboardKeysCenterTheirLabels() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val buttons = QwertyPadView(context).buttons() + SymbolPadView(context).buttons()

            assertTrue(buttons.isNotEmpty())
            buttons.forEach { button ->
                assertEquals("gravity for ${button.text}", Gravity.CENTER, button.gravity)
                assertEquals(
                    "text alignment for ${button.text}",
                    View.TEXT_ALIGNMENT_CENTER,
                    button.textAlignment
                )
                assertFalse("font padding for ${button.text}", button.includeFontPadding)
            }
        }
    }

    @Test
    fun shiftUsesAStatefulIconAndFillsWhenSelected() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val pad = QwertyPadView(context)
            val shift = pad.buttons().single { it.contentDescription == "Shift" }

            assertEquals("", shift.text.toString())
            assertTrue(shift.compoundDrawablesRelative.all { it == null })
            assertNotNull(shift.foreground)
            assertEquals(Gravity.CENTER, shift.foregroundGravity)
            assertFalse(shift.isSelected)
            shift.performClick()
            assertTrue(shift.isSelected)
            shift.performClick()
            assertFalse(shift.isSelected)
        }
    }

    @Test
    fun longVowelKeyAppearsOnlyOnJapaneseRomajiPad() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val englishLabels = QwertyPadView(context).buttons().map { it.text.toString() }
            val romajiLabels = QwertyPadView(
                context,
                includeJapaneseLongVowelKey = true
            ).buttons().map { it.text.toString() }

            assertFalse(englishLabels.contains("ー"))
            assertTrue(romajiLabels.contains("ー"))
        }
    }

    @Test
    fun englishPadUsesAsciiPunctuationWhileJapaneseRomajiKeepsJapaneseMarks() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            var englishText = ""
            val english = QwertyPadView(context).apply { onText = { englishText += it } }
            val romaji = QwertyPadView(context, includeJapaneseLongVowelKey = true)

            english.findButton(",").performClick()
            english.findButton(".").performClick()
            assertEquals(",.", englishText)
            assertTrue(romaji.buttons().map { it.text.toString() }.containsAll(listOf("、", "。")))
        }
    }

    @Test
    fun symbolPadUsesJapanesePunctuation() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            var text = ""
            val pad = SymbolPadView(context).apply { onText = { text += it } }
            val labels = pad.buttons().map { it.text.toString() }

            assertTrue(labels.contains("。"))
            assertTrue(labels.contains("、"))
            assertFalse(labels.contains("."))
            assertFalse(labels.contains(","))
            pad.findButton("。").performClick()
            pad.findButton("、").performClick()
            assertEquals("。、", text)
        }
    }

    @Test
    fun xmlKeyboardControlsAndGlobeAreCentered() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
            val context = ContextThemeWrapper(appContext, R.style.Theme_FuriganaKeyboard)
            val root = LayoutInflater.from(context).inflate(R.layout.keyboard_view, null)
            val bottomRow = root.findViewById<View>(R.id.handwritingBottomRow)
            assertEquals(
                (48 * context.resources.displayMetrics.density).toInt(),
                bottomRow.layoutParams.height
            )
            val controlIds = listOf(
                R.id.keySymbol,
                R.id.keyEnglish,
                R.id.keySpace,
                R.id.keyRomaji,
                R.id.keyEnter,
                R.id.keyClearInk,
                R.id.keyDelete,
                R.id.keyComma,
                R.id.keyPeriod,
                R.id.keyQuestion
            )

            controlIds.map { root.findViewById<Button>(it) }.forEach { button ->
                assertEquals("gravity for ${button.id}", Gravity.CENTER, button.gravity)
                assertEquals(View.TEXT_ALIGNMENT_CENTER, button.textAlignment)
                assertFalse(button.includeFontPadding)
            }
            val globe = root.findViewById<ImageButton>(R.id.keyKeyboardSwitch)
            assertNotNull(globe.drawable)
            assertEquals(ImageView.ScaleType.CENTER, globe.scaleType)
        }
    }

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

    private fun View.buttons(): List<Button> = buildList {
        if (this@buttons is Button) add(this@buttons)
        if (this@buttons is ViewGroup) {
            for (index in 0 until childCount) addAll(getChildAt(index).buttons())
        }
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
