package com.example.furiganakeyboard.settings

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.furiganakeyboard.R

/** Shown whenever this IME is not the currently selected Android input method. */
class OnboardingActivity : AppCompatActivity() {
    private lateinit var prefs: KeyboardPrefs
    private lateinit var actionsHost: LinearLayout
    private var initialized = false
    private var navigatingAway = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = KeyboardPrefs(this)
        super.onCreate(savedInstanceState)

        if (KeyboardSetupState.isSelected(this)) {
            openSettings()
            return
        }

        val darkMode = isDarkMode()
        val background = prefs.accentColor.settingsBackground(darkMode)
        window.statusBarColor = background
        window.navigationBarColor = background
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !darkMode
            isAppearanceLightNavigationBars = !darkMode
        }
        setContentView(buildScreen())
        initialized = true
        refreshState()
    }

    override fun onResume() {
        super.onResume()
        if (initialized) refreshOrContinue()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The system input-method picker can take focus without pausing this activity.
        if (hasFocus && initialized) refreshOrContinue()
    }

    private fun buildScreen(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(24), dp(24), dp(24), dp(22))
        setBackgroundColor(prefs.accentColor.settingsBackground(isDarkMode()))

        addView(View(context), LinearLayout.LayoutParams(1, 0, 0.85f))
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_launcher)
            contentDescription = null
        }, LinearLayout.LayoutParams(dp(116), dp(116)))
        addView(TextView(context).apply {
            setText(R.string.onboarding_title)
            gravity = Gravity.CENTER
            setTextColor(color(R.color.settings_text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 29f)
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(20) })
        addView(TextView(context).apply {
            setText(R.string.onboarding_description)
            gravity = Gravity.CENTER
            setTextColor(color(R.color.settings_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        addView(View(context), LinearLayout.LayoutParams(1, 0, 0.75f))
        actionsHost = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        addView(actionsHost, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        addView(TextView(context).apply {
            setText(R.string.onboarding_privacy_note)
            gravity = Gravity.CENTER
            setTextColor(color(R.color.settings_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setLineSpacing(0f, 1.12f)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(14) })
        addView(View(context), LinearLayout.LayoutParams(1, 0, 0.35f))
    }

    private fun refreshOrContinue() {
        if (KeyboardSetupState.isSelected(this)) openSettings() else refreshState()
    }

    private fun refreshState() {
        val keyboardEnabled = KeyboardSetupState.isEnabled(this)
        actionsHost.removeAllViews()
        actionsHost.addView(setupAction(
            labelRes = R.string.onboarding_step_enable,
            active = !keyboardEnabled
        ) {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }, actionParams(bottom = 12))
        actionsHost.addView(setupAction(
            labelRes = R.string.onboarding_step_select,
            active = keyboardEnabled
        ) {
            val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            manager.showInputMethodPicker()
        }, actionParams())
    }

    private fun setupAction(labelRes: Int, active: Boolean, action: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isEnabled = active
            isClickable = active
            isFocusable = active
            background = roundedBackground(
                if (active) prefs.accentColor.accent(isDarkMode())
                else color(R.color.settings_card),
                dp(18).toFloat()
            )
            setPadding(dp(22), 0, dp(20), 0)
            contentDescription = getString(labelRes)
            if (active) setOnClickListener { action() }

            addView(TextView(context).apply {
                setText(labelRes)
                setTextColor(
                    if (active) ContextCompat.getColor(context, android.R.color.white)
                    else color(R.color.settings_secondary)
                )
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTypeface(typeface, Typeface.BOLD)
                alpha = if (active) 1f else 0.62f
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(context).apply {
                text = "›"
                gravity = Gravity.CENTER
                setTextColor(
                    if (active) ContextCompat.getColor(context, android.R.color.white)
                    else color(R.color.settings_secondary)
                )
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
                alpha = if (active) 1f else 0.38f
            }, LinearLayout.LayoutParams(dp(28), LinearLayout.LayoutParams.MATCH_PARENT))
        }

    private fun actionParams(bottom: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        dp(74)
    ).apply { bottomMargin = dp(bottom) }

    private fun openSettings() {
        if (navigatingAway) return
        navigatingAway = true
        startActivity(Intent(this, SettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        finish()
    }

    private fun roundedBackground(fill: Int, radius: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = radius
    }

    private fun isDarkMode(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES

    private fun color(resId: Int): Int = ContextCompat.getColor(this, resId)

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
}
