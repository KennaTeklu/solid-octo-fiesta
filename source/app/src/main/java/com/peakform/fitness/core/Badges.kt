package com.peakform.fitness.core

import android.content.Context
import com.peakform.fitness.ProLog
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.*
import java.util.Calendar

/**
 * Badge engine — native port of P4.Motivation + window.P4_BADGES (PF L51447–51549, 56097+).
 * 101 verbatim legacy definitions (check.type × value ladders + meta-badges).
 * Award flow: evaluate() → newly unlocked → staggered toasts + confetti(90) + buzz.
 */
object Badges {

    data class BadgeDef(
        val id: String,
        val icon: String,
        val name: String,
        val desc: String,
        val type: String,
        val value: Double,
    )

    /** Evaluation context — legacy L56200–56241. */
    data class Ctx(
        val workouts: Int,
        val streak: Int,
        val volume: Double,
        val prs: Int,
        val tried: Int,
        val backups: Int,
        val studioAdds: Int,
        val imports: Int,
        val exports: Int,
        val earlyBird: Boolean,
        val nightOwl: Boolean,
        val comeback: Boolean,
        val gymSessions: Int,
        val vocabChanged: Int,
        val themesTried: Int,
    )

    data class Result(val unlocked: List<BadgeDef>, val all: List<BadgeDef>, val unlockedIds: Set<String>)

    private var defs: List<BadgeDef> = emptyList()

    fun init(ctx: Context) {
        if (defs.isNotEmpty()) return
        defs = try {
            val raw = ctx.assets.open("data/badges.json").bufferedReader().use { it.readText() }
            val arr = ProJson.json.parseToJsonElement(raw).jsonArray
            arr.map { el ->
                val o = el.jsonObject
                val check = o["check"]!!.jsonObject
                BadgeDef(
                    id = (o["id"] as JsonPrimitive).content,
                    icon = (o["icon"] as JsonPrimitive).content,
                    name = (o["name"] as JsonPrimitive).content,
                    desc = (o["desc"] as JsonPrimitive).content,
                    type = (check["type"] as JsonPrimitive).content,
                    value = check["value"]?.let { v -> (v as? JsonPrimitive)?.doubleOrNull } ?: 1.0,
                )
            }
        } catch (e: Exception) {
            ProLog.e("BADGE", "defs load failed: ${e.message}")
            emptyList()
        }
        ProLog.i("BADGE", "badge defs: ${defs.size}")
    }

    fun all(): List<BadgeDef> = defs

    // ---- persisted counters ----
    private const val COUNTERS_KEY = "p4_badge_counters"
    private const val UNLOCKED_KEY = "p4_badges"
    private const val SEEN_KEY = "p4_badges_seen"

    fun counter(ctx: Context, key: String): Int = (counters(ctx)[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0

    private fun counters(ctx: Context): JsonObject = try {
        ProPrefs.get(ctx, COUNTERS_KEY)?.let { ProJson.json.parseToJsonElement(it).jsonObject } ?: JsonObject(emptyMap())
    } catch (_: Exception) { JsonObject(emptyMap()) }

    fun bumpCounter(ctx: Context, key: String, by: Int = 1) {
        val c = counters(ctx).toMutableMap()
        val cur = ((c[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()) ?: 0
        c[key] = JsonPrimitive(cur + by)
        ProPrefs.put(ctx, COUNTERS_KEY, ProJson.json.encodeToString(JsonObject.serializer(), JsonObject(c)))
    }

    fun unlockedIds(ctx: Context): Set<String> = try {
        ProPrefs.get(ctx, UNLOCKED_KEY)?.let {
            ProJson.json.decodeFromString(ListSerializer(String.serializer()), it).toSet()
        } ?: emptySet()
    } catch (_: Exception) { emptySet() }

    fun seenTimestamps(ctx: Context): Map<String, Long> = try {
        ProPrefs.get(ctx, SEEN_KEY)?.let { raw ->
            ProJson.json.parseToJsonElement(raw).jsonObject.mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L }
        } ?: emptyMap()
    } catch (_: Exception) { emptyMap() }

    private fun markUnlocked(ctx: Context, ids: List<String>) {
        val merged = (unlockedIds(ctx) + ids).toList()
        ProPrefs.put(ctx, UNLOCKED_KEY, ProJson.json.encodeToString(ListSerializer(String.serializer()), merged))
        val seen = seenTimestamps(ctx).toMutableMap()
        ids.forEach { if (it !in seen) seen[it] = System.currentTimeMillis() }
        ProPrefs.put(ctx, SEEN_KEY, ProJson.json.encodeToString(JsonObject.serializer(), JsonObject(seen.mapValues { (_, v) -> JsonPrimitive(v) })))
    }

    /** Pure evaluation (testable): which defs pass given the context, minus already unlocked. */
    fun evaluateDefs(all: List<BadgeDef>, c: Ctx, alreadyUnlocked: Set<String>): List<BadgeDef> {
        fun met(type: String, value: Double): Boolean = when (type) {
            "workouts" -> c.workouts >= value
            "streak" -> c.streak >= value
            "volume" -> c.volume >= value
            "prs" -> c.prs >= value
            "tried" -> c.tried >= value
            "backups" -> c.backups >= value
            "studioAdds" -> c.studioAdds >= value
            "imports" -> c.imports >= value
            "exports" -> c.exports >= value
            "earlyBird" -> c.earlyBird && value <= 1
            "nightOwl" -> c.nightOwl && value <= 1
            "comeback" -> c.comeback && value <= 1
            "gym" -> c.gymSessions >= value
            "vocab" -> c.vocabChanged >= value
            "themes" -> c.themesTried >= value
            else -> false
        }
        return all.filter { it.id !in alreadyUnlocked && met(it.type, it.value) }
    }

    /** Build the live context from app state and run evaluation, persisting unlocks. */
    fun evaluate(ctx: Context, justCompletedWorkout: Boolean = false): Result {
        init(ctx)
        val d = ProState.data
        val completed = d.workouts.filter { it.isCompleted }
        val streak = com.peakform.fitness.engine.Stats.calculateStreak()
        val volume = completed.sumOf { it.summary?.totalVolume ?: 0.0 }
        val prs = d.exercises.values.sumOf { rec ->
            rec.history.count { h -> h.estimated1RM != null && rec.bestWeight != null && (h.weight) >= (rec.bestWeight ?: 0.0) }
        }.coerceAtLeast(d.exercises.values.count { (it.bestWeight ?: 0.0) > 0 })
        val tried = d.exercises.values.count { it.history.isNotEmpty() }
        val c = Ctx(
            workouts = completed.size,
            streak = streak,
            volume = volume,
            prs = prs,
            tried = tried,
            backups = counter(ctx, "backups"),
            studioAdds = counter(ctx, "studioAdds"),
            imports = counter(ctx, "imports"),
            exports = counter(ctx, "exports"),
            earlyBird = earlyBird(completed),
            nightOwl = nightOwl(completed),
            comeback = comeback(completed),
            gymSessions = counter(ctx, "gymSessions"),
            vocabChanged = counter(ctx, "vocabChanged"),
            themesTried = counter(ctx, "themesTried"),
        )
        val already = unlockedIds(ctx)
        val newly = evaluateDefs(defs, c, already)
        if (newly.isNotEmpty()) markUnlocked(ctx, newly.map { it.id })
        return Result(newly, defs, already + newly.map { it.id })
    }

    /** Last 10 workouts before 08:00 local (L56265). */
    private fun earlyBird(completed: List<WorkoutRecord>): Boolean {
        val recent = completed.takeLast(10)
        if (recent.isEmpty()) return false
        return recent.any { w ->
            val cal = Calendar.getInstance().apply {
                timeInMillis = ProState.utcDayMillis(w.dateCompleted ?: w.date)
            }
            // dateCompleted is UTC ISO; compare the local wall clock of "now" only for today's sessions
            cal.get(Calendar.HOUR_OF_DAY) in 0..7
        }
    }

    /** ≥9 PM in the last 10 workouts (L56272). */
    private fun nightOwl(completed: List<WorkoutRecord>): Boolean {
        val recent = completed.takeLast(10)
        if (recent.isEmpty()) return false
        return recent.any { w ->
            val cal = Calendar.getInstance().apply {
                timeInMillis = ProState.utcDayMillis(w.dateCompleted ?: w.date)
            }
            cal.get(Calendar.HOUR_OF_DAY) >= 21
        }
    }

    /** >21-day gap then returned within 10 days (L56279). */
    private fun comeback(completed: List<WorkoutRecord>): Boolean {
        if (completed.size < 2) return false
        val last = ProState.utcDayMillis(completed.last().date)
        val prev = ProState.utcDayMillis(completed[completed.size - 2].date)
        val gap = last - prev
        val sinceLast = System.currentTimeMillis() - last
        return gap > 21L * 24 * 3600 * 1000 && sinceLast < 10L * 24 * 3600 * 1000
    }
}
