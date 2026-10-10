package com.peakform.fitness.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * StreakFire — the 4-state streak fire (PF L59529–59585) + honor-system recovery
 * (L59587–59663). Tiers by days since the last logged workout:
 *   full (≤2d, 🔥 opacity 1) → dim (≤4d, 🔥 .7) → smoke (≤6d, 🌫️ .5) → dead (>6d or streak 0, ❄️ .4)
 */
object StreakFire {

    data class Fire(val tier: String, val emoji: String, val opacity: Float, val streak: Int, val daysSince: Int)

    fun evaluate(streak: Int, daysSinceLastWorkout: Int): Fire {
        val tier = when {
            streak <= 0 || daysSinceLastWorkout > 6 -> "dead"
            daysSinceLastWorkout <= 2 -> "full"
            daysSinceLastWorkout <= 4 -> "dim"
            else -> "smoke"
        }
        return Fire(
            tier = tier,
            emoji = when (tier) { "dead" -> "❄️"; "smoke" -> "🌫️"; else -> "🔥" },
            opacity = when (tier) { "full" -> 1f; "dim" -> 0.7f; "smoke" -> 0.5f; else -> 0.4f },
            streak = streak,
            daysSince = daysSinceLastWorkout,
        )
    }

    fun tooltip(fire: Fire): String = when (fire.tier) {
        "full" -> "${fire.streak} day streak — burning bright"
        "dim" -> "${fire.streak} day streak — keep it alive"
        "smoke" -> "${fire.streak} day streak — smoke is showing, train soon"
        else -> "Streak lost — share your workout today to relight the fire"
    }

    const val RECOVERY_SHARE_TEXT =
        "Just crushed my workout with PeakForm \uD83D\uDCAA — back at it today. Join me: https://try4ever.com/fitness"
    const val RECOVERY_TITLE = "Streak lost"
    const val RECOVERY_BODY =
        "Share your workout today to relight the fire. No verification — sharing is on the honor system."
    const val RECOVERY_CONFIRM = "I shared it — relight my fire 🔥"
    const val RECOVERY_TOAST = "Fire relit 🔥 — keep it going tomorrow!"

    /**
     * Recovery action port of bumpStreakAfterShare (L59630): stamp lastStreakRecoverAt and
     * insert a synthetic streak_recover workout so the calculator yields ≥1.
     */
    fun performRecovery(): WorkoutRecord {
        val today = ProState.todayLocal()
        val rec = WorkoutRecord(
            id = "streak-recover-${System.currentTimeMillis()}",
            date = today,
            type = "streak_recover",
            name = "Streak recovery share",
            dateCompleted = ProState.nowIso(),
            summary = WorkoutSummary(0.0, 0.0, 0),
        )
        ProState.data = ProState.data.copy(workouts = (ProState.data.workouts + rec).sortedBy { it.date })
        ProState.saveWorkoutData()
        ProPrefs.put(com.peakform.fitness.ui.Fa.appContext!!, "lastStreakRecoverAt", ProState.nowIso())
        ProState.notifyChanged()
        return rec
    }

    /** One honor recovery per calendar day (legacy guard). */
    fun recoveryUsedToday(): Boolean {
        val ctx = com.peakform.fitness.ui.Fa.appContext ?: return false
        val last = ProPrefs.get(ctx, "lastStreakRecoverAt") ?: return false
        return last.take(10) == ProState.todayLocal()
    }
}
