package com.peakform.fitness.core

/**
 * Nudges — pure decision logic for the dashboard data-safety banners.
 * Extracted from the UI so the athlete-simulation suite can test the exact
 * thresholds (audit #35: the legacy app nudged via p4_last_backup; v1.2 shipped
 * without any backup nudge — restored here as pure, testable logic).
 */
object Nudges {

    /** 14 days — after two weeks without a backup, start nudging. */
    const val BACKUP_STALE_MS: Long = 14L * 24 * 60 * 60 * 1000

    /** 56 days (8 weeks) — the engine's own "re-test your estimates" cadence. */
    const val RETEST_STALE_MS: Long = 56L * 24 * 60 * 60 * 1000

    /** A backup nudge needs something worth backing up. */
    const val MIN_WORKOUTS_FOR_NUDGE = 3

    /**
     * Show the "time for a backup" banner?
     * @param lastBackupIso the p4_last_backup pref (ISO timestamp), null if never backed up
     * @param workoutCount  completed workouts on record
     */
    fun shouldNudgeBackup(lastBackupIso: String?, workoutCount: Int, nowMillis: Long): Boolean {
        if (workoutCount < MIN_WORKOUTS_FOR_NUDGE) return false
        if (lastBackupIso.isNullOrBlank()) return true
        val t = ProState.utcDayMillis(lastBackupIso)
        if (t <= 0L) return true // unparsable stamp — treat as "never"
        return nowMillis - t >= BACKUP_STALE_MS
    }

    /**
     * Show the "re-test your 1RM estimates" banner? True when any lift with a
     * calibrated max carries a date older than RETEST_STALE_MS (or an unparsable
     * date is the only trace of an otherwise-unused calibration — ignored).
     */
    fun retestDue(exercises: Map<String, ExerciseRecord>, nowMillis: Long): Boolean {
        var anyDated = false
        val stale = exercises.values.any { rec ->
            val max = rec.tested1RM ?: rec.mu ?: return@any false
            if (max <= 0.0) return@any false
            val dateRaw = rec.testDate ?: rec.lastUpdate ?: return@any false
            val t = ProState.utcDayMillis(dateRaw)
            if (t <= 0L) return@any false
            anyDated = true
            nowMillis - t >= RETEST_STALE_MS
        }
        return stale && anyDated
    }
}
