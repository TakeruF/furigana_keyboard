package com.example.furiganakeyboard.conversion

/** Stable part-of-speech identifiers shared with the conversion dictionary. */
enum class PosClass(val id: Int) {
    BOS(0),
    EOS(1),
    PRONOUN(2),
    NOUN(3),
    PROPER_NOUN(4),
    PARTICLE(5),
    AUXILIARY(6),
    VERB(7),
    ADJECTIVE(8),
    ADVERB(9),
    PREFIX(10),
    SUFFIX(11),
    EXPRESSION(12),
    SYMBOL(13),
    OTHER(14),
    COPY(15);

    companion object {
        fun fromId(id: Int): PosClass? = entries.firstOrNull { it.id == id }
    }
}

/** A dictionary edge. [start] and [end] are Unicode-scalar offsets in [reading]. */
data class ConversionLexeme(
    val start: Int,
    val end: Int,
    val reading: String,
    val surface: String,
    val leftId: Int,
    val rightId: Int,
    val wordCost: Int,
)

/** Cost of connecting a preceding right ID to a following left ID. */
data class ConversionConnection(
    val rightId: Int,
    val leftId: Int,
    val cost: Int,
)

data class ConversionSegment(
    val start: Int,
    val end: Int,
    val reading: String,
    val surface: String,
    val leftId: Int,
    val rightId: Int,
    val wordCost: Int,
    val isCopy: Boolean,
)

data class ConversionResult(
    val surface: String,
    val reading: String,
    val cost: Int,
    val segments: List<ConversionSegment>,
)
