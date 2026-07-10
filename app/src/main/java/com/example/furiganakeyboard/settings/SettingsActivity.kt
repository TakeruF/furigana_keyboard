package com.example.furiganakeyboard.settings

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.furiganakeyboard.R
import com.example.furiganakeyboard.reading.ReadingRepository
import com.example.furiganakeyboard.view.HanluToggleView
import com.example.furiganakeyboard.view.Haptics
import com.google.android.material.card.MaterialCardView

/** Card-based settings hub with focused preference, privacy, legal, and help pages. */
class SettingsActivity : AppCompatActivity() {
    private lateinit var prefs: KeyboardPrefs
    private lateinit var backButton: ImageButton
    private lateinit var headerIcon: ImageView
    private lateinit var headerTitle: TextView
    private lateinit var contentHost: LinearLayout
    private var showingDetail = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = KeyboardPrefs(this)
        val activeTags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        if (activeTags != prefs.localeTag) {
            AppCompatDelegate.setApplicationLocales(
                LocaleListCompat.forLanguageTags(prefs.localeTag)
            )
        }
        super.onCreate(savedInstanceState)
        Haptics.enabled = prefs.haptics
        val darkMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !darkMode
            isAppearanceLightNavigationBars = !darkMode
        }
        setContentView(buildScreen())
        showHub()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (showingDetail) showHub() else super.onBackPressed()
    }

    private fun buildScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.settings_background))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        backButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_arrow_back)
            imageTintList = ColorStateList.valueOf(color(R.color.settings_text))
            background = neutralFeedback()
            contentDescription = getString(R.string.settings_back)
            setOnClickListener {
                Haptics.click(it)
                showHub()
            }
        }
        header.addView(backButton, LinearLayout.LayoutParams(dp(44), dp(44)))
        headerIcon = ImageView(this).apply { setImageResource(R.drawable.ic_launcher) }
        header.addView(headerIcon, LinearLayout.LayoutParams(dp(46), dp(46)).apply {
            marginEnd = dp(13)
        })
        headerTitle = TextView(this).apply {
            setTextColor(color(R.color.settings_text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTypeface(typeface, Typeface.BOLD)
        }
        header.addView(headerTitle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(header, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
        }
        contentHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(6), dp(14), dp(24))
        }
        scroll.addView(contentHost, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun showHub() {
        showingDetail = false
        backButton.visibility = View.GONE
        headerIcon.visibility = View.VISIBLE
        headerTitle.setText(R.string.app_name)
        contentHost.removeAllViews()
        contentHost.addView(sectionTitle(getString(R.string.settings_section)))

        val grid = GridLayout(this).apply {
            columnCount = 2
            alignmentMode = GridLayout.ALIGN_BOUNDS
            useDefaultMargins = false
        }
        addCard(grid, R.drawable.ic_settings_reading, R.string.card_reading_title, R.string.card_reading_desc) {
            showReadingDetails()
        }
        addCard(grid, R.drawable.ic_settings_handwriting, R.string.card_handwriting_title, R.string.card_handwriting_desc) {
            showHandwritingDetails()
        }
        addCard(grid, R.drawable.ic_settings_effects, R.string.card_effects_title, R.string.card_effects_desc) {
            showEffectsDetails()
        }
        addCard(grid, R.drawable.ic_settings_language, R.string.card_language_title, R.string.card_language_desc) {
            showLanguageDetails()
        }
        addCard(grid, R.drawable.ic_settings_keyboard, R.string.card_system_title, R.string.card_system_desc) {
            showSystemDetails()
        }
        contentHost.addView(grid, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        contentHost.addView(sectionTitle(getString(R.string.settings_more)).apply {
            setPadding(dp(6), dp(22), 0, dp(10))
        })
        val moreGrid = GridLayout(this).apply {
            columnCount = 2
            alignmentMode = GridLayout.ALIGN_BOUNDS
            useDefaultMargins = false
        }
        addCard(
            moreGrid,
            R.drawable.ic_settings_privacy,
            R.string.card_privacy_title,
            R.string.card_privacy_desc,
            compact = true
        ) { showPrivacyDetails() }
        addCard(
            moreGrid,
            R.drawable.ic_settings_legal,
            R.string.card_legal_title,
            R.string.card_legal_desc,
            compact = true
        ) { showLegalDetails() }
        addCard(
            moreGrid,
            R.drawable.ic_settings_info,
            R.string.card_about_title,
            R.string.card_about_desc,
            compact = true
        ) { showAboutDetails() }
        addCard(
            moreGrid,
            R.drawable.ic_settings_help,
            R.string.card_help_title,
            R.string.card_help_desc,
            compact = true
        ) { showHelpDetails() }
        contentHost.addView(moreGrid, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun addCard(
        grid: GridLayout,
        iconRes: Int,
        titleRes: Int,
        descriptionRes: Int,
        compact: Boolean = false,
        action: () -> Unit
    ) {
        val card = MaterialCardView(this).apply {
            radius = dp(17).toFloat()
            cardElevation = 0f
            strokeWidth = 0
            setCardBackgroundColor(color(R.color.settings_card))
            isClickable = true
            isFocusable = true
            foreground = neutralFeedback()
            setOnClickListener {
                Haptics.click(it)
                action()
            }
            contentDescription = getString(titleRes) + ". " + getString(descriptionRes)
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            setPadding(
                dp(16),
                dp(if (compact) 13 else 15),
                dp(14),
                dp(if (compact) 10 else 14)
            )
        }
        body.addView(ImageView(this).apply { setImageResource(iconRes) }, LinearLayout.LayoutParams(
            dp(if (compact) 26 else 30),
            dp(if (compact) 26 else 30)
        ))
        body.addView(TextView(this).apply {
            setText(titleRes)
            setTextColor(color(R.color.settings_text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 15.5f else 16.5f)
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(if (compact) 17 else 14) })
        if (!compact) {
            body.addView(TextView(this).apply {
                setText(descriptionRes)
                setTextColor(color(R.color.settings_secondary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                maxLines = 3
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) })
        }
        card.addView(body)
        grid.addView(card, GridLayout.LayoutParams(
            GridLayout.spec(GridLayout.UNDEFINED),
            GridLayout.spec(GridLayout.UNDEFINED, 1f)
        ).apply {
            width = 0
            height = dp(if (compact) 116 else 158)
            val margin = dp(5)
            setMargins(margin, margin, margin, margin)
        })
    }

    private fun showDetail(title: String, descriptionRes: Int, iconRes: Int, content: LinearLayout) {
        showingDetail = true
        backButton.visibility = View.VISIBLE
        headerIcon.visibility = View.GONE
        headerTitle.text = title.removeSuffix(" ›")
        contentHost.removeAllViews()
        contentHost.addView(pageIntro(iconRes, descriptionRes))
        contentHost.addView(content)
    }

    private fun showReadingDetails() {
        val body = detailBody()
        body.addView(sectionLabel(R.string.settings_section_display))
        body.addView(choiceCard(
            options = listOf(
                Choice(ReadingMode.KANA.name, R.string.mode_kana, R.string.settings_kana_desc),
                Choice(ReadingMode.ROMAJI.name, R.string.mode_romaji, R.string.settings_romaji_desc),
                Choice(ReadingMode.OFF.name, R.string.mode_off, R.string.settings_off_desc)
            ),
            selected = prefs.readingMode.name
        ) { prefs.readingMode = ReadingMode.valueOf(it) })
        showDetail(
            getString(R.string.card_reading_title),
            R.string.card_reading_desc,
            R.drawable.ic_settings_reading,
            body
        )
    }

    private fun showHandwritingDetails() {
        val body = detailBody()
        body.addView(sectionLabel(R.string.settings_section_input))
        body.addView(groupCard(listOf(
            toggleRow(
                R.string.settings_auto_commit,
                R.string.settings_auto_commit_desc,
                prefs.autoCommit
            ) { prefs.autoCommit = it }
        )))
        body.addView(sectionLabel(R.string.settings_section_offline_data))
        val version = runCatching {
            ReadingRepository(this).use { it.metadata("kanjidic_date") }
        }.getOrNull() ?: "2026-07-09"
        body.addView(groupCard(listOf(
            infoRow(
                getString(R.string.settings_offline_model),
                getString(R.string.settings_offline_model_desc),
                getString(R.string.settings_status_ready)
            ),
            infoRow(
                getString(R.string.settings_dictionary_data),
                getString(R.string.settings_dictionary_version, version),
                getString(R.string.settings_status_ready)
            )
        )))
        body.addView(footerNote(getString(R.string.settings_offline_note)))
        showDetail(
            getString(R.string.card_handwriting_title),
            R.string.card_handwriting_desc,
            R.drawable.ic_settings_handwriting,
            body
        )
    }

    private fun showEffectsDetails() {
        val body = detailBody()
        body.addView(sectionLabel(R.string.settings_section_feedback))
        body.addView(groupCard(listOf(
            toggleRow(
                R.string.settings_haptics,
                R.string.settings_haptics_desc,
                prefs.haptics
            ) {
                prefs.haptics = it
                Haptics.enabled = it
            }
        )))
        showDetail(
            getString(R.string.card_effects_title),
            R.string.card_effects_desc,
            R.drawable.ic_settings_effects,
            body
        )
    }

    private fun showLanguageDetails() {
        val body = detailBody()
        body.addView(sectionLabel(R.string.settings_section_language))
        val options = listOf(
            Choice("", R.string.language_system),
            Choice("ja", R.string.language_japanese),
            Choice("zh-CN", R.string.language_chinese),
            Choice("ko", R.string.language_korean),
            Choice("en", R.string.language_english)
        )
        body.addView(choiceCard(options, prefs.localeTag) { tag ->
            if (tag != prefs.localeTag) {
                prefs.localeTag = tag
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
            }
        })
        body.addView(footerNote(getString(R.string.settings_language_note)))
        showDetail(
            getString(R.string.card_language_title),
            R.string.card_language_desc,
            R.drawable.ic_settings_language,
            body
        )
    }

    private fun showSystemDetails() {
        val body = detailBody()
        body.addView(sectionLabel(R.string.settings_section_keyboard_setup))
        body.addView(groupCard(listOf(
            actionRow(
                R.string.settings_enable_ime,
                R.string.settings_enable_ime_desc,
                R.drawable.ic_settings_keyboard
            ) { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
            actionRow(
                R.string.settings_choose_ime,
                R.string.settings_choose_ime_desc,
                R.drawable.ic_settings_language
            ) {
                val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                manager.showInputMethodPicker()
            }
        )))
        body.addView(footerNote(getString(R.string.settings_note)))
        showDetail(
            getString(R.string.card_system_title),
            R.string.card_system_desc,
            R.drawable.ic_settings_keyboard,
            body
        )
    }

    private fun showPrivacyDetails() {
        val body = detailBody()
        body.addView(sectionLabel(R.string.settings_section_data_handling))
        body.addView(groupCard(listOf(
            infoRow(
                getString(R.string.privacy_input_title),
                getString(R.string.privacy_input_desc),
                getString(R.string.privacy_not_collected)
            ),
            infoRow(
                getString(R.string.privacy_network_title),
                getString(R.string.privacy_network_desc),
                getString(R.string.privacy_none)
            ),
            infoRow(
                getString(R.string.privacy_storage_title),
                getString(R.string.privacy_storage_desc),
                getString(R.string.settings_status_on_device)
            )
        )))
        body.addView(sectionLabel(R.string.settings_section_permissions))
        body.addView(groupCard(listOf(
            infoRow(
                getString(R.string.privacy_ime_access_title),
                getString(R.string.privacy_ime_access_desc)
            ),
            infoRow(
                getString(R.string.privacy_permissions_title),
                getString(R.string.privacy_permissions_desc),
                getString(R.string.privacy_none)
            )
        )))
        body.addView(footerNote(getString(R.string.privacy_target_app_note)))
        showDetail(
            getString(R.string.card_privacy_title),
            R.string.card_privacy_desc,
            R.drawable.ic_settings_privacy,
            body
        )
    }

    private fun showLegalDetails() {
        val body = detailBody()
        body.addView(sectionLabel(R.string.settings_section_legal_documents))
        body.addView(groupCard(listOf(
            actionRow(
                R.string.privacy_policy_title,
                summary = getString(R.string.privacy_policy_desc),
                iconRes = R.drawable.ic_settings_privacy
            ) {
                showAsset(R.string.privacy_policy_title, localizedLegalAsset("privacy-policy"))
            },
            actionRow(
                R.string.terms_title,
                summary = getString(R.string.terms_desc),
                iconRes = R.drawable.ic_settings_legal
            ) {
                showAsset(R.string.terms_title, localizedLegalAsset("terms"))
            }
        )))
        body.addView(sectionLabel(R.string.settings_section_notices))
        body.addView(groupCard(listOf(
            actionRow(
                R.string.third_party_notices_title,
                summary = getString(R.string.third_party_notices_desc),
                iconRes = R.drawable.ic_settings_info
            ) {
                showAssets(
                    R.string.third_party_notices_title,
                    "licenses/EDRDG-CC-BY-SA-4.0.txt",
                    "licenses/ZINNIA-BSD.txt",
                    "licenses/TEGAKI-MODEL-README.txt",
                    "licenses/TEGAKI-MODEL-LGPL-2.1.txt",
                    "licenses/THIRD-PARTY-NOTICES.txt",
                    "licenses/APACHE-2.0.txt"
                )
            }
        )))
        body.addView(footerNote(getString(R.string.legal_effective_date)))
        showDetail(
            getString(R.string.card_legal_title),
            R.string.card_legal_desc,
            R.drawable.ic_settings_legal,
            body
        )
    }

    private fun showAboutDetails() {
        val body = detailBody()
        val version = packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
        body.addView(sectionLabel(R.string.settings_section_app))
        body.addView(groupCard(listOf(
            infoRow(
                getString(R.string.settings_version_label),
                getString(R.string.about_version, version)
            ),
            infoRow(
                getString(R.string.settings_private_title),
                getString(R.string.about_offline),
                getString(R.string.settings_status_on_device)
            )
        )))
        body.addView(sectionLabel(R.string.settings_section_open_source))
        body.addView(groupCard(listOf(
            actionRow(R.string.license_edrdg, summary = getString(R.string.about_edrdg)) {
                showAsset(R.string.license_edrdg, "licenses/EDRDG-CC-BY-SA-4.0.txt")
            },
            actionRow(R.string.license_zinnia, summary = getString(R.string.about_zinnia)) {
                showAsset(R.string.license_zinnia, "licenses/ZINNIA-BSD.txt")
            },
            actionRow(R.string.license_tegaki, summary = getString(R.string.about_tegaki)) {
                showAsset(R.string.license_tegaki, "licenses/TEGAKI-MODEL-LGPL-2.1.txt")
            },
            actionRow(R.string.license_app_libraries, summary = getString(R.string.about_app_libraries)) {
                showAssets(
                    R.string.license_app_libraries,
                    "licenses/THIRD-PARTY-NOTICES.txt",
                    "licenses/APACHE-2.0.txt"
                )
            }
        )))
        showDetail(
            getString(R.string.card_about_title),
            R.string.card_about_desc,
            R.drawable.ic_settings_info,
            body
        )
    }

    private fun showHelpDetails() {
        val body = detailBody()
        body.addView(sectionLabel(R.string.settings_section_getting_started))
        body.addView(groupCard(listOf(
            actionRow(
                R.string.settings_enable_ime,
                R.string.settings_enable_ime_desc,
                R.drawable.ic_settings_keyboard
            ) { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
            actionRow(
                R.string.settings_choose_ime,
                R.string.settings_choose_ime_desc,
                R.drawable.ic_settings_language
            ) {
                val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                manager.showInputMethodPicker()
            }
        )))
        body.addView(sectionLabel(R.string.settings_section_help_topics))
        body.addView(groupCard(listOf(
            infoRow(
                getString(R.string.help_offline_title),
                getString(R.string.help_offline_desc),
                getString(R.string.settings_status_ready)
            ),
            infoRow(
                getString(R.string.help_coverage_title),
                getString(R.string.help_coverage_desc)
            ),
            infoRow(
                getString(R.string.help_support_title),
                getString(R.string.help_support_desc)
            )
        )))
        showDetail(
            getString(R.string.card_help_title),
            R.string.card_help_desc,
            R.drawable.ic_settings_help,
            body
        )
    }

    private fun localizedLegalAsset(baseName: String): String {
        val locale = resources.configuration.locales[0]
        val suffix = when (locale.language) {
            "ja" -> "ja"
            "zh" -> "zh-CN"
            "ko" -> "ko"
            else -> "en"
        }
        return "legal/$baseName-$suffix.txt"
    }

    private fun showAsset(titleRes: Int, name: String) {
        showAssets(titleRes, name)
    }

    private fun showAssets(titleRes: Int, vararg names: String) {
        val text = names.joinToString("\n\n────────────────────\n\n") { name ->
            assets.open(name).bufferedReader().use { it.readText() }
        }
        val scroll = ScrollView(this).apply {
            addView(detailText(text).apply {
                setTextColor(color(R.color.settings_text))
                setTextIsSelectable(true)
                setPadding(dp(20), dp(12), dp(20), dp(12))
            })
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setView(scroll)
            .setPositiveButton(R.string.license_close, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { button ->
                Haptics.click(button)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun detailBody() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, 0, dp(30))
    }

    private fun sectionTitle(value: String) = TextView(this).apply {
        text = value
        setTextColor(color(R.color.settings_text))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 25f)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(6), dp(4), 0, dp(10))
    }

    private fun detailText(value: String) = TextView(this).apply {
        text = value
        setTextColor(color(R.color.settings_secondary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setLineSpacing(0f, 1.2f)
        setPadding(0, dp(8), 0, dp(8))
    }

    private data class Choice(val value: String, val titleRes: Int, val summaryRes: Int? = null)

    private fun pageIntro(iconRes: Int, descriptionRes: Int): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(5), dp(2), dp(6), dp(5))
        }.also { row ->
        row.addView(ImageView(this).apply {
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(color(R.color.settings_icon))
        }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(10) })
        row.addView(TextView(this).apply {
            setText(descriptionRes)
            setTextColor(color(R.color.settings_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setLineSpacing(0f, 1.08f)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun sectionLabel(textRes: Int) = TextView(this).apply {
        setText(textRes)
        setTextColor(color(R.color.settings_secondary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTypeface(typeface, Typeface.BOLD)
        letterSpacing = 0.04f
        setPadding(dp(5), dp(17), dp(5), dp(7))
    }

    private fun groupCard(rows: List<View>): MaterialCardView {
        val card = MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = color(R.color.settings_card_stroke)
            setCardBackgroundColor(color(R.color.settings_card))
        }
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rows.forEachIndexed { index, row ->
            if (index > 0) container.addView(divider())
            container.addView(row)
        }
        card.addView(container)
        return card
    }

    private fun choiceCard(
        options: List<Choice>,
        selected: String,
        onSelected: (String) -> Unit
    ): MaterialCardView {
        val marks = mutableListOf<Pair<String, TextView>>()
        val rows = options.map { option ->
            val optionSummary = option.summaryRes?.let(::getString)
            val mark = TextView(this).apply {
                text = if (option.value == selected) "✓" else ""
                setTextColor(color(R.color.settings_text))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                gravity = Gravity.CENTER
                minimumWidth = dp(24)
            }
            marks += option.value to mark
            settingRow(
                getString(option.titleRes),
                optionSummary,
                mark
            ).apply {
                contentDescription = listOfNotNull(getString(option.titleRes), optionSummary)
                    .joinToString(". ")
                isSelected = option.value == selected
                isClickable = true
                isFocusable = true
                foreground = neutralFeedback()
                setOnClickListener {
                    if (!isSelected) Haptics.selection(this)
                    marks.forEach { (value, selectionMark) ->
                        selectionMark.text = if (value == option.value) "✓" else ""
                        (selectionMark.parent as? View)?.isSelected = value == option.value
                    }
                    onSelected(option.value)
                }
            }
        }
        return groupCard(rows)
    }

    private fun toggleRow(
        titleRes: Int,
        summaryRes: Int,
        checked: Boolean,
        onChanged: (Boolean) -> Unit
    ): View {
        val toggle = HanluToggleView(this).apply {
            setChecked(checked)
            contentDescription = getString(titleRes)
            setOnCheckedChangeListener(onChanged)
        }
        return settingRow(getString(titleRes), getString(summaryRes), toggle).apply {
            contentDescription = getString(titleRes) + ". " + getString(summaryRes)
            isClickable = true
            isFocusable = true
            foreground = neutralFeedback()
            setOnClickListener { toggle.performClick() }
        }
    }

    private fun actionRow(
        titleRes: Int,
        summaryRes: Int,
        iconRes: Int,
        action: () -> Unit
    ) = actionRow(titleRes, getString(summaryRes), iconRes, action)

    private fun actionRow(
        titleRes: Int,
        summary: String,
        iconRes: Int? = null,
        action: () -> Unit
    ): View {
        val chevron = TextView(this).apply {
            text = "›"
            setTextColor(color(R.color.settings_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            gravity = Gravity.CENTER
        }
        return settingRow(getString(titleRes), summary, chevron, iconRes).apply {
            contentDescription = getString(titleRes) + ". " + summary
            isClickable = true
            isFocusable = true
            foreground = neutralFeedback()
            setOnClickListener {
                Haptics.click(it)
                action()
            }
        }
    }

    private fun infoRow(title: String, summary: String, badge: String? = null): View {
        val badgeView = badge?.let {
            TextView(this).apply {
                text = it
                setTextColor(color(R.color.settings_success))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                background = roundedBackground(R.color.settings_success_surface, 99)
                setPadding(dp(8), dp(4), dp(8), dp(4))
            }
        }
        return settingRow(title, summary, badgeView)
    }

    private fun settingRow(
        title: String,
        summary: String? = null,
        trailing: View? = null,
        iconRes: Int? = null
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(58)
            setPadding(dp(14), dp(9), dp(12), dp(9))
        }
        if (iconRes != null) {
            row.addView(ImageView(this).apply {
                setImageResource(iconRes)
                imageTintList = ColorStateList.valueOf(color(R.color.settings_icon))
            }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(11) })
        }
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(TextView(this).apply {
            text = title
            setTextColor(color(R.color.settings_text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
        })
        if (!summary.isNullOrBlank()) {
            labels.addView(TextView(this).apply {
                text = summary
                setTextColor(color(R.color.settings_secondary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setLineSpacing(0f, 1.12f)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) })
        }
        row.addView(labels, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (trailing != null) {
            row.addView(trailing, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(9) })
        }
        return row
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(color(R.color.settings_divider))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(1)
        ).apply {
            marginStart = dp(14)
            marginEnd = dp(14)
        }
    }

    private fun footerNote(value: String) = TextView(this).apply {
        text = value
        setTextColor(color(R.color.settings_secondary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setLineSpacing(0f, 1.15f)
        setPadding(dp(5), dp(9), dp(5), 0)
    }

    private fun roundedBackground(colorRes: Int, radiusDp: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color(colorRes))
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun neutralFeedback() = StateListDrawable().apply {
        addState(
            intArrayOf(android.R.attr.state_pressed),
            ColorDrawable(color(R.color.settings_pressed_overlay))
        )
        addState(
            intArrayOf(android.R.attr.state_focused),
            ColorDrawable(color(R.color.settings_focused_overlay))
        )
        addState(intArrayOf(), ColorDrawable(android.graphics.Color.TRANSPARENT))
    }

    private fun color(res: Int) = ContextCompat.getColor(this, res)
    private fun dp(value: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics
    ).toInt()

}
