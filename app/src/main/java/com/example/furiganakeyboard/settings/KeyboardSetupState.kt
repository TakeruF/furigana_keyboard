package com.example.furiganakeyboard.settings

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import com.example.furiganakeyboard.ime.FuriganaImeService

/** Reads the current Android IME enablement and selection state. */
object KeyboardSetupState {
    fun isEnabled(context: Context): Boolean {
        val target = component(context)
        val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        return manager.enabledInputMethodList.any {
            ComponentName(it.packageName, it.serviceName) == target
        }
    }

    fun isSelected(context: Context): Boolean {
        val current = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD
        )
        return current == component(context).flattenToString()
    }

    private fun component(context: Context) =
        ComponentName(context, FuriganaImeService::class.java)
}
