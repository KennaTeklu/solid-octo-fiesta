package com.peakform.fitness.core

import android.content.Context
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Exporter — BATCH-2A import/export parity helpers that did not already live in
 * Backup / Studio / Consent / Vocab:
 *  - feature 1: workout-history CSV, ONE ROW PER LOGGED SET
 *               (columns: date, exercise, set, reps, weight, RPE, note)
 *  - feature 5: minimal feedback log (textarea + submit UI lives in FeedbackScreen)
 *               with JSON export
 */
object Exporter {
    const val FEEDBACK_KEY = "p4_feedback_log"
    private const val FEEDBACK_CAP = 200

    // ---------- feature 1: history CSV, one row per logged set ----------

    /** Wrap a CSV field in quotes, doubling any inner quotes (commas/newlines stay safe inside quotes). */
    private fun csvField(raw: String): String = "\"" + raw.replace("\"", "\"\"") + "\""

    /**
     * History CSV with one row per LOGGED SET (BATCH-2A feature 1). Columns:
     * date, exercise, set, reps, weight, RPE, note. Returns csv text + row count.
     */
    fun historyCsvPerSet(): Pair<String, Int> {
        val sb = StringBuilder("date,exercise,set,reps,weight,RPE,note")
        var rows = 0
        ProState.data.workouts.sortedBy { it.date }.forEach { w ->
            w.exercises.forEach { ex ->
                val a = ex.actual ?: return@forEach
                if (ex.skipped) return@forEach
                // per-set rows: reps/RPE lists are per-set; weight and note are per-exercise
                val reps = a.reps
                val rpes = a.rpeList
                val setCount = maxOf(a.sets, reps.size, rpes.size)
                if (setCount <= 0) return@forEach
                for (i in 0 until setCount) {
                    val fields = listOf(
                        w.date,
                        ex.name,
                        (i + 1).toString(),
                        reps.getOrNull(i)?.let { ProJson.formatNum(it) } ?: "",
                        ProJson.formatNum(a.weight),
                        rpes.getOrNull(i)?.let { ProJson.formatNum(it) } ?: "",
                        a.notes ?: "",
                    )
                    sb.append('\n').append(fields.joinToString(",") { csvField(it) })
                    rows++
                }
            }
        }
        return sb.toString() to rows
    }

    // ---------- feature 5: feedback log ----------

    @kotlinx.serialization.Serializable
    data class FeedbackEntry(val at: String, val text: String, val page: String = "unknown")

    fun addFeedback(ctx: Context, text: String, page: String = "settings") {
        val t = text.trim()
        if (t.isEmpty()) return
        val entries = readFeedback(ctx).toMutableList()
        entries.add(0, FeedbackEntry(ProState.nowIso(), t.take(2000), page))
        ProPrefs.put(ctx, FEEDBACK_KEY, ProJson.json.encodeToString(
            ListSerializer(FeedbackEntry.serializer()), entries.take(FEEDBACK_CAP)))
    }

    fun readFeedback(ctx: Context): List<FeedbackEntry> = try {
        val raw = ProPrefs.get(ctx, FEEDBACK_KEY) ?: return emptyList()
        ProJson.json.decodeFromString(ListSerializer(FeedbackEntry.serializer()), raw)
    } catch (_: Exception) { emptyList() }

    /** Feedback export — pretty JSON with every entry. */
    fun feedbackJson(ctx: Context): String {
        val obj = buildJsonObject {
            put("kind", "peakform-feedback-log")
            put("exportedAt", ProState.nowIso())
            put("entries", JsonArray(readFeedback(ctx).map {
                ProJson.encodeElement(FeedbackEntry.serializer(), it)
            }))
        }
        return ProJson.pretty.encodeToString(JsonObject.serializer(), obj)
    }
}
