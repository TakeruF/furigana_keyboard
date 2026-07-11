package com.example.furiganakeyboard.update

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DirectUpdateManifestTest {
    @Test
    fun parsesValidManifest() {
        val manifest = DirectUpdateManifest.parse(
            """
            {
              "versionCode": 12,
              "versionName": "1.2.0",
              "downloadUrl": "https://downloads.hanlu.app/1.2.0.apk",
              "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
              "releaseNotes": "Recognition improvements"
            }
            """.trimIndent()
        )

        assertEquals(12L, manifest.versionCode)
        assertEquals("1.2.0", manifest.versionName)
        assertEquals("https://downloads.hanlu.app/1.2.0.apk", manifest.downloadUrl)
        assertEquals("a".repeat(64), manifest.sha256)
        assertEquals("Recognition improvements", manifest.releaseNotes)
    }

    @Test
    fun acceptsManifestWithoutReleaseNotes() {
        val manifest = DirectUpdateManifest.parse(
            """
            {
              "versionCode": 2,
              "versionName": "1.1",
              "downloadUrl": "https://downloads.hanlu.app/1.1.apk",
              "sha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
            }
            """.trimIndent()
        )

        assertNull(manifest.releaseNotes)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonHttpsDownload() {
        DirectUpdateManifest.parse(
            """
            {
              "versionCode": 2,
              "versionName": "1.1",
              "downloadUrl": "http://downloads.hanlu.app/1.1.apk",
              "sha256": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
            }
            """.trimIndent()
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDownloadFromAnotherHost() {
        DirectUpdateManifest.parse(
            """
            {
              "versionCode": 2,
              "versionName": "1.1",
              "downloadUrl": "https://example.com/1.1.apk",
              "sha256": "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
            }
            """.trimIndent()
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidSha256() {
        DirectUpdateManifest.parse(
            """
            {
              "versionCode": 2,
              "versionName": "1.1",
              "downloadUrl": "https://downloads.hanlu.app/1.1.apk",
              "sha256": "not-a-hash"
            }
            """.trimIndent()
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsManifestBodyLargerThan64KiB() {
        readDirectUpdateManifest(ByteArrayInputStream(ByteArray(64 * 1024 + 1)))
    }
}
