package com.example.furiganakeyboard

import com.example.furiganakeyboard.update.ReadingDataUpdater
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/** Robolectric supplies the real `android.util.Base64` the verifier decodes its key with. */
@RunWith(RobolectricTestRunner::class)
class ReadingDataUpdaterTest {
    @Test
    fun signatureVerificationAcceptsOnlyTheSignedManifestBytes() {
        val pair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val manifest = "{\"formatVersion\":1}".encodeToByteArray()
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private)
            update(manifest)
            sign()
        }
        val publicKey = Base64.getEncoder().encodeToString(pair.public.encoded)

        assertTrue(ReadingDataUpdater.verifySignature(manifest, signature, publicKey))
        assertFalse(
            ReadingDataUpdater.verifySignature("changed".encodeToByteArray(), signature, publicKey)
        )
    }
}
