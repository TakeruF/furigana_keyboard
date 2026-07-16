package com.example.furiganakeyboard.reading

import android.database.sqlite.SQLiteDatabase
import android.os.Looper

/**
 * Dictionary evidence for one exact handwriting surface.
 *
 * [costAdjustment] shares H1's lower-is-better direction. It is deliberately
 * non-positive: dictionary hits receive a small bounded discount while an
 * unknown surface remains neutral instead of receiving a large penalty.
 */
data class SurfaceLexicalEvidence(
    val surface: String,
    val exact: Boolean,
    val properName: Boolean,
    val placeName: Boolean,
    val costAdjustment: Float,
) {
    val unknown: Boolean get() = !exact
    val named: Boolean get() = properName || placeName

    init {
        require(surface.isNotEmpty()) { "Evidence requires a non-empty surface" }
        require(costAdjustment.isFinite() && costAdjustment in MIN_COST_ADJUSTMENT..0f) {
            "Lexical cost adjustment must be in [$MIN_COST_ADJUSTMENT, 0]"
        }
    }

    /** Apply this bounded evidence to H1's finite [0, 1] shape-cost band. */
    fun applyToShapeCost(shapeCost: Float): Float {
        require(shapeCost.isFinite() && shapeCost in 0f..1f) {
            "Shape cost must be finite and in [0, 1]"
        }
        return (shapeCost + costAdjustment).coerceIn(0f, 1f)
    }

    companion object {
        const val MIN_COST_ADJUSTMENT = -0.08f
        private const val PROPER_NAME_COST_ADJUSTMENT = -0.04f

        internal fun fromMatch(
            surface: String,
            exact: Boolean,
            properName: Boolean,
            placeName: Boolean,
        ): SurfaceLexicalEvidence = SurfaceLexicalEvidence(
            surface = surface,
            exact = exact,
            properName = properName,
            placeName = placeName,
            costAdjustment = when {
                properName || placeName -> PROPER_NAME_COST_ADJUSTMENT
                exact -> MIN_COST_ADJUSTMENT
                else -> 0f
            },
        )

        internal fun unknown(surface: String): SurfaceLexicalEvidence =
            fromMatch(surface, exact = false, properName = false, placeName = false)
    }
}

/** Shared input normalization for real and fake [ReadingDataSource] implementations. */
object SurfaceLexicalEvidenceBatch {
    const val MAX_SURFACES = 24

    fun normalize(surfaces: List<String>): List<String> = surfaces.asSequence()
        .filter(String::isNotEmpty)
        .distinct()
        .take(MAX_SURFACES)
        .toList()

    fun unknown(surfaces: List<String>): Map<String, SurfaceLexicalEvidence> =
        normalize(surfaces).associateWithTo(LinkedHashMap()) { surface ->
            SurfaceLexicalEvidence.unknown(surface)
        }
}

/** One-query SQLite implementation; the public repository remains worker-confined. */
internal class SQLiteSurfaceLexicalEvidenceReader(
    private val database: SQLiteDatabase,
    private val requireWorkerThread: () -> Unit = {
        check(Looper.getMainLooper().thread !== Thread.currentThread()) {
            "Lexical evidence database reads must run off the main thread"
        }
    },
    private val onQuery: () -> Unit = {},
) {
    fun load(surfaces: List<String>): Map<String, SurfaceLexicalEvidence> {
        val normalized = SurfaceLexicalEvidenceBatch.normalize(surfaces)
        if (normalized.isEmpty()) return emptyMap()
        requireWorkerThread()

        val requestedValues = normalized.indices.joinToString(",") { "(?, $it)" }
        val sql = """
            WITH requested(surface, ordinal) AS (VALUES $requestedValues),
            matched(surface, exact_match, proper_name, place_name) AS (
                SELECT word.surface, 1, 0, 0
                FROM word_reading AS word
                WHERE word.surface IN (SELECT surface FROM requested)
                UNION ALL
                SELECT kanji.literal, 1, 0, 0
                FROM kanji_reading AS kanji
                WHERE kanji.literal IN (SELECT surface FROM requested)
                UNION ALL
                SELECT lexeme.surface,
                       1,
                       CASE WHEN lexeme.left_id = 4 OR lexeme.right_id = 4 THEN 1 ELSE 0 END,
                       CASE WHEN lexeme.source = 'jmnedict_place' THEN 1 ELSE 0 END
                FROM conversion_lexeme AS lexeme NOT INDEXED
                WHERE lexeme.surface IN (SELECT surface FROM requested)
            )
            SELECT requested.surface,
                   coalesce(max(matched.exact_match), 0),
                   coalesce(max(matched.proper_name), 0),
                   coalesce(max(matched.place_name), 0)
            FROM requested
            LEFT JOIN matched ON matched.surface = requested.surface
            GROUP BY requested.ordinal, requested.surface
            ORDER BY requested.ordinal
        """.trimIndent()

        onQuery()
        return database.rawQuery(sql, normalized.toTypedArray()).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    val surface = cursor.getString(0)
                    put(
                        surface,
                        SurfaceLexicalEvidence.fromMatch(
                            surface = surface,
                            exact = cursor.getInt(1) != 0,
                            properName = cursor.getInt(2) != 0,
                            placeName = cursor.getInt(3) != 0,
                        ),
                    )
                }
            }
        }
    }
}
