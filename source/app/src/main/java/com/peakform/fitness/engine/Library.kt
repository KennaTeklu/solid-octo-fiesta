package com.peakform.fitness.engine

import android.content.Context
import com.peakform.fitness.core.BodyWeightEntry
import com.peakform.fitness.core.ExerciseRecord
import com.peakform.fitness.core.FatigueState
import com.peakform.fitness.core.LibraryExercise
import com.peakform.fitness.core.ProJson
import com.peakform.fitness.ProLog
import com.peakform.fitness.core.BodyweightExercise
import com.peakform.fitness.core.ProState
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Library — loads the verbatim legacy data assets (ultimateExerciseLibrary 1,413 entries,
 * 33 groups + 145 bodyweight exercises + muscle database + fiber profiles + component registry)
 * and provides the lookup helpers the engine needs (translations of getExerciseById,
 * getAllMuscleGroups, augmentLibrary, getEnhancedExerciseList).
 */
object Library {
    var groups: Map<String, List<LibraryExercise>> = emptyMap()
        private set
    var bodyweight: Map<String, List<BodyweightExercise>> = emptyMap()
        private set
    var muscleDatabase: JsonObject = JsonObject(emptyMap())
        private set
    var fiberProfiles: Map<String, FiberWindow> = emptyMap()
        private set
    var muscleDecay: Map<String, Decay> = emptyMap()
        private set
    var defaultDecay: Decay = Decay(0.0833, 0.0072)
        private set
    var muscleWeights: Map<String, Double> = emptyMap()
        private set
    var componentRegistry: Map<String, List<String>> = emptyMap()
        private set
    var mpComponents: List<MpComponent> = emptyList()
        private set
    var accents: List<AccentDef> = emptyList()
        private set
    var readingLevels: List<LevelDef> = emptyList()
        private set
    var sheetItems: List<SheetItem> = emptyList()
        private set
    var langs: List<LangDef> = emptyList()
        private set
    var animals: List<String> = emptyList()
        private set
    var strengthRatios: Map<String, Map<String, Double>> = emptyMap()
        private set
    var config: JsonObject = JsonObject(emptyMap())
        private set

    @kotlinx.serialization.Serializable
    data class FiberWindow(val fiber: String, val min: Double, val max: Double, val note: String)
    @kotlinx.serialization.Serializable
    data class Decay(val fast: Double, val slow: Double)
    @kotlinx.serialization.Serializable
    data class MpComponent(val id: String, val name: String, val short: String, val icon: String, val color: String, val tagline: String, val desc: String, val train: List<String>)
    @kotlinx.serialization.Serializable
    data class AccentDef(val id: String, val name: String, val hex: String)
    @kotlinx.serialization.Serializable
    data class LevelDef(val id: Int, val name: String, val desc: String)
    @kotlinx.serialization.Serializable
    data class SheetItem(val id: String, val icon: String, val label: String, val fn: String?)
    @kotlinx.serialization.Serializable
    data class LangDef(val code: String, val name: String)

    private val customExercises = mutableListOf<LibraryExercise>()

    @Volatile private var loaded = false

    /** Test hook (JVM): optional first-chance asset source. */
    @Volatile var testAssetLoader: ((String) -> String?)? = null

    fun ensure(ctx: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            loadImpl { name -> testAssetLoader?.invoke(name) ?: ctx.assets.open("data/$name").bufferedReader().use { it.readText() } }
        }
    }

    /** JVM-test entry: loads the library from an injected source (no Android context needed). */
    fun ensureForTest(loader: (String) -> String) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            loadImpl { name -> testAssetLoader?.invoke(name) ?: loader(name) }
        }
    }

    private fun loadImpl(asset: (String) -> String) {
        try {
                val j = Json { ignoreUnknownKeys = true; isLenient = true }

                val rawGroups = j.parseToJsonElement(asset("exercises.json")).jsonObject
                groups = rawGroups.mapValues { (_, v) ->
                    j.decodeFromString(kotlinx.serialization.builtins.ListSerializer(LibraryExercise.serializer()), v.toString())
                }
                val bw = j.parseToJsonElement(asset("bodyweight.json")).jsonObject
                bodyweight = bw.mapValues { (_, v) ->
                    j.decodeFromString(kotlinx.serialization.builtins.ListSerializer(BodyweightExercise.serializer()), v.toString())
                }
                muscleDatabase = j.parseToJsonElement(asset("muscles.json")).jsonObject
                runCatching {
                    val fp = j.parseToJsonElement(asset("fiber_profiles.json")).jsonObject
                    fiberProfiles = fp.mapValues { (_, v) ->
                        val o = v.jsonObject
                        FiberWindow(
                            o["fiber"]?.jsonPrimitive?.content ?: "mixed",
                            o["min"]?.jsonPrimitive?.doubleOrNull ?: 0.5,
                            o["max"]?.jsonPrimitive?.doubleOrNull ?: 0.85,
                            o["note"]?.jsonPrimitive?.contentOrNull ?: "",
                        )
                    }
                }
                runCatching {
                    val md = j.parseToJsonElement(asset("muscle_decay.json")).jsonObject
                    muscleDecay = md.mapValues { (_, v) ->
                        val o = v.jsonObject
                        Decay(o["fast"]?.jsonPrimitive?.double ?: 0.0833, o["slow"]?.jsonPrimitive?.double ?: 0.0072)
                    }
                    val dd = j.parseToJsonElement(asset("default_decay.json")).jsonObject
                    defaultDecay = Decay(dd["fast"]?.jsonPrimitive?.double ?: 0.0833, dd["slow"]?.jsonPrimitive?.double ?: 0.0072)
                }
                runCatching {
                    val mw = j.parseToJsonElement(asset("muscle_weights.json")).jsonObject
                    muscleWeights = mw.mapValues { it.value.jsonPrimitive.doubleOrNull ?: 1.0 }
                }
                runCatching {
                    val cr = j.parseToJsonElement(asset("components.json")).jsonObject
                    componentRegistry = cr.mapValues { (_, v) ->
                        val o = v.jsonObject
                        (o["components"] as? kotlinx.serialization.json.JsonArray)
                            ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: emptyList()
                    }
                }
                runCatching {
                    val comps = j.parseToJsonElement(asset("mp_components.json"))
                    mpComponents = j.decodeFromString(kotlinx.serialization.builtins.ListSerializer(MpComponent.serializer()), comps.toString())
                }
                runCatching {
                    accents = j.decodeFromString(kotlinx.serialization.builtins.ListSerializer(AccentDef.serializer()), asset("accents.json"))
                }
                runCatching {
                    readingLevels = j.decodeFromString(kotlinx.serialization.builtins.ListSerializer(LevelDef.serializer()), asset("levels.json"))
                }
                runCatching {
                    sheetItems = j.decodeFromString(kotlinx.serialization.builtins.ListSerializer(SheetItem.serializer()), asset("sheet_items.json"))
                }
                runCatching {
                    langs = j.decodeFromString(kotlinx.serialization.builtins.ListSerializer(LangDef.serializer()), asset("langs.json"))
                }
                runCatching {
                    animals = j.decodeFromString(kotlinx.serialization.builtins.ListSerializer(String.serializer()), asset("animals.json"))
                }
                runCatching {
                    val sr = j.parseToJsonElement(asset("strength_ratios.json")).jsonObject
                    strengthRatios = sr.mapValues { (_, v) ->
                        v.jsonObject.mapValues { it.value.jsonPrimitive.doubleOrNull ?: 0.0 }
                    }
                }
                runCatching { config = j.parseToJsonElement(asset("config.json")).jsonObject }
                ProLog.i("LIB", "library loaded: ${groups.values.sumOf { it.size }} exercises in ${groups.size} groups, ${bodyweight.values.sumOf { it.size }} bodyweight")
        } catch (e: Exception) {
            ProLog.e("LIB", "FAILED to load data assets: ${e.message}", e)
        }
        loaded = true
    }

    // ---- lookups (translations of getExerciseById / augmentLibrary) ----

    /** All exercises from the gym library + (optionally) bodyweight library + user customs. */
    fun getExerciseById(id: String, includeBodyweight: Boolean = false): LibraryExercise? {
        groups.forEach { (_, list) -> list.firstOrNull { it.id == id }?.let { return it } }
        customExercises.firstOrNull { it.id == id }?.let { return it }
        if (includeBodyweight) {
            bodyweight.forEach { (_, list) ->
                list.firstOrNull { it.name.lowercase().replace(Regex("\\s+"), "_") == id }?.let { bw ->
                    return bwToLibrary(bw)
                }
            }
        }
        return null
    }

    fun bwToLibrary(bw: BodyweightExercise): LibraryExercise = LibraryExercise(
        name = bw.name, muscles = bw.muscles, equipment = bw.equipment, defaultSets = bw.defaultSets,
        defaultReps = bw.defaultReps, progression = bw.progression, instructions = bw.instructions,
        strengthIndex = bw.strengthIndex, skillFactor = bw.skillFactor, genderSuitability = "both",
        prescriptionType = bw.prescriptionType, fitnessComponents = bw.fitnessComponents,
        defaultDuration = bw.defaultDuration, loadDistribution = bw.loadDistribution, noFatigue = bw.noFatigue,
        primaryMuscle = bw.muscles.firstOrNull() ?: "", importance = "core",
    )

    fun allLibraryExercises(trainingMode: String = "gym"): List<LibraryExercise> {
        val base = groups.flatMap { it.value }
        val bwFlat = bodyweight.flatMap { it.value }.map { bw -> bwToLibrary(bw) }
        return when (trainingMode) {
            "bodyweight" -> bwFlat
            "mixed" -> base + bwFlat
            else -> base
        } + customExercises
    }

    fun addCustomExercise(ex: LibraryExercise) {
        if (customExercises.none { it.id == ex.id }) customExercises.add(ex)
    }

    fun customExercisesSnapshot(): List<LibraryExercise> = customExercises.toList()

    /**
     * Reinject persisted custom exercises (p4_custom_ex) into the library — called at
     * boot and after Studio add/import (legacy reinjectCustom, PF L57204–57216).
     */
    fun reinjectCustom(ctx: Context) {
        try {
            val raw = com.peakform.fitness.core.ProPrefs.get(ctx, com.peakform.fitness.core.Studio.CUST_KEY) ?: return
            val arr = ProJson.json.parseToJsonElement(raw) as? kotlinx.serialization.json.JsonArray ?: return
            arr.forEach { el ->
                val o = el as? kotlinx.serialization.json.JsonObject ?: return@forEach
                val name = (o["name"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: return@forEach
                val muscles = (o["muscles"] as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull } ?: emptyList()
                val steps = (o["instructions"] as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull } ?: emptyList()
                val ex = LibraryExercise(
                    name = name,
                    muscles = muscles,
                    equipment = (o["equipment"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: "bodyweight",
                    instructions = steps,
                    prescriptionType = (o["prescriptionType"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: "reps",
                    imageUrl = (o["imageUrl"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull,
                )
                addCustomExercise(ex)
            }
            ProLog.i("LIBRARY", "custom exercises reinjected: ${arr.size}")
        } catch (e: Exception) {
            ProLog.e("LIBRARY", "reinjectCustom failed: ${e.message}")
        }
    }

    /** augmentLibrary: primaryMuscle + importance by keyword. */
    fun augmented(ex: LibraryExercise): LibraryExercise {
        val name = ex.name.lowercase()
        val importance = when {
            listOf("squat", "deadlift", "bench", "press", "row", "pull-up", "pullup", "chin-up", "dip", "clean", "snatch", "jerk", "swing", "thruster").any { name.contains(it) } -> "core"
            listOf("raise", "curl", "extension", "fly", "kickback", " shrug", "calve", "calf", "crunch", "plank", "hold").any { name.contains(it) } -> "accessory"
            else -> "specialised"
        }
        return ex.copy(primaryMuscle = ex.muscles.firstOrNull() ?: "", importance = importance)
    }

    /** muscle database traversal: 4 categories {major, longevity, grip, foot} of {name, restDays, agingRisk?} */
    fun allMuscleGroups(): List<MuscleDef> {
        val out = mutableListOf<MuscleDef>()
        val cats = listOf("major", "longevity", "grip", "foot")
        for (cat in cats) {
            val arr = muscleDatabase[cat] as? kotlinx.serialization.json.JsonArray ?: continue
            for (el in arr) {
                val o = el.jsonObject
                out.add(
                    MuscleDef(
                        name = o["name"]?.jsonPrimitive?.content ?: continue,
                        display = (o["display"]?.jsonPrimitive?.contentOrNull ?: o["name"]?.jsonPrimitive?.content ?: "").replace('_', ' '),
                        restDays = o["restDays"]?.jsonPrimitive?.doubleOrNull ?: 2.0,
                        agingRisk = o["agingRisk"]?.jsonPrimitive?.contentOrNull,
                        category = cat,
                    )
                )
            }
        }
        return out
    }

    data class MuscleDef(val name: String, val display: String, val restDays: Double, val agingRisk: String?, val category: String)

    fun muscleDef(name: String): MuscleDef? = allMuscleGroups().firstOrNull { it.name == name }

    fun fiberWindowFor(exerciseMuscles: List<String>): FiberWindow {
        var low = 0.0; var high = 1.0; var note = ""
        for (m in exerciseMuscles) {
            val p = fiberProfiles[m] ?: continue
            low = max(low, p.min); high = min(high, p.max)
            if (note.isEmpty()) note = p.note
        }
        if (high <= 0 || high > 1) high = 0.85
        if (low <= 0) low = 0.45
        if (low >= high) {
            val mid = (low + high) / 2
            low = max(0.45, mid - 0.05); high = min(0.95, mid + 0.05)
        }
        return FiberWindow("mixed", low, high, note)
    }

    fun decayFor(muscle: String): Decay = muscleDecay[muscle] ?: defaultDecay

    fun componentFor(exerciseId: String): List<String> =
        componentRegistry[exerciseId] ?: emptyList()
}

// ---------------------------------------------------------------------------
// Fatigue — translations of the muscle fatigue model (L7383/8281/8328/8352...),
// the coupled-fatigue variant, recent-exercise rotation and readiness helpers.
// ---------------------------------------------------------------------------
object Fatigue {
    const val READINESS_THRESHOLD = 0.4

    fun applyFatigueDecay() {
        val now = System.currentTimeMillis()
        val last = ProState.data.lastFatigueDecay
        if (last == null) {
            ProState.data = ProState.data.copy(lastFatigueDecay = ProState.nowIso())
            return
        }
        val diffDays = (now - ProState.utcDayMillis(last)) / (1000.0 * 60 * 60 * 24)
        if (diffDays >= 0.1) {
            decayFatigue(diffDays)
            ProState.data = ProState.data.copy(lastFatigueDecay = ProState.nowIso())
        }
    }

    fun decayFatigue(days: Double) {
        if (days <= 0) return
        val hours = days * 24
        val mf = ProState.data.muscleFatigue.toMutableMap()
        for ((muscle, f) in mf) {
            if (f.fast == 0.0 && f.slow == 0.0) continue
            val d = Library.decayFor(muscle)
            val df = exp(-d.fast * hours)
            val ds = exp(-d.slow * hours)
            var fast = f.fast * df
            var slow = f.slow * ds
            if (fast < 0.01) fast = 0.0
            if (slow < 0.01) slow = 0.0
            mf[muscle] = f.copy(fast = fast, slow = slow)
        }
        ProState.data = ProState.data.copy(muscleFatigue = mf)
    }

    fun initializeMuscleFatigue() {
        val mf = ProState.data.muscleFatigue.toMutableMap()
        for (m in Library.allMuscleGroups()) {
            if (!mf.containsKey(m.name)) mf[m.name] = FatigueState()
        }
        ProState.data = ProState.data.copy(muscleFatigue = mf)
    }

    /** updateMuscleFatigue (Eq7) — per-set stress distributed over muscles. */
    fun updateMuscleFatigue(
        exerciseMuscles: List<String>,
        effectiveWeight: Double,
        reps: Double,
        rpe: Double,
        timestampMs: Long,
        phaseMultiplier: Double = 1.0,
        decayMap: Map<String, com.peakform.fitness.core.FatigueState>? = null,
    ) {
        if (exerciseMuscles.isEmpty()) return
        val userWeight = ProState.data.user.weight ?: 150.0
        val eff = if (effectiveWeight == 0.0) userWeight * 0.7 else effectiveWeight
        val sTotal = (eff * reps) / 10000.0 * (rpe / 10.0) * phaseMultiplier
        val clampedS = min(1.0, max(0.0, sTotal))
        val fastFrac = if (rpe > 7) 0.7 else 0.3
        val slowFrac = 1 - fastFrac
        val mf = (decayMap ?: ProState.data.muscleFatigue).toMutableMap()
        for (muscle in exerciseMuscles) {
            val f = mf[muscle] ?: FatigueState()
            val d = Library.decayFor(muscle)
            if (f.lastUpdate == null) {
                mf[muscle] = f.copy(fast = clampedS * fastFrac, slow = clampedS * slowFrac, lastUpdate = ProState.nowIso())
            } else {
                val lastMs = try { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(f.lastUpdate!!.take(19))?.time ?: 0L } catch (_: Exception) { 0L }
                var dt = (timestampMs - lastMs) / (1000.0 * 60 * 60)
                if (dt > 168) dt = 168.0
                val df = exp(-d.fast * dt)
                val ds = exp(-d.slow * dt)
                val fast = f.fast * df + (clampedS * fastFrac / d.fast) * (1 - df)
                val slow = f.slow * ds + (clampedS * slowFrac / d.slow) * (1 - ds)
                mf[muscle] = f.copy(
                    fast = min(1.0, max(0.0, fast)),
                    slow = min(1.0, max(0.0, slow)),
                    lastUpdate = ProState.nowIso(),
                )
            }
        }
        ProState.data = ProState.data.copy(muscleFatigue = mf)
    }

    /** updateFatigueAfterWorkout — per-exercise stress using actuals and load distribution. */
    fun updateFatigueAfterWorkout(exercise: com.peakform.fitness.core.WorkoutExercise, phaseMultiplier: Double = 1.0) {
        val actual = exercise.actual ?: return
        if (exercise.skipped || exercise.noFatigue) return
        if (actual.sets == 0) return
        val muscles = exercise.muscleGroup
        if (muscles.isEmpty()) return

        val stressPerSet: Double = if (exercise.prescriptionType == "time") {
            if (actual.durations.isNotEmpty()) {
                val avgDuration = actual.durations.sum() / actual.durations.size
                val rpe = actual.rpeList.average().takeIf { !it.isNaN() } ?: 7.0
                avgDuration * (rpe / 10.0)
            } else 0.0
        } else {
            if (actual.reps.isNotEmpty()) {
                val avgReps = actual.reps.sum() / actual.reps.size
                val rpe = actual.rpeList.average().takeIf { !it.isNaN() } ?: 7.0
                actual.weight * avgReps * (rpe / 10.0)
            } else 0.0
        }
        val totalStress = stressPerSet * actual.sets
        val clampedS = min(1.0, max(0.0, totalStress / 100.0)) // legacy: weight*avgReps*scale; clamped to 0..1
        val rpe = actual.rpeList.average().takeIf { !it.isNaN() } ?: 7.0
        val fastFrac = if (rpe > 7) 0.7 else 0.3
        val slowFrac = 1 - fastFrac
        val loadDist = loadDistributionFor(exercise)
        val mf = ProState.data.muscleFatigue.toMutableMap()
        fun addStress(muscle: String, fraction: Double) {
            val f = mf[muscle] ?: FatigueState()
            mf[muscle] = f.copy(
                fast = f.fast + clampedS * fastFrac * fraction,
                slow = f.slow + clampedS * slowFrac * fraction,
            )
        }
        if (loadDist.isEmpty()) {
            val factor = 1.0 / muscles.size
            muscles.forEach { addStress(it, factor) }
        } else {
            loadDist.forEach { (m, f) -> addStress(m, f) }
        }
        ProState.data = ProState.data.copy(muscleFatigue = mf)
    }

    fun loadDistributionFor(exercise: com.peakform.fitness.core.WorkoutExercise): Map<String, Double> {
        val ld = exercise.loadDistribution
        if (ld != null && ld.isNotEmpty()) {
            val sum = ld.values.sum()
            if (abs(sum - 1.0) < 0.01) return ld
        }
        val muscles = exercise.muscleGroup
        if (muscles.isEmpty()) return emptyMap()
        val factor = 1.0 / muscles.size
        return muscles.associateWith { factor }
    }

    fun getCoupledFatigue(muscle: String): Double {
        val f = ProState.data.muscleFatigue[muscle] ?: return 0.0
        var total = f.fast + f.slow
        if (ProState.data.user.settings.muscleCoupling) {
            val neighbors = ProState.data.couplingMatrix[muscle] ?: return min(1.0, total)
            for ((n, w) in neighbors) {
                val nf = ProState.data.muscleFatigue[n] ?: continue
                total += w * (nf.fast + nf.slow)
            }
        }
        return min(1.0, total)
    }

    fun isMuscleReady(muscle: String): Boolean = getCoupledFatigue(muscle) < READINESS_THRESHOLD

    fun getReadyMuscles(ignore: Set<String> = emptySet()): List<Library.MuscleDef> {
        applyFatigueDecay()
        return Library.allMuscleGroups()
            .filter { it.name !in ignore && isMuscleReady(it.name) }
            .sortedWith(compareBy({ lastTrainedMs(it.name) == null }, { lastTrainedMs(it.name) ?: Long.MAX_VALUE }))
    }

    fun updateRecentExercises(exercise: com.peakform.fitness.core.WorkoutExercise) {
        if (exercise.actual == null || exercise.skipped || exercise.noFatigue) return
        val map = ProState.data.muscleRecentExercises.toMutableMap()
        for (muscle in exercise.muscleGroup) {
            val list = (map[muscle] ?: emptyList()).toMutableList()
            list.add(0, exercise.id)
            map[muscle] = list.distinct().take(3)
        }
        ProState.data = ProState.data.copy(muscleRecentExercises = map)
    }

    /** learnCouplingFromWorkout — Hebbian update of the coupling matrix. */
    fun learnCouplingFromWorkout(exercises: List<com.peakform.fitness.core.WorkoutExercise>) {
        if (!ProState.data.user.settings.muscleCoupling) return
        val active = exercises.filter { it.actual != null && !it.skipped && !it.noFatigue }
            .flatMap { it.muscleGroup }.distinct()
        if (active.size < 2) return
        val cm = ProState.data.couplingMatrix.toMutableMap()
        for (a in active) for (b in active) {
            if (a == b) continue
            val row = (cm[a] ?: emptyMap()).toMutableMap()
            row[b] = min(0.2, (row[b] ?: 0.0) + 0.01)
            cm[a] = row
        }
        ProState.data = ProState.data.copy(couplingMatrix = cm)
    }

    /** rebuildFatigueFromHistory — recompute muscleLastTrained and rebuild fatigue from ≤60d history. */
    fun rebuildFromHistory() {
        val now = System.currentTimeMillis()
        val lastTrained = mutableMapOf<String, Long>()
        val mf = mutableMapOf<String, FatigueState>()
        val userWeight = ProState.data.user.weight ?: 150.0
        for (w in ProState.data.workouts) {
            val wMs = ProState.utcDayMillis(w.date)
            if (wMs <= 0 || now - wMs > 60L * 24 * 3600 * 1000) continue
            for (ex in w.exercises) {
                val actual = ex.actual ?: continue
                if (ex.skipped || ex.noFatigue) continue
                val t = ProState.utcDayMillis(ex.actual?.let { w.date } ?: w.date)
                for (m in ex.muscleGroup) {
                    val cur = lastTrained[m]
                    if (cur == null || wMs > cur) lastTrained[m] = wMs
                }
                // accumulate stress like updateFatigueAfterWorkout
                val repsAvg = if (ex.prescriptionType == "time") actual.durations.average().takeIf { !it.isNaN() } ?: 0.0
                else actual.reps.average().takeIf { !it.isNaN() } ?: 0.0
                val rpe = actual.rpeList.average().takeIf { !it.isNaN() } ?: 7.0
                val eff = if (actual.weight == 0.0) userWeight * 0.7 else actual.weight
                val s = (eff * repsAvg * (rpe / 10.0) * actual.sets) / 100.0
                val clamped = min(1.0, max(0.0, s))
                val fastFrac = if (rpe > 7) 0.7 else 0.3
                val dist = loadDistributionFor(ex)
                val entries = if (dist.isEmpty()) ex.muscleGroup.associateWith { 1.0 / ex.muscleGroup.size } else dist
                for ((m, frac) in entries) {
                    val f = mf[m] ?: FatigueState()
                    mf[m] = f.copy(
                        fast = min(1.0, f.fast + clamped * fastFrac * frac),
                        slow = min(1.0, f.slow + clamped * (1 - fastFrac) * frac),
                    )
                }
            }
        }
        _lastTrainedCache = lastTrained
        // apply decay since each entry's date roughly: simple approximation via decayFatigue on age
        ProState.data = ProState.data.copy(muscleFatigue = mf)
        initializeMuscleFatigue()
    }

    private var _lastTrainedCache: Map<String, Long> = emptyMap()

    fun lastTrainedMs(muscle: String): Long? = _lastTrainedCache[muscle]

    fun calculateMuscleLastTrained() {
        val map = mutableMapOf<String, Long>()
        for (w in ProState.data.workouts.asReversed()) {
            for (ex in w.exercises) {
                if (ex.actual == null || ex.skipped || ex.noFatigue) continue
                for (m in ex.muscleGroup) {
                    if (!map.containsKey(m)) {
                        val t = try { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(w.date.take(19))?.time } catch (_: Exception) { null }
                        if (t != null) map[m] = t
                    }
                }
            }
        }
        _lastTrainedCache = map
    }

    fun daysSinceTrained(muscle: String): Double? {
        val t = _lastTrainedCache[muscle] ?: return null
        return (System.currentTimeMillis() - t).toDouble() / (1000.0 * 60 * 60 * 24)
    }

    fun getOverdueMuscles(multiplier: Double = 1.5, maxCount: Int = 2): List<Library.MuscleDef> {
        return Library.allMuscleGroups()
            .map { m ->
                val days = daysSinceTrained(m.name)
                Triple(m, days, (days ?: 999.0) >= m.restDays * multiplier)
            }
            .filter { it.third }
            .sortedByDescending { it.second ?: 999.0 }
            .take(maxCount)
            .map { it.first }
    }
}

// ---------------------------------------------------------------------------
// OneRm — Kalman 1RM estimator + fiber-aware weight windows + test processing
// (translations of updateRecursive1RM L7302, computeFiberWeightRange L8767,
//  processTestResult L8821, robustEstimate1RM L38929)
// ---------------------------------------------------------------------------
object OneRm {
    fun roundToNearest(value: Double, increment: Double = 2.5): Double =
        (value / increment).roundToInt() * increment

    fun epleyIntensityForReps(reps: Double): Double = 1.0 / (1.0 + max(1.0, reps) / 30.0)

    fun effectiveWeightFor(exercise: LibraryExercise, weight: Double): Double {
        val n = exercise.name.lowercase()
        val bodyweightStyle = exercise.equipment == "bodyweight" || n.contains("pull") || n.contains("push") || n.contains("dip") || n.contains("chin")
        return if (bodyweightStyle) (ProState.data.user.weight ?: 150.0) + (if (weight > 0) weight else 0.0) else weight
    }

    /** updateRecursive1RM — Kalman update of mu/sigma2 from an observed set. */
    fun updateRecursive(exerciseId: String, weight: Double, reps: Double, rpe: Double? = null) {
        // FIX (audit): never silently drop the 1RM update. The old `?: return` meant the
        // FIRST logged session of any exercise recorded no mu — and since the roulette
        // picker deliberately avoids repeating recent exercises, mu could stay null
        // indefinitely, disabling the entire progression engine. Seed the record instead.
        val rec = ProState.data.exercises[exerciseId] ?: ExerciseRecord(lastUpdate = ProState.nowIso())
        val ex = Library.getExerciseById(exerciseId, includeBodyweight = true) ?: return
        val effectiveWeight = effectiveWeightFor(ex, weight)
        if (effectiveWeight <= 0) return

        var mu = rec.mu ?: effectiveWeight * (1 + reps / 30.0)
        var sigma2 = rec.sigma2 ?: (0.2 * mu).pow(2)
        val residuals = rec.residuals.toMutableList()

        val observed = effectiveWeight * (1 + reps / 30.0)
        val innovation = observed - mu

        val nu = if (residuals.size < 20) {
            (0.2 * mu).pow(2)
        } else {
            val sorted = residuals.sorted()
            val median = sorted[sorted.size / 2]
            val mad = residuals.map { abs(it - median) }.sorted()[residuals.size / 2]
            (1.4826 * mad).pow(2)
        }
        val rpeFactor = when {
            rpe != null && rpe >= 9 -> 0.3
            rpe != null && rpe >= 7 -> 0.6
            rpe != null && rpe >= 1 && rpe <= 10 -> 1.2
            else -> 1.0
        }
        val nuAdj = nu * rpeFactor
        val k = sigma2 / (sigma2 + nuAdj)
        mu += k * innovation
        sigma2 = (1 - k) * sigma2 + k * innovation * innovation
        if (mu < 50) mu = 50.0
        sigma2 = min(sigma2, (0.3 * mu).pow(2))
        residuals.add(innovation)
        if (residuals.size > 20) residuals.removeAt(0)

        var bestWeight = rec.bestWeight ?: 0.0
        var bestReps = rec.bestReps ?: 0.0
        if (effectiveWeight > bestWeight) { bestWeight = effectiveWeight; bestReps = reps }
        else if (abs(effectiveWeight - bestWeight) < 0.1 && reps > bestReps) bestReps = reps

        val maxPlausible = (ProState.data.user.weight ?: 150.0) * (if (ProState.data.user.experience == "advanced") 5.0 else 3.5)
        if (mu > maxPlausible) { mu = maxPlausible; sigma2 = (0.2 * mu).pow(2) }

        val updated = rec.copy(
            mu = mu, sigma2 = sigma2, residuals = residuals,
            bestWeight = bestWeight, bestReps = bestReps,
            lastUpdate = ProState.nowIso(),
        )
        ProState.data = ProState.data.copy(exercises = ProState.data.exercises + (exerciseId to updated))
    }

    /** computeFiberWeightRange — fiber-aware suggested/ceiling weights for target reps. */
    fun fiberWeightRange(exercise: LibraryExercise, oneRM: Double, targetReps: Double): FiberRange? {
        if (oneRM <= 0 || targetReps <= 0) return null
        val win = Library.fiberWindowFor(exercise.muscles)
        val atReps = oneRM * epleyIntensityForReps(targetReps)
        val lowW = oneRM * win.min
        val highW = oneRM * win.max
        var suggested = max(atReps, lowW)
        suggested = min(suggested, min(highW, oneRM * 0.95))
        return FiberRange(
            min = roundToNearest(suggested),
            max = roundToNearest(highW),
            lowPct = (win.min * 100).roundToInt(),
            maxPct = (win.max * 100).roundToInt(),
            note = win.note,
        )
    }

    data class FiberRange(val min: Double, val max: Double, val lowPct: Int, val maxPct: Int, val note: String)

    /** processTestResult — set tested1RM from a successful 1RM attempt. */
    fun processTestResult(exerciseId: String, weight: Double, reps: Int): Double? {
        if (reps < 1 || reps > 15) return null
        val estimated = weight * (1 + reps / 30.0)
        val rec = ProState.data.exercises[exerciseId] ?: ExerciseRecord()
        val entry = com.peakform.fitness.core.HistoryEntry(
            date = ProState.nowIso(),
            weight = weight,
            sets = 1,
            rpe = kotlinx.serialization.json.JsonPrimitive(7),
            isTest = true,
            estimated1RM = estimated,
            reps = listOf(reps.toDouble()),
            muscles = Library.getExerciseById(exerciseId, includeBodyweight = true)?.muscles ?: emptyList(),
        )
        val updated = rec.copy(
            tested1RM = estimated,
            testDate = ProState.nowIso(),
            testReps = reps,
            testWeight = weight,
            history = rec.history + entry,
        )
        ProState.data = ProState.data.copy(exercises = ProState.data.exercises + (exerciseId to updated))
        ProState.saveWorkoutData()
        return estimated
    }

    /** robustEstimate1RM — shrinkage estimator from a recent best set. */
    fun robustEstimate(exerciseId: String): Double? {
        val rec = ProState.data.exercises[exerciseId] ?: return null
        rec.tested1RM?.let { return it }
        val best = rec.history.lastOrNull { !it.skipped } ?: return null
        val reps = best.reps.maxOrNull() ?: 1.0
        return best.weight * (1 + reps / 30.0)
    }
}
