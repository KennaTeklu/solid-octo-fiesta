package com.peakform.fitness.core

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.permission.HealthPermission
import com.peakform.fitness.ProLog

/**
 * HealthConnect — native improvement NA9: writes completed workouts as ExerciseSession
 * records. Availability- and permission-gated; permission requested from Settings →
 * Integrations. No data is ever read.
 */
object HealthConnectBridge {

    const val WRITE_EXERCISE = "android.permission.health.WRITE_EXERCISE"

    fun availability(ctx: Context): Int =
        HealthConnectClient.getSdkStatus(ctx)

    fun isAvailable(ctx: Context): Boolean = availability(ctx) == HealthConnectClient.SDK_AVAILABLE

    suspend fun writeWorkoutSession(ctx: Context, w: WorkoutRecord): Boolean {
        if (!isAvailable(ctx)) return false
        val client = try { HealthConnectClient.getOrCreate(ctx) } catch (e: Exception) {
            ProLog.e("HC", "client get failed: ${e.message}"); return false
        }
        val granted = client.permissionController.getGrantedPermissions().contains(WRITE_EXERCISE)
        if (!granted) return false
        return try {
            val start = ProState.utcDayMillis(w.draftStartedAt ?: w.date).coerceAtLeast(0L)
            val end = ProState.utcDayMillis(w.dateCompleted ?: w.date)
            val duration = (end - start).coerceAtLeast(15L * 60 * 1000)
            val rec = ExerciseSessionRecord(
                startTime = java.time.Instant.ofEpochMilli(start),
                startZoneOffset = null,
                endTime = java.time.Instant.ofEpochMilli(start + duration),
                endZoneOffset = null,
                exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
                title = w.name.ifBlank { "Pro workout" },
            )
            client.insertRecords(listOf(rec))
            ProLog.i("HC", "session written for ${w.id}")
            true
        } catch (e: Exception) {
            ProLog.e("HC", "write failed: ${e.message}")
            false
        }
    }

}
