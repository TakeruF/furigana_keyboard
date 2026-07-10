package com.example.furiganakeyboard.settings

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.graphics.Typeface
import android.net.Uri
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
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.furiganakeyboard.R
import com.example.furiganakeyboard.reading.ReadingRepository
import com.example.furiganakeyboard.view.HanluToggleView
import com.example.furiganakeyboard.view.Haptics
import com.example.furiganakeyboard.view.KeySounds
import com.google.android.material.card.MaterialCardView

/** Card-based settings hub with focused preference, privacy, legal, and help pages. */
class SettingsActivity : AppCompatActivity() {
    private lateinit var prefs: KeyboardPrefs
    private lateinit var backButton: ImageButton
    private lateinit var headerIcon: ImageView
    private lateinit var headerTitle: TextView
    private lateinit var contentHost: LinearLayout
    private lateinit var screenRoot: LinearLayout
    private var backAction: (() -> Unit)? = null

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
        if (!KeyboardSetupState.isSelected(this)) {
            showKeyboardSetup()
            return
        }
        Haptics.enabled = prefs.haptics
        Haptics.strength = prefs.hapticStrength
        KeySounds.enabled = prefs.keySound
        KeySounds.volumeStep = prefs.keySoundVolume
        val darkMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        val settingsBackground = prefs.accentColor.settingsBackground(darkMode)
        window.statusBarColor = settingsBackground
        window.navigationBarColor = settingsBackground
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !darkMode
            isAppearanceLightNavigationBars = !darkMode
        }
        setContentView(buildScreen())
        showHub()
    }

    override fun onResume() {
        super.onResume()
        if (!isFinishing && !KeyboardSetupState.isSelected(this)) showKeyboardSetup()
    }

    private fun showKeyboardSetup() {
        startActivity(Intent(this, OnboardingActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        finish()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        backAction?.invoke() ?: super.onBackPressed()
    }

    private fun buildScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(prefs.accentColor.settingsBackground(isDarkMode()))
        }
        screenRoot = root
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
                backAction?.invoke()
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
        backAction = null
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
        addCard(grid, R.drawable.ic_settings_layout, R.string.card_layout_title, R.string.card_layout_desc) {
            showLayoutDetails()
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
            contentDescription = getString(titleRes).removeSuffix(" ›") + ". " + getString(descriptionRes)
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
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(this).apply {
            text = getString(titleRes).removeSuffix(" ›")
            setTextColor(color(R.color.settings_text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 15.5f else 16.5f)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 2
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_chevron_right)
            imageTintList = ColorStateList.valueOf(color(R.color.settings_secondary))
        }, LinearLayout.LayoutParams(dp(19), dp(19)).apply { marginStart = dp(4) })
        body.addView(titleRow, LinearLayout.LayoutParams(
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

    private fun showDetail(
        title: String,
        descriptionRes: Int,
        iconRes: Int,
        content: LinearLayout,
        onBack: (() -> Unit)? = null
    ) {
        backAction = onBack ?: ::showHub
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
        body.addView(sectionLabel(R.string.settings_section_plus))
        body.addView(groupCard(listOf(
            toggleRow(
                R.string.settings_plus_recognition,
                R.string.settings_plus_recognition_desc,
                prefs.plusRecognition
            ) { prefs.plusRecognition = it }
        )))
        body.addView(footerNote(getString(R.string.settings_plus_note)))
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
        body.addView(sectionLabel(R.string.settings_key_sound))
        body.addView(effectControlCard(
            titleRes = R.string.settings_key_sound,
            checked = prefs.keySound,
            progress = prefs.keySoundVolume,
            max = 4,
            labels = listOf(R.string.sound_quiet, R.string.sound_loud),
            onToggle = {
                prefs.keySound = it
                KeySounds.enabled = it
            },
            onProgress = {
                prefs.keySoundVolume = it
                KeySounds.volumeStep = it
            },
            preview = { view -> KeySounds.key(view) }
        ))
        body.addView(footerNote(getString(R.string.settings_key_sound_silent_note)))
        body.addView(sectionLabel(R.string.settings_haptic_strength))
        body.addView(effectControlCard(
            titleRes = R.string.settings_haptic_strength,
            checked = prefs.haptics,
            progress = prefs.hapticStrength.ordinal,
            max = HapticStrength.entries.lastIndex,
            labels = listOf(
                R.string.haptic_none,
                R.string.haptic_system,
                R.string.haptic_weak,
                R.string.haptic_medium_weak,
                R.string.haptic_medium,
                R.string.haptic_medium_strong,
                R.string.haptic_strong
            ),
            onToggle = {
                prefs.haptics = it
                Haptics.enabled = it
            },
            onProgress = {
                val strength = HapticStrength.entries[it]
                prefs.hapticStrength = strength
                Haptics.strength = strength
            },
            preview = { view -> Haptics.selection(view) }
        ))
        showDetail(
            getString(R.string.card_effects_title),
            R.string.card_effects_desc,
            R.drawable.ic_settings_effects,
            body
        )
    }

    private fun effectControlCard(
        titleRes: Int,
        checked: Boolean,
        progress: Int,
        max: Int,
        labels: List<Int>,
        onToggle: (Boolean) -> Unit,
        onProgress: (Int) -> Unit,
        preview: (View) -> Unit
    ): MaterialCardView {
        val card = MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = color(R.color.settings_card_stroke)
            setCardBackgroundColor(color(R.color.settings_card))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(4), dp(2), dp(12))
        }
        val seek = SeekBar(this).apply {
            this.max = max
            this.progress = progress.coerceIn(0, max)
            progressTintList = ColorStateList.valueOf(
                prefs.accentColor.accent(isDarkMode())
            )
            thumbTintList = progressTintList
        }
        val toggle = HanluToggleView(this).apply {
            setChecked(checked)
            setOnCheckedChangeListener {
                seek.isEnabled = it
                seek.alpha = if (it) 1f else 0.45f
                onToggle(it)
            }
        }
        seek.isEnabled = checked
        seek.alpha = if (checked) 1f else 0.45f
        content.addView(settingRow(getString(titleRes), trailing = toggle))
        content.addView(seek, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(42)
        ).apply {
            marginStart = dp(12)
            marginEnd = dp(12)
        })
        val labelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        labels.forEach { labelRes ->
            labelRow.addView(TextView(this).apply {
                setText(labelRes)
                setTextColor(color(R.color.settings_secondary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (labels.size > 2) 10.5f else 13f)
                gravity = Gravity.CENTER
                maxLines = 1
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        content.addView(labelRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            marginStart = dp(12)
            marginEnd = dp(12)
        })
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, value: Int, fromUser: Boolean) {
                if (!fromUser) return
                onProgress(value)
                preview(seekBar)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
        card.addView(content)
        return card
    }

    private fun showLayoutDetails() {
        val body = detailBody()
        body.addView(sectionLabel(R.string.settings_section_keyboard_height))
        body.addView(choiceCard(
            options = listOf(
                Choice(KeyboardHeight.COMPACT.name, R.string.keyboard_height_compact),
                Choice(KeyboardHeight.STANDARD.name, R.string.keyboard_height_standard),
                Choice(KeyboardHeight.TALL.name, R.string.keyboard_height_tall)
            ),
            selected = prefs.keyboardHeight.name
        ) { prefs.keyboardHeight = KeyboardHeight.fromStored(it) })

        body.addView(sectionLabel(R.string.settings_section_candidate_size))
        body.addView(choiceCard(
            options = listOf(
                Choice(CandidateTextSize.SMALL.name, R.string.candidate_size_small),
                Choice(CandidateTextSize.STANDARD.name, R.string.candidate_size_standard),
                Choice(CandidateTextSize.LARGE.name, R.string.candidate_size_large)
            ),
            selected = prefs.candidateTextSize.name
        ) { prefs.candidateTextSize = CandidateTextSize.fromStored(it) })

        body.addView(sectionLabel(R.string.settings_section_accent_color))
        body.addView(accentChoiceCard())
        body.addView(footerNote(getString(R.string.settings_layout_note)))
        showDetail(
            getString(R.string.card_layout_title),
            R.string.card_layout_desc,
            R.drawable.ic_settings_layout,
            body
        )
    }

    private fun accentChoiceCard(): MaterialCardView {
        val options = listOf(
            AccentColor.BLUE to R.string.accent_blue,
            AccentColor.PURPLE to R.string.accent_purple,
            AccentColor.GREEN to R.string.accent_green,
            AccentColor.ORANGE to R.string.accent_orange,
            AccentColor.PINK to R.string.accent_pink
        )
        val rows = options.map { (accent, titleRes) ->
            val selected = accent == prefs.accentColor
            val swatch = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(accent.accent(isDarkMode()))
                }
            }
            val mark = ImageView(this).apply {
                setImageResource(R.drawable.ic_check)
                imageTintList = ColorStateList.valueOf(color(R.color.settings_text))
                visibility = if (selected) View.VISIBLE else View.INVISIBLE
            }
            settingRow(getString(titleRes), trailing = mark).apply {
                addView(swatch, 0, LinearLayout.LayoutParams(dp(26), dp(26)).apply {
                    marginStart = dp(2)
                    marginEnd = dp(12)
                })
                contentDescription = getString(titleRes)
                isSelected = selected
                isClickable = true
                isFocusable = true
                foreground = neutralFeedback()
                setOnClickListener {
                    if (!selected) {
                        Haptics.selection(it)
                        prefs.accentColor = accent
                        applyAccentSurface()
                        showLayoutDetails()
                    }
                }
            }
        }
        return groupCard(rows)
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
                getString(R.string.privacy_model_only)
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
                showAsset(
                    R.string.privacy_policy_title,
                    R.string.privacy_policy_desc,
                    R.drawable.ic_settings_privacy,
                    ::showLegalDetails,
                    localizedLegalAsset("privacy-policy")
                )
            },
            actionRow(
                R.string.terms_title,
                summary = getString(R.string.terms_desc),
                iconRes = R.drawable.ic_settings_legal
            ) {
                showAsset(
                    R.string.terms_title,
                    R.string.terms_desc,
                    R.drawable.ic_settings_legal,
                    ::showLegalDetails,
                    localizedLegalAsset("terms")
                )
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
                    R.string.third_party_notices_desc,
                    R.drawable.ic_settings_info,
                    ::showLegalDetails,
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
            infoRow(
                getString(R.string.about_plus_title),
                getString(R.string.about_plus_desc)
            ),
            actionRow(R.string.license_edrdg, summary = getString(R.string.about_edrdg)) {
                showAsset(
                    R.string.license_edrdg,
                    R.string.about_edrdg,
                    R.drawable.ic_settings_info,
                    ::showAboutDetails,
                    "licenses/EDRDG-CC-BY-SA-4.0.txt"
                )
            },
            actionRow(R.string.license_zinnia, summary = getString(R.string.about_zinnia)) {
                showAsset(
                    R.string.license_zinnia,
                    R.string.about_zinnia,
                    R.drawable.ic_settings_info,
                    ::showAboutDetails,
                    "licenses/ZINNIA-BSD.txt"
                )
            },
            actionRow(R.string.license_tegaki, summary = getString(R.string.about_tegaki)) {
                showAsset(
                    R.string.license_tegaki,
                    R.string.about_tegaki,
                    R.drawable.ic_settings_info,
                    ::showAboutDetails,
                    "licenses/TEGAKI-MODEL-LGPL-2.1.txt"
                )
            },
            actionRow(R.string.license_app_libraries, summary = getString(R.string.about_app_libraries)) {
                showAssets(
                    R.string.license_app_libraries,
                    R.string.about_app_libraries,
                    R.drawable.ic_settings_info,
                    ::showAboutDetails,
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
            actionRow(
                R.string.help_support_title,
                getString(R.string.help_support_desc),
                R.drawable.ic_settings_help
            ) {
                val email = getString(R.string.support_email)
                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email")).apply {
                    putExtra(Intent.EXTRA_SUBJECT, getString(R.string.app_name))
                }
                if (intent.resolveActivity(packageManager) != null) startActivity(intent)
            }
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

    private fun showAsset(
        titleRes: Int,
        descriptionRes: Int,
        iconRes: Int,
        onBack: () -> Unit,
        name: String
    ) {
        showAssets(titleRes, descriptionRes, iconRes, onBack, name)
    }

    private fun showAssets(
        titleRes: Int,
        descriptionRes: Int,
        iconRes: Int,
        onBack: () -> Unit,
        vararg names: String
    ) {
        val text = names.joinToString("\n\n────────────────────\n\n") { name ->
            assets.open(name).bufferedReader().use { it.readText() }
        }
        val body = detailBody()
        body.addView(MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = color(R.color.settings_card_stroke)
            setCardBackgroundColor(color(R.color.settings_card))
            addView(detailText(text).apply {
                setTextColor(color(R.color.settings_text))
                setTextIsSelectable(true)
                setPadding(dp(16), dp(14), dp(16), dp(18))
            })
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })
        showDetail(getString(titleRes), descriptionRes, iconRes, body, onBack)
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
        val marks = mutableListOf<Pair<String, ImageView>>()
        val rows = options.map { option ->
            val optionSummary = option.summaryRes?.let(::getString)
            val mark = ImageView(this).apply {
                setImageResource(R.drawable.ic_check)
                imageTintList = ColorStateList.valueOf(color(R.color.settings_text))
                visibility = if (option.value == selected) View.VISIBLE else View.INVISIBLE
                minimumWidth = dp(24)
                minimumHeight = dp(24)
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
                        selectionMark.visibility = if (value == option.value) View.VISIBLE else View.INVISIBLE
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
        val chevron = ImageView(this).apply {
            setImageResource(R.drawable.ic_chevron_right)
            imageTintList = ColorStateList.valueOf(color(R.color.settings_secondary))
            minimumWidth = dp(24)
            minimumHeight = dp(24)
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
    private fun isDarkMode(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES

    private fun applyAccentSurface() {
        val background = prefs.accentColor.settingsBackground(isDarkMode())
        screenRoot.setBackgroundColor(background)
        window.statusBarColor = background
        window.navigationBarColor = background
    }

    private fun dp(value: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics
    ).toInt()

}
