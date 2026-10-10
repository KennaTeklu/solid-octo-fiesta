package com.peakform.fitness.engine

import com.peakform.fitness.ProLog
import com.peakform.fitness.core.ActualPerformance
import com.peakform.fitness.core.Aggregates
import com.peakform.fitness.core.BodyWeightEntry
import com.peakform.fitness.core.DeloadStatus
import com.peakform.fitness.core.ExerciseRecord
import com.peakform.fitness.core.FormulaProbabilities
import com.peakform.fitness.core.GeneticPotential
import com.peakform.fitness.core.HistoryEntry
import com.peakform.fitness.core.ProJson
import com.peakform.fitness.core.ProState
import com.peakform.fitness.core.WeeklyVolume
import com.peakform.fitness.core.WorkoutExercise
import com.peakform.fitness.core.WorkoutRecord
import com.peakform.fitness.core.WorkoutSummary
import kotlinx.serialization.json.JsonArray
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Sessions — translation of the legacy workout-logging / completion pipeline
 * (index-github.html): saveExercisePerformance (~L40323), updateExerciseHistory (~L40552),
 * updateExerciseLastPerformed / backfillLastPerformed (~L40629), removeExerciseFromWorkout /
 * skipExercise / performSkipExercise (~L40180), completeWorkout (~L41026), postWorkoutUpdate
 * (~L7662) with inferRestTime / updateBodyWeightEstimate / computeProgressionPotential /
 * computeNextWeight / muscle-EWMA / updateFormulaProbabilitiesFromWorkout, updateMuscleDeloadStatus
 * (L36269), updateWeeklyVolumeTracking (L39027), calculateTotalVolume / calculateAverageRPE
 * (L45475/45499), calculateAge (L37885), recomputeAggregates (L7436), cycle-phase helpers (L35275).
 *
 * Swal.fire dialogs and showNotification calls are converted into returned structured data
 * (CompletionResult / LogPerformanceResult / Notification) plus the string constants below —
 * every user-facing string is preserved verbatim.
 *
 * All state writes are IMMUTABLE: ProState.data is replaced via copy().
 */
object Sessions {

    // ---------------- result shapes ----------------

    data class Notification(val message: String, val type: String, val id: String?)

    data class CompletionResult(
        val workout: WorkoutRecord,          // completed record (with dateCompleted + summary + recommendedRest)
        val totalVolume: Double,
        val averageRPE: Double,
        val completedExercises: Int,
        val celebrationTitle: String,        // "🎉 Workout Complete!" / "🏆💪 Perfect Workout!" / "⚠️ Workout Completed (No Logs)"
        val celebrationLines: List<String>,  // streak/volume/RPE lines
        val nextWorkoutGenerated: Boolean,
        val backupNudge: Boolean,            // every 5 workouts nudge
        val notifications: List<Notification>,
        /** legacy steering modal buttons ("updateMomentumBias('bigger'|'stronger')") — UI renders these, then calls [applyMomentumTap]. */
        val steeringOptions: List<String> = listOf("bigger", "stronger"),
    )

    data class LogPerformanceResult(
        val ok: Boolean,
        val error: String?,
        val workout: WorkoutRecord?,
        val autoAdvancedTo: Int?,
        val allLogged: Boolean,
        val notification: Notification?,
    )

    // ---------------- user-facing strings (verbatim from legacy) ----------------

    // "No Active Workout" modal (completeWorkout guard — caller checks currentWorkout)
    const val NO_WORKOUT_TITLE = "No Active Workout"
    const val NO_WORKOUT_TEXT = "There is no workout to complete. Generate one first."

    // saveExercisePerformance validation strings (caller-side validation; kept verbatim for the UI)
    const val VAL_WEIGHT = "Please enter a valid weight"
    const val VAL_SETS = "Please select at least one completed set"
    const val VAL_ATTEMPTED_WEIGHT = "Please enter the attempted weight for the failed set"
    fun valDurationForSet(setIndex: Int) = "Please enter a valid duration for set ${setIndex + 1}"
    fun valRepsForSet(setIndex: Int) = "Please enter valid reps for set ${setIndex + 1}"
    fun valRepValueForSet(setIndex: Int) = "Please select a rep value for set ${setIndex + 1}"

    // "All exercises logged!" modal
    const val ALL_LOGGED_TITLE = "🎉 All exercises logged!"
    const val ALL_LOGGED_BODY = "Great job! You've completed all exercises in this workout."
    const val ALL_LOGGED_PROMPT = "Ready to finalise your workout and see your progress?"
    const val ALL_LOGGED_CONFIRM = "Complete Workout 🏆"
    const val ALL_LOGGED_CANCEL = "Later"

    // skip confirm (skipExercise)
    const val SKIP_TITLE = "Skip Exercise?"
    const val SKIP_CONFIRM = "Yes, skip it"
    const val SKIP_CANCEL = "Cancel"
    fun skipConfirmText(name: String) = "Are you sure you want to skip \"$name\"? Any unsaved data will be lost."

    // remove confirm (removeExerciseFromWorkout)
    const val REMOVE_TITLE = "Remove exercise?"
    const val REMOVE_CONFIRM = "Yes, remove"
    const val REMOVE_CANCEL = "Cancel"
    fun removeConfirmText(name: String) = "Are you sure you want to remove \"$name\" from this workout?"

    // unlogged-exercises confirm (completeWorkout — caller confirms BEFORE calling completeWorkout)
    const val UNLOGGED_TITLE = "⚠️ Unlogged Exercises"
    const val UNLOGGED_CONFIRM = "Yes, complete anyway"
    const val UNLOGGED_CANCEL = "No, go back"
    fun unloggedConfirmText(names: List<String>): String =
        "You have ${names.size} exercise(s) without logged performance:\n\n" +
            names.joinToString("\n") { "• $it" } +
            "\n\nComplete workout anyway?"

    // celebration modal
    const val CELEBRATION_HEADER = "📊 Your Performance"
    const val CELEBRATION_FOOTER = "Amazing effort! Keep building momentum. 💪"
    const val CELEBRATION_NEXT_BUTTON = "Next"

    // steering modal
    const val STEERING_TITLE = "🎯 Next time, I want to be..."
    const val STEERING_BIGGER = "💪 BIGGER"
    const val STEERING_STRONGER = "🔥 STRONGER"
    const val STEERING_HINT = "(This will influence your next workout)"
    const val STEERING_SKIP = "Skip"

    // backup nudge toast (every 5 workouts)
    const val BACKUP_NUDGE_TEXT = "🎉 You've completed 5 workouts! Backup your data?"
    const val BACKUP_EXPORT = "📦 Export now"
    const val BACKUP_LATER = "Remind later"

    // toasts that have no channel in the return types (UI can render these directly)
    fun skippedNotification(name: String) = Notification("$name skipped", "info", "exercise_skipped")
    fun removeNotification() = Notification("Exercise removed from workout", "info", null)

    // ---------------- logging (saveExercisePerformance L40323) ----------------

    /**
     * Translation of saveExercisePerformance (L40323) minus the DOM input parsing:
     * the caller validates input (see VAL_* constants) and builds [actual]; this function
     * applies the Kalman 1RM update / failure penalty, appends history, persists and
     * computes the auto-advance index.
     *
     * Legacy flow preserved: failure path → calculateFailureSeverity + applyFailurePenalty +
     * "Logged failure for X. Strength adjusted." (info); otherwise → updateRecursive1RM with the
     * best set. Then updateExerciseHistory + updateExerciseLastPerformed, immediate persistence
     * (performSave ≙ force-save + workoutEmergencyBackup v2), auto-advance to the next
     * unfinished exercise, and the "All exercises logged!" detection (title kept verbatim;
     * see [allLoggedLines] for the modal body the UI should render).
     *
     * Note: legacy removed draft_<workoutId>_<exId> from localStorage — native drafts are
     * in-memory (ProState.currentWorkout), so there is nothing to clear (no-op).
     */
    fun applyPerformance(
        workoutIn: WorkoutRecord,
        exIndex: Int,
        actual: ActualPerformance,
        notes: String,
    ): LogPerformanceResult {
        val exercise = workoutIn.exercises.getOrNull(exIndex)
            ?: return LogPerformanceResult(false, "No exercise at index $exIndex", null, null, false, null)

        // legacy: `if (isNaN(weight) || weight <= 0) → 'Please enter a valid weight'` — applies to
        // reps-based weighted lifts only; time-based and bodyweight/no-weight drills pass with 0.
        val weightRequired = actual.durations.isEmpty() && !exercise.noFatigue &&
            exercise.prescriptionType != "time" && (exercise.prescribed.weight ?: 0.0) > 0.0
        if (weightRequired && (actual.weight.isNaN() || actual.weight <= 0)) {
            return LogPerformanceResult(false, null, null, null, false, Notification(VAL_WEIGHT, "warning", null))
        }

        val effActual = if (notes.isNotEmpty()) actual.copy(notes = notes) else actual
        val updatedExercise = exercise.copy(actual = effActual, skipped = false)
        val updatedWorkout = workoutIn.copy(
            exercises = workoutIn.exercises.toMutableList().also { it[exIndex] = updatedExercise },
        )

        if (effActual.failure) {
            // legacy failure path: attemptedWeight (validated by caller) + severity penalty
            val attemptedWeight = effActual.attemptedWeight ?: effActual.weight
            val severity = calculateFailureSeverity(updatedExercise)
            applyFailurePenalty(updatedExercise.id, attemptedWeight, severity)
        } else {
            // legacy: updateRecursive1RM(exercise.id, weight, bestReps, rpeArray[0])
            if (effActual.reps.isNotEmpty() && effActual.weight > 0) {
                OneRm.updateRecursive(updatedExercise.id, effActual.weight, effActual.reps.max(), effActual.firstRPE)
            }
        }

        updateExerciseHistory(updatedExercise.id, effActual, updatedExercise.prescriptionType, updatedExercise.muscleGroup)
        updateExerciseLastPerformed(updatedExercise.id)

        // draft_<workoutId>_<exId> removal — in-memory drafts, nothing to clear (no-op)

        // CRITICAL PERSISTENCE: force immediate save (bypass debounce) + emergency backup
        ProState.currentWorkout = updatedWorkout
        ProState.performSave()       // ≙ saveCurrentWorkoutToStorage + workoutEmergencyBackup v2
        ProState.saveWorkoutData()

        // auto-advance to the next unfinished exercise (legacy findNextUnfinished)
        val nextIndex = findNextUnfinished(updatedWorkout, exIndex)
        val allLogged = updatedWorkout.exercises.all { it.actual != null || it.skipped }

        val notification = when {
            effActual.failure -> Notification("Logged failure for ${updatedExercise.name}. Strength adjusted.", "info", null)
            allLogged -> Notification(ALL_LOGGED_TITLE, "success", "all_logged")
            else -> Notification("${updatedExercise.name} logged successfully!", "success", null)
        }
        ProState.notifyChanged()
        return LogPerformanceResult(
            ok = true,
            error = null,
            workout = updatedWorkout,
            autoAdvancedTo = if (nextIndex == -1) null else nextIndex,
            allLogged = allLogged,
            notification = notification,
        )
    }

    // ---------------- history (updateExerciseHistory L40552) ----------------

    /**
     * Translation of updateExerciseHistory: builds the per-set HistoryEntry (rpe passed through
     * as array-or-scalar JsonElement), appends it to the exercise record, updates bestWeight /
     * bestReps (new-best strings preserved), bestTimes for time-based work, and last-trained.
     * Legacy P4.Motivation.newBest has no native channel — the verbatim string is logged.
     */
    fun updateExerciseHistory(
        exerciseId: String,
        actual: ActualPerformance,
        prescriptionType: String,
        muscles: List<String>,
    ) {
        val presType = prescriptionType.ifBlank { "reps" }
        val record = ProState.data.exercises[exerciseId] ?: ExerciseRecord()
        var newRecord = record

        val totalReps = actual.reps.sum()
        val totalTime = actual.durations.sum()

        val historyEntry = HistoryEntry(
            date = ProState.nowIso(),
            weight = actual.weight,
            sets = actual.sets,
            rpe = actual.rpe,                       // array or scalar — pass through
            notes = actual.notes,
            prescriptionType = presType,
            muscles = muscles.toList(),             // store a copy
            firstRPE = actual.firstRPE,
            restEstimate = actual.restEstimate,
        ).let { entry ->
            if (presType == "time") {
                // Time-based: durations per set; volume = total seconds under tension
                entry.copy(durations = actual.durations, totalTime = totalTime, volume = totalTime)
            } else {
                // Reps-based (including AMRAP): volume = weight * total reps
                entry.copy(reps = actual.reps, volume = actual.weight * totalReps)
            }
        }

        // best weight / reps updates (only for reps-based, matching legacy)
        if (presType != "time" && actual.reps.isNotEmpty()) {
            val maxReps = actual.reps.max()
            val prevBestWeight = record.bestWeight
            var p5NewBestTxt = ""
            if (actual.weight > (prevBestWeight ?: 0.0)) {
                p5NewBestTxt = "${ProJson.formatNum(actual.weight)} lbs × ${ProJson.formatNum(maxReps)} reps"
                newRecord = newRecord.copy(bestWeight = actual.weight, bestReps = maxReps)
            } else if (prevBestWeight != null && actual.weight == prevBestWeight) {
                val currentBestReps = record.bestReps ?: 0.0
                if (maxReps > currentBestReps) {
                    p5NewBestTxt = "${ProJson.formatNum(maxReps)} reps @ ${ProJson.formatNum(actual.weight)} lbs"
                    newRecord = newRecord.copy(bestReps = maxReps)
                }
            }
            if (p5NewBestTxt.isNotEmpty()) {
                // legacy: P4.Motivation.newBest(exercise.name, p5NewBestTxt) — verbatim string preserved
                ProLog.i("SESSIONS", "newBest: $p5NewBestTxt")
            }
        }

        // push to history
        newRecord = newRecord.copy(history = newRecord.history + historyEntry)

        // time-based: optionally track best total time at a given weight
        if (presType == "time") {
            val key = ProJson.formatNum(actual.weight)
            val currentBest = newRecord.bestTimes[key]
            if (currentBest == null || totalTime > currentBest) {
                newRecord = newRecord.copy(bestTimes = newRecord.bestTimes + (key to totalTime))
            }
        }

        ProState.data = ProState.data.copy(exercises = ProState.data.exercises + (exerciseId to newRecord))

        // legacy updated the muscleLastTrained map here; that map lives inside Fatigue in the
        // native app — refresh it from history.
        Fatigue.calculateMuscleLastTrained()

        ProState.saveWorkoutData()
    }

    /** Translation of updateExerciseLastPerformed (L40629) — default date = now. */
    private fun updateExerciseLastPerformed(exerciseId: String) {
        val record = ProState.data.exercises[exerciseId] ?: ExerciseRecord()
        ProState.data = ProState.data.copy(
            exercises = ProState.data.exercises + (exerciseId to record.copy(lastPerformed = ProState.nowIso())),
        )
    }

    /** Translation of backfillLastPerformed (L40646) — lastPerformed from newest history entry; no history → cleared. */
    fun backfillLastPerformed() {
        val updated = ProState.data.exercises.mapValues { (_, record) ->
            if (record.history.isNotEmpty()) {
                var latestMs = 0L
                record.history.forEach { entry ->
                    val ms = parseDateMs(entry.date) ?: return@forEach
                    if (ms > latestMs) latestMs = ms
                }
                record.copy(lastPerformed = msToIso(latestMs))
            } else {
                record.copy(lastPerformed = null) // legacy `delete record.lastPerformed` — "never done"
            }
        }
        ProState.data = ProState.data.copy(exercises = updated)
        ProState.saveWorkoutData()
    }

    // ---------------- skip / remove (L40180 / L40204) ----------------

    /**
     * Translation of performSkipExercise (the Swal confirm is the caller's — see SKIP_* strings).
     * Marks the exercise skipped with actual = { notes: "Skipped - <reason>" } and persists.
     * Legacy toast `${exercise.name} skipped` (info, id exercise_skipped) → [skippedNotification].
     */
    fun skipExercise(workoutIn: WorkoutRecord, exIndex: Int, reason: String): WorkoutRecord {
        val exercise = workoutIn.exercises.getOrNull(exIndex) ?: return workoutIn
        val notes = reason.ifBlank { "No reason given" }
        val updatedExercise = exercise.copy(
            skipped = true,
            actual = ActualPerformance(notes = "Skipped - $notes"),
        )
        val updated = workoutIn.copy(
            exercises = workoutIn.exercises.toMutableList().also { it[exIndex] = updatedExercise },
        )
        // draft clearing — no-op (in-memory drafts)
        ProState.currentWorkout = updated
        ProState.saveCurrentWorkoutToStorage()
        ProState.notifyChanged()
        ProLog.i("SESSIONS", "${exercise.name} skipped")
        return updated
    }

    /**
     * Translation of removeExerciseFromWorkout (splice at index + save).
     * Confirm dialog is the caller's (see REMOVE_* strings); toast → [removeNotification].
     */
    fun removeExerciseFromWorkout(workoutIn: WorkoutRecord, exIndex: Int): WorkoutRecord {
        if (exIndex !in workoutIn.exercises.indices) return workoutIn
        val updated = workoutIn.copy(
            exercises = workoutIn.exercises.toMutableList().also { it.removeAt(exIndex) },
        )
        ProState.currentWorkout = updated
        ProState.saveCurrentWorkoutToStorage()
        ProState.notifyChanged()
        ProLog.i("SESSIONS", "Exercise removed from workout")
        return updated
    }

    /** Names of unlogged exercises for the confirm modal (legacy unlogged list in completeWorkout). */
    fun unloggedExercises(workout: WorkoutRecord): List<String> =
        workout.exercises.filter { it.actual == null && !it.skipped }.map { it.name }

    // ---------------- completeWorkout (L41026) ----------------

    /**
     * Translation of completeWorkout. The legacy "No Active Workout" guard and the
     * "⚠️ Unlogged Exercises" confirm happen in the CALLER (use [unloggedExercises] +
     * UNLOGGED_* strings); this function assumes the user confirmed.
     *
     * Flow: activeDates default [today] → dateCompleted → summary{totalVolume, averageRPE (1 decimal
     * or 0), completedExercises} → recommendedRest via [Stats.calculateRecommendedRestDays] → push to
     * ProState.data.workouts → [postWorkoutUpdate] (which now carries the legacy
     * updateFatigueAfterWorkout/updateRecentExercises calls, since the native Fatigue API is
     * per-exercise) → save → clear saved workout + emergency backup → optional next-workout
     * generation ([Generator.performGenerateWorkout]) → celebration data + steering options +
     * "Workout completed!" notification + backup nudge (workouts.size % 5 == 0).
     *
     * Legacy backend sync (GAS payload / syncTrialWorkoutCount) has no native equivalent — omitted.
     */
    fun completeWorkout(workoutIn: WorkoutRecord, generateNext: Boolean): CompletionResult {
        var completed = workoutIn
        completed = if (completed.activeDates.isNotEmpty()) {
            completed.copy(activeDates = completed.activeDates.toList())
        } else {
            completed.copy(activeDates = listOf(utcToday()))
        }
        completed = completed.copy(dateCompleted = ProState.nowIso())

        val totalVolume = calculateTotalVolume(completed)
        val avgRPE = calculateAverageRPE(completed)
        val completedCount = completed.exercises.count { it.actual != null && !it.skipped }
        val totalExercises = completed.exercises.size

        completed = completed.copy(
            summary = WorkoutSummary(
                totalVolume = totalVolume,
                averageRPE = avgRPE ?: 0.0,   // legacy: avgRPE !== null ? avgRPE : 0
                completedExercises = completedCount,
            ),
        )
        val recommendedRest = Stats.calculateRecommendedRestDays(completed)
        completed = completed.copy(recommendedRest = recommendedRest)

        ProState.data = ProState.data.copy(workouts = ProState.data.workouts + completed)
        postWorkoutUpdate(completed)

        // legacy: updateFatigueAfterWorkout(completedWorkout) + updateRecentExercises(completedWorkout)
        // are handled per-exercise inside postWorkoutUpdate (native Fatigue API is per-exercise).

        ProState.saveWorkoutData()
        // clearSavedWorkout + emergency backup removal
        ProState.currentWorkout = null
        ProState.saveWorkoutData()
        ProState.clearEmergencyBackup()
        // draft_<id>_* cleanup — in-memory drafts, nothing to clear (no-op)

        var nextGenerated = false
        if (generateNext) {
            Generator.performGenerateWorkout(null)
            nextGenerated = true
        }
        // legacy updateHeaderNameAndStreak() — UI concern.

        // 🎉 celebratory stats (legacy Swal #1)
        val streak = Stats.calculateStreak()
        val totalWorkouts = ProState.data.workouts.size
        val formattedVolume = Stats.formatNumber(totalVolume)
        val rpeDisplay = if (avgRPE != null && avgRPE != 0.0) "${fmt(avgRPE)}/10" else "N/A"
        val celebrationTitle = when {
            completedCount == totalExercises -> "🏆💪 Perfect Workout!"
            completedCount == 0 -> "⚠️ Workout Completed (No Logs)"
            else -> "🎉 Workout Complete!"
        }
        val celebrationLines = listOf(
            "✅ $completedCount / $totalExercises exercises logged",
            "🏋️ Total volume: $formattedVolume lbs",
            "📈 Average RPE: $rpeDisplay",
            "🔥 Current streak: $streak day${if (streak != 1) "s" else ""}",
            "📅 Total workouts: $totalWorkouts",
            "🎯 Recommended rest: ${fmt(recommendedRest)} day${if (recommendedRest != 1.0) "s" else ""}",
        )

        val backupNudge = totalWorkouts % 5 == 0
        ProState.notifyChanged()
        return CompletionResult(
            workout = completed,
            totalVolume = totalVolume,
            averageRPE = avgRPE ?: 0.0,
            completedExercises = completedCount,
            celebrationTitle = celebrationTitle,
            celebrationLines = celebrationLines,
            nextWorkoutGenerated = nextGenerated,
            backupNudge = backupNudge,
            notifications = listOf(
                Notification("Workout completed! $completedCount exercises logged.", "success", "workout_completed"),
            ),
        )
    }

    /**
     * Modal body for the "🎉 All exercises logged!" dialog (legacy computes logged/skipped counts
     * and a quick volume: weight*Σreps or Σdurations). Returns the bullet lines verbatim.
     */
    fun allLoggedLines(workout: WorkoutRecord): List<String> {
        val loggedCount = workout.exercises.count { it.actual != null && !it.skipped }
        val skippedCount = workout.exercises.count { it.skipped }
        var totalVolume = 0.0
        workout.exercises.forEach { ex ->
            val actual = ex.actual ?: return@forEach
            if (ex.skipped) return@forEach
            if (actual.reps.isNotEmpty()) {
                totalVolume += actual.weight * actual.reps.sum()
            } else if (actual.durations.isNotEmpty()) {
                totalVolume += actual.durations.sum()
            }
        }
        val lines = mutableListOf("✅ $loggedCount logged")
        if (skippedCount > 0) lines.add("⏭️ $skippedCount skipped")
        lines.add("🏋️ Total volume: ${Stats.formatNumber(totalVolume)} lbs")
        return lines
    }

    /**
     * Translation of updateMomentumBias (L8517) — legacy is only invoked from the completion-flow
     * steering buttons ('bigger' | 'stronger'). Decay → nudge (stronger +0.35 / bigger −0.35) →
     * clamp to −1..1 → persist.
     */
    fun applyMomentumTap(tapType: String) {
        val bias = ProState.data.user.momentumBias
        val decayed = bias.value * (1 - bias.decayRate)
        val nudge = if (tapType == "stronger") 0.35 else -0.35
        var newValue = decayed + nudge
        newValue = max(-1.0, min(1.0, newValue))
        ProState.data = ProState.data.copy(
            user = ProState.data.user.copy(
                momentumBias = bias.copy(value = newValue, lastUpdated = ProState.nowIso(), lastTap = tapType),
            ),
        )
        ProState.saveWorkoutData()
        ProLog.i("SESSIONS", "Momentum bias updated: $newValue ($tapType)")
    }

    // ---------------- postWorkoutUpdate (L7662) ----------------

    /**
     * Translation of postWorkoutUpdate: per-exercise fatigue + recent-exercises, aggregate
     * recompute, rest-time inference (Eq5, last 5 kept), coupling learning, body-weight estimate
     * (Eq6), next-weight for every known exercise (Eq9/Eq10), weekly volume tracking, muscle-group
     * EWMA volume, muscle deload status, Bayesian formula probabilities, save.
     */
    fun postWorkoutUpdate(workout: WorkoutRecord) {
        val phase = getCurrentCyclePhase(parseDateMs(workout.date) ?: System.currentTimeMillis())
        val phaseMult = getPhaseMultiplier(phase) // legacy `|| 1.0` (never 0)

        // fatigue per exercise (legacy called updateMuscleFatigue with avgReps; native Fatigue
        // packages the per-exercise stress in updateFatigueAfterWorkout) + recent-exercise list
        workout.exercises.forEach { ex ->
            if (ex.actual == null || ex.skipped) return@forEach
            Fatigue.updateFatigueAfterWorkout(ex, phaseMult)
            Fatigue.updateRecentExercises(ex)
        }

        recomputeAggregates()

        // Eq5: rest-time inference, keep last 5 estimates
        workout.exercises.forEach { ex ->
            if (ex.actual == null || ex.skipped) return@forEach
            val restEst = inferRestTime(ex) ?: return@forEach
            val record = ProState.data.exercises[ex.id] ?: ExerciseRecord()
            ProState.data = ProState.data.copy(
                exercises = ProState.data.exercises +
                    (ex.id to record.copy(restEstimates = (record.restEstimates + restEst).takeLast(5))),
            )
        }

        Fatigue.learnCouplingFromWorkout(workout.exercises)
        updateBodyWeightEstimate()

        // next recommended weight for every exercise record
        ProState.data.exercises.keys.toList().forEach { exId ->
            val next = computeNextWeight(exId) ?: return@forEach
            val record = ProState.data.exercises[exId] ?: return@forEach
            ProState.data = ProState.data.copy(
                exercises = ProState.data.exercises +
                    (exId to record.copy(nextWeight = next.weight, nextWeightConfidence = next.confidence)),
            )
        }

        updateWeeklyVolumeTracking(workout)

        // ----- Muscle-group EWMA volume tracking -----
        val alpha = 0.3 // smoothing factor (30% weight to current workout)
        var muscleEWMA: Map<String, Double> = ProState.data.user.muscleEWMA ?: emptyMap()

        // Decay all existing EWMAs based on days since last update
        val now = Date()
        val lastUpdateMs = ProState.data.user.lastEWMAUpdate?.let { parseDateMs(it) } ?: now.time
        val daysSinceLast = (now.time - lastUpdateMs) / (1000.0 * 60 * 60 * 24)
        if (daysSinceLast > 0) {
            val decayFactor = (1 - alpha).pow(daysSinceLast)
            muscleEWMA = muscleEWMA.mapValues { it.value * decayFactor }
        }

        // Volume per muscle for this workout (exercise volume split equally among target muscles)
        val volumeThisWorkout = mutableMapOf<String, Double>()
        workout.exercises.forEach { ex ->
            val actual = ex.actual ?: return@forEach
            if (ex.skipped) return@forEach
            val repsTotal = actual.reps.sum()
            val volume = actual.weight * repsTotal
            val muscles = ex.muscleGroup
            if (muscles.isEmpty()) return@forEach
            val perMuscleVolume = volume / muscles.size
            muscles.forEach { muscle ->
                volumeThisWorkout[muscle] = (volumeThisWorkout[muscle] ?: 0.0) + perMuscleVolume
            }
        }

        // EWMA update per muscle
        volumeThisWorkout.forEach { (muscle, vol) ->
            val oldVal = muscleEWMA[muscle] ?: 0.0
            muscleEWMA = muscleEWMA + (muscle to oldVal * (1 - alpha) + vol * alpha)
        }

        ProState.data = ProState.data.copy(
            user = ProState.data.user.copy(muscleEWMA = muscleEWMA, lastEWMAUpdate = ProState.nowIso()),
        )

        updateMuscleDeloadStatus(workout)
        // ----- Update Bayesian formula probabilities -----
        updateFormulaProbabilitiesFromWorkout(workout)
        ProState.saveWorkoutData()
        ProState.notifyChanged()
    }

    // ---------------- deload status (L36269) ----------------

    /**
     * Translation of updateMuscleDeloadStatus: for each muscle trained (actual && !skipped),
     * track consecutive sessions with coupled fatigue ≥ 0.6; 3 in a row activates the deload,
     * one low-fatigue session deactivates it.
     */
    fun updateMuscleDeloadStatus(workout: WorkoutRecord) {
        val musclesTrained = LinkedHashSet<String>()
        workout.exercises.forEach { ex ->
            if (ex.actual != null && !ex.skipped) ex.muscleGroup.forEach { musclesTrained.add(it) }
        }
        val current = ProState.data.user.muscleDeloadStatus ?: emptyMap()
        val newMap = current.toMutableMap()
        for (muscle in musclesTrained) {
            val fatigue = Fatigue.getCoupledFatigue(muscle)
            val status = newMap[muscle] ?: DeloadStatus(consecutiveHighFatigue = 0, active = false)
            newMap[muscle] = if (fatigue >= 0.6) {
                val streak = status.consecutiveHighFatigue + 1
                DeloadStatus(consecutiveHighFatigue = streak, active = if (streak >= 3) true else status.active)
            } else {
                // Deactivate deload after one low-fatigue session
                DeloadStatus(consecutiveHighFatigue = 0, active = false)
            }
        }
        ProState.data = ProState.data.copy(user = ProState.data.user.copy(muscleDeloadStatus = newMap))
        ProState.saveWorkoutData()
    }

    // ---------------- weekly volume (L39027) ----------------

    /**
     * Translation of updateWeeklyVolumeTracking: ISO week key ("2025-W14") of the workout date,
     * add/merge into user.weeklyVolumes, sort by weekKey descending, keep the last 4 weeks, save.
     */
    fun updateWeeklyVolumeTracking(workout: WorkoutRecord) {
        // 1. ISO week number of the workout date (Monday as first day of week)
        val workoutMs = parseDateMs(workout.date) ?: System.currentTimeMillis()
        val weekKey = getISOWeekKey(workoutMs) // e.g., "2025-W14"

        // 2. Total volume of this workout (already computed in summary)
        val workoutVolume = workout.summary?.totalVolume ?: 0.0

        // 4. Find existing entry for this week / 5. sort descending / 6. keep last 4
        val entries = ProState.data.user.weeklyVolumes.toMutableList()
        val existingIndex = entries.indexOfFirst { it.weekKey == weekKey }
        if (existingIndex != -1) {
            entries[existingIndex] = entries[existingIndex].copy(totalVolume = entries[existingIndex].totalVolume + workoutVolume)
        } else {
            entries.add(WeeklyVolume(weekKey = weekKey, totalVolume = workoutVolume))
        }
        val sorted = entries.sortedByDescending { it.weekKey }
        val trimmed = if (sorted.size > 4) sorted.take(4) else sorted

        ProState.data = ProState.data.copy(user = ProState.data.user.copy(weeklyVolumes = trimmed))
        ProState.saveWorkoutData()
    }

    /** Translation of getISOWeekKey (L39072) — ISO 8601 week key, computed on a UTC calendar. */
    private fun getISOWeekKey(dateMs: Long): String {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US)
        cal.timeInMillis = dateMs
        // Nearest Thursday: current date + 4 - current day number (Mon=1, Sun=7)
        val dayNum = if (cal.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) 7 else cal.get(Calendar.DAY_OF_WEEK)
        cal.add(Calendar.DAY_OF_MONTH, 4 - dayNum)
        val year = cal.get(Calendar.YEAR)
        val firstThursday = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US)
        firstThursday.clear()
        firstThursday.set(year, Calendar.JANUARY, 1)
        val firstThursdayDay = if (firstThursday.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) 7 else firstThursday.get(Calendar.DAY_OF_WEEK)
        val weekNumber = ceil(((cal.timeInMillis - firstThursday.timeInMillis) / 86400000.0 + firstThursdayDay) / 7).toInt()
        return "$year-W${weekNumber.toString().padStart(2, '0')}"
    }

    // ---------------- volumes / RPE (L45475 / L45499) ----------------

    /** Translation of calculateTotalVolume: Σ calculateVolume(actual) over logged exercises. */
    fun calculateTotalVolume(workout: WorkoutRecord): Double =
        workout.exercises.fold(0.0) { total, ex ->
            val actual = ex.actual
            if (actual != null && !ex.skipped) total + calculateVolume(actual) else total
        }

    /** Legacy calculateVolume: weight * sets * avgReps (0 when no reps — includes time-based). */
    private fun calculateVolume(performance: ActualPerformance): Double {
        if (performance.reps.isEmpty()) return 0.0
        val avgReps = performance.reps.sum() / performance.reps.size
        return performance.weight * performance.sets * avgReps
    }

    /**
     * Translation of calculateAverageRPE: array rpe → sum elements / count elements;
     * scalar rpe → sum / count once. Returns null when no valid RPE data; rounds to 1 decimal.
     * (Legacy `Array.isArray` ⇔ `rpe is JsonArray`; scalars are read via rpeList.firstOrNull().)
     */
    private fun calculateAverageRPE(workout: WorkoutRecord): Double? {
        var totalRPE = 0.0
        var count = 0
        workout.exercises.forEach { ex ->
            val actual = ex.actual ?: return@forEach
            if (ex.skipped) return@forEach
            val rpe = actual.rpe ?: return@forEach
            if (rpe is JsonArray) {
                actual.rpeList.forEach { totalRPE += it; count++ }
            } else {
                val parsed = actual.rpeList.firstOrNull()
                if (parsed != null) {
                    totalRPE += parsed
                    count++
                }
            }
        }
        if (count == 0) return null
        return round(totalRPE / count * 10) / 10 // one decimal place, e.g., 7.3
    }

    // ---------------- age (L37885) ----------------

    /** Translation of calculateAge — null when no/invalid birthDate. */
    fun calculateAge(birthDate: String?): Int? {
        if (birthDate == null || birthDate.isBlank()) return null
        val birthMs = parseDateMs(birthDate) ?: return null
        val birth = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US)
        birth.timeInMillis = birthMs
        val today = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US)
        var age = today.get(Calendar.YEAR) - birth.get(Calendar.YEAR)
        val m = today.get(Calendar.MONTH) - birth.get(Calendar.MONTH)
        if (m < 0 || (m == 0 && today.get(Calendar.DAY_OF_MONTH) < birth.get(Calendar.DAY_OF_MONTH))) age--
        return age
    }

    // ---------------- aggregates (L7436) ----------------

    /**
     * Translation of recomputeAggregates: totalVolumeLast7Days, avgRPELast7Days (per-set average,
     * default 5), volumeByMuscleLast60Days. Conditioning/stretch work (noFatigue) stays out of
     * volume & RPE aggregates.
     */
    fun recomputeAggregates() {
        val now = System.currentTimeMillis()
        val sevenDaysAgo = calendarDaysAgoMs(7)
        val sixtyDaysAgo = calendarDaysAgoMs(60)

        var totalVolume7 = 0.0
        var totalRPE7 = 0.0
        var setCount7 = 0
        val volumeByMuscle60 = mutableMapOf<String, Double>()

        ProState.data.workouts.forEach { workout ->
            val wMs = parseDateMs(workout.date) ?: return@forEach
            val isRecent7 = wMs >= sevenDaysAgo
            val isRecent60 = wMs >= sixtyDaysAgo

            workout.exercises.forEach { ex ->
                val actual = ex.actual ?: return@forEach
                if (ex.skipped) return@forEach
                if (ex.noFatigue) return@forEach
                val repsTotal = actual.reps.sum()
                val volume = actual.weight * repsTotal
                if (isRecent7) {
                    totalVolume7 += volume
                    val rpe = actual.rpe
                    if (rpe != null) {
                        if (rpe is JsonArray) {
                            actual.rpeList.forEach { totalRPE7 += it; setCount7++ }
                        } else {
                            totalRPE7 += (actual.rpeList.firstOrNull() ?: 0.0) * actual.sets
                            setCount7 += actual.sets
                        }
                    }
                }
                if (isRecent60) {
                    ex.muscleGroup.forEach { muscle ->
                        volumeByMuscle60[muscle] = (volumeByMuscle60[muscle] ?: 0.0) + volume
                    }
                }
            }
        }

        ProState.data = ProState.data.copy(
            user = ProState.data.user.copy(
                aggregates = Aggregates(
                    totalVolumeLast7Days = totalVolume7,
                    avgRPELast7Days = if (setCount7 > 0) totalRPE7 / setCount7 else 5.0,
                    volumeByMuscleLast60Days = volumeByMuscle60.toMap(),
                ),
            ),
        )
    }

    // ---------------- cycle phase (L35275 / L35287) ----------------

    /**
     * Translation of getCurrentCyclePhase — null when not female or no lastPeriodStart.
     * cycleDay = (daysSince % cycleLength) + 1; ≤5 menstrual, ≤13 follicular, ≤17 ovulatory, else luteal.
     */
    fun getCurrentCyclePhase(dateMs: Long = System.currentTimeMillis()): String? {
        val user = ProState.data.user
        if (user.gender != "female") return null
        val lastPeriodStart = user.menstrual.lastPeriodStart ?: return null
        val lastMs = parseDateMs(lastPeriodStart) ?: return null
        val daysSince = floor((dateMs - lastMs) / (1000.0 * 60 * 60 * 24)).toLong()
        val cycleLength = if (user.menstrual.cycleLength > 0) user.menstrual.cycleLength else 28
        val cycleDay = (daysSince % cycleLength) + 1
        return when {
            cycleDay <= 5 -> "menstrual"
            cycleDay <= 13 -> "follicular"
            cycleDay <= 17 -> "ovulatory"
            else -> "luteal"
        }
    }

    /** Translation of getPhaseMultiplier — null → 1.0. */
    fun getPhaseMultiplier(phase: String?): Double = when (phase) {
        null -> 1.0
        "menstrual" -> 0.6
        "follicular" -> 1.0
        "ovulatory" -> 1.05
        "luteal" -> 0.8
        else -> 1.0
    }

    // ---------------- Eq5: rest-time inference (L7486 / L7498) ----------------

    private val COMPOUND_KEYS = listOf("squat", "deadlift", "bench", "press", "row")

    private fun isCompound(exerciseId: String): Boolean = COMPOUND_KEYS.any { exerciseId.contains(it) }

    /** Eq5: estimate rest time from performance decay in a single exercise; clamp 30..300. */
    private fun inferRestTime(exercise: WorkoutExercise): Double? {
        val actual = exercise.actual ?: return null
        val reps = actual.reps
        if (reps.size < 2) return null
        val weight = actual.weight
        val r1 = reps.first()
        val rN = reps.last()
        if (r1 <= 0) return null
        val deltaR = max(1.0, r1 - rN)
        val avgRPE = if (actual.rpe is JsonArray) {
            val list = actual.rpeList
            if (list.isEmpty()) return null
            list.sum() / list.size
        } else {
            actual.rpeList.firstOrNull() ?: return null
        }
        val tau0 = if (isCompound(exercise.id)) 120.0 else 90.0
        val restEst = tau0 * (1 + 0.3 * deltaR / r1).pow(-1) * exp(0.1 * (avgRPE - 7))
        return min(300.0, max(30.0, restEst))
    }

    // ---------------- Eq6: body weight estimate (L7515) ----------------

    /** Eq6: update body weight estimate based on strength gains. */
    private fun updateBodyWeightEstimate() {
        var user = ProState.data.user
        val h = user.height ?: return
        val squat = ProState.data.exercises["squat"]?.mu ?: 0.0
        val bench = ProState.data.exercises["bench_press"]?.mu ?: 0.0
        val deadlift = ProState.data.exercises["deadlift"]?.mu ?: 0.0
        var s = 0.0
        var count = 0
        if (squat > 0) { s += squat; count++ }
        if (bench > 0) { s += bench; count++ }
        if (deadlift > 0) { s += deadlift; count++ }
        if (count == 0) return
        s /= count
        user = user.copy(strengthComposite = s)

        val wrist = 7.0
        val ankle = 9.0 // median inches (you can expand later)
        val maxLeanMass = (h * h * (wrist / PI) * (ankle / PI) * 0.5) / 100
        val sGenetic = 3.0 * maxLeanMass
        user = user.copy(geneticPotential = GeneticPotential(maxLeanMass = maxLeanMass, sGenetic = sGenetic))

        val firstWorkoutDate = ProState.data.workouts.firstOrNull()?.date
        val firstMs = firstWorkoutDate?.let { parseDateMs(it) }
        if (firstMs == null) {
            ProState.data = ProState.data.copy(user = user) // legacy persists strengthComposite/geneticPotential first
            return
        }
        val daysSinceStart = floor((System.currentTimeMillis() - firstMs) / (1000.0 * 60 * 60 * 24))

        var neuralScale = 1.0
        var neuralPhaseEnd = user.neuralPhaseEnd
        if (neuralPhaseEnd != null) {
            val neuralEndMs = parseDateMs(neuralPhaseEnd)
            if (neuralEndMs != null && System.currentTimeMillis() < neuralEndMs) {
                neuralScale = daysSinceStart / 56
            }
        } else {
            neuralPhaseEnd = msToIso(firstMs + 56L * 24 * 60 * 60 * 1000)
        }

        val bwGenetic = maxLeanMass * tanh(s / sGenetic) * neuralScale
        val bwInitial = user.bodyWeightHistory.firstOrNull { it.source == "entry" }?.weight ?: (user.weight ?: 150.0)
        val bwEst = bwGenetic + bwInitial * exp(-0.01 * daysSinceStart)

        val lastEst = user.bodyWeightHistory.lastOrNull { it.source == "estimate" }
        var bwFinal = bwEst
        if (lastEst != null) {
            bwFinal = 0.9 * lastEst.weight + 0.1 * bwEst
        }
        var bodyWeightHistory = user.bodyWeightHistory + BodyWeightEntry(
            date = ProState.nowIso(), weight = bwFinal, source = "estimate",
        )
        if (bodyWeightHistory.size > 100) bodyWeightHistory = bodyWeightHistory.drop(1)

        ProState.data = ProState.data.copy(
            user = user.copy(bodyWeightHistory = bodyWeightHistory, neuralPhaseEnd = neuralPhaseEnd),
        )
    }

    // ---------------- Eq9/Eq10: progression potential & next weight (L7563 / L7629) ----------------

    private data class LastSet(val weight: Double, val rpe: Double, val restEstimate: Double, val firstRPE: Double)
    private data class NextWeight(val weight: Double, val delta: Double, val confidence: Double)

    private fun getTargetReps(goal: String): Int = when (goal) {
        "hypertrophy" -> 10
        "strength" -> 5
        "endurance" -> 17
        "longevity" -> 12
        "balanced" -> 8
        else -> 8
    }

    /** Eq9: compute progression potential Φ for a given exercise. */
    private fun computeProgressionPotential(exerciseId: String, lastSet: LastSet): Double {
        val ex = ProState.data.exercises[exerciseId] ?: return 0.0
        val libraryEntry = Library.getExerciseById(exerciseId) ?: return 0.0
        val mu = ex.mu ?: return 0.0 // legacy would propagate NaN; null mu ⇒ no meaningful progression

        val w = lastSet.weight
        val rpe = lastSet.rpe
        val age = calculateAge(ProState.data.user.birthDate) ?: 30
        val gender = ProState.data.user.gender

        val primaryMuscles = libraryEntry.muscles
        val vol60 = ProState.data.user.aggregates?.volumeByMuscleLast60Days ?: emptyMap()
        var totalVol = 0.0
        primaryMuscles.forEach { m -> totalVol += vol60[m] ?: 0.0 }
        val csa = (totalVol / 60) * (1 - w / mu)
        val csaClamped = csa.coerceIn(0.0, 1.0)

        val pTk = 1 / (1 + exp(-3 * (w / mu - 0.6)))
        val aMyo = tanh(4 * w / mu)
        val ne = (w / mu) / (rpe / 10)
        val neClamped = ne.coerceIn(0.0, 1.0)

        var cf = 0.0
        if (lastSet.restEstimate > 0 && lastSet.firstRPE > 0) {
            cf = (lastSet.rpe - lastSet.firstRPE) / lastSet.restEstimate * (1 / (1 + exp(-0.5 * (age - 50))))
            cf = cf.coerceIn(0.0, 1.0)
        }

        var betaAs = 1 + 0.01 * (25 - age)
        if (age >= 60) betaAs = 1 + 0.01 * (25 - 60) - 0.02 * (age - 60)
        betaAs = betaAs.coerceIn(0.8, 1.2)
        if (gender == "female") betaAs -= 0.05

        var fatigueSum = 0.0
        primaryMuscles.forEach { m -> fatigueSum += Fatigue.getCoupledFatigue(m) }
        val fTotal = if (primaryMuscles.isNotEmpty()) fatigueSum / primaryMuscles.size else 0.0

        val hComp = (1 - exp(-0.01 * csaClamped)) / (1 + exp(-0.5 * pTk))
        val mComp = aMyo / (1 + 0.2 * fTotal)
        val nComp = neClamped / (1 + 0.3 * cf)
        val iComp = 0.05 * hComp * mComp

        var phi = hComp * mComp * nComp * betaAs + iComp
        val phase = getCurrentCyclePhase() // today's phase
        val phaseMult = getPhaseMultiplier(phase)
        phi *= phaseMult
        return phi.coerceIn(0.0, 1.0)
    }

    /** Eq10: compute next recommended weight for an exercise. */
    private fun computeNextWeight(exerciseId: String): NextWeight? {
        val ex = ProState.data.exercises[exerciseId] ?: return null
        if (Library.getExerciseById(exerciseId) == null) return null
        val mu = ex.mu ?: return null // legacy would produce NaN → nextWeight never usefully set

        val last = ex.history.lastOrNull { !it.skipped } ?: return null
        val w = last.weight
        val r = if (last.reps.isNotEmpty()) last.reps.max() else 0.0
        val rTarget = getTargetReps(ProState.data.user.goal).toDouble()

        val aggregates = ProState.data.user.aggregates
        val totalVol7 = aggregates?.totalVolumeLast7Days ?: 0.0
        val avgRPE7Raw = aggregates?.avgRPELast7Days ?: 0.0
        val avgRPE7 = if (avgRPE7Raw != 0.0) avgRPE7Raw else 5.0 // legacy `|| 5` (0 is falsy)
        val fd = (1 - exp(-0.01 * totalVol7)) * (avgRPE7 / 10)

        // For Φ we need a lastSet with restEstimate and firstRPE — scalar rpe via rpeList
        val rpeScalar = last.rpeList.firstOrNull() ?: 0.0
        val lastSet = LastSet(
            weight = w,
            rpe = rpeScalar,
            restEstimate = last.restEstimate ?: 0.0,
            firstRPE = last.firstRPE ?: rpeScalar,
        )
        val phi = computeProgressionPotential(exerciseId, lastSet)

        var deltaW = 5 * phi * (1 - w / mu) * tanh((r - rTarget) / rTarget) * exp(-0.5 * fd)
        deltaW = minOf(deltaW, 0.05 * w, 10.0)
        if (deltaW < 0) deltaW = 0.0
        val wNext = w + deltaW
        return NextWeight(weight = wNext, delta = deltaW, confidence = 2 * sqrt(ex.sigma2 ?: 0.0))
    }

    // ---------------- Bayesian formula probabilities (L7769) ----------------

    /**
     * Translation of updateFormulaProbabilitiesFromWorkout: squared errors of Epley / Brzycki /
     * Lombardi against the ground-truth 1RM (tested1RM, else Epley fallback) → likelihoods
     * exp(-err/totalSets) → normalized posterior. (Legacy successWeight/failWeight interpolation
     * is omitted — the native ExerciseRecord has no such fields.)
     */
    private fun updateFormulaProbabilitiesFromWorkout(workout: WorkoutRecord) {
        var probabilities = ProState.data.user.formulaProbabilities
            ?: FormulaProbabilities(epley = 1.0 / 3, brzycki = 1.0 / 3, lombardi = 1.0 / 3)
        var totalSets = 0
        var errEpley = 0.0
        var errBrzycki = 0.0
        var errLombardi = 0.0

        workout.exercises.forEach { ex ->
            val actual = ex.actual ?: return@forEach
            if (ex.skipped) return@forEach
            if (actual.weight > 0 && actual.reps.isNotEmpty()) {
                val weight = actual.weight
                val reps = actual.reps.max()
                // stored tested 1RM as ground truth, otherwise Epley fallback
                val record = ProState.data.exercises[ex.id]
                var true1RM = record?.tested1RM
                if (true1RM == null) {
                    true1RM = weight * (1 + reps / 30)
                }
                if (true1RM > 0) {
                    val epleyEst = weight * (1 + reps / 30)
                    val brzyckiEst = if (reps <= 10) weight * 36 / (37 - reps) else epleyEst
                    val lombardiEst = weight * reps.pow(0.1)
                    errEpley += ((epleyEst - true1RM) / true1RM).pow(2)
                    errBrzycki += ((brzyckiEst - true1RM) / true1RM).pow(2)
                    errLombardi += ((lombardiEst - true1RM) / true1RM).pow(2)
                    totalSets++
                }
            }
        }

        if (totalSets > 0) {
            // Convert errors to likelihoods (smaller error = higher likelihood)
            val likeEpley = exp(-errEpley / totalSets)
            val likeBrzycki = exp(-errBrzycki / totalSets)
            val likeLombardi = exp(-errLombardi / totalSets)
            val sumLikelihood = likeEpley + likeBrzycki + likeLombardi
            probabilities = FormulaProbabilities(
                epley = likeEpley / sumLikelihood,
                brzycki = likeBrzycki / sumLikelihood,
                lombardi = likeLombardi / sumLikelihood,
            )
            ProState.data = ProState.data.copy(user = ProState.data.user.copy(formulaProbabilities = probabilities))
            ProState.saveWorkoutData()
        }
    }

    // ---------------- failure severity & penalty (L46170 / L46200) ----------------

    /** Translation of calculateSystemicRecoveryFactor (L42213) — heuristic 0.5..1.0, safe default 0.8. */
    private fun calculateSystemicRecoveryFactor(): Double {
        return try {
            val now = System.currentTimeMillis()
            val sevenDaysAgo = calendarDaysAgoMs(7)

            val recentWorkouts = ProState.data.workouts.filter { (parseDateMs(it.date) ?: 0L) >= sevenDaysAgo }
            if (recentWorkouts.isEmpty()) return 1.0 // no fatigue

            var totalVolume = 0.0
            var totalSets = 0
            var rpeSum = 0.0
            var rpeCount = 0
            val userWeight = ProState.data.user.weight ?: 150.0

            recentWorkouts.forEach { w ->
                w.exercises.forEach { ex ->
                    val actual = ex.actual ?: return@forEach
                    if (ex.skipped) return@forEach
                    if (ex.prescriptionType == "time") {
                        totalVolume += actual.durations.sum()
                    } else {
                        if (actual.weight > 0 && actual.reps.isNotEmpty()) {
                            totalVolume += actual.weight * actual.reps.sum()
                        }
                    }
                    totalSets += actual.sets
                    val rpe = actual.rpe
                    if (rpe != null) {
                        if (rpe is JsonArray) {
                            actual.rpeList.forEach { rpeSum += it; rpeCount++ }
                        } else {
                            rpeSum += actual.rpeList.firstOrNull() ?: 0.0
                            rpeCount++
                        }
                    }
                }
            }

            val relativeVolume = totalVolume / userWeight
            val avgRPE = if (rpeCount > 0) rpeSum / rpeCount else 5.0

            // Fatigue index (heuristic, tunable)
            val fatigueFromFreq = min(50.0, recentWorkouts.size * 10.0)
            val fatigueFromVolume = min(30.0, relativeVolume / 500)
            val fatigueFromRPE = min(20.0, (avgRPE - 5) * 4)
            var totalFatigue = fatigueFromFreq + fatigueFromVolume + fatigueFromRPE

            totalFatigue = totalFatigue.coerceIn(0.0, 100.0)
            var factor = 1.0 - (totalFatigue / 200)
            factor = factor.coerceIn(0.5, 1.0)
            if (factor.isNaN()) 0.8 else factor
        } catch (e: Exception) {
            ProLog.w("SESSIONS", "calculateSystemicRecoveryFactor error: ${e.message}")
            0.8 // safe default
        }
    }

    /** Translation of calculateFailureSeverity (L46170). */
    private fun calculateFailureSeverity(exercise: WorkoutExercise): Double {
        var severity = 1.0
        val user = ProState.data.user

        if (user.experience == "advanced") severity *= 1.5
        else if (user.experience == "beginner") severity *= 0.7

        val phase = getCurrentCyclePhase()
        if (user.gender == "male") severity *= 1.2
        else if (user.gender == "female" && (phase == "luteal" || phase == "menstrual")) severity *= 0.8

        val systemicFatigue = 1 - calculateSystemicRecoveryFactor()
        severity *= (1 + systemicFatigue)

        val age = calculateAge(user.birthDate) ?: 30
        if (age < 30) severity *= 1.2
        else if (age > 60) severity *= 0.6

        val userWeight = user.weight ?: 150.0
        val weightFactor = 150 / userWeight
        severity *= min(1.5, max(0.5, weightFactor))

        val primaryMuscle = exercise.muscleGroup.firstOrNull()
        if (primaryMuscle != null && (user.muscleEWMA?.get(primaryMuscle) ?: 0.0) > 15000) {
            severity *= 0.8
        }

        return severity.coerceIn(0.2, 2.0)
    }

    /**
     * Translation of applyFailurePenalty (L46200) — mu drop preserved (the "Strength adjusted"
     * part). Legacy also stored originalMu/failedAtWeight/failedSeverity/failedDate on the record
     * and decremented user.muscleGroupStats; the native data model has no such fields, so those
     * branches are omitted.
     */
    private fun applyFailurePenalty(exerciseId: String, attemptedWeight: Double, severity: Double) {
        val record = ProState.data.exercises[exerciseId] ?: ExerciseRecord()
        val muDropPercent = min(0.3, 0.1 * severity)
        val newMu = record.mu?.let { it * (1 - muDropPercent) }
        if (newMu != null) {
            ProState.data = ProState.data.copy(
                exercises = ProState.data.exercises + (exerciseId to record.copy(mu = newMu)),
            )
        }
        ProLog.i("SESSIONS", "Failure penalty: $exerciseId @ ${ProJson.formatNum(attemptedWeight)} lbs, severity=$severity")
        ProState.saveWorkoutData()
    }

    // ---------------- small helpers ----------------

    private fun findNextUnfinished(workout: WorkoutRecord, startIndex: Int): Int {
        for (i in startIndex + 1 until workout.exercises.size) {
            if (workout.exercises[i].actual == null && !workout.exercises[i].skipped) return i
        }
        for (i in 0 until startIndex) {
            if (workout.exercises[i].actual == null && !workout.exercises[i].skipped) return i
        }
        return -1
    }

    /** legacy `new Date().toISOString().split('T')[0]` (UTC date). */
    private fun utcToday(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

    private fun msToIso(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

    /** Calendar-day-based "N days ago" (legacy setDate(now.getDate() - N)). */
    private fun calendarDaysAgoMs(days: Int): Long {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_MONTH, -days)
        return cal.timeInMillis
    }

    /** Parse the ISO shapes the legacy app produces (with/without millis/Z, or date-only), as UTC. */
    private fun parseDateMs(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSS",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd",
        )
        for (pattern in formats) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.US)
                sdf.isLenient = false
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                val parsed = sdf.parse(iso) ?: continue
                return parsed.time
            } catch (_: Exception) {
                // try next pattern
            }
        }
        return null
    }

    /** JS-style number rendering in templated strings (135.0 → "135", 132.5 → "132.5"). */
    private fun fmt(d: Double): String =
        if (d == floor(d) && d.isFinite()) d.toLong().toString() else d.toString()
}
