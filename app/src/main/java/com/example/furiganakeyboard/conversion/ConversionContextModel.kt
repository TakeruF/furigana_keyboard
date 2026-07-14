package com.example.furiganakeyboard.conversion

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction

data class ConversionContextMetadata(
    val formatVersion: Int,
    val modelVersion: Int,
    val unigramCount: Int,
    val bigramCount: Int,
    val sourceSha256: String,
) {
    val entryCount: Int get() = unigramCount + bigramCount
}

/** Immutable, generated context costs shared byte-for-byte with the iOS runtime. */
class ConversionContextModel private constructor(
    val metadata: ConversionContextMetadata,
    private val unigramCosts: Map<String, Int>,
    private val bigramCosts: Map<BigramKey, Int>,
) {
    /** Exact surface bigram first; an unknown pair backs off to the next-surface unigram. */
    fun cost(previousSurface: String?, nextSurface: String): Int {
        if (previousSurface != null) {
            bigramCosts[BigramKey(previousSurface, nextSurface)]?.let { return it }
        }
        return unigramCosts[nextSurface] ?: 0
    }

    fun hasBigram(previousSurface: String, nextSurface: String): Boolean =
        bigramCosts.containsKey(BigramKey(previousSurface, nextSurface))

    private data class BigramKey(val previous: String, val next: String)

    companion object {
        private const val HEADER_BYTES = 52
        private const val FORMAT_VERSION = 1
        private const val MAX_ENTRIES = 1_000_000
        private val MAGIC = "FKCTX001".toByteArray(Charsets.US_ASCII)
        private val EMPTY = ConversionContextModel(
            ConversionContextMetadata(0, 0, 0, 0, "0".repeat(64)),
            emptyMap(),
            emptyMap(),
        )

        fun empty(): ConversionContextModel = EMPTY

        fun decode(bytes: ByteArray): ConversionContextModel {
            require(bytes.size >= HEADER_BYTES) { "context model header is truncated" }
            val reader = Reader(bytes)
            require(reader.readBytes(MAGIC.size).contentEquals(MAGIC)) {
                "invalid context model magic"
            }
            val formatVersion = reader.readUInt16()
            require(formatVersion == FORMAT_VERSION) {
                "unsupported context model format $formatVersion"
            }
            val modelVersion = reader.readUInt16()
            require(modelVersion > 0) { "invalid context model version" }
            val unigramCount = reader.readUInt32()
            val bigramCount = reader.readUInt32()
            require(unigramCount <= MAX_ENTRIES && bigramCount <= MAX_ENTRIES &&
                unigramCount.toLong() + bigramCount <= MAX_ENTRIES
            ) { "context model entry count is too large" }
            val sourceSha256 = reader.readBytes(32).joinToString("") {
                "%02x".format(it.toInt() and 0xff)
            }

            val unigrams = LinkedHashMap<String, Int>(unigramCount)
            repeat(unigramCount) {
                val surface = reader.readString()
                require(unigrams.put(surface, reader.readInt32()) == null) {
                    "duplicate context unigram"
                }
            }
            val bigrams = LinkedHashMap<BigramKey, Int>(bigramCount)
            repeat(bigramCount) {
                val key = BigramKey(reader.readString(), reader.readString())
                require(bigrams.put(key, reader.readInt32()) == null) {
                    "duplicate context bigram"
                }
            }
            require(reader.isAtEnd) { "context model has trailing bytes" }
            return ConversionContextModel(
                ConversionContextMetadata(
                    formatVersion = formatVersion,
                    modelVersion = modelVersion,
                    unigramCount = unigramCount,
                    bigramCount = bigramCount,
                    sourceSha256 = sourceSha256,
                ),
                unigrams.toMap(),
                bigrams.toMap(),
            )
        }

        private class Reader(private val bytes: ByteArray) {
            private var offset = 0
            val isAtEnd: Boolean get() = offset == bytes.size

            fun readUInt16(): Int {
                requireAvailable(2)
                val value = (bytes[offset].toInt() and 0xff) or
                    ((bytes[offset + 1].toInt() and 0xff) shl 8)
                offset += 2
                return value
            }

            fun readUInt32(): Int {
                requireAvailable(4)
                val value = ByteBuffer.wrap(bytes, offset, 4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .int
                    .toLong() and 0xffff_ffffL
                offset += 4
                require(value <= Int.MAX_VALUE) { "context model count overflows Int" }
                return value.toInt()
            }

            fun readInt32(): Int {
                requireAvailable(4)
                val value = ByteBuffer.wrap(bytes, offset, 4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .int
                offset += 4
                return value
            }

            fun readString(): String {
                val length = readUInt16()
                require(length > 0) { "empty context model string" }
                val encoded = readBytes(length)
                return Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(encoded))
                    .toString()
            }

            fun readBytes(count: Int): ByteArray {
                requireAvailable(count)
                return bytes.copyOfRange(offset, offset + count).also { offset += count }
            }

            private fun requireAvailable(count: Int) {
                require(count >= 0 && offset <= bytes.size - count) {
                    "context model is truncated"
                }
            }
        }
    }
}
