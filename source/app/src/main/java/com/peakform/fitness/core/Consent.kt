package com.peakform.fitness.core

import android.content.Context
import com.peakform.fitness.ProLog
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray

/**
 * Consent — versioned consent recording, native port of P4.Legal.record()/agreedTo()
 * (PF L56464–56476) + the p4_consent_log storage (last 200 kept, 8 shown in UI).
 */
object Consent {
    const val LOG_KEY = "p4_consent_log"
    const val VERSION = "2026-09-21"
    private const val CAP = 200

    @kotlinx.serialization.Serializable
    data class Entry(val at: String, val kind: String, val detail: String, val version: String)

    fun record(ctx: Context, kind: String, detail: String, version: String = VERSION) {
        val entries = readLog(ctx).toMutableList()
        entries.add(0, Entry(ProState.nowIso(), kind, detail, version))
        val capped = entries.take(CAP)
        ProPrefs.put(ctx, LOG_KEY, ProJson.json.encodeToString(ListSerializer(Entry.serializer()), capped))
    }
    fun agreedTo(ctx: Context, kind: String, version: String = VERSION): Boolean =
        readLog(ctx).any { it.kind == kind && it.version == version }

    fun readLog(ctx: Context): List<Entry> = try {
        val raw = ProPrefs.get(ctx, LOG_KEY) ?: return emptyList()
        ProJson.json.decodeFromString(ListSerializer(Entry.serializer()), raw)
    } catch (e: Exception) {
        ProLog.w("CONSENT", "log read failed: ${e.message}")
        emptyList()
    }

    /** Consent history panel shows the last 8 records (legacy behavior). */
    fun recent(ctx: Context, n: Int = 8): List<Entry> = readLog(ctx).take(n)

    fun recentJson(ctx: Context, n: Int = 8): List<JsonElement> =
        recent(ctx, n).map { ProJson.encodeElement(Entry.serializer(), it) }

    /** "Export consent log" — pretty JSON of the whole log. */
    fun exportLog(ctx: Context): String =
        ProJson.pretty.encodeToString(ListSerializer(Entry.serializer()), readLog(ctx))
}
