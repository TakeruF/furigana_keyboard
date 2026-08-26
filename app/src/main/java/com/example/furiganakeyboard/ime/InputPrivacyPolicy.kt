package com.example.furiganakeyboard.ime

import android.view.inputmethod.EditorInfo

/**
 * What the keyboard is allowed to do for the editor it is attached to.
 *
 * The keyboard has no learning store: nothing the user types is ever written to
 * disk. This policy makes the remaining promises explicit, so a password field
 * cannot reach the dictionary, cannot become recognition context, and cannot be
 * left behind in the in-memory candidate caches after the editor goes away.
 */
data class InputPrivacyPolicy(
    /** Password-like editors, by [EditorInfo.inputType] variation. */
    val isSensitiveField: Boolean,
    /** The editor asked for [EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING]. */
    val forbidsPersonalizedLearning: Boolean,
) {
    /** Dictionary suggestions, kana-kanji conversion, and reading inference. */
    val allowsDictionaryLookup: Boolean get() = !isSensitiveField

    /** Feeding already-typed text back to the handwriting recognizer as context. */
    val allowsRecognitionContext: Boolean get() = !isSensitiveField

    /** Holding a multi-character composing span in the editor before committing. */
    val allowsComposingText: Boolean get() = !isSensitiveField

    /** Whether candidates derived from this editor may outlive it in memory. */
    val allowsRetainingCandidates: Boolean
        get() = !isSensitiveField && !forbidsPersonalizedLearning

    companion object {
        val UNRESTRICTED = InputPrivacyPolicy(
            isSensitiveField = false,
            forbidsPersonalizedLearning = false,
        )

        fun of(info: EditorInfo?): InputPrivacyPolicy =
            if (info == null) UNRESTRICTED else of(info.inputType, info.imeOptions)

        /**
         * Primitive entry point so the contract stays testable without an
         * [EditorInfo] instance.
         */
        // The learning flag is an API 26 constant inlined into this class. API 24
        // and 25 editors cannot set that bit, so the test simply never matches there.
        @Suppress("InlinedApi")
        fun of(inputType: Int, imeOptions: Int): InputPrivacyPolicy = InputPrivacyPolicy(
            isSensitiveField = isSensitiveInputType(inputType),
            forbidsPersonalizedLearning =
                imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0,
        )

        private fun isSensitiveInputType(inputType: Int): Boolean {
            val variation = inputType and EditorInfo.TYPE_MASK_VARIATION
            return when (inputType and EditorInfo.TYPE_MASK_CLASS) {
                EditorInfo.TYPE_CLASS_TEXT ->
                    variation == EditorInfo.TYPE_TEXT_VARIATION_PASSWORD ||
                        variation == EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                        variation == EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD
                EditorInfo.TYPE_CLASS_NUMBER ->
                    variation == EditorInfo.TYPE_NUMBER_VARIATION_PASSWORD
                else -> false
            }
        }
    }
}
