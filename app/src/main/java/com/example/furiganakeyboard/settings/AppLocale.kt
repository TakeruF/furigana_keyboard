package com.example.furiganakeyboard.settings

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object AppLocale {
    fun wrap(base: Context): Context {
        val tag = KeyboardPrefs(base).localeTag
        if (tag.isEmpty()) return base
        val locale = Locale.forLanguageTag(tag)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }
}
