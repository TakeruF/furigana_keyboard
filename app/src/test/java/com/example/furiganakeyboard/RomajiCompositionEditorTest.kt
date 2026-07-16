package com.example.furiganakeyboard

import com.example.furiganakeyboard.ime.CompositionCursorMutation
import com.example.furiganakeyboard.ime.RomajiCompositionCursor
import com.example.furiganakeyboard.ime.RomajiCompositionEditor
import com.example.furiganakeyboard.ime.RomajiSelectionAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RomajiCompositionEditorTest {
    @Test
    fun middleInsertResolveAndDeletePreserveRightSuffix() {
        val cursor = RomajiCompositionCursor()
        val editor = RomajiCompositionEditor(cursor)
        cursor.replace("かな", "かな")
        assertEquals(
            RomajiSelectionAction.CursorChanged(cursorUtf16 = 1),
            cursor.onUpdateSelection(101, 101, 100, 102),
        )

        assertApplied(editor.append("k"))
        assertEquals("かkな", cursor.displayText)
        assertEquals("な", cursor.displayText.substring(cursor.cursorUtf16))
        assertNull(cursor.conversionSlice())

        assertApplied(editor.append("i"))
        assertEquals("かきな", cursor.displayText)
        assertEquals("かき", cursor.conversionSlice()!!.request.reading)
        assertEquals("な", cursor.conversionSlice()!!.resolvedSuffix)

        assertApplied(editor.deleteBeforeCursor())
        assertEquals("かな", cursor.displayText)
        assertEquals("か", cursor.conversionSlice()!!.request.reading)
        assertEquals("な", cursor.conversionSlice()!!.resolvedSuffix)
    }

    @Test
    fun unresolvedRawDeletionNeverConsumesResolvedSuffix() {
        val cursor = RomajiCompositionCursor()
        val editor = RomajiCompositionEditor(cursor)
        cursor.replace("かな", "かな")
        cursor.onUpdateSelection(101, 101, 100, 102)

        assertApplied(editor.append("s"))
        assertApplied(editor.append("h"))
        assertEquals("かshな", cursor.displayText)
        assertApplied(editor.deleteBeforeCursor())
        assertEquals("かsな", cursor.displayText)
        assertApplied(editor.deleteBeforeCursor())
        assertEquals("かな", cursor.displayText)
    }

    private fun assertApplied(result: CompositionCursorMutation) {
        assertTrue("expected Applied, got $result", result is CompositionCursorMutation.Applied)
    }
}
