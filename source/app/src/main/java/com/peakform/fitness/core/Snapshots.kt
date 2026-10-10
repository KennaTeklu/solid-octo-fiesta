package com.peakform.fitness.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Snapshots — named vault snapshots with a browsable list and guarded restore
 * (legacy P4.Vault + rolling snapshots, reshaped per the 1.2 P4 fix: restore is
 * ALWAYS user-initiated with a confirmation; never blind auto-restore).
 * Storage: Room `meta` table rows keyed snapshot_<ts> holding {name, at, workoutData, currentWorkout}.
 */
object Snapshots {
    private const val PREFIX = "snapshot_"
    private const val CAP = 10

    data class Entry(val id: String, val name: String, val at: Long, val bytes: Long = 0)

    suspend fun list(ctx: Context): List<Entry> = withContext(Dispatchers.IO) {
        val dao = ProStore(ctx).db.dao()
        dao.let { d ->
            (0 until 64).mapNotNull { i ->
                val key = "${PREFIX}$i"
                val raw = d.meta(key) ?: return@mapNotNull null
                try {
                    val o = ProJson.json.parseToJsonElement(raw).jsonObject
                    val at = (o["at"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L
                    // BATCH-2A feature 7: the list shows timestamp AND size
                    Entry(key, (o["name"] as? JsonPrimitive)?.contentOrNull ?: "Snapshot", at, raw.toByteArray().size.toLong())
                } catch (_: Exception) { null }
            }
        }.sortedByDescending { it.at }
    }

    suspend fun create(ctx: Context, name: String) = withContext(Dispatchers.IO) {
        val dao = ProStore(ctx).db.dao()
        val ts = System.currentTimeMillis()
        val bundle = buildJsonObject {
            put("name", name.ifBlank { defaultName() })
            put("at", ts.toString())
            put("profile", ProState.activeProfileId)
            put("workoutData", ProState.data.toJsonElement())
            ProState.currentWorkout?.let { put("currentWorkout", ProJson.encodeElement(WorkoutRecord.serializer(), it)) }
        }
        // reuse the lowest free slot; overwrite the oldest when full
        var slot = -1
        var oldestKey: String? = null
        var oldestAt = Long.MAX_VALUE
        for (i in 0 until CAP) {
            val key = "${PREFIX}$i"
            val raw = dao.meta(key)
            if (raw == null) { slot = i; break }
            val at = runCatching { ((ProJson.json.parseToJsonElement(raw).jsonObject["at"]) as JsonPrimitive).contentOrNull?.toLongOrNull() ?: 0L }.getOrDefault(0L)
            if (at < oldestAt) { oldestAt = at; oldestKey = key }
        }
        val target = if (slot >= 0) "${PREFIX}$slot" else oldestKey!!
        dao.putMeta(MetaRow(target, ProJson.json.encodeToString(JsonObject.serializer(), bundle)))
    }

    data class Restored(val workouts: Int, val exercises: Int, val name: String)

    suspend fun restore(ctx: Context, id: String): Restored? = withContext(Dispatchers.IO) {
        val raw = ProStore(ctx).db.dao().meta(id) ?: return@withContext null
        val o = try { ProJson.json.parseToJsonElement(raw).jsonObject } catch (_: Exception) { return@withContext null }
        val dataEl = o["workoutData"] ?: return@withContext null
        // guarded: snapshot current state first (pre-restore safety)
        create(ctx, "pre-restore safety")
        val restored = try { ProJson.decodeElement(WorkoutData.serializer(), dataEl) } catch (_: Exception) { return@withContext null }
        val cw = o["currentWorkout"]?.let {
            try { ProJson.decodeElement(WorkoutRecord.serializer(), it) } catch (_: Exception) { null }
        }
        ProState.data = restored
        ProState.currentWorkout = cw
        ProState.saveWorkoutData()
        EngineHooks.rebuildDerived()
        ProState.notifyChanged()
        Restored(restored.workouts.size, restored.exercises.size, (o["name"] as? JsonPrimitive)?.contentOrNull ?: "Snapshot")
    }

    suspend fun delete(ctx: Context, id: String) = withContext(Dispatchers.IO) {
        ProStore(ctx).db.dao().deleteMeta(listOf(id))
    }

    fun defaultName(): String =
        "Snapshot · " + SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date())
}
