package com.peakform.fitness.core

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.peakform.fitness.MainActivity
import com.peakform.fitness.ProLog
import com.peakform.fitness.R
import java.util.concurrent.TimeUnit

/**
 * Notify — real system notifications (native-only improvement NA1) carrying the legacy
 * in-app nudges (retest N2, deload N3, period N4, backup N7) with "don't show today"
 * suppression (N5) and WorkManager periodic evaluation that survives reboot (N6).
 */
object Notify {
    const val CHANNEL_REMINDERS = "pro_reminders"
    const val WORK_NAME = "pro_reminder_scan"
    private const val NOTIF_ID_BASE = 4100

    /** Deep-link section routing per nudge key (legacy retest_reminder → Library). */
    private val routes = mapOf(
        "retest_reminder" to "library",
        "deload_notice" to "dashboard",
        "period_notice" to "dashboard",
        "backup_nudge" to "settings",
    )

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(CHANNEL_REMINDERS, "Training reminders", NotificationManager.IMPORTANCE_DEFAULT)
            ch.description = "1RM retests, deload notices, cycle phases and backup nudges"
            nm.createNotificationChannel(ch)
        }
    }

    fun hasPermission(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Legacy per-toast "Don't show today" suppression (L45782–45800). */
    fun suppressedToday(ctx: Context, key: String): Boolean {
        val stamp = ProPrefs.get(ctx, "p4_notif_suppressed_$key")
        return stamp != null && stamp == ProState.todayLocal()
    }

    fun suppressToday(ctx: Context, key: String) {
        ProPrefs.put(ctx, "p4_notif_suppressed_$key", ProState.todayLocal())
    }

    private fun toggleEnabled(ctx: Context, key: String): Boolean =
        ProPrefs.get(ctx, "p4_notif_$key") != "false"

    fun notify(ctx: Context, key: String, title: String, body: String) {
        if (!toggleEnabled(ctx, key) || suppressedToday(ctx, key) || !hasPermission(ctx)) return
        ensureChannels(ctx)
        val route = routes[key] ?: "dashboard"
        val intent = Intent(ctx, MainActivity::class.java).apply {
            setAction(Intent.ACTION_VIEW)
            putExtra("section", route)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            ctx, key.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif: Notification = NotificationCompat.Builder(ctx, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_pro)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID_BASE + key.hashCode().mod(1000), notif)
        } catch (e: Exception) {
            ProLog.e("NOTIFY", "notify failed: ${e.message}")
        }
    }

    /** Schedule the periodic scan (idempotent; safe to call on every boot). */
    fun schedule(ctx: Context) {
        val request = PeriodicWorkRequestBuilder<ReminderWorker>(6, TimeUnit.HOURS).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request,
        )
    }

    /** One evaluation pass — also used by the app at boot to surface in-app banners. */
    fun evaluateAll(ctx: Context) {
        ProState.init(ctx)
        if (!ProState.initialized) {
            kotlinx.coroutines.runBlocking { ProState.loadAll() }
        }
        val d = ProState.data
        // retest: calibrated lifts stale >6 weeks (L8880)
        if (com.peakform.fitness.core.Nudges.retestDue(d.exercises, System.currentTimeMillis())) {
            notify(ctx, "retest_reminder",
                "1RM retest due",
                "Your 1RM test for some lifts is over 6 weeks old. Consider re-testing.")
        }
        // deload notice: muscle deload status active (L36246)
        val deloadActive = d.user.muscleDeloadStatus?.values?.any { it.active } == true
        if (deloadActive) {
            notify(ctx, "deload_notice", "Deload week",
                "Fatigue has been running high — a deload week is recommended.")
        }
        // period phase notice (L36986): expected within 2 days
        val m = d.user.menstrual
        if (m.lastPeriodStart != null) {
            val startMs = ProState.utcDayMillis(m.lastPeriodStart!!)
            val dayInCycle = ((System.currentTimeMillis() - startMs) / (24 * 3600 * 1000)).toInt()
            val expectedIn = m.cycleLength - (dayInCycle % m.cycleLength)
            if (expectedIn in 0..2) {
                notify(ctx, "period_notice", "Period expected soon",
                    "Your cycle says your period is expected in $expectedIn day(s).")
            }
        }
        // backup nudge every 5 workouts (L41182)
        val completed = d.workouts.count { it.isCompleted }
        if (completed in 1..9999 && completed % 5 == 0 && ProPrefs.get(ctx, "p4_last_backup") == null) {
            notify(ctx, "backup_nudge", "Backup your data",
                "🎉 You've completed $completed workouts! Backup your data?")
        }
    }

    /** WorkManager worker — evaluates reminders even when the app UI is closed. */
    class ReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): Result = try {
            evaluateAll(applicationContext)
            Result.success()
        } catch (e: Exception) {
            ProLog.e("NOTIFY", "worker failed: ${e.message}")
            Result.retry()
        }
    }
}
