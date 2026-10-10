package com.peakform.fitness.core

import android.content.Context
import com.peakform.fitness.ProLog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Vocab — reading-level engine (PF L55343–55535). Levels L1–L10 with the legacy names;
 * t() resolves an exact registered phrase at the current level (L5 = authored default).
 * Native scope: exact-phrase lookup + label substitution for core UI strings — the DOM
 * tree-walk of the web app has no equivalent (and no need) in Compose.
 */
object Vocab {
    data class Level(val id: Int, val name: String)

    val LEVELS = listOf(
        Level(1, "Super Simple"), Level(2, "Easy"), Level(3, "Everyday"), Level(4, "Clear"),
        Level(5, "Standard"), Level(6, "Sharp"), Level(7, "Advanced"), Level(8, "Technical"),
        Level(9, "Pro Coaching"), Level(10, "Grandiloquent"),
    )

    const val PREF_KEY = "p4_vocab"
    const val CUSTOM_KEY = "p4_vocab_custom"
    private var ladders: Map<String, List<String>> = emptyMap()

    fun init(ctx: Context) {
        if (ladders.isNotEmpty()) return
        ladders = try {
            val raw = ctx.assets.open("data/vocab_ladders.json").bufferedReader().use { it.readText() }
            val obj = ProJson.json.parseToJsonElement(raw).jsonObject
            obj.mapValues { (_, v) ->
                v.jsonArray.map { (it as JsonPrimitive).content }
            }
        } catch (e: Exception) {
            ProLog.e("VOCAB", "ladder load failed: ${e.message}")
            emptyMap()
        }
        // BATCH-2A feature 6: re-apply the persisted custom phrasebook over the assets
        assetLadders = ladders
        ladders = ladders + readCustom(ctx)
        ProLog.i("VOCAB", "ladders loaded: ${ladders.size} keys")
    }

    private fun readCustom(ctx: Context): Map<String, List<String>> = try {
        val raw = ProPrefs.get(ctx, CUSTOM_KEY) ?: return emptyMap()
        if (raw.isBlank()) emptyMap() else {
            ProJson.json.parseToJsonElement(raw).jsonObject.mapValues { (_, v) ->
                v.jsonArray.map { (it as JsonPrimitive).content }
            }
        }
    } catch (e: Exception) {
        ProLog.w("VOCAB", "custom ladders load failed: ${e.message}")
        emptyMap()
    }

    /** Re-read the persisted custom phrasebook after a bundle import (feature 10). */
    fun reloadCustom(ctx: Context) {
        ladders = ladders + readCustom(ctx)
    }

    private fun persistCustom(ctx: Context) {
        val custom = ladders.filterKeys { key -> key !in ASSET_LADDER_KEYS || customDiffersFromAsset(key) }
        ProPrefs.put(ctx, CUSTOM_KEY, ProJson.json.encodeToString(
            JsonObject.serializer(),
            JsonObject(custom.mapValues { (_, v) -> JsonArray(v.map { JsonPrimitive(it) }) })))
    }

    private var assetLadders: Map<String, List<String>> = emptyMap()
    private val ASSET_LADDER_KEYS: Set<String> get() = assetLadders.keys

    private fun customDiffersFromAsset(key: String): Boolean {
        val asset = assetLadders[key] ?: return true
        return ladders[key] != asset
    }

    fun currentLevel(ctx: Context): Int = ProPrefs.get(ctx, PREF_KEY)?.toIntOrNull()?.coerceIn(1, 10) ?: 5

    fun setLevel(ctx: Context, level: Int) {
        ProPrefs.put(ctx, PREF_KEY, level.coerceIn(1, 10).toString())
    }

    /** Legacy invariant: L5 always equals the authored default (no-op level). */
    fun t(ctx: Context, key: String): String {
        val level = currentLevel(ctx)
        val ladder = ladders[key] ?: return key
        return ladder.getOrNull(level - 1) ?: key
    }

    /** Word list entry for the glossary overlay: word → replacement at the current level. */
    fun glossaryEntries(ctx: Context): List<Pair<String, String>> {
        val level = currentLevel(ctx)
        return ladders.mapNotNull { (key, variants) ->
            val rep = variants.getOrNull(level - 1) ?: return@mapNotNull null
            if (rep != key) key to rep else null
        }.sortedBy { it.first }
    }

    fun exportPhrasebook(ctx: Context): String {
        val level = currentLevel(ctx)
        val obj = JsonObject(ladders.mapValues { (_, v) -> JsonPrimitive(v.getOrNull(level - 1) ?: "") })
        return ProJson.pretty.encodeToString(JsonObject.serializer(), obj)
    }

    /** Phrasebook import merges community translations INTO the ladders for the current level. */
    fun importPhrasebook(ctx: Context, raw: String): Int {
        val level = currentLevel(ctx)
        return try {
            val obj = ProJson.json.parseToJsonElement(JsonHeal.heal(raw)).jsonObject
            var applied = 0
            val updated = ladders.toMutableMap()
            obj.forEach { (key, value) ->
                val text = (value as? JsonPrimitive)?.contentOrNull ?: return@forEach
                val ladder = updated[key]?.toMutableList() ?: MutableList(10) { idx -> if (idx == 4) key else "" }
                if (level - 1 in ladder.indices) {
                    ladder[level - 1] = text
                    updated[key] = ladder
                    applied++
                }
            }
            ladders = updated
            ProLog.i("VOCAB", "phrasebook applied $applied phrases at L$level")
            applied
        } catch (e: Exception) {
            ProLog.e("VOCAB", "phrasebook import failed: ${e.message}")
            0
        }
    }

    /** Phrasebook merge report (BATCH-2A feature 6): added / updated / rejected counts. */
    data class PhrasebookReport(val added: Int, val updated: Int, val rejected: Int, val total: Int)

    fun importPhrasebookReport(ctx: Context, raw: String): PhrasebookReport {
        val level = currentLevel(ctx)
        var added = 0; var updated = 0; var rejected = 0; var total = 0
        try {
            val obj = ProJson.json.parseToJsonElement(JsonHeal.heal(raw)).jsonObject
            val updatedLadders = ladders.toMutableMap()
            obj.forEach { (key, value) ->
                total++
                val text = (value as? JsonPrimitive)?.contentOrNull
                if (text.isNullOrBlank()) { rejected++; return@forEach }
                val ladder = updatedLadders[key]?.toMutableList()
                if (ladder == null || level - 1 !in ladder.indices) {
                    // unknown/short key: build a fresh 10-slot ladder (L5 keeps the key, legacy invariant)
                    val fresh = MutableList(10) { idx -> if (idx == 4) key else "" }
                    if (level - 1 in fresh.indices) {
                        fresh[level - 1] = text
                        updatedLadders[key] = fresh
                        added++
                    } else rejected++
                    return@forEach
                }
                val existing = ladder[level - 1]
                if (existing == text) { rejected++; return@forEach }
                ladder[level - 1] = text
                updatedLadders[key] = ladder
                if (existing.isBlank()) added++ else updated++
            }
            ladders = updatedLadders
            persistCustom(ctx)
            ProLog.i("VOCAB", "phrasebook merge: +$added ~$updated x$rejected of $total at L$level")
            return PhrasebookReport(added, updated, rejected, total)
        } catch (e: Exception) {
            ProLog.e("VOCAB", "phrasebook import failed: ${e.message}")
            return PhrasebookReport(0, 0, if (total > 0) total else 1, total)
        }
    }

    /** Tip band by level: band 1 = L1–3, band 2 = L4–6, band 3 = L7–10 (L51551). */
    fun band(level: Int): Int = when (level) {
        in 1..3 -> 1
        in 4..6 -> 2
        else -> 3
    }
}
