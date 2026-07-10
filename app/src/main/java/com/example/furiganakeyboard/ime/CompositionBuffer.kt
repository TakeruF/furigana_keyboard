package com.example.furiganakeyboard.ime

/** Unicode-safe model for the text currently owned by InputConnection composition. */
class CompositionBuffer {
    var text: String = ""
        private set

    val isEmpty: Boolean get() = text.isEmpty()

    fun append(value: String): String {
        text += value
        return text
    }

    fun replace(value: String): String {
        text = value
        return text
    }

    fun deleteLastCodePoint(): String {
        if (text.isEmpty()) return text
        val end = text.offsetByCodePoints(text.length, -1)
        text = text.substring(0, end)
        return text
    }

    fun clear() {
        text = ""
    }
}
