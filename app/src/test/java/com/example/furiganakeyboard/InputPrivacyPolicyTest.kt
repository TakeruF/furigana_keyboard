package com.example.furiganakeyboard

import android.view.inputmethod.EditorInfo
import com.example.furiganakeyboard.ime.InputPrivacyPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The privacy promises the keyboard makes to the editor it is attached to. */
class InputPrivacyPolicyTest {
    @Test
    fun ordinaryTextEditorsKeepEveryConversionFeature() {
        val policy = InputPrivacyPolicy.of(
            inputType = EditorInfo.TYPE_CLASS_TEXT,
            imeOptions = EditorInfo.IME_ACTION_DONE,
        )

        assertFalse(policy.isSensitiveField)
        assertTrue(policy.allowsDictionaryLookup)
        assertTrue(policy.allowsRecognitionContext)
        assertTrue(policy.allowsComposingText)
        assertTrue(policy.allowsRetainingCandidates)
        assertEquals(InputPrivacyPolicy.UNRESTRICTED, policy)
        assertEquals(InputPrivacyPolicy.UNRESTRICTED, InputPrivacyPolicy.of(null))
    }

    @Test
    fun everyPasswordVariationSuppressesLookupContextAndComposition() {
        val passwordTypes = mapOf(
            "text password" to
                (EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_PASSWORD),
            "visible password" to
                (EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD),
            "web password" to
                (EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD),
            "numeric PIN" to
                (EditorInfo.TYPE_CLASS_NUMBER or EditorInfo.TYPE_NUMBER_VARIATION_PASSWORD),
        )

        passwordTypes.forEach { (name, inputType) ->
            val policy = InputPrivacyPolicy.of(inputType, imeOptions = 0)
            assertTrue(name, policy.isSensitiveField)
            assertFalse(name, policy.allowsDictionaryLookup)
            assertFalse(name, policy.allowsRecognitionContext)
            assertFalse(name, policy.allowsComposingText)
            assertFalse(name, policy.allowsRetainingCandidates)
        }
    }

    @Test
    fun lookalikeTextVariationsAreNotTreatedAsPasswords() {
        val ordinaryTypes = listOf(
            EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_URI,
            EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
            EditorInfo.TYPE_CLASS_NUMBER,
            EditorInfo.TYPE_CLASS_PHONE,
            EditorInfo.TYPE_NULL,
        )

        ordinaryTypes.forEach { inputType ->
            assertFalse(
                "inputType $inputType",
                InputPrivacyPolicy.of(inputType, imeOptions = 0).isSensitiveField,
            )
        }
    }

    @Test
    fun noPersonalizedLearningEndsRetentionWithoutDisablingConversion() {
        val policy = InputPrivacyPolicy.of(
            inputType = EditorInfo.TYPE_CLASS_TEXT,
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
        )

        assertTrue(policy.forbidsPersonalizedLearning)
        // The dictionary is immutable and shipped with the app, so conversion is
        // not personalization; only the session's cached candidates are dropped.
        assertTrue(policy.allowsDictionaryLookup)
        assertTrue(policy.allowsComposingText)
        assertFalse(policy.allowsRetainingCandidates)
    }
}
