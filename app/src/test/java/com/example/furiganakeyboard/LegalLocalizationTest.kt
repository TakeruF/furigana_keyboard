package com.example.furiganakeyboard

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Keeps legal documents and their settings entry points complete in every supported UI locale. */
class LegalLocalizationTest {
    @Test
    fun everySupportedLocaleHasPrivacyPolicyAndTerms() {
        supportedLocales.forEach { locale ->
            listOf("privacy-policy", "terms").forEach { document ->
                val file = projectFile("src/main/assets/legal/$document-$locale.txt")
                assertTrue("Missing $document for $locale", file.isFile)
                val text = file.readText()
                // CJK documents encode the same content with substantially fewer characters.
                assertTrue("$document-$locale is unexpectedly short", text.length > 500)
                assertTrue("$document-$locale has no effective date", text.contains("2026"))
                assertTrue("$document-$locale has no contact route", text.contains(contactUrl))
            }
        }
    }

    @Test
    fun everyTranslatedSettingsFileHasAllLegalUiStrings() {
        translatedResourceFolders.forEach { folder ->
            val file = projectFile("src/main/res/$folder/strings.xml")
            val names = namePattern.findAll(file.readText()).map { it.groupValues[1] }.toSet()
            val missing = requiredLegalStrings - names
            assertTrue("$folder is missing: ${missing.sorted()}", missing.isEmpty())
        }
    }

    @Test
    fun originalThirdPartyLicensesRemainBundled() {
        requiredLicenseFiles.forEach { name ->
            assertTrue("Missing third-party notice: $name", projectFile("src/main/assets/licenses/$name").isFile)
        }
    }

    private fun projectFile(relativeToApp: String): File = sequenceOf(
        File(relativeToApp),
        File("app/$relativeToApp")
    ).first { it.exists() }

    companion object {
        private const val contactUrl = "https://github.com/TakeruF/furigana_keyboard/issues"
        private val supportedLocales = listOf("en", "ja", "zh-CN", "ko")
        private val translatedResourceFolders = listOf("values-ja", "values-zh-rCN", "values-ko")
        private val namePattern = Regex("name=\"([^\"]+)\"")
        private val requiredLegalStrings = setOf(
            "settings_more",
            "card_privacy_title",
            "card_privacy_desc",
            "card_legal_title",
            "card_legal_desc",
            "card_help_title",
            "card_help_desc",
            "privacy_policy_title",
            "privacy_policy_desc",
            "terms_title",
            "terms_desc",
            "third_party_notices_title",
            "third_party_notices_desc",
            "legal_effective_date",
            "license_app_libraries",
            "about_app_libraries"
        )
        private val requiredLicenseFiles = listOf(
            "EDRDG-CC-BY-SA-4.0.txt",
            "ZINNIA-BSD.txt",
            "TEGAKI-MODEL-LGPL-2.1.txt",
            "THIRD-PARTY-NOTICES.txt",
            "APACHE-2.0.txt"
        )
    }
}
