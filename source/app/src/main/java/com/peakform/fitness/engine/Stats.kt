package com.peakform.fitness.engine

import com.peakform.fitness.ProLog
import com.peakform.fitness.core.ActualPerformance
import com.peakform.fitness.core.LibraryExercise
import com.peakform.fitness.core.ProState
import com.peakform.fitness.core.WorkoutRecord
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Stats — native translation of the legacy dashboard/stats layer.
 *
 * Source: work/handoff/html-source/index-github.html
 *  - calculateStreak                    L45430
 *  - calculateRecommendedRestDays       L37895
 *  - updateStrengthForecast             L38015 / L37997 (SBD forecast + Wilks)
 *  - calculateOverallStrengthProgress   L38057
 *  - calculateSystemicRecoveryFactor    L42213
 *  - updateRecoverySection              L42263 (49-muscle weight map, 0.7/0.3 blend)
 *  - updateMuscleRecoveryDisplay        L42863 (per-muscle ready/soon/resting/never)
 *  - calculateProjectedEliteDate        L42477 (log-projection)
 *  - computeProgressionPotential        L7563  (Φ, used only by the elite projection)
 *  - calculateLongevityScore + helpers  L44053..L44945
 *  - updateLongevityScoreDisplay        L44946 (ring colors 80/60, status text)
 *  - updateNextWorkoutDate              L45533
 *  - updateDashboardChart               L38303 (composite 0.4/0.3/0.2/0.1)
 *  - P4.Rings.render                    L58162
 *  - formatNumber                       L45595
 *
 * Pure Kotlin (no Android imports). Deterministic: all "locale" formatting is pinned to Locale.US,
 * mirroring the legacy en-US toLocaleDateString calls.
 */
object Stats {

    // ------------------------------------------------------------------
    // Public data shapes (the UI depends on these signatures)
    // ------------------------------------------------------------------

    data class MuscleRecovery(
        val name: String,
        val display: String,
        val category: String,
        val agingRisk: String?,
        val lastTrainedDays: Double?,   // null = never trained
        val recoveryPct: Double,
        val restDays: Double,
        val status: String,             // ready | soon | resting | never
        val daysLeft: Double,
    )

    data class RecoveryReport(
        val overall: Int,
        val systemicPct: Double,
        val muscles: List<MuscleRecovery>,
        val categorySummary: Map<String, List<MuscleRecovery>>,
        val lastOverallRecovery: Int,
    )

    data class RingData(
        val id: String,
        val label: String,
        val value: Double,
        val max: Double,
        val target: String?,   // legacy txt suffix unit: "%" / "d" / null ("n/max" for week ring)
        val color: String?,    // legacy CSS var strings: var(--p4-ok)/var(--p4-warn)/var(--p4-bad)/var(--p4-surface-3)
        val section: String,   // legacy tap target (data-go)
    )

    data class CompositePoint(val timestamp: Long, val value: Double)

    data class ForecastRow(val lift: String, val est1RM: Double, val bodyweightRatio: Double)

    /**
     * Classic Wilks coefficient (SC8) — 500 × total / Σ(coeff·BW^k), BW in kg.
     * Gender-specific polynomial coefficients (Wilks 1995).
     */
    fun wilksScore(totalLbs: Double): Int {
        return try {
            val bwKg = bodyWeight() * 0.45359237
            if (bwKg <= 0) return 0
            val c = if (ProState.data.user.gender == "female") {
                doubleArrayOf(594.31747775582, -27.23842536447, 0.82112226871, -0.00930733913, 4.731582e-5, -9.054e-8)
            } else {
                doubleArrayOf(-216.0475144, 16.2606339, -0.002388645, -0.00113732, 7.01863e-6, -1.291e-8)
            }
            var denom = 0.0
            for (i in c.indices) denom += c[i] * Math.pow(bwKg, i.toDouble())
            if (denom <= 0) return 0
            (500.0 * (totalLbs * 0.45359237) / denom).toInt()
        } catch (_: Exception) { 0 }
    }

    data class LongevityScore(val total: Int, val sub: Map<String, Double>, val status: String, val color: String)

    data class AgingRiskRow(
        val muscle: String,
        val severity: String,
        val score: Double,
        val reason: String,
        val ideal: String,
        val current: String,
    )

    // Mirrors of legacy window.lastOverallRecovery / window.lastStrengthSlope
    @Volatile var lastOverallRecovery: Int = 0
        private set
    @Volatile var lastStrengthSlope: Double = 0.0
        private set

    private val DAY_MS = 86400000.0

    /** Parse a legacy ISO/date string to epoch millis (UTC), matching `new Date(iso)` closely enough for diffs. */
    private fun parseMs(iso: String): Long = ProState.utcDayMillis(iso)

    private fun bodyWeight(): Double {
        val user = ProState.data.user
        return if (user.bodyWeightHistory.isNotEmpty()) user.bodyWeightHistory.last().weight
        else (user.weight?.takeIf { it != 0.0 } ?: 150.0)
    }

    /** Legacy `user.weight || 150` (0 is falsy in JS). */
    private fun staticUserWeight(): Double = ProState.data.user.weight?.takeIf { it != 0.0 } ?: 150.0

    /** Legacy `w.recommendedRest || 2` (0 is falsy). */
    private fun restOr2(w: WorkoutRecord): Double = w.recommendedRest?.takeIf { it != 0.0 } ?: 2.0

    private fun plusYears(years: Int): Long {
        val cal = Calendar.getInstance()
        cal.add(Calendar.YEAR, years)
        return cal.timeInMillis
    }

    // ------------------------------------------------------------------
    // Streak (L45430)
    // ------------------------------------------------------------------

    /**
     * Consecutive-day chain: sorting workouts oldest→newest, the streak continues while the gap
     * between workouts is <= the previous workout's recommendedRest (default 2). A too-long gap
     * resets the chain to 1. Today counts toward the streak if it falls inside the last workout's
     * rest window and has no workout yet. Day diffs use UTC-day millis (ProState.utcDayMillis),
     * matching the legacy floor((b-a)/86400000) semantics on ISO timestamps.
     */
    fun calculateStreak(): Int {
        val workouts = ProState.data.workouts
        if (workouts.isEmpty()) return 0

        val sorted = workouts.sortedBy { parseMs(it.date) }

        var streak = 1 // start with the first workout
        var lastWorkout = sorted[0]
        var lastMs = parseMs(lastWorkout.date)

        for (i in 1 until sorted.size) {
            val current = sorted[i]
            val curMs = parseMs(current.date)

            val diffDays = floor((curMs - lastMs) / DAY_MS)

            val requiredRest = restOr2(lastWorkout)

            // If we worked out within required rest days (or same day), streak continues
            streak = if (diffDays <= requiredRest) streak + 1 else 1

            lastWorkout = current
            lastMs = curMs
        }

        // Check if today is within the rest window of the last workout
        val last = sorted.last()
        val todayMs = parseMs(ProState.nowIso())
        val daysSinceLast = floor((todayMs - parseMs(last.date)) / DAY_MS)
        val lastRest = restOr2(last)

        if (daysSinceLast <= lastRest) {
            // If today already has a workout, it's already counted (UTC-day comparison;
            // legacy compared local toDateString — differs only across UTC/local day edges)
            val hasWorkoutToday = sorted.any { parseMs(it.date) / 86400000L == todayMs / 86400000L }
            if (!hasWorkoutToday) streak++
        }

        return streak
    }

    // ------------------------------------------------------------------
    // Recommended rest days (L37895)
    // ------------------------------------------------------------------

    /**
     * Base 1 day + volume factor (every 5000 lbs of volume adds 0.5 days, capped +3)
     * + age factor (>50: 1.5, >40: 1.0, >30: 0.5) + gender factor (female: 0.3),
     * clamped 1..5 and rounded to the nearest 0.5.
     */
    fun calculateRecommendedRestDays(workout: WorkoutRecord): Double {
        val summary = workout.summary ?: return 1.0 // default 1 day

        val user = ProState.data.user
        val age = user.birthDate?.let { Sessions.calculateAge(it) }
        val gender = user.gender.ifBlank { "male" }

        // Volume is in lbs × total reps
        val volume = summary.totalVolume

        var volumeFactor = (volume / 5000.0) * 0.5
        volumeFactor = min(volumeFactor, 3.0)

        var ageFactor = 0.0
        if (age != null) {
            if (age > 50) ageFactor = 1.5
            else if (age > 40) ageFactor = 1.0
            else if (age > 30) ageFactor = 0.5
        }

        val genderFactor = if (gender == "female") 0.3 else 0.0

        var restDays = 1.0 + volumeFactor + ageFactor + genderFactor
        restDays = max(1.0, min(restDays, 5.0)) // clamp between 1 and 5 days

        // (legacy declares userWeight here but never uses it)

        return Math.round(restDays * 2) / 2.0 // round to nearest 0.5
    }

    // ------------------------------------------------------------------
    // Overall strength progress (L38057)
    // ------------------------------------------------------------------

    /** Brzycki (reps <= 10) or Epley (reps > 10). */
    private fun estimate1RMAdvanced(weight: Double, reps: Double): Double {
        if (!(weight > 0)) return 0.0
        return if (reps <= 10) weight * 36 / (37 - reps) else weight * (1 + reps / 30)
    }

    private val BW_NAME_REGEX =
        Regex("pull|push|dip|chin|plank|bodyweight|push-?up|pull-?up|squat|jump", RegexOption.IGNORE_CASE)

    private fun isBodyweightExercise(exerciseName: String, equipment: String?): Boolean {
        if (equipment == "bodyweight") return true
        if (BW_NAME_REGEX.containsMatchIn(exerciseName)) return true
        return false
    }

    /**
     * Average per-muscle 1RM gain %: for every muscle, collect (date, estimated1RM) from the
     * history of every library exercise targeting it, then gain = (last-first)/first on the
     * date-sorted series. Returns clamp(0..100, round(avgGain*100)).
     */
    fun calculateOverallStrengthProgress(): Double {
        val allMuscles = Library.allMuscleGroups()
        if (allMuscles.isEmpty()) return 0.0

        val userWeight = staticUserWeight()

        // Build muscle → list of exercises that target it (gym + bodyweight + customs;
        // legacy iterated ultimateExerciseLibrary only, ids are name.lowercase().\s→_)
        val muscleToExercises = LinkedHashMap<String, MutableList<LibraryExercise>>()
        for (muscle in allMuscles) muscleToExercises[muscle.name] = mutableListOf()
        for (ex in Library.allLibraryExercises(trainingMode = "mixed")) {
            if (ex.muscles.isEmpty()) continue
            for (muscle in ex.muscles) muscleToExercises[muscle]?.add(ex)
        }

        val muscleGains = mutableListOf<Double>()
        val muscleSlopes = mutableListOf<Double>() // optional, stored for future use
        val now = System.currentTimeMillis()
        val thirtyDaysAgo = now - 30.0 * DAY_MS

        for ((_, exercisesList) in muscleToExercises) {
            val allEntries = mutableListOf<Pair<Long, Double>>() // (date, estimated1RM)

            for (libEx in exercisesList) {
                val exRecord = ProState.data.exercises[libEx.id] ?: continue
                val isBodyweight = isBodyweightExercise(libEx.name, libEx.equipment)

                for (entry in exRecord.history) {
                    if (entry.skipped) continue
                    if (entry.reps.isEmpty()) continue

                    var effectiveWeight = entry.weight
                    if (effectiveWeight == 0.0 && isBodyweight) effectiveWeight = userWeight
                    if (!(effectiveWeight > 0)) continue

                    val bestReps = entry.reps.max()
                    if (!(bestReps > 0)) continue

                    val estimated1RM = estimate1RMAdvanced(effectiveWeight, bestReps)
                    allEntries.add(Pair(parseMs(entry.date), estimated1RM))
                }
            }

            if (allEntries.size < 2) continue

            allEntries.sortBy { it.first }
            val first = allEntries.first().second
            val last = allEntries.last().second
            if (!(first > 0)) continue

            val gain = (last - first) / first
            muscleGains.add(gain)

            // Slope over last 30 days (optional, for future use)
            val recentEntries = allEntries.filter { it.first >= thirtyDaysAgo }
            if (recentEntries.size >= 2) {
                val n = recentEntries.size
                val sumX = recentEntries.sumOf { it.first.toDouble() }
                val sumY = recentEntries.sumOf { it.second }
                val sumXY = recentEntries.sumOf { it.first.toDouble() * it.second }
                val sumX2 = recentEntries.sumOf { it.first.toDouble() * it.first }
                val denom = n * sumX2 - sumX * sumX
                if (denom != 0.0) {
                    muscleSlopes.add((n * sumXY - sumX * sumY) / denom)
                }
            }
        }

        if (muscleGains.isEmpty()) return 0.0

        val avgGain = muscleGains.sum() / muscleGains.size
        if (muscleSlopes.isNotEmpty()) {
            lastStrengthSlope = muscleSlopes.sum() / muscleSlopes.size
        }

        return min(100.0, max(0.0, Math.round(avgGain * 100).toDouble()))
    }

    // ------------------------------------------------------------------
    // Systemic recovery factor (L42213)
    // ------------------------------------------------------------------

    /**
     * 1.0 = fully recovered, 0.5 = fully fatigued.
     *
     * Source formula (NOTE: three separately capped components, NOT a single min()):
     *   fatigueFromFreq   = min(50, workoutsLast7Days * 10)
     *   fatigueFromVolume = min(30, relativeVolume / 500)      // relativeVolume = totalVolume/userWeight
     *   fatigueFromRPE    = min(20, (avgRPE - 5) * 4)
     *   totalFatigue      = clamp(0..100, sum); factor = 1 - totalFatigue/200; clamp 0.5..1.0
     */
    fun systemicRecoveryFactor(): Double {
        return try {
            val now = System.currentTimeMillis()
            val sevenDaysAgo = now - 7.0 * DAY_MS

            val recentWorkouts = ProState.data.workouts.filter { parseMs(it.date) >= sevenDaysAgo }
            if (recentWorkouts.isEmpty()) return 1.0 // no fatigue

            var totalVolume = 0.0
            var totalSets = 0.0
            var rpeSum = 0.0
            var rpeCount = 0
            val userWeight = staticUserWeight()

            for (w in recentWorkouts) {
                for (ex in w.exercises) {
                    val actual = ex.actual ?: continue
                    if (ex.skipped) continue
                    if (ex.prescriptionType == "time") {
                        if (actual.durations.isNotEmpty()) {
                            totalVolume += actual.durations.sum()
                        }
                    } else {
                        if (actual.weight != 0.0 && actual.reps.isNotEmpty()) {
                            val totalReps = actual.reps.sum()
                            totalVolume += actual.weight * totalReps
                        }
                    }
                    totalSets += actual.sets
                    val rpes = actual.rpeList // array or single number, already normalized
                    if (rpes.isNotEmpty()) {
                        rpeSum += rpes.sum()
                        rpeCount += rpes.size
                    }
                }
            }

            val relativeVolume = totalVolume / userWeight
            val avgRPE = if (rpeCount > 0) rpeSum / rpeCount else 5.0

            val fatigueFromFreq = min(50.0, recentWorkouts.size * 10.0)
            val fatigueFromVolume = min(30.0, relativeVolume / 500.0)
            val fatigueFromRPE = min(20.0, (avgRPE - 5.0) * 4.0)
            var totalFatigue = fatigueFromFreq + fatigueFromVolume + fatigueFromRPE

            // Clamp and convert to recovery factor (0.5 to 1.0)
            totalFatigue = min(100.0, max(0.0, totalFatigue))
            var factor = 1.0 - (totalFatigue / 200.0)
            factor = min(1.0, max(0.5, factor))

            if (factor.isNaN()) 0.8 else factor
        } catch (e: Exception) {
            ProLog.w("STATS", "systemicRecoveryFactor: ${e.message}")
            0.8 // safe default
        }
    }

    // ------------------------------------------------------------------
    // Recovery report (L42263 updateRecoverySection + L42863 per-muscle renderer)
    // ------------------------------------------------------------------

    /**
     * Per muscle: recovery% = min(100, daysSince/restDays*100);
     * status: daysSince>=restDays → "ready", daysSince>=restDays-1 → "soon", else "resting";
     * never trained → "never" (counts as 100% in the weighted average, matching legacy).
     *
     * overall = round(0.7 * muscleWeightedAvg + 0.3 * systemicPct) where the muscle average is
     * weighted by Library.muscleWeights (default 1.0).
     */
    fun recoveryReport(): RecoveryReport {
        // 1. Refresh last-trained timestamps (legacy calls applyFatigueDecay() upstream; Fatigue owns decay)
        Fatigue.calculateMuscleLastTrained()

        // 2. Systemic recovery factor (0–100)
        val systemicFactor = systemicRecoveryFactor()
        val systemicPercent = Math.round(systemicFactor * 100).toDouble()

        // 3. Weighted muscle recovery
        val allMuscles = Library.allMuscleGroups()
        var totalWeight = 0.0
        var weightedRecovery = 0.0
        val rows = mutableListOf<MuscleRecovery>()

        for (muscle in allMuscles) {
            val lastTrained = Fatigue.daysSinceTrained(muscle.name) // null = never trained
            val weight = Library.muscleWeights[muscle.name] ?: 1.0

            val recoveryPercent: Double
            val status: String
            val daysLeft: Double
            if (lastTrained == null) {
                recoveryPercent = 100.0 // assume ready
                status = "never"
                daysLeft = 0.0
            } else {
                recoveryPercent = min(100.0, (lastTrained / muscle.restDays) * 100.0)
                status = when {
                    lastTrained >= muscle.restDays -> "ready"
                    lastTrained >= muscle.restDays - 1.0 -> "soon"
                    else -> "resting"
                }
                daysLeft = muscle.restDays - lastTrained
            }

            weightedRecovery += weight * recoveryPercent
            totalWeight += weight
            rows.add(
                MuscleRecovery(
                    name = muscle.name,
                    display = muscle.display,
                    category = muscle.category,
                    agingRisk = muscle.agingRisk,
                    lastTrainedDays = lastTrained,
                    recoveryPct = recoveryPercent,
                    restDays = muscle.restDays,
                    status = status,
                    daysLeft = daysLeft,
                )
            )
        }

        val muscleAvg = if (totalWeight > 0) weightedRecovery / totalWeight else 100.0

        // 4. Combine muscle and systemic recovery (70% muscle, 30% systemic)
        val overallRecovery = Math.round(0.7 * muscleAvg + 0.3 * systemicPercent).toInt()
        lastOverallRecovery = overallRecovery

        // Category summary, sorted like the legacy per-category renderer:
        // never-trained first, then ready, then by days left (closest to ready first).
        fun legacySort(list: List<MuscleRecovery>): List<MuscleRecovery> = list.sortedWith(
            compareBy<MuscleRecovery>(
                { if (it.lastTrainedDays == null) 0 else 1 },
                { if (it.lastTrainedDays == null || it.status == "ready") 0 else 1 },
                { it.daysLeft },
            )
        )

        val categorySummary = LinkedHashMap<String, List<MuscleRecovery>>()
        for ((cat, list) in rows.groupBy { it.category }) {
            categorySummary[cat] = legacySort(list)
        }

        return RecoveryReport(
            overall = overallRecovery,
            systemicPct = systemicPercent,
            muscles = rows,
            categorySummary = categorySummary,
            lastOverallRecovery = overallRecovery,
        )
    }

    /** Overall readiness 0–100 (same computation the legacy dashboard gauge displays). */
    fun overallRecoveryPct(): Int = recoveryReport().overall

    // ------------------------------------------------------------------
    // P4 rings (L58162)
    // ------------------------------------------------------------------

    private fun ringColor(pct: Double): String =
        if (pct >= 80) "var(--p4-ok)"
        else if (pct >= 50) "var(--p4-warn)"
        else if (pct > 0) "var(--p4-bad)"
        else "var(--p4-surface-3)"

    /**
     * The 4 dashboard rings (data computation only; UI renders natively).
     *  - Recovery: window.lastOverallRecovery (0 until the first recoveryReport()).
     *  - 30-day fire: streak days, pct = min(100, streak/30*100).
     *  - This week: workouts in the last 7 days vs goal (legacy reads settings.preferredDays;
     *    the native model stores that list as settings.workoutDays), default goal 3.
     *  - Longevity: longevity score pct.
     * Tap targets (data-go): recovery → 'recovery', fire → 'progress', week → 'workout', longevity → 'progress'.
     */
    fun ringsData(): List<RingData> {
        val rec = lastOverallRecovery.coerceAtLeast(0)

        val streak = calculateStreak()
        val streakPct = min(100.0, Math.round(streak / 30.0 * 100.0).toDouble())

        var weekCount = 0
        var weekGoal = 3
        val cutoff = System.currentTimeMillis() - 7.0 * DAY_MS
        for (w in ProState.data.workouts) {
            if (parseMs(w.date) >= cutoff) weekCount++
        }
        val pref = ProState.data.user.settings.workoutDays
        if (pref.size >= 1 && pref.size <= 7) weekGoal = pref.size
        val weekPct = min(100.0, Math.round(weekCount / max(1, weekGoal).toDouble() * 100.0).toDouble())

        val lon = calculateLongevityScore().total

        return listOf(
            RingData("rec", "Recovery", rec.toDouble(), 100.0, "%", ringColor(rec.toDouble()), "recovery"),
            RingData("strk", "30-day fire", streak.toDouble(), 30.0, "d", ringColor(streakPct), "progress"),
            RingData("week", "This week", weekCount.toDouble(), weekGoal.toDouble(), null, ringColor(weekPct), "workout"),
            RingData("lon", "Longevity", lon.toDouble(), 100.0, "%", ringColor(min(100.0, lon.toDouble())), "progress"),
        )
    }

    // ------------------------------------------------------------------
    // Dashboard composite chart (L38303)
    // ------------------------------------------------------------------

    /**
     * Per completed workout: composite of min-max normalized strength + volume plus raw
     * consistency and RPE scores: 0.4*norm(strength) + 0.3*norm(volume) + 0.2*consistency + 0.1*rpeScore,
     * rounded. norm() includes 0 and 1 in the min/max search (legacy Math.min(...arr, 0)).
     * rangeWeeks: 4 → 28d, 8 → 56d, 12 → 84d (legacy default), null → all.
     */
    fun dashboardComposite(rangeWeeks: Int?): List<CompositePoint> {
        val cutoffDays = when (rangeWeeks) {
            null -> -1.0 // 'all'
            4 -> 28.0
            8 -> 56.0
            12 -> 84.0
            else -> 84.0 // legacy default range '12weeks'
        }

        val now = System.currentTimeMillis()
        var workouts = ProState.data.workouts.toMutableList()
        if (cutoffDays >= 0) {
            val cutoff = now - cutoffDays * DAY_MS
            workouts = workouts.filter { parseMs(it.date) >= cutoff }.toMutableList()
        }
        workouts.sortBy { parseMs(it.date) }

        if (workouts.isEmpty()) return emptyList()

        class Raw(val ts: Long, val strength: Double, val volume: Double, val consistency: Double, val rpeScore: Double)

        val raws = mutableListOf<Raw>()
        val allStrength = mutableListOf<Double>()
        val allVolume = mutableListOf<Double>()

        for ((idx, workout) in workouts.withIndex()) {
            val ts = parseMs(workout.date)

            // Strength: avg of best muscle-group 1RM (Brzycki <= 10 reps, else Epley)
            val muscleBest = LinkedHashMap<String, Double>()
            for (ex in workout.exercises) {
                val a = ex.actual ?: continue
                if (ex.skipped) continue
                if (!(a.weight > 0)) continue
                var reps = 0.0
                if (a.reps.isNotEmpty()) reps = a.reps.max()
                else if (a.durations.isNotEmpty()) reps = a.durations.max()
                if (reps == 0.0) continue
                val oneRM = if (reps <= 10) a.weight * 36 / (37 - reps) else a.weight * (1 + reps / 30)
                for (m in ex.muscleGroup) {
                    val cur = muscleBest[m]
                    if (cur == null || oneRM > cur) muscleBest[m] = oneRM
                }
            }
            val strength = if (muscleBest.isNotEmpty()) muscleBest.values.sum() / muscleBest.size else 0.0
            allStrength.add(strength)

            // Volume
            val volume = workout.summary?.totalVolume ?: 0.0
            allVolume.add(volume)

            // Consistency (inverse days since last workout in the filtered series)
            var consistency = 50.0
            if (idx > 0) {
                val days = (parseMs(workout.date) - parseMs(workouts[idx - 1].date)) / DAY_MS
                consistency = min(100.0, 100.0 / max(1.0, days))
            }

            // RPE (lower better); legacy: summary?.averageRPE || 7 → 0 counts as missing
            var avgRPE = workout.summary?.averageRPE ?: 7.0
            if (avgRPE == 0.0) avgRPE = 7.0
            val rpeScore = max(0.0, 100.0 - (avgRPE - 1.0) * 12.5)

            raws.add(Raw(ts, strength, volume, consistency, rpeScore))
        }

        // Normalize strength and volume across the selected range
        val minStrength = min(allStrength.minOrNull() ?: 0.0, 0.0)
        val maxStrength = max(allStrength.maxOrNull() ?: 1.0, 1.0)
        val minVolume = min(allVolume.minOrNull() ?: 0.0, 0.0)
        val maxVolume = max(allVolume.maxOrNull() ?: 1.0, 1.0)
        fun norm(v: Double, mn: Double, mx: Double): Double =
            if (mx == mn) 50.0 else ((v - mn) / (mx - mn)) * 100.0

        return raws.map { s ->
            val v = norm(s.strength, minStrength, maxStrength) * 0.4 +
                norm(s.volume, minVolume, maxVolume) * 0.3 +
                s.consistency * 0.2 +
                s.rpeScore * 0.1
            CompositePoint(timestamp = s.ts, value = Math.round(v).toDouble())
        }
    }

    // ------------------------------------------------------------------
    // Elite-date projection (L42477) + Φ (L7563)
    // ------------------------------------------------------------------

    internal data class EliteProjection(
        val medianMs: Long,
        val confidenceLowMs: Long,
        val confidenceHighMs: Long,
        val message: String,
        val total1RM: Double,
        val targetTotal: Double,
    )

    /**
     * Progression potential Φ for a set (L7563). Returns Double.NaN for inputs the legacy code
     * would have driven to NaN (missing mu/rpe, array-shaped firstRPE), so callers can skip them.
     */
    private fun computeProgressionPotential(exerciseId: String, a: ActualPerformance): Double {
        val ex = ProState.data.exercises[exerciseId] ?: return 0.0
        val exercise = Library.getExerciseById(exerciseId) ?: return 0.0

        val W = a.weight
        val mu = ex.mu ?: return Double.NaN
        val rpeRaw = a.rpe
        val rpe = when (rpeRaw) {
            null, is JsonNull -> return Double.NaN
            is JsonArray -> (rpeRaw.lastOrNull() as? JsonPrimitive)?.doubleOrNull ?: return Double.NaN
            is JsonPrimitive -> rpeRaw.doubleOrNull ?: return Double.NaN
            else -> return Double.NaN
        }
        // Legacy: firstRPE = actual.firstRPE || actual.rpe — an ARRAY there makes CF NaN → Φ NaN (set skipped).
        val firstRPE: Double? = a.firstRPE ?: when (rpeRaw) {
            is JsonPrimitive -> rpeRaw.doubleOrNull
            else -> null
        }

        val user = ProState.data.user
        val age = Sessions.calculateAge(user.birthDate) ?: 30
        val gender = user.gender

        val primaryMuscles = exercise.muscles
        var totalVol = 0.0
        val volByMuscle = user.aggregates?.volumeByMuscleLast60Days ?: emptyMap()
        for (m in primaryMuscles) totalVol += volByMuscle[m] ?: 0.0

        val CSA = (totalVol / 60.0) * (1.0 - W / mu)
        val CSAc = min(1.0, max(0.0, CSA))

        val PTK = 1.0 / (1.0 + exp(-3.0 * (W / mu - 0.6)))
        val Amyo = tanh(4.0 * W / mu)
        val NE = (W / mu) / (rpe / 10.0)
        val NEc = min(1.0, max(0.0, NE))

        var CF = 0.0
        val restEstimate = a.restEstimate ?: 0.0
        if (restEstimate > 0 && firstRPE != null) {
            CF = (rpe - firstRPE) / restEstimate * (1.0 / (1.0 + exp(-0.5 * (age - 50))))
            CF = min(1.0, max(0.0, CF))
        }

        var betaAs = 1.0 + 0.01 * (25.0 - age)
        if (age >= 60) betaAs = 1.0 + 0.01 * (25.0 - 60.0) - 0.02 * (age - 60.0)
        betaAs = min(1.2, max(0.8, betaAs))
        if (gender == "female") betaAs -= 0.05

        var fatigueSum = 0.0
        for (m in primaryMuscles) fatigueSum += Fatigue.getCoupledFatigue(m)
        val fTotal = if (primaryMuscles.isNotEmpty()) fatigueSum / primaryMuscles.size else 0.0

        val h = (1.0 - exp(-0.01 * CSAc)) / (1.0 + exp(-0.5 * PTK))
        val m = Amyo / (1.0 + 0.2 * fTotal)
        val n = NEc / (1.0 + 0.3 * CF)
        val i = 0.05 * h * m

        var phi = h * m * n * betaAs + i
        val phaseMult = Sessions.getPhaseMultiplier(Sessions.getCurrentCyclePhase())
        phi *= if (phaseMult != 0.0 && !phaseMult.isNaN()) phaseMult else 1.0
        return min(1.0, max(0.0, phi))
    }

    private fun eliteProjection(): EliteProjection {
        val user = ProState.data.user
        val workouts = ProState.data.workouts
        val exercises = ProState.data.exercises

        // If no workouts exist, show placeholder (+5 years)
        if (workouts.isEmpty()) {
            val p = plusYears(5)
            return EliteProjection(p, p, p, "Complete your first workout to see your projection! 💪", 0.0, 900.0)
        }

        // ----- 1. Current estimated total (SBD) – only count lifts with >= 3 history entries -----
        val keyLifts = listOf("squat", "bench_press", "deadlift")
        var total1RM = 0.0
        var count = 0
        val userWeight = bodyWeight()

        var suspicious = false
        for (lift in keyLifts) {
            val ex = exercises[lift] ?: continue
            val mu = ex.mu ?: continue
            if (mu <= 0) continue
            // Sanity: if mu > 3x bodyweight for a beginner/intermediate, it's likely corrupted
            if (mu > userWeight * 3 && (user.experience == "beginner" || user.experience == "intermediate")) {
                suspicious = true
            }
            if (ex.history.size >= 3) {
                total1RM += mu
                count++
            }
        }

        if (suspicious) {
            total1RM = if (user.strengthComposite != 0.0 && user.strengthComposite < 800) user.strengthComposite else 0.0
            count = 0
        } else if (count == 0 && user.strengthComposite != 0.0) {
            total1RM = user.strengthComposite
        } else if (count > 0) {
            total1RM = total1RM / count * 3
        }

        // Cap unrealistic total
        if (total1RM > userWeight * 9) total1RM = 0.0

        // Not enough data → placeholder
        if (count == 0 || total1RM == 0.0) {
            val p = plusYears(5)
            return EliteProjection(
                p, p, p,
                "Log at least 3 sessions of squat, bench, and deadlift to see your projection.",
                0.0, 900.0,
            )
        }

        // ----- 2. Target elite total (gender-specific) -----
        val bodyWeight = userWeight
        val isFemale = user.gender == "female"
        val targetTotal = when {
            bodyWeight < 132 -> if (isFemale) 400.0 else 850.0
            bodyWeight < 165 -> if (isFemale) 500.0 else 1000.0
            else -> if (isFemale) 600.0 else 1100.0
        }

        // ----- 3. Consistency -----
        val now = System.currentTimeMillis()
        val eightWeeksAgo = now - 56.0 * DAY_MS
        val workoutsLast8Weeks = workouts.count { parseMs(it.date) >= eightWeeksAgo }
        val consistency = min(1.0, workoutsLast8Weeks / 24.0)

        // ----- 4. Progression potential (Φ), last 10 logged sets, newest first -----
        var phiSum = 0.0
        var phiCount = 0
        for (i in workouts.indices.reversed()) {
            if (phiCount >= 10) break
            val workout = workouts[i]
            for (ex in workout.exercises) {
                val a = ex.actual ?: continue
                if (ex.skipped) continue
                if (a.reps.isEmpty()) continue
                val phi = computeProgressionPotential(ex.id, a)
                if (!phi.isNaN() && !phi.isInfinite()) {
                    phiSum += phi
                    phiCount++
                }
            }
        }
        val avgPhi = if (phiCount > 0) phiSum / phiCount else 0.2

        // ----- 5. Systemic fatigue -----
        val systemic = systemicRecoveryFactor()
        val fatiguePenalty = 1.0 - (1.0 - systemic) * 0.5

        // ----- 6. Weekly gain -----
        val progressionRate = user.settings.progressionRate.takeIf { it != 0.0 } ?: 0.02
        var weeklyGainPercent = progressionRate * avgPhi * consistency * fatiguePenalty
        weeklyGainPercent = min(0.03, max(0.002, weeklyGainPercent))

        // ----- 7. Weeks needed (log projection toward a carrying capacity) -----
        val remaining = targetTotal - total1RM
        if (remaining <= 0) {
            return if (total1RM > 0 && total1RM < userWeight * 9 && total1RM >= targetTotal * 0.9) {
                EliteProjection(now, now, now, "You're already elite! 🏆 Keep inspiring others.", total1RM, targetTotal)
            } else {
                val p = plusYears(5)
                EliteProjection(p, p, p, "Not enough reliable data. Keep training – estimate will appear soon!", 0.0, targetTotal)
            }
        }
        val carrying = targetTotal * 1.2
        val weeks = ln(1.0 + remaining / (carrying - total1RM)) / ln(1.0 + weeklyGainPercent)
        val weeksMedian = max(4.0, weeks)

        // ----- 8. Confidence intervals (capped) -----
        val weeklyCounts = IntArray(8)
        for (i in 0 until 8) {
            val weekStart = eightWeeksAgo + i * 7.0 * DAY_MS
            val weekEnd = weekStart + 6.0 * DAY_MS
            weeklyCounts[i] = workouts.count { val d = parseMs(it.date); d >= weekStart && d <= weekEnd }
        }
        val avgW = weeklyCounts.average()
        val variance = weeklyCounts.map { (it - avgW) * (it - avgW) }.average()
        val uncertainty = min(1.3, 1.0 + min(0.3, variance / 10.0))
        var weeksLow = weeksMedian * 0.6 * uncertainty
        var weeksHigh = weeksMedian * 1.8 * uncertainty
        weeksHigh = min(weeksHigh, weeksMedian + 104.0)
        weeksLow = max(weeksLow, 4.0)

        val medianMs = now + Math.round(weeksMedian * 7) * DAY_MS.toLong()
        val lowMs = now + Math.round(weeksLow * 7) * DAY_MS.toLong()
        val highMs = now + Math.round(weeksHigh * 7) * DAY_MS.toLong()

        // ----- 10. Motivational message -----
        val message = when {
            consistency < 0.3 -> "Consistency is your superpower. Keep showing up!"
            avgPhi < 0.15 -> "Push a little harder on your last reps – you'll grow faster."
            weeklyGainPercent > 0.02 -> "You're on fire! This date could come sooner. 🔥"
            else -> "Every workout brings you closer. You've got this! 💪"
        }

        return EliteProjection(medianMs, lowMs, highMs, message, total1RM, targetTotal)
    }

    /** Formatted projected elite date, e.g. "Mar 5, 2027". "" only if computation throws. */
    fun projectedEliteDate(): String {
        return try {
            val p = eliteProjection()
            SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date(p.medianMs))
        } catch (e: Exception) {
            ProLog.e("STATS", "projectedEliteDate: ${e.message}")
            ""
        }
    }

    // ------------------------------------------------------------------
    // Strength forecast (L38015 updateStrengthForecast)
    // ------------------------------------------------------------------

    /**
     * SBD forecast ratios toward the elite target total:
     *  male default 0.35/0.25/0.40, female 0.32/0.22/0.46, male & bodyweight > 198 → 0.33/0.23/0.44.
     * Plus a Wilks-ish score: male targetTotal * 500/(bw+150), female targetTotal * 400/(bw+100).
     */
    fun strengthForecast(): List<ForecastRow> {
        return try {
            val projection = eliteProjection()
            val targetTotal = projection.targetTotal
            val bodyWeight = bodyWeight()

            var squatRatio = 0.35; var benchRatio = 0.25; var deadliftRatio = 0.40
            val isFemale = ProState.data.user.gender == "female"
            if (isFemale) {
                squatRatio = 0.32; benchRatio = 0.22; deadliftRatio = 0.46
            } else if (bodyWeight > 198) {
                squatRatio = 0.33; benchRatio = 0.23; deadliftRatio = 0.44
            }

            val squat = Math.round(targetTotal * squatRatio).toDouble()
            val bench = Math.round(targetTotal * benchRatio).toDouble()
            val deadlift = Math.round(targetTotal * deadliftRatio).toDouble()

            var wilks = if (!isFemale) targetTotal * (500.0 / (bodyWeight + 150.0))
            else targetTotal * (400.0 / (bodyWeight + 100.0))
            wilks = Math.round(wilks).toDouble()

            val bwRatio = if (bodyWeight > 0) bodyWeight else 150.0
            listOf(
                ForecastRow("squat", squat, squat / bwRatio),
                ForecastRow("bench_press", bench, bench / bwRatio),
                ForecastRow("deadlift", deadlift, deadlift / bwRatio),
                ForecastRow("wilks", wilks, wilks / bwRatio),
            )
        } catch (e: Exception) {
            ProLog.w("STATS", "strengthForecast: ${e.message}")
            emptyList()
        }
    }

    // ------------------------------------------------------------------
    // Next workout date (L45533)
    // ------------------------------------------------------------------

    /**
     * Next workout date = today + split.restAfter days (legacy default 2 when the split is not found).
     * The native port has no workoutProgram store, so the legacy fallback of 2 days applies.
     */
    fun nextWorkoutDate(): String {
        val currentWorkout = ProState.currentWorkout ?: return "No workout scheduled"
        val restAfter = 2.0 // legacy: workoutProgram.splits.find { it.id == currentWorkout.type }?.restAfter || 2
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, restAfter.toInt())
        // legacy: toLocaleDateString('en-US', { weekday:'long', month:'short', day:'numeric' })
        return SimpleDateFormat("EEEE, MMM d", Locale.US).format(cal.time)
    }

    // ------------------------------------------------------------------
    // formatNumber (L45595)
    // ------------------------------------------------------------------

    private val numberLocaleFormat: NumberFormat = NumberFormat.getNumberInstance(Locale.US).apply {
        maximumFractionDigits = 3
        isGroupingUsed = true
    }

    /** >= 1e6 → "1.2M", >= 1e3 → "850.0K" (legacy always emits one decimal for K), else grouped locale string. */
    fun formatNumber(n: Double): String {
        if (n >= 1000000) {
            return String.format(Locale.US, "%.1f", n / 1000000.0) + "M"
        } else if (n >= 1000) {
            return String.format(Locale.US, "%.1f", n / 1000.0) + "K"
        }
        return numberLocaleFormat.format(n) // legacy num.toLocaleString()
    }

    // ------------------------------------------------------------------
    // Longevity score (L44053 + sub-scorers + L44946 display thresholds)
    // ------------------------------------------------------------------

    /** Legacy estimate1RM: Epley only. */
    private fun epley1RM(weight: Double, reps: Double): Double =
        if (!(weight > 0)) 0.0 else weight * (1 + reps / 30)

    private fun longevityMuscleDefs(): List<Library.MuscleDef> =
        Library.allMuscleGroups().filter { it.category == "longevity" }

    // ---- grip (L44290) ----

    private fun calculateGripStrengthScore(): Double {
        val gripExerciseIds = listOf(
            "deadlift", "pull_up", "farmer_walk", "dead_hang", "wrist_curl",
            "plate_pinch", "grippers", "barbell_shrug", "dumbbell_shrug", "rack_pull",
        )

        val userWeight = staticUserWeight()
        var bestScore = 0.0

        for (exId in gripExerciseIds) {
            val exData = ProState.data.exercises[exId] ?: continue

            var gripLoad = 0.0
            var ratio = 0.0

            // For dead hang, bestWeight is interpreted as seconds: 60s = 50, 120s = 100
            if (exId == "dead_hang") {
                val duration = exData.bestWeight ?: 0.0 // legacy: bestWeight || bestDuration (no bestDuration field in the native model)
                if (duration > 0) {
                    val durationScore = min(100.0, (duration / 120.0) * 100.0)
                    if (durationScore > bestScore) bestScore = durationScore
                }
                continue
            }

            val bestWeight = exData.bestWeight
            if (bestWeight != null && bestWeight != 0.0) {
                if (exData.history.isNotEmpty()) {
                    var best1RM = 0.0
                    for (entry in exData.history) {
                        if (entry.weight != 0.0 && entry.reps.isNotEmpty()) {
                            val maxReps = entry.reps.max()
                            val estimated = epley1RM(entry.weight, maxReps)
                            if (estimated > best1RM) best1RM = estimated
                        }
                    }
                    gripLoad = if (best1RM > 0) best1RM else bestWeight // fallback
                } else {
                    gripLoad = bestWeight
                }

                // Farmer's walk: weight per hand → total grip load is 2x
                if (exId == "farmer_walk") gripLoad *= 2

                ratio = gripLoad / userWeight
            }
            // NOTE: the legacy `else if (exId === "pull_up" && exData.bestWeight)` branch was
            // unreachable (bestWeight is always truthy inside the first branch) — not ported.

            if (ratio > 0) {
                val score = when {
                    ratio >= 2.0 -> 100.0
                    ratio >= 1.5 -> 90.0
                    ratio >= 1.2 -> 75.0
                    ratio >= 1.0 -> 60.0
                    ratio >= 0.8 -> 40.0
                    ratio >= 0.5 -> 20.0
                    else -> 0.0
                }
                if (score > bestScore) bestScore = score
            }
        }

        return if (bestScore > 0) bestScore else 50.0
    }

    // ---- balance (L44305) ----

    private fun calculateBalanceScore(): Double {
        val balanceExerciseIds = setOf(
            "single_leg_stand", "balance_board", "yoga_tree", "single_leg_rdl",
            "single_leg_squat", "ankle_stability", "pistol_squat", "staggered_stance_deadlift",
        )
        val unilateralExerciseIds = setOf(
            "bulgarian_split_squat", "single_leg_press", "lunge", "step_up",
            "single_leg_rdl", "pistol_squat", "single_leg_squat",
        )
        val ankleMuscles = listOf("peroneus_tertius", "tibialis", "foot_intrinsics")

        val lookbackDays = 60.0
        val cutoffDate = System.currentTimeMillis() - lookbackDays * DAY_MS

        var balanceSessions = 0
        var lastBalanceMs = -1L
        var unilateralSessions = 0

        for (workout in ProState.data.workouts) {
            val workoutMs = parseMs(workout.date)
            if (workoutMs < cutoffDate) continue

            var hasBalance = false
            var hasUnilateral = false
            for (ex in workout.exercises) {
                if (ex.actual == null || ex.skipped) continue
                if (ex.id in balanceExerciseIds) hasBalance = true
                if (ex.id in unilateralExerciseIds) hasUnilateral = true
            }
            if (hasBalance) {
                balanceSessions++
                if (workoutMs > lastBalanceMs) lastBalanceMs = workoutMs
            }
            if (hasUnilateral) unilateralSessions++
        }

        // Ankle muscle recency (most recent of the three)
        var mostRecentAnkleMs = -1L
        for (muscle in ankleMuscles) {
            val last = Fatigue.lastTrainedMs(muscle) ?: continue
            if (last > mostRecentAnkleMs) mostRecentAnkleMs = last
        }

        // Component scores (each 0-100), legacy weights: frequency .4, recency .2, ankle .2, unilateral .2
        val freqScore = min(100.0, (balanceSessions / 8.0) * 100.0)

        var recencyScore = 0.0
        if (lastBalanceMs > 0) {
            val daysSince = floor((System.currentTimeMillis() - lastBalanceMs) / DAY_MS)
            recencyScore = when {
                daysSince <= 7 -> 100.0
                daysSince <= 14 -> 80.0
                daysSince <= 30 -> 60.0
                daysSince <= 60 -> 40.0
                else -> 20.0
            }
        }

        var ankleScore = 0.0
        if (mostRecentAnkleMs > 0) {
            val daysSince = floor((System.currentTimeMillis() - mostRecentAnkleMs) / DAY_MS)
            ankleScore = when {
                daysSince <= 14 -> 100.0
                daysSince <= 30 -> 80.0
                daysSince <= 60 -> 60.0
                else -> 40.0
            }
        } else {
            ankleScore = 20.0 // never trained – low score
        }

        val unilateralScore = min(100.0, (unilateralSessions / 4.0) * 100.0)

        val totalScore = Math.round(
            freqScore * 0.4 + recencyScore * 0.2 + ankleScore * 0.2 + unilateralScore * 0.2
        ).toDouble()

        return min(100.0, max(0.0, totalScore))
    }

    // ---- shared 60-day per-muscle session counts ----

    private fun muscleSessionCountsLast60Days(muscleNames: List<String>): MutableMap<String, Int> {
        val counts = LinkedHashMap<String, Int>()
        for (name in muscleNames) counts[name] = 0
        val cutoff = System.currentTimeMillis() - 60.0 * DAY_MS
        for (workout in ProState.data.workouts) {
            if (parseMs(workout.date) < cutoff) continue
            for (ex in workout.exercises) {
                if (ex.actual == null || ex.skipped) continue
                for (mg in ex.muscleGroup) {
                    if (counts.containsKey(mg)) counts[mg] = counts[mg]!! + 1
                }
            }
        }
        return counts
    }

    /** daysSince for longevity sub-scorers: null (never trained) → +Infinity, as in legacy. */
    private fun daysSinceOrInfinity(muscle: String): Double =
        Fatigue.daysSinceTrained(muscle) ?: Double.POSITIVE_INFINITY

    // ---- joint mobility (L44383) ----

    private fun calculateJointMobilityScore(): Double {
        val longevityMuscles = longevityMuscleDefs()
        val sessionCounts = muscleSessionCountsLast60Days(longevityMuscles.map { it.name })

        var totalWeightedScore = 0.0
        var totalWeight = 0.0

        for (muscle in longevityMuscles) {
            val daysSince = daysSinceOrInfinity(muscle.name)

            // Recency component (0–1): fresh <= 7d, stale >= 60d, linear between
            val recencyScore = when {
                daysSince >= 60.0 -> 0.0
                daysSince <= 7.0 -> 1.0
                else -> 1.0 - (daysSince - 7.0) / 53.0
            }

            // Frequency bonus (0–0.2)
            val sessions = sessionCounts[muscle.name] ?: 0
            val freqBonus = min(sessions, 4) * 0.05

            val muscleScore = min(recencyScore + freqBonus, 1.0)

            // Aging-risk weight
            val weight = when (muscle.agingRisk) {
                "high" -> 2.0
                "medium" -> 1.5
                else -> 1.0
            }

            totalWeightedScore += muscleScore * weight
            totalWeight += weight
        }

        if (totalWeight == 0.0) return 50.0 // fallback (should not happen)

        val rawScore = (totalWeightedScore / totalWeight) * 100.0
        return min(100.0, Math.round(rawScore).toDouble())
    }

    // ---- posture (L44426) ----

    private fun calculatePostureScore(): Double {
        val postureMuscles = listOf(
            "neck" to 1.5, "deep_neck" to 1.5, "rhomboids" to 2.0, "rear_delts" to 1.2, "traps" to 1.0,
        )
        val sessionCounts = muscleSessionCountsLast60Days(postureMuscles.map { it.first })

        var totalWeightedScore = 0.0
        var totalWeight = 0.0

        for ((name, weight) in postureMuscles) {
            val daysSince = daysSinceOrInfinity(name)

            // Recency: 1.0 if <= 14 days, 0 if >= 60, linear between
            val recencyScore = when {
                daysSince <= 14.0 -> 1.0
                daysSince >= 60.0 -> 0.0
                else -> 1.0 - (daysSince - 14.0) / 46.0
            }

            val sessions = sessionCounts[name] ?: 0
            val freqBonus = min(sessions, 4) * 0.05

            val muscleScore = min(recencyScore + freqBonus, 1.0)

            totalWeightedScore += muscleScore * weight
            totalWeight += weight
        }

        if (totalWeight == 0.0) return 50.0 // fallback (should not happen)

        val rawScore = (totalWeightedScore / totalWeight) * 100.0
        return min(100.0, Math.round(rawScore).toDouble())
    }

    // ---- push/pull muscle balance (L44455) ----

    private fun calculateMuscleBalanceScore(): Double {
        val pushMuscles = listOf("chest", "triceps", "front_delts")
        val pullMuscles = listOf("back", "biceps", "rear_delts")

        fun recencyScore(muscleName: String): Double {
            val last = Fatigue.daysSinceTrained(muscleName) ?: return 0.0
            return when {
                last <= 14.0 -> 1.0
                last >= 60.0 -> 0.0
                else -> 1.0 - (last - 14.0) / 46.0
            }
        }

        val pushAvg = pushMuscles.map { recencyScore(it) }.average()
        val pullAvg = pullMuscles.map { recencyScore(it) }.average()

        // Balance ratio (smaller / larger)
        if (pushAvg == 0.0 && pullAvg == 0.0) return 0.0
        val maxAvg = max(pushAvg, pullAvg)
        val minAvg = min(pushAvg, pullAvg)
        val balanceRatio = minAvg / maxAvg

        return Math.round(balanceRatio * 100.0).toDouble()
    }

    // ---- consistency (L44470) ----

    private fun calculateConsistencyScore(): Double {
        val now = System.currentTimeMillis()
        val eightWeeksAgo = now - 56.0 * DAY_MS

        // 1. Weekly workout counts (last 8 weeks, 7-day buckets)
        val weekStarts = DoubleArray(8) { eightWeeksAgo + it * 7.0 * DAY_MS }
        val weekEnds = DoubleArray(8) { weekStarts[it] + 6.0 * DAY_MS }
        val counts = IntArray(8)
        for (workout in ProState.data.workouts) {
            val wMs = parseMs(workout.date)
            if (wMs < eightWeeksAgo) continue
            for (i in 0 until 8) {
                if (wMs >= weekStarts[i] && wMs <= weekEnds[i]) {
                    counts[i]++
                    break
                }
            }
        }
        val weeklyCounts = counts.toList()

        // 2. Long-term frequency (avg workouts/week)
        val avgWeekly = weeklyCounts.average()
        val freqScore = when {
            avgWeekly >= 3.5 -> 40.0
            avgWeekly >= 2.5 -> 30.0
            avgWeekly >= 1.5 -> 20.0
            avgWeekly >= 0.5 -> 10.0
            else -> 0.0
        }

        // 3. Streak bonus
        val streak = calculateStreak()
        val streakScore = when {
            streak >= 30 -> 30.0
            streak >= 14 -> 25.0
            streak >= 7 -> 20.0
            streak >= 3 -> 15.0
            streak >= 1 -> 10.0
            else -> 0.0
        }

        // 4. Adherence to planned days (if set)
        val plannedDays = ProState.data.user.settings.workoutDays
        var adherenceScore = 0.0
        if (plannedDays.isNotEmpty()) {
            val fourWeeksAgo = now - 28.0 * DAY_MS
            var plannedCount = 0
            var actualCount = 0

            // Legacy iterates d = fourWeeksAgo; d <= now; d += 1 day → 29 day-slots
            val dayFormat = SimpleDateFormat("EEEE", Locale.US)
            val cal = Calendar.getInstance()
            cal.timeInMillis = fourWeeksAgo.toLong()
            while (cal.timeInMillis <= now) {
                val dayName = dayFormat.format(cal.time).lowercase(Locale.US)
                if (plannedDays.contains(dayName)) {
                    plannedCount++
                    val slotDay = cal.timeInMillis / 86400000L
                    val hasWorkout = ProState.data.workouts.any { parseMs(it.date) / 86400000L == slotDay }
                    if (hasWorkout) actualCount++
                }
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }

            if (plannedCount > 0) {
                adherenceScore = Math.round((actualCount.toDouble() / plannedCount) * 20.0).toDouble() // max 20
            }
        }

        // 5. Regularity penalty (coefficient of variation)
        val mean = if (avgWeekly != 0.0) avgWeekly else 0.01
        val variance = weeklyCounts.map { (it - mean) * (it - mean) }.average()
        val stdDev = sqrt(variance)
        val cv = if (mean > 0) stdDev / mean else 0.0
        val regularityScore = when {
            cv <= 0.3 -> 10.0
            cv <= 0.5 -> 7.0
            cv <= 0.7 -> 4.0
            cv <= 1.0 -> 2.0
            else -> 0.0
        }

        // 6. Combine (max 40 + 30 + 20 + 10 = 100)
        val total = freqScore + streakScore + adherenceScore + regularityScore
        return min(100.0, max(0.0, total))
    }

    // ---- trend (3-month compound 1RM improvement) ----

    private fun calculateTrendScore(): Double {
        val cal = Calendar.getInstance()
        cal.add(Calendar.MONTH, -3)
        val threeMonthsAgoMs = cal.timeInMillis

        val keyExercises = listOf("squat", "deadlift", "bench_press", "overhead_press")
        var totalImprovement = 0.0
        var count = 0

        for (exId in keyExercises) {
            val exData = ProState.data.exercises[exId] ?: continue
            if (exData.history.size < 2) continue

            val recent = exData.history.filter { parseMs(it.date) >= threeMonthsAgoMs }
            if (recent.size < 2) continue

            val oldest = recent.first()
            val newest = recent.last()
            // Legacy: Math.max(...(reps || [1])) — a MISSING reps field means 1 rep.
            val oldestReps = if (oldest.reps.isEmpty()) 1.0 else oldest.reps.max()
            val newestReps = if (newest.reps.isEmpty()) 1.0 else newest.reps.max()
            val oldest1RM = epley1RM(oldest.weight, oldestReps)
            val newest1RM = epley1RM(newest.weight, newestReps)

            if (oldest1RM > 0) {
                val improvement = (newest1RM - oldest1RM) / oldest1RM // fractional gain
                totalImprovement += improvement
                count++
            }
        }

        if (count == 0) return 50.0 // neutral score if no data
        val avgImprovement = totalImprovement / count
        // 0% improvement → 50, +10% → 75, -10% → 25
        return min(100.0, max(0.0, 50.0 + avgImprovement * 250.0))
    }

    // ---- variety (30-day longevity-muscle coverage) ----

    private fun calculateVarietyScore(): Double {
        val longevityMuscles = longevityMuscleDefs().map { it.name }
        if (longevityMuscles.isEmpty()) return 0.0 // degenerate library; legacy would produce NaN

        val thirtyDaysAgo = System.currentTimeMillis() - 30.0 * DAY_MS
        val longevitySet = longevityMuscles.toSet()
        val trainedLongevity = LinkedHashSet<String>()

        for (workout in ProState.data.workouts) {
            if (parseMs(workout.date) < thirtyDaysAgo) continue
            for (ex in workout.exercises) {
                if (ex.actual == null || ex.skipped) continue
                for (mg in ex.muscleGroup) {
                    if (mg in longevitySet) trainedLongevity.add(mg)
                }
            }
        }

        val percentage = trainedLongevity.size.toDouble() / longevityMuscles.size * 100.0
        return min(100.0, Math.round(percentage * 1.5).toDouble()) // training 67% gives 100
    }

    // ---- aging risks (L44782) ----

    private class RiskDef(
        val factor: String,
        val scoreKey: String,
        val idealMin: Int,
        val severeThreshold: Int,
        val severity: String,
        val impact: String,
        val baseRecommendation: String,
        val exercises: List<String>,
        val ageAdjust: (Int) -> Int,
    )

    private val riskDefinitions = listOf(
        RiskDef("Neck/Posture Weakness", "posture", 70, 50, "High",
            "Forward head posture, cervical degeneration, headaches",
            "Add neck strengthening 2x/week", listOf("neck_isometric", "chin_tucks", "face_pull")) { age -> if (age > 50) 5 else 0 },
        RiskDef("Low Grip Strength", "gripStrength", 60, 40, "Medium",
            "Reduced independence, difficulty with daily tasks",
            "Add grip training 2x/week", listOf("wrist_curl", "hand_therapy", "farmer_walks")) { age -> if (age > 60) 10 else 0 },
        RiskDef("Poor Balance", "balance", 70, 40, "High",
            "Increased fall risk, fractures, fear of falling",
            "Add balance exercises daily", listOf("ankle_stability", "single_leg_stands", "balance_board")) { age -> if (age > 50) 10 else 0 },
        RiskDef("Joint Stiffness", "jointMobility", 60, 40, "Medium",
            "Reduced range of motion, arthritis progression",
            "Add joint mobility work 3x/week", listOf("terminal_extension", "ankle_stability", "shoulder_circles")) { age -> if (age > 50) 5 else 0 },
        RiskDef("Muscle Imbalance (Push/Pull)", "muscleBalance", 80, 60, "Medium",
            "Postural issues, increased injury risk",
            "Incorporate more pulling exercises to balance pushing", listOf("face_pull", "barbell_row", "pull_up")) { _ -> 0 },
        RiskDef("Low Training Consistency", "consistency", 70, 40, "Low",
            "Missed training opportunities, reduced long‑term gains",
            "Aim for at least 3 workouts per week", emptyList()) { _ -> 0 },
        RiskDef("Neglected Longevity Muscles", "variety", 70, 50, "Medium",
            "Unaddressed joint health muscles increase future injury risk",
            "Add more variety to target all joint health areas", emptyList()) { _ -> 0 },
        RiskDef("Strength Decline Trend", "trend", 50, 30, "Medium",
            "Possible loss of muscle mass or strength",
            "Consider a structured progressive overload program", emptyList()) { age -> if (age > 50) 5 else 0 },
    )

    internal data class AgingRiskInternal(
        val factor: String,
        val severity: String,
        val score: Double,
        val impact: String,
        val recommendation: String,
        val exercises: List<String>,
        val adjustedIdeal: Int,
    )

    /** Legacy calls assessAgingRisks(baseScores) — trend/variety keys are absent there, so those two risk defs never fire. */
    private fun assessAgingRisksInternal(scores: Map<String, Double>): List<AgingRiskInternal> {
        val age = Sessions.calculateAge(ProState.data.user.birthDate)
        val risks = mutableListOf<AgingRiskInternal>()

        for (def in riskDefinitions) {
            val scoreValue = scores[def.scoreKey] ?: continue

            var adjustedIdeal = def.idealMin
            if (age != null) adjustedIdeal += def.ageAdjust(age)

            if (scoreValue < def.severeThreshold) {
                risks.add(
                    AgingRiskInternal(
                        def.factor, def.severity, scoreValue, def.impact,
                        def.baseRecommendation + " (current score ${scoreValue.toInt()}/100)",
                        def.exercises, adjustedIdeal,
                    )
                )
            } else if (scoreValue < adjustedIdeal) {
                risks.add(
                    AgingRiskInternal(
                        def.factor, "Low", scoreValue, def.impact,
                        "Your ${def.factor.lowercase()} score is ${scoreValue.toInt()}/100. " + def.baseRecommendation,
                        def.exercises, adjustedIdeal,
                    )
                )
            }
        }
        return risks
    }

    fun assessAgingRisks(): List<AgingRiskRow> {
        return assessAgingRisksInternal(baseScores()).map { r ->
            AgingRiskRow(
                muscle = r.factor,
                severity = r.severity,
                score = r.score,
                reason = r.impact,
                ideal = r.adjustedIdeal.toString(),
                current = "${r.score.toInt()}/100",
            )
        }
    }

    // ---- recommendations (L44905) ----

    private fun longevityBaseRecommendations(scores: Map<String, Double>, risks: List<AgingRiskInternal>): List<String> {
        val recommendations = mutableListOf<String>()

        // 1. Risk-based recommendations (prioritised by severity)
        val severityOrder = mapOf("High" to 3, "Medium" to 2, "Low" to 1)
        val sortedRisks = risks.sortedByDescending { severityOrder[it.severity] ?: 0 }

        for (risk in sortedRisks) {
            var rec = risk.recommendation

            if (risk.exercises.isNotEmpty()) {
                val exerciseNames = risk.exercises.joinToString(", ") { exId ->
                    Library.getExerciseById(exId)?.name ?: exId.replace('_', ' ')
                }
                rec += " Try: $exerciseNames."
            }

            if (risk.severity == "High") rec = "⚠️ URGENT: $rec"

            recommendations.add(rec)
        }

        // 2. Score-based general recommendations (if no risk for that area)
        val posture = scores["posture"]
        if (posture != null) {
            if (posture >= 70 && posture < 85) recommendations.add("Your posture is good. Maintain with regular upper back work.")
            else if (posture >= 85) recommendations.add("Excellent posture! Keep up the good work.")
        }
        val balance = scores["balance"]
        if (balance != null) {
            if (balance >= 60 && balance < 80) recommendations.add("Balance is decent. Aim for 2‑3 balance sessions per week to maintain.")
            else if (balance >= 80) recommendations.add("Great balance! Continue your routine.")
        }
        val jointMobility = scores["jointMobility"]
        if (jointMobility != null) {
            if (jointMobility >= 50 && jointMobility < 75) recommendations.add("Joint mobility is acceptable. Add one mobility session per week.")
            else if (jointMobility >= 75) recommendations.add("Excellent joint mobility! Keep moving.")
        }

        // trend/variety blocks: legacy guards on scores.trend !== undefined — never true when called
        // with baseScores, so they are skipped here exactly as in the original.

        // 3. Goal-based advice
        if (ProState.data.user.goal == "longevity") {
            recommendations.add("Your goal is longevity. Keep focusing on joint health, balance, and consistency.")
        }

        // 4. Reassessment reminder
        if (recommendations.isNotEmpty()) {
            recommendations.add("Reassess your longevity score in 4‑6 weeks to track progress.")
        }

        return recommendations.distinct()
    }

    fun generateLongevityRecommendations(): List<String> {
        val baseScores = baseScores()
        val trendScore = calculateTrendScore()
        val varietyScore = calculateVarietyScore()

        val recommendations = mutableListOf<String>()

        // Trend-based recommendation
        if (trendScore < 40) {
            recommendations.add("Your strength is declining. Consider a more structured progressive overload program.")
        } else if (trendScore > 70) {
            recommendations.add("Great strength gains! Keep progressing.")
        }

        // Variety-based recommendation
        if (varietyScore < 50) {
            recommendations.add("You're neglecting many longevity muscles. Add more joint‑health exercises.")
        }

        // Existing recommendations from the helper
        recommendations.addAll(longevityBaseRecommendations(baseScores, assessAgingRisksInternal(baseScores)))

        return recommendations.distinct()
    }

    // ---- score assembly (L44053) ----

    private fun baseScores(): Map<String, Double> = linkedMapOf(
        "gripStrength" to calculateGripStrengthScore(),
        "balance" to calculateBalanceScore(),
        "jointMobility" to calculateJointMobilityScore(),
        "posture" to calculatePostureScore(),
        "muscleBalance" to calculateMuscleBalanceScore(),
        "consistency" to calculateConsistencyScore(),
    )

    private fun longevityStatus(score: Int): Pair<String, String> = when {
        score >= 80 -> "Excellent Longevity" to "var(--success)"
        score >= 60 -> "Good Longevity" to "var(--warning)"
        score >= 40 -> "Needs Improvement" to "var(--warning)"
        else -> "High Risk" to "var(--danger)"
    }

    /**
     * Age-dependent weight sets (<30, <50, 50+), normalized to sum to 1.0.
     * Legacy quirk preserved: the <30 set never reassigns posture (stays 0.20), so its raw sum
     * is 1.2 before normalization.
     */
    fun calculateLongevityScore(): LongevityScore {
        val age = Sessions.calculateAge(ProState.data.user.birthDate)

        val baseScores = baseScores()
        val trendScore = calculateTrendScore()
        val varietyScore = calculateVarietyScore()

        val weightBase = linkedMapOf(
            "gripStrength" to 0.15,
            "balance" to 0.15,
            "jointMobility" to 0.25,
            "posture" to 0.20,
            "muscleBalance" to 0.15,
            "consistency" to 0.10,
            "trend" to 0.0,
            "variety" to 0.0,
        )

        if (age != null) {
            when {
                age < 30 -> {
                    // Youth: focus on strength and muscle balance
                    weightBase["gripStrength"] = 0.20
                    weightBase["balance"] = 0.10
                    weightBase["jointMobility"] = 0.15
                    weightBase["muscleBalance"] = 0.25
                    weightBase["consistency"] = 0.10
                    weightBase["trend"] = 0.10
                    weightBase["variety"] = 0.10
                    // posture intentionally left at base 0.20 (legacy)
                }
                age < 50 -> {
                    // Middle age: joint health becomes important
                    weightBase["gripStrength"] = 0.15
                    weightBase["balance"] = 0.15
                    weightBase["jointMobility"] = 0.25
                    weightBase["posture"] = 0.15
                    weightBase["muscleBalance"] = 0.10
                    weightBase["consistency"] = 0.10
                    weightBase["trend"] = 0.05
                    weightBase["variety"] = 0.05
                }
                else -> {
                    // 50+: balance and joint mobility are critical
                    weightBase["gripStrength"] = 0.10
                    weightBase["balance"] = 0.25
                    weightBase["jointMobility"] = 0.30
                    weightBase["posture"] = 0.15
                    weightBase["muscleBalance"] = 0.05
                    weightBase["consistency"] = 0.05
                    weightBase["trend"] = 0.05
                    weightBase["variety"] = 0.05
                }
            }
        }

        // Normalize weights to sum to 1.0
        val totalWeight = weightBase.values.sum()

        val allScores = LinkedHashMap(baseScores)
        allScores["trend"] = trendScore
        allScores["variety"] = varietyScore

        var total = 0.0
        for ((key, w) in weightBase) {
            val score = allScores[key] ?: continue
            total += score * (w / totalWeight)
        }
        val finalScore = Math.round(total).toInt()

        val sub = LinkedHashMap<String, Double>(baseScores)
        sub["trend"] = Math.round(trendScore).toDouble()
        sub["variety"] = Math.round(varietyScore).toDouble()

        val (status, color) = longevityStatus(finalScore)
        return LongevityScore(total = finalScore, sub = sub, status = status, color = color)
    }

    // ------------------------------------------------------------------
    // Weekly volume (legacy weeklyVolumes weekKey semantics)
    // ------------------------------------------------------------------

    /** ISO week key "2025-W14" — port of getISOWeekKey (L39072, Monday-based, nearest Thursday). */
    private fun isoWeekKey(dateMs: Long): String {
        val cal = Calendar.getInstance()
        cal.timeInMillis = dateMs
        val dayNum = if (cal.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) 7 else cal.get(Calendar.DAY_OF_WEEK)
        cal.add(Calendar.DAY_OF_MONTH, 4 - dayNum) // nearest Thursday
        val year = cal.get(Calendar.YEAR)

        val firstThursday = Calendar.getInstance()
        firstThursday.clear()
        firstThursday.set(year, 0, 1)
        val firstThursdayDay = if (firstThursday.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) 7
        else firstThursday.get(Calendar.DAY_OF_WEEK)

        val diffDays = (cal.timeInMillis - firstThursday.timeInMillis) / DAY_MS
        val weekNumber = ceil((diffDays + firstThursdayDay) / 7.0).toInt()
        return "$year-W" + weekNumber.toString().padStart(2, '0')
    }

    /**
     * Current week volume. Legacy stores weeklyVolumes sorted by weekKey DESC (most recent first),
     * so the "last" entry = max weekKey. If none stored, compute this ISO week (Mon–Sun) volume.
     */
    fun weeklyVolumeCurrent(): Double {
        val stored = ProState.data.user.weeklyVolumes
        if (stored.isNotEmpty()) {
            return stored.maxByOrNull { it.weekKey }?.totalVolume ?: 0.0
        }
        val currentKey = isoWeekKey(System.currentTimeMillis())
        var total = 0.0
        for (w in ProState.data.workouts) {
            if (w.date.isBlank()) continue
            if (isoWeekKey(parseMs(w.date)) == currentKey) total += w.summary?.totalVolume ?: 0.0
        }
        return total
    }

    /** Average weekly volume: last 4 stored weeklyVolumes, else aggregates.totalVolumeLast7Days fallback (legacy L36077). */
    fun weeklyVolumeAvg4(): Double {
        val stored = ProState.data.user.weeklyVolumes
        return if (stored.size >= 4) stored.takeLast(4).map { it.totalVolume }.average()
        else ProState.data.user.aggregates?.totalVolumeLast7Days ?: 0.0
    }
}
