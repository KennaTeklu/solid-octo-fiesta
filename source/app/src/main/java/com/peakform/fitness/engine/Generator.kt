package com.peakform.fitness.engine

import com.peakform.fitness.core.ExerciseRecord
import com.peakform.fitness.core.LibraryExercise
import com.peakform.fitness.ProLog
import com.peakform.fitness.core.ProState
import com.peakform.fitness.core.Settings
import com.peakform.fitness.core.WorkoutExercise
import com.peakform.fitness.core.WorkoutRecord
import com.peakform.fitness.core.Prescription
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Generator — faithful translation of the legacy generation engine:
 * performGenerateWorkout (L36069), selectExerciseForMuscle (L40714 incl. P4 coach math),
 * generateExercisePrescription (L35363 all branches), appendAthleticBlocks (L35971),
 * generateLongevityWorkout (L45177), recalcExerciseFromWeight (L35803) and the
 * time-wizard estimator/trimmer (L63545/L63568).
 */
object Generator {

    data class Split(
        val id: String, val name: String, val focus: List<String>, val restAfter: Int,
        val drills: List<String>, val component: String, val componentsCovered: List<String>,
    )

    val SPLITS = listOf(
        Split("full_body_power", "Full Body Strength + Power", listOf("quads", "chest", "back"), 1, listOf("athletic_power"), "power", listOf("muscular_strength", "power")),
        Split("push_hypertrophy_balance", "Push Hypertrophy + Balance", listOf("chest", "shoulders", "triceps"), 1, listOf("athletic_balance"), "balance", listOf("muscular_strength", "balance")),
        Split("pull_hypertrophy_coordination", "Pull Hypertrophy + Coordination", listOf("back", "biceps", "rear_delts"), 1, listOf("athletic_coordination"), "coordination", listOf("muscular_strength", "coordination")),
        Split("legs_speed", "Legs & Speed", listOf("quads", "hamstrings", "glutes", "calves"), 1, listOf("athletic_speed"), "speed", listOf("muscular_strength", "speed")),
        Split("athletic_agility_reaction", "Athletic Day: Agility + Reaction", listOf("core", "obliques", "hip_flexors"), 1, listOf("athletic_agility", "athletic_reaction"), "agility_reaction", listOf("agility", "reaction_time", "muscular_endurance")),
        Split("cardio_endurance", "Cardio Endurance & Conditioning", listOf("core", "calves", "glutes"), 1, listOf("cardio_condition"), "cardio", listOf("cardiorespiratory_endurance", "body_composition", "muscular_endurance")),
        Split("mobility_flexibility", "Mobility & Flexibility (Longevity)", listOf("neck", "forearms", "hip_flexors", "tibialis"), 1, listOf("stretching_static", "mobility_dynamic"), "flexibility", listOf("flexibility", "muscular_endurance")),
    )

    fun splitById(id: String): Split? = SPLITS.firstOrNull { it.id == id }

    // ---------- muscle type & rep floors (L35183) ----------
    private val LARGE = setOf("quads", "glutes", "lats", "back", "chest", "hamstrings", "erectors")
    private val MEDIUM = setOf("biceps", "triceps", "shoulders", "front_delts", "rear_delts", "traps", "calves", "core", "obliques", "hip_flexors", "adductors", "abductors")
    private val SMALL = setOf("forearms", "neck", "deep_neck", "tibialis", "soleus", "peroneus_tertius", "foot_intrinsics", "hand_intrinsics", "thenar", "supinator", "pronator", "brachialis", "brachioradialis", "anconeus", "popliteus", "articularis_genus", "multifidus", "transverse", "quadratus_plantae")

    fun getMuscleType(muscleName: String): String = when {
        LARGE.contains(muscleName) -> "large"
        MEDIUM.contains(muscleName) -> "medium"
        else -> "small"
    }

    fun getRepFloorForMuscle(muscleName: String): Int = when (getMuscleType(muscleName)) {
        "small" -> 10
        "medium" -> 5
        else -> 1
    }

    fun getExerciseRepFloor(exercise: WorkoutExercise): Int =
        exercise.muscleGroup.maxOfOrNull { getRepFloorForMuscle(it) } ?: 1

    fun getExerciseMuscleType(exercise: WorkoutExercise): String {
        if (exercise.muscleGroup.isEmpty()) return "medium"
        val types = exercise.muscleGroup.map { getMuscleType(it) }
        return when {
            types.contains("small") -> "small"
            types.contains("medium") -> "medium"
            else -> "large"
        }
    }

    /** humpSets — Prilepin-style set curve (L35247). */
    fun humpSets(intensity: Double, muscleType: String, workouts: Int, express: Boolean): Int {
        val peak = mapOf("large" to 5.0, "medium" to 4.0, "small" to 3.0)[muscleType] ?: 4.0
        val minSets = 2.0
        val t = (intensity - 0.75) / 0.25
        val curve = 1 - t * t
        var raw = minSets + (peak - minSets) * curve
        raw = min(peak, max(minSets, raw))
        var expFactor = min(1.2, 0.8 + workouts / 150.0)
        if (express && workouts > 20) expFactor = min(1.3, 0.9 + workouts / 100.0)
        val sets = raw * expFactor
        return min(peak.toInt() + 1, max(2, sets.roundToInt()))
    }

    // ---------- muscle → library key map (L40715) ----------
    private val KEY_MAP = mapOf(
        "quads" to "quads", "hamstrings" to "hamstrings", "glutes" to "glutes", "chest" to "chest",
        "back" to "back_lats", "shoulders" to "shoulders_anterior", "biceps" to "biceps", "triceps" to "triceps",
        "calves" to "calves", "core" to "core_abs", "forearms" to "forearms", "traps" to "traps",
        "lats" to "back_lats", "rear_delts" to "rhomboids_reardelts", "obliques" to "core_abs",
        "hip_flexors" to "hip_flexors", "adductors" to "adductors", "abductors" to "abductors",
        "erectors" to "erectors", "serratus" to "chest",
        "neck" to "neck", "deep_neck" to "neck", "levator_scap" to "rhomboids_reardelts",
        "rhomboids" to "rhomboids_reardelts", "teres" to "back_lats", "infraspinatus" to "rotator_cuff",
        "supraspinatus" to "rotator_cuff", "subscapularis" to "rotator_cuff", "brachialis" to "forearm_specialized",
        "brachioradialis" to "forearm_specialized", "anconeus" to "triceps", "supinator" to "forearm_specialized",
        "pronator" to "forearm_specialized", "pec_minor" to "chest", "coracobrach" to "shoulders_anterior",
        "popliteus" to "hamstrings", "tibialis" to "tibialis_anterior", "soleus" to "calves",
        "peroneus_tertius" to "feet_ankles", "articularis_genus" to "quads", "multifidus" to "back_lats",
        "transverse" to "core_abs", "quadratus_plantae" to "feet_ankles",
        "hand_lumbricals" to "hands_grip", "hand_interossei" to "hands_grip", "thenar" to "hands_grip",
        "foot_intrinsics" to "feet_ankles", "foot_interossei" to "feet_ankles",
        "abductor_hallucis" to "feet_ankles", "flexor_brevis" to "feet_ankles",
    )

    private fun exId(name: String) = name.lowercase().replace(Regex("\\s+"), "_")

    // ---------- selectExerciseForMuscle (L40714 with P4 coach math) ----------
    fun selectExerciseForMuscle(
        muscleGroup: String,
        ignoreReadiness: Boolean = false,
        currentWorkoutExercises: List<WorkoutExercise> = ProState.currentWorkout?.exercises ?: emptyList(),
        lastOverallRecovery: Int = Stats.lastOverallRecovery,
        trainingMode: String = ProState.data.user.settings.trainingMode,
    ): WorkoutExercise? {
        val libKey = KEY_MAP[muscleGroup] ?: muscleGroup

        // Pool from gym library (+ bodyweight library when training mode includes it)
        val sectionExercises: List<LibraryExercise> = when (trainingMode) {
            "bodyweight" -> Library.bodyweight[libKey]?.map { bw -> Library.bwToLibrary(bw) } ?: emptyList()
            else -> Library.groups[libKey] ?: emptyList()
        }
        var pool0 = sectionExercises.filter { it.muscles.contains(muscleGroup) }
        if (pool0.isEmpty()) return null

        // Equipment availability filter
        // HL3: joint protection — auto-skip movements that heavily load chosen joints
        val protectedJoints = com.peakform.fitness.ui.Fa.appContext?.let {
            com.peakform.fitness.core.Health.chosenJoints(it)
        } ?: emptyList()
        if (protectedJoints.isNotEmpty()) {
            val jointFiltered = pool0.filter { !com.peakform.fitness.core.Health.hitsProtectedJoint(it.muscles, protectedJoints) }
            if (jointFiltered.isNotEmpty()) pool0 = jointFiltered.toMutableList()
        }

        val avail = ProState.data.user.settings.equipment
        var pool = pool0
        if (avail.isNotEmpty()) {
            val filtered = pool.filter { ex ->
                val eq = ex.equipment.lowercase()
                avail.map { it.lowercase() }.contains(eq) || eq == "bodyweight" || eq == "none"
            }
            if (filtered.isNotEmpty()) pool = filtered
        }

        // Volume balance context
        val currentMuscleCount = currentWorkoutExercises.count { it.muscleGroup.contains(muscleGroup) }

        // Recovery context
        val overallRec = if (lastOverallRecovery in 1..100) lastOverallRecovery else 80
        var muscleRecPct = 100.0
        val muscleInfo = Library.muscleDef(muscleGroup)
        if (muscleInfo != null && muscleInfo.restDays > 0) {
            val dSince = Fatigue.daysSinceTrained(muscleGroup)
            if (dSince != null && dSince.isFinite()) {
                muscleRecPct = min(100.0, (dSince / muscleInfo.restDays) * 100.0)
            }
        }

        // Recent avoidance
        val recentIds = (ProState.data.muscleRecentExercises[muscleGroup] ?: emptyList()).take(2)

        val candidates = pool.mapNotNull { ex ->
            val id = exId(ex.name)
            val record = ProState.data.exercises[id]
            var daysSince = 1000.0
            if (record?.lastPerformed != null) {
                val lastMs = ProState.utcDayMillis(record.lastPerformed)
                if (lastMs > 0) daysSince = max(0.0, (System.currentTimeMillis() - lastMs).toDouble() / (1000.0 * 60 * 60 * 24))
            }
            val multiplier = when (ex.importance) {
                "core" -> 1.5
                "accessory" -> 0.8
                else -> 1.0
            }
            var weight = daysSince * multiplier
            val overallBoost = 0.5 + (overallRec / 100.0)
            val muscleBoost = 0.7 + (muscleRecPct / 100.0) * 0.6
            weight *= overallBoost * muscleBoost
            if (recentIds.contains(id)) weight *= 0.35
            weight *= 1.0 / (1.0 + currentMuscleCount * 0.6)
            if (overallRec < 50) {
                val eq = ex.equipment.lowercase()
                if (eq == "machine" || eq == "cable" || eq == "band" || eq == "bodyweight") weight *= 1.6
                else if (eq == "barbell" || eq == "trap bar" || eq == "safety bar" || eq == "specialty bar") weight *= 0.4
            }
            if (!weight.isFinite() || weight <= 0) weight = daysSince * multiplier
            Triple(ex, id, weight)
        }.filter { ignoreReadiness || Fatigue.isMuscleReady(muscleGroup) }

        if (candidates.isEmpty()) return null
        val totalWeight = candidates.sumOf { it.third }
        var rand = Random.nextDouble() * totalWeight
        var chosen = candidates.first()
        for (c in candidates) {
            if (rand < c.third) { chosen = c; break }
            rand -= c.third
        }

        val base = Library.augmented(chosen.first)
        val stored = ProState.data.exercises[chosen.second]
        return buildBaseExercise(base, stored?.startingWeight)
    }

    fun buildBaseExercise(base: LibraryExercise, startingWeight: Double? = null): WorkoutExercise {
        val muscles = base.muscles
        val dist = if (muscles.isNotEmpty()) muscles.associateWith { 1.0 / muscles.size } else emptyMap()
        return WorkoutExercise(
            id = exId(base.name),
            name = base.name,
            muscleGroup = muscles,
            prescribed = Prescription(sets = base.defaultSets, reps = base.defaultReps, weight = startingWeight),
            progressionNotes = base.progression,
            equipment = base.equipment,
            instructions = base.instructions,
            prescriptionType = base.prescriptionType,
            loadDistribution = dist,
            noFatigue = base.noFatigue,
            fitnessComponents = base.fitnessComponents,
            defaultDuration = base.defaultDuration,
            strengthIndex = base.strengthIndex,
        )
    }

    /** generateExerciseFromLibrary (L35299) — fallback random pick excluding recents. */
    fun generateExerciseFromLibrary(muscleGroup: String): WorkoutExercise? {
        val groupKey = KEY_MAP[muscleGroup] ?: muscleGroup
        val all = Library.groups[groupKey].orEmpty().ifEmpty {
            // muscle group may itself be a library section key
            Library.groups[muscleGroup].orEmpty()
        }
        if (all.isEmpty()) return null
        val recent = ProState.data.muscleRecentExercises[muscleGroup] ?: emptyList()
        var candidates = all.filter { exId(it.name) !in recent }
        if (candidates.isEmpty()) candidates = all
        val base = Library.augmented(candidates[Random.nextInt(candidates.size)])
        val stored = ProState.data.exercises[exId(base.name)]
        return buildBaseExercise(base, stored?.startingWeight)
    }

    // ---------- prescription (L35363, all branches, verbatim strings) ----------
    fun generateExercisePrescription(exerciseIn: WorkoutExercise, phaseMultiplier: Double = 1.0): WorkoutExercise {
        var exercise = exerciseIn
        val exIdStr = exercise.id
        val record = ProState.data.exercises[exIdStr] ?: ExerciseRecord()
        val user = ProState.data.user

        // 1. exercise-specific history
        var lastSession: com.peakform.fitness.core.HistoryEntry? = record.history.lastOrNull()

        // 2. muscle-group fallback
        if (lastSession == null && exercise.muscleGroup.isNotEmpty()) {
            val target = exercise.muscleGroup.toSet()
            var bestDate = 0L
            var bestEntry: com.peakform.fitness.core.HistoryEntry? = null
            for ((otherId, exData) in ProState.data.exercises) {
                if (exData.history.isEmpty()) continue
                val other = Library.getExerciseById(otherId, includeBodyweight = true) ?: continue
                if (other.muscles.none { it in target }) continue
                val recentEntry = exData.history.lastOrNull { !it.skipped } ?: continue
                val d = ProState.utcDayMillis(recentEntry.date)
                if (d > bestDate) { bestDate = d; bestEntry = recentEntry }
            }
            lastSession = bestEntry
        }

        // ---- 3. First exposure ----
        if (lastSession == null) {
            // 3a drills / time-based
            if (exercise.noFatigue || exercise.prescriptionType == "time") {
                val baseSets = exercise.prescribed.sets.takeIf { it > 0 } ?: exercise.defaultDuration?.toInt() ?: 3
                val baseReps = exercise.prescribed.reps.ifBlank { "10" }
                exercise = exercise.copy(prescribed = exercise.prescribed.copy(sets = baseSets, reps = baseReps, weight = null))
                if (exercise.prescriptionType == "time" || exercise.defaultDuration != null) {
                    exercise = exercise.copy(prescribed = exercise.prescribed.copy(duration = exercise.defaultDuration ?: 45.0))
                }
                return exercise.copy(progressionNotes = exercise.progressionNotes.ifBlank { "Quality over quantity — crisp reps, steady breathing." })
            }
            val lib = Library.getExerciseById(exIdStr, includeBodyweight = true)
            var estimated1RM: Double
            val exRec = ProState.data.exercises[exIdStr]
            if (exRec?.tested1RM != null && exRec.tested1RM > 0) {
                estimated1RM = exRec.tested1RM
            } else {
                val userWeight = user.weight ?: 150.0
                val targetMuscles = exercise.muscleGroup
                var strengthIndex = 0.0
                if (targetMuscles.isNotEmpty()) {
                    var totalSI = 0.0; var count = 0
                    for ((_, list) in Library.groups) for (ex in list) {
                        if (ex.noFatigue) continue
                        if (ex.muscles.any { it in targetMuscles }) {
                            totalSI += ex.strengthIndex; count++
                        }
                    }
                    strengthIndex = if (count > 0) totalSI / count else (exercise.strengthIndex ?: 0.7)
                } else {
                    strengthIndex = exercise.strengthIndex ?: 0.7
                }
                if (user.birthDate != null) {
                    val age = Sessions.calculateAge(user.birthDate) ?: 30
                    if (age > 60) strengthIndex *= 0.7
                    else if (age > 50) strengthIndex *= 0.8
                    else if (age > 40) strengthIndex *= 0.9
                }
                estimated1RM = userWeight * strengthIndex * phaseMultiplier
                if (estimated1RM.isNaN() || estimated1RM < 20) estimated1RM = 50.0
            }
            // muscle maturity
            val targetMuscles = exercise.muscleGroup
            var muscleWorkouts = 0
            if (targetMuscles.isNotEmpty()) {
                for (w in ProState.data.workouts) {
                    val trained = w.exercises.any { ex ->
                        ex.actual != null && !ex.skipped && ex.muscleGroup.any { it in targetMuscles }
                    }
                    if (trained) muscleWorkouts++
                }
            }
            estimated1RM *= 1 + 0.1 * (1 - exp(-muscleWorkouts / 20.0))

            val startingIntensity = mapOf("beginner" to 0.50, "intermediate" to 0.55, "advanced" to 0.60)[user.experience] ?: 0.55
            val firstFiberRange = if (lib != null) OneRm.fiberWeightRange(lib, estimated1RM, 18.0) else null
            var prescribedWeight = (firstFiberRange?.min ?: (estimated1RM * startingIntensity))
                .coerceAtMost(estimated1RM * 0.60)
            prescribedWeight = OneRm.roundToNearest(prescribedWeight)
            if (prescribedWeight.isNaN() || prescribedWeight < 5) prescribedWeight = 5.0

            val intensity = startingIntensity
            val totalWorkouts = ProState.data.workouts.size
            val expressMode = user.settings.expressMode
            val muscleType = getExerciseMuscleType(exercise)
            var targetSets = humpSets(intensity, muscleType, totalWorkouts, expressMode)
            if (targetSets < 2) targetSets = 3
            targetSets = min(7, max(2, targetSets))

            exercise = exercise.copy(prescribed = Prescription(
                sets = targetSets, reps = "17-20", weight = prescribedWeight,
                weightMax = firstFiberRange?.max,
                duration = if (exercise.prescriptionType == "time") (exercise.defaultDuration ?: 60.0) else null,
            ))
            val fiberNoteFirst = firstFiberRange?.let { " Fiber window ${it.lowPct}-${it.maxPct}% 1RM." } ?: ""
            return exercise.copy(progressionNotes = "First time for this muscle group! Starting with higher reps (17-20). Aim for 20 reps before increasing weight.$fiberNoteFirst")
        }

        // ---- 3b time-based with history ----
        if (exercise.prescriptionType == "time") {
            val libSets = exercise.prescribed.sets.takeIf { it > 0 } ?: 3
            val duration = exercise.defaultDuration ?: exercise.prescribed.duration ?: 45.0
            return exercise.copy(
                prescribed = Prescription(sets = libSets, reps = "hold", weight = null, duration = duration),
                progressionNotes = "Hold for ${duration.toInt()} seconds per set. Breathe slowly, sink deeper on each exhale, never bounce.",
            )
        }

        // ---- 3c no-fatigue drills with history ----
        if (exercise.noFatigue) {
            val libSets = exercise.prescribed.sets.takeIf { it > 0 } ?: 3
            val libReps = exercise.prescribed.reps.ifBlank { "10" }
            var drillSets = libSets
            val lastRPEdrill = lastSession.rpeList.firstOrNull() ?: 7.0
            drillSets = when {
                lastRPEdrill >= 9 -> max(2, lastSession.sets - 1)
                lastRPEdrill <= 5 -> min(8, lastSession.sets + 1)
                else -> lastSession.sets
            }
            exercise = exercise.copy(prescribed = exercise.prescribed.copy(sets = drillSets, reps = libReps, weight = null))
            if (exercise.defaultDuration != null) {
                exercise = exercise.copy(prescribed = exercise.prescribed.copy(duration = exercise.defaultDuration))
            }
            return exercise.copy(progressionNotes = "Athletic drill — every rep crisp. Only add load when form is perfect on all sets.")
        }

        // ---- 4. main progression path ----
        var newReps = lastSession.reps.maxOrNull() ?: 1.0
        var newSets = lastSession.sets

        val bias = user.momentumBias.value
        val lastRPE = lastSession.rpeList.firstOrNull() ?: 7.0
        val rpeFactor = when {
            lastRPE >= 9 -> 0.6
            lastRPE <= 5 -> 1.3
            else -> 1.0
        }
        val maxRepDrop = if (user.settings.expressMode) 3 else 2
        val repChange = min(2, max(-maxRepDrop, ((-bias) * rpeFactor).roundToInt()))
        newReps += repChange

        // natural progression when top of previous range was reached
        val lastRange = exercise.prescribed.reps.split("-")
        if (lastRange.size == 2 && lastRange[1].toIntOrNull() != null && newReps >= lastRange[1].toInt() && bias == 0.0) {
            newReps = max(1.0, newReps - 1)
        }

        val repFloor = getExerciseRepFloor(exercise)
        newReps = max(repFloor.toDouble(), newReps)
        newReps = min(20.0, max(1.0, newReps.roundToInt().toDouble()))

        // ---- 5. weight from reps ----
        val muscleType = getExerciseMuscleType(exercise)
        val lib = Library.getExerciseById(exIdStr, includeBodyweight = true)
        var oneRM = ProState.data.exercises[exIdStr]?.tested1RM
        if (oneRM == null || oneRM <= 0) {
            val lastRepsFor1RM = lastSession.reps.maxOrNull() ?: 1.0
            oneRM = lastSession.weight * (1 + lastRepsFor1RM / 30.0)
        }
        if (oneRM == null || oneRM <= 0) {
            oneRM = (ProState.data.user.cache?.effectiveBodyWeight ?: user.weight ?: 150.0) * (exercise.strengthIndex ?: 0.7)
        }
        val fiberRange = if (lib != null) OneRm.fiberWeightRange(lib, oneRM!!, newReps) else null
        val targetIntensity = min(0.95, OneRm.epleyIntensityForReps(newReps))
        var targetWeight = fiberRange?.min ?: (oneRM!! * targetIntensity)

        val lastRPEStep = lastSession.rpeList.firstOrNull() ?: 7.0
        var newWeight = lastSession.weight
        val maxStep = min(7.5, max(2.5, lastSession.weight * 0.10))
        if (targetWeight > newWeight) newWeight += min(maxStep, targetWeight - newWeight)
        else if (targetWeight < newWeight - 2.5) newWeight -= min(maxStep, newWeight - targetWeight)
        newWeight = OneRm.roundToNearest(newWeight)
        newWeight += (if (lastRPEStep >= 9) -2.5 else if (lastRPEStep <= 5) 2.5 else 0.0)
        newWeight = OneRm.roundToNearest(newWeight)
        if (fiberRange != null && fiberRange.max > 0) newWeight = min(newWeight, fiberRange.max)
        newWeight = min(min(oneRM!! * 0.95, lastSession.weight * 1.10), newWeight)
        if (newWeight < 20) newWeight = 20.0

        // ---- 6. rep range wrapper ----
        val muscleRepFloor = getExerciseRepFloor(exercise)
        var repLow = max(muscleRepFloor, max(1, newReps.roundToInt() - 1))
        var repHigh = min(20, max(repLow + 1, newReps.roundToInt() + 1))
        if (repLow == repHigh) {
            repLow = max(1, repLow - 1)
            repHigh = min(20, repHigh + 1)
        }

        val totalWorkouts = ProState.data.workouts.size
        val expressMode = user.settings.expressMode
        var targetSets = humpSets(targetIntensity, muscleType, totalWorkouts, expressMode)
        targetSets = min(7, max(2, targetSets))

        val setChange = min(1, max(-1, targetSets - lastSession.sets))
        newSets = min(7, max(2, lastSession.sets + setChange))

        // ---- 8. build prescription ----
        val repsString = if (exercise.prescriptionType == "amrap") "$repLow-$repHigh (AMRAP)" else "$repLow-$repHigh"
        exercise = exercise.copy(prescribed = Prescription(
            sets = newSets,
            reps = repsString,
            weight = newWeight,
            weightMax = fiberRange?.max,
            duration = exercise.prescribed.duration ?: exercise.defaultDuration,
        ))

        // ---- 9. notes ----
        var notes = "Gradual step from last session on same muscle group (${OneRm.roundToNearest(lastSession.weight)} lbs × ${lastSession.reps.joinToString(" / ").ifEmpty { "?" }} reps). "
        notes += when {
            bias > 0.2 -> "Steering toward STRONGER → $repChange reps. "
            bias < -0.2 -> "Steering toward BIGGER → $repChange reps. "
            else -> "Neutral steering. "
        }
        notes += "Aim for $repHigh reps before increasing weight. "
        if (fiberRange != null) {
            notes += "Fiber window ${fiberRange.lowPct}-${fiberRange.maxPct}% 1RM — today's cap ${fiberRange.max} lbs. "
            if (fiberRange.note.isNotEmpty()) notes += fiberRange.note
        }
        return exercise.copy(progressionNotes = notes)
    }

    // ---------- performGenerateWorkout (L36069) ----------
    data class GenResult(val workout: WorkoutRecord, val notification: String?, val deloadByVolume: Boolean)

    fun performGenerateWorkout(timeBudgetMin: Int? = null, suppressConfirm: Boolean = true): GenResult {
        Fatigue.applyFatigueDecay()
        decayMomentumBias()

        val d = ProState.data
        val systemicFactor = Stats.systemicRecoveryFactor()

        // deload decision
        val avgWeeklyVolume = if ((d.user.weeklyVolumes.size) >= 4) {
            d.user.weeklyVolumes.takeLast(4).sumOf { it.totalVolume } / 4.0
        } else {
            d.user.aggregates?.totalVolumeLast7Days ?: 0.0
        }
        val currentWeekVolume = d.user.aggregates?.totalVolumeLast7Days ?: 0.0
        val volumeDeload = currentWeekVolume > avgWeeklyVolume * 1.5
        val systemicDeload = systemicFactor < 0.6
        val needsDeload = systemicDeload || volumeDeload

        var isTapering = false
        d.user.competition?.date?.let { comp ->
            val meetMs = ProState.utcDayMillis(comp)
            if (meetMs > 0) {
                val daysToMeet = Math.ceil((meetMs - System.currentTimeMillis()).toDouble() / (1000.0 * 60 * 60 * 24))
                if (daysToMeet <= 7 && daysToMeet >= 0) isTapering = true
            }
        }

        // split rotation
        val lastWorkout = d.workouts.lastOrNull()
        var split = if (lastWorkout != null) {
            val idx = SPLITS.indexOfFirst { it.id == lastWorkout.type }
            if (idx != -1) SPLITS[(idx + 1) % SPLITS.size] else SPLITS[0]
        } else SPLITS[0]
        if (needsDeload && !isTapering) {
            split = SPLITS.firstOrNull { it.id == "mobility_flexibility" } ?: split
        }

        val phase = Sessions.getCurrentCyclePhase()
        val mult = Sessions.getPhaseMultiplier(phase)

        var workout = WorkoutRecord(
            id = ProState.newWorkoutId(),
            date = ProState.nowIso(),
            type = split.id,
            name = split.name + (if (needsDeload && !isTapering) " (Deload)" else if (isTapering) " (Taper)" else ""),
            exercises = emptyList(),
            isDeload = needsDeload && !isTapering,
            isTaper = isTapering,
        )
        // updateWorkoutCache
        workout = workout.copy(cache = workout.cache?.copy(
            recoveryFactor = systemicFactor,
            sessionPriorBase = (d.user.cache?.priorBaseMultiplier ?: 1.0) * systemicFactor,
            sessionDeltaBase = (d.user.cache?.progBaseMultiplier ?: 1.0) * systemicFactor,
        ) ?: com.peakform.fitness.core.WorkoutCache(
            recoveryFactor = systemicFactor,
            sessionPriorBase = (d.user.cache?.priorBaseMultiplier ?: 1.0) * systemicFactor,
            sessionDeltaBase = (d.user.cache?.progBaseMultiplier ?: 1.0) * systemicFactor,
        ))

        // muscle selection
        val allMuscles = Library.allMuscleGroups().map { it.name }
        val targetMuscles = split.focus.toMutableSet()
        val overdue = Fatigue.getOverdueMuscles(1.5, 2)
        overdue.forEach { targetMuscles.add(it.name) }

        val readyMuscles = Fatigue.getReadyMuscles(targetMuscles).map { it.name }
        val focusCount = split.focus.size
        var accessoryTarget = Random.nextInt(3) + 2
        accessoryTarget = min(accessoryTarget, max(2, 8 - focusCount))
        accessoryTarget = max(0, accessoryTarget)

        if (readyMuscles.isNotEmpty() && accessoryTarget > 0) {
            val shuffled = readyMuscles.shuffled()
            shuffled.take(min(accessoryTarget, shuffled.size)).forEach { targetMuscles.add(it) }
        } else {
            val fallback = allMuscles.filter { it !in targetMuscles }
            fallback.take(min(accessoryTarget, fallback.size)).forEach { targetMuscles.add(it) }
        }
        if ("core" !in targetMuscles) {
            val coreFatigue = (d.muscleFatigue["core"]?.fast ?: 0.0) + (d.muscleFatigue["core"]?.slow ?: 0.0)
            if (coreFatigue < Fatigue.READINESS_THRESHOLD) targetMuscles.add("core")
        }

        // per-muscle exercise generation
        val selectedIds = mutableSetOf<String>()
        val exercises = mutableListOf<WorkoutExercise>()
        for (mg in targetMuscles) {
            val ignoreReadiness = overdue.any { it.name == mg }
            var ex = selectExerciseForMuscle(mg, ignoreReadiness, currentWorkoutExercises = exercises, trainingMode = d.user.settings.trainingMode)
            if (ex == null) ex = generateExerciseFromLibrary(mg)
            if (ex == null) continue
            if (selectedIds.contains(ex.id)) continue
            selectedIds.add(ex.id)

            var localMultiplier = mult
            if (needsDeload && !isTapering) localMultiplier *= 0.8
            val muscleDeloadMultiplier = if (d.user.muscleDeloadStatus?.get(mg)?.active == true) 0.7 else 1.0
            if (muscleDeloadMultiplier < 1.0) localMultiplier *= muscleDeloadMultiplier

            val prescribed = generateExercisePrescription(ex, localMultiplier)
            exercises.add(prescribed)
        }

        // fallback defaults
        if (exercises.isEmpty()) {
            val defaults = listOf("quads", "chest", "hamstrings")
            for (mg in defaults) {
                val ex = generateExerciseFromLibrary(mg) ?: continue
                exercises.add(generateExercisePrescription(ex, mult))
            }
        }

        // shuffle main lifts, then athletic blocks
        workout = workout.copy(exercises = exercises.shuffled())
        workout = appendAthleticBlocks(workout, needsDeload && !isTapering, isTapering)

        // time wizard trim
        if (timeBudgetMin != null && timeBudgetMin > 0) {
            val trimmed = trimWorkoutToBudget(workout, timeBudgetMin)
            workout = trimmed
        }

        ProState.currentWorkout = workout
        ProState.saveCurrentWorkoutToStorage()
        ProState.performSave()
        ProState.notifyChanged()

        val notification = when {
            needsDeload && !isTapering && volumeDeload -> "Deload triggered by high weekly volume. Reduce intensity."
            needsDeload && !isTapering -> "Deload week activated – focus on form and recovery."
            isTapering -> "Taper week – reduce volume, keep intensity for peak performance."
            else -> "Workout generated: ${workout.name}"
        }
        ProLog.i("GEN", notification ?: "")
        return GenResult(workout, notification, volumeDeload)
    }

    fun decayMomentumBias() {
        val bias = ProState.data.user.momentumBias
        if (bias.lastUpdated == null) return
        val daysSince = (System.currentTimeMillis() - ProState.utcDayMillis(bias.lastUpdated)) / (1000.0 * 60 * 60 * 24)
        if (daysSince >= 1) {
            ProState.data = ProState.data.copy(
                user = ProState.data.user.copy(
                    momentumBias = bias.copy(value = bias.value * (1 - bias.decayRate), lastUpdated = ProState.nowIso())
                )
            )
        }
    }

    // ---------- athletic blocks (L35971) ----------
    fun pickDrillFromGroup(groupKey: String, excludeIds: Set<String>): LibraryExercise? {
        val pool = (Library.groups[groupKey] ?: emptyList()).filter { exId(it.name) !in excludeIds }
        if (pool.isEmpty()) return null
        return pool[Random.nextInt(pool.size)]
    }

    fun buildDrillExercise(base: LibraryExercise): WorkoutExercise {
        val ex = buildBaseExercise(base)
        return ex.copy(
            prescribed = ex.prescribed.copy(
                sets = base.defaultSets.takeIf { it > 0 } ?: 3,
                reps = base.defaultReps.ifBlank { "10" },
                weight = null,
                duration = base.defaultDuration,
            ),
            noFatigue = true,
            equipment = base.equipment.ifBlank { "none" },
        )
    }

    fun appendAthleticBlocks(workoutIn: WorkoutRecord, isDeload: Boolean, isTaper: Boolean): WorkoutRecord {
        var workout = workoutIn
        val split = splitById(workout.type)
        val usedIds = workout.exercises.map { it.id }.toSet()

        // 1) warm-up
        if (!isDeload) {
            val warmBase = pickDrillFromGroup("mobility_dynamic", usedIds)
            if (warmBase != null) {
                val warm = buildDrillExercise(warmBase).copy(
                    isWarmup = true,
                    prescribed = Prescription(sets = 2, reps = warmBase.defaultReps, weight = null, duration = warmBase.defaultDuration),
                    progressionNotes = "Dynamic warm-up — move smoothly, no straining.",
                )
                workout = workout.copy(exercises = listOf(warm) + workout.exercises)
            }
        }

        // 2) component drills
        if (split != null && split.drills.isNotEmpty() && !isDeload) {
            val used = workout.exercises.map { it.id }.toMutableSet()
            for (groupKey in split.drills) {
                val base = pickDrillFromGroup(groupKey, used) ?: continue
                var drill = buildDrillExercise(base).copy(isComponentDrill = true, component = split.component)
                if (isTaper) {
                    drill = drill.copy(prescribed = drill.prescribed.copy(sets = max(2, (drill.prescribed.sets * 0.6).roundToInt())))
                }
                used.add(drill.id)
                workout = workout.copy(exercises = workout.exercises + drill)
            }
        }

        // 3) cooldown stretches
        val trained = workout.exercises.flatMap { it.muscleGroup }.toSet()
        val stretchCount = if (isDeload) 4 else 2
        val picked = mutableSetOf<String>()
        val stretchPool = Library.groups["stretching_static"].orEmpty()
        repeat(stretchCount) {
            var pool = stretchPool.filter { ex ->
                exId(ex.name) !in picked && ex.muscles.any { m -> trained.contains(m) }
            }
            if (pool.isEmpty()) {
                pool = stretchPool.filter { exId(it.name) !in picked }
                if (pool.isEmpty()) return@repeat
            }
            val base = pool[Random.nextInt(pool.size)]
            picked.add(exId(base.name))
            val stretch = buildDrillExercise(base).copy(
                isCooldown = true,
                progressionNotes = "Cooldown stretch — breathe slowly, never bounce.",
            )
            workout = workout.copy(exercises = workout.exercises + stretch)
        }
        return workout
    }

    // ---------- longevity workout (L45177) ----------
    fun generateLongevityWorkout(): GenResult {
        Fatigue.applyFatigueDecay()
        val targetMuscles = mutableSetOf<String>()

        // risk-exercise muscles
        for (risk in Stats.assessAgingRisks()) {
            Library.allLibraryExercises(ProState.data.user.settings.trainingMode)
                .firstOrNull { it.name == risk.muscle || it.muscles.contains(risk.muscle) }
                ?.muscles?.forEach { targetMuscles.add(it) }
        }
        // never-trained longevity muscles
        for (m in Library.allMuscleGroups().filter { it.category == "longevity" || it.category == "grip" || it.category == "foot" }) {
            if (Fatigue.lastTrainedMs(m.name) == null) targetMuscles.add(m.name)
        }
        if (targetMuscles.isEmpty()) {
            listOf("neck", "rhomboids", "rear_delts", "forearms", "foot_intrinsics").forEach { targetMuscles.add(it) }
        }

        val exercises = mutableListOf<WorkoutExercise>()
        val used = mutableSetOf<String>()
        for (mg in targetMuscles) {
            val ex = selectExerciseForMuscle(mg, ignoreReadiness = true, currentWorkoutExercises = exercises) ?: continue
            if (used.contains(ex.id)) continue
            used.add(ex.id)
            exercises.add(generateExercisePrescription(ex, 1.0))
        }

        var workout = WorkoutRecord(
            id = ProState.newWorkoutId(),
            date = ProState.nowIso(),
            type = "longevity_day",
            name = "Personalised Longevity & Joint Health",
            exercises = exercises,
        )
        workout = appendAthleticBlocks(workout, isDeload = true, isTaper = false)
        ProState.currentWorkout = workout
        ProState.saveCurrentWorkoutToStorage()
        ProState.performSave()
        ProState.notifyChanged()
        return GenResult(workout, "Longevity workout generated", false)
    }

    // ---------- recalcExerciseFromWeight (L35803) ----------
    fun recalcExerciseFromWeight(exercise: WorkoutExercise, newWeight: Double): WorkoutExercise {
        val userW = ProState.data.user.weight ?: 150.0
        val oneRM = ProState.data.exercises[exercise.id]?.mu ?: (newWeight * 1.5)
        val intensity = (newWeight / oneRM).coerceIn(0.3, 0.95)
        val exp = ProState.data.user.experience
        val points = if (exp == "beginner") RepPoints.BEGINNER else RepPoints.ADVANCED
        // interpolate reps at intensity
        val sorted = points.sortedBy { it.first }
        val reps: Double = when {
            intensity <= sorted.first().first -> sorted.first().second
            intensity >= sorted.last().first -> sorted.last().second
            else -> {
                for (i in 0 until sorted.size - 1) {
                    val a = sorted[i]; val b = sorted[i + 1]
                    if (intensity >= a.first && intensity <= b.first) {
                        val t = (intensity - a.first) / (b.first - a.first)
                        return@recalcExerciseFromWeight finishRecalc(exercise, newWeight, a.second + t * (b.second - a.second))
                    }
                }
                8.0
            }
        }
        return finishRecalc(exercise, newWeight, reps)
    }

    private fun finishRecalc(exercise: WorkoutExercise, weight: Double, reps: Double): WorkoutExercise {
        val muscleType = getExerciseMuscleType(exercise)
        val sets = humpSets(weight / (ProState.data.exercises[exercise.id]?.mu ?: (weight * 1.5)), muscleType, ProState.data.workouts.size, ProState.data.user.settings.expressMode)
        val r = reps.roundToInt().coerceIn(1, 20)
        return exercise.copy(prescribed = exercise.prescribed.copy(weight = weight, reps = "$r-${min(20, r + 2)}", sets = sets))
    }

    object RepPoints {
        val BEGINNER = listOf(
            0.30 to 20.0, 0.40 to 15.0, 0.50 to 12.0, 0.60 to 10.0, 0.75 to 8.0, 0.85 to 5.0,
        )
        val ADVANCED = listOf(
            0.30 to 20.0, 0.40 to 16.0, 0.50 to 12.0, 0.60 to 9.0, 0.75 to 6.0, 0.85 to 3.0,
        )
    }

    // ---------- time wizard (L63545) ----------
    fun estimateMin(ex: WorkoutExercise, restSec: Int): Double = when {
        ex.isWarmup -> 2.0
        ex.isCooldown -> 2.0
        ex.isComponentDrill || ex.noFatigue -> (ex.prescribed.sets.takeIf { it > 0 } ?: 3) * 0.75
        else -> ((ex.prescribed.sets.takeIf { it > 0 } ?: 3) * (50.0 + restSec) + 60.0) / 60.0
    }

    fun estimateWorkoutMin(workout: WorkoutRecord, restSec: Int): Double =
        workout.exercises.sumOf { estimateMin(it, restSec) }

    /** trimWorkoutToBudget — returns (workout, minutesSaved) */
    fun trimWorkoutToBudget(workoutIn: WorkoutRecord, maxMinutes: Int): WorkoutRecord {
        var exercises = workoutIn.exercises.toMutableList()
        if (exercises.isEmpty()) return workoutIn
        val restSec = ProState.data.user.settings.restTime

        fun totalMin(): Double = exercises.sumOf { estimateMin(it, restSec) }
        fun mainCount(): Int = exercises.count { !it.isWarmup && !it.isCooldown }
        fun prio(ex: WorkoutExercise): Int = when {
            ex.isWarmup -> 100
            ex.isCooldown -> 70
            ex.isComponentDrill -> 20
            ex.noFatigue -> 15
            ex.fitnessComponents.contains("core") -> 90
            else -> 60
        }
        fun dropLowest(maxPrio: Int): Boolean {
            var dropIdx = -1
            var dropPrio = Int.MAX_VALUE
            exercises.forEachIndexed { i, ex ->
                val p = prio(ex)
                if (p <= maxPrio && p < dropPrio) { dropPrio = p; dropIdx = i }
            }
            if (dropIdx == -1) return false
            exercises.removeAt(dropIdx)
            return true
        }

        // Stage 1 — drop drills
        while (totalMin() > maxMinutes && dropLowest(25)) { }
        // Stage 2 — reduce sets (floor 2), largest first
        while (totalMin() > maxMinutes) {
            var target = -1
            var targetSets = 2
            exercises.forEachIndexed { i, ex ->
                if (ex.isWarmup || ex.isCooldown) return@forEachIndexed
                val cnt = ex.prescribed.sets
                if (cnt > targetSets) { targetSets = cnt; target = i }
            }
            if (target == -1) break
            exercises[target] = exercises[target].let { it.copy(prescribed = it.prescribed.copy(sets = targetSets - 1)) }
        }
        // Stage 3 — accessories
        while (totalMin() > maxMinutes && mainCount() > 2 && dropLowest(40)) { }
        // Stage 4 — regular mains
        while (totalMin() > maxMinutes && mainCount() > 2 && dropLowest(60)) { }
        // Stage 5 — extra cooldowns, floor 1
        while (totalMin() > maxMinutes) {
            val coolIdx = exercises.indexOfFirst { it.isCooldown }
            if (coolIdx == -1 || exercises.count { it.isCooldown } <= 1) break
            exercises.removeAt(coolIdx)
        }

        return workoutIn.copy(exercises = exercises)
    }

    // ---------- workout chips data (L38178) ----------
    data class ChipData(val name: String, val focus: String, val estTime: Int, val intensity: String, val deload: Boolean, val taper: Boolean, val componentChip: String?)

    fun chipData(workout: WorkoutRecord?): ChipData? {
        if (workout == null || workout.exercises.isEmpty()) return null
        val split = splitById(workout.type)
        val focus = split?.focus?.joinToString(", ") { it.replace('_', ' ') } ?: "Full Body"
        val exerciseCount = workout.exercises.size
        // FIX (audit): was exerciseCount * 5 + 10 — wildly inconsistent with the engine's own
        // estimateWorkoutMin() (e.g. 13 drills = "75 min" chip vs 45 min real). Use the engine estimate.
        val estTime = estimateWorkoutMin(workout, ProState.data.user.settings.restTime).toInt().coerceAtLeast(5)
        val intensity = when {
            workout.readiness == null -> "Moderate"
            workout.readiness >= 80 -> "High"
            workout.readiness >= 60 -> "Moderate-High"
            workout.readiness >= 40 -> "Moderate"
            else -> "Low"
        }
        return ChipData(
            name = workout.name.ifBlank { split?.name ?: "Custom" },
            focus = focus,
            estTime = estTime,
            intensity = intensity,
            deload = workout.isDeload,
            taper = workout.isTaper,
            componentChip = null,
        )
    }
}
