package com.peakform.fitness.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.builtins.serializer

/**
 * Native translation of the legacy app's data shapes (see work/native-docs/data-shapes.md).
 * Field names match the legacy JSON byte-for-byte via @SerialName so that exports import
 * cleanly into the old WebView app and vice versa.
 *
 * Defaults mirror the legacy defaults (L6997/L46336/L43934).
 */

object ProJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = false
        allowSpecialFloatingPointValues = true
    }
    val pretty: Json = Json(from = json) { prettyPrint = true; prettyPrintIndent = "  " }

    /** rpe arrives as number[] | number | null — kept raw, helpers below. */
    fun rpeToList(e: JsonElement?): List<Double> = when (e) {
        null, is JsonNull -> emptyList()
        is JsonArray -> e.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }
        is JsonPrimitive -> listOfNotNull(e.doubleOrNull)
        else -> emptyList()
    }
    fun rpeFirst(e: JsonElement?): Double? = rpeToList(e).firstOrNull()
    fun rpeToDisplay(e: JsonElement?): String {
        val l = rpeToList(e)
        return if (l.isEmpty()) "—" else if (l.size == 1) formatNum(l[0]) else l.joinToString(", ") { formatNum(it) }
    }
    fun formatNum(d: Double): String =
        if (d == d.toLong().toDouble()) d.toLong().toString() else String.format("%.1f", d)

    fun <T> encode(serializer: KSerializer<T>, value: T): String = json.encodeToString(serializer, value)
    fun <T> encodeElement(serializer: KSerializer<T>, value: T): JsonElement = json.encodeToJsonElement(serializer, value)
    fun <T> decode(serializer: KSerializer<T>, raw: String): T = json.decodeFromString(serializer, raw)
    fun <T> decodeElement(serializer: KSerializer<T>, el: JsonElement): T = json.decodeFromJsonElement(serializer, el)
}

@Serializable
data class WorkoutData(
    val user: UserProfile = UserProfile(),
    val workouts: List<WorkoutRecord> = emptyList(),
    val exercises: Map<String, ExerciseRecord> = emptyMap(),
    val history: List<JsonElement> = emptyList(), // legacy: declared, never used
    @SerialName("muscleFatigue") val muscleFatigue: Map<String, FatigueState> = emptyMap(),
    @SerialName("lastFatigueDecay") val lastFatigueDecay: String? = null,
    @SerialName("muscleRecentExercises") val muscleRecentExercises: Map<String, List<String>> = emptyMap(),
    @SerialName("syncedToBackend") val syncedToBackend: Boolean = false,
    @SerialName("couplingMatrix") val couplingMatrix: Map<String, Map<String, Double>> = emptyMap(),
    @SerialName("_p4SavedAt") val p4SavedAt: Long? = null,
) {
    fun toJsonElement(): JsonElement = ProJson.encodeElement(serializer(), this)
    fun toPrettyJson(): String = ProJson.pretty.encodeToString(serializer(), this)
}

@Serializable
data class UserProfile(
    val name: String = "",
    @SerialName("birthDate") val birthDate: String? = null,
    val gender: String = "male",
    val weight: Double? = null,
    val height: Double? = null,
    val experience: String = "",
    val goal: String = "balanced",
    val created: String = "",
    val settings: Settings = Settings(),
    // Phase-2 / extended
    @SerialName("agingRisks") val agingRisks: List<AgingRisk> = emptyList(),
    val menstrual: MenstrualState = MenstrualState(),
    @SerialName("momentumBias") val momentumBias: MomentumBias = MomentumBias(),
    @SerialName("_cache") val cache: UserCache? = null,
    @SerialName("strengthComposite") val strengthComposite: Double = 0.0,
    @SerialName("bodyWeightHistory") val bodyWeightHistory: List<BodyWeightEntry> = emptyList(),
    @SerialName("geneticPotential") val geneticPotential: GeneticPotential? = null,
    @SerialName("neuralPhaseEnd") val neuralPhaseEnd: String? = null,
    val aggregates: Aggregates? = null,
    @SerialName("formulaProbabilities") val formulaProbabilities: FormulaProbabilities? = null,
    @SerialName("muscleEWMA") val muscleEWMA: Map<String, Double>? = null,
    @SerialName("lastEWMAUpdate") val lastEWMAUpdate: String? = null,
    @SerialName("weeklyVolumes") val weeklyVolumes: List<WeeklyVolume> = emptyList(),
    @SerialName("muscleDeloadStatus") val muscleDeloadStatus: Map<String, DeloadStatus>? = null,
    val competition: CompetitionInfo? = null,
    @SerialName("postpartumMonths") val postpartumMonths: Double? = null,
    @SerialName("goalCycle") val goalCycle: GoalCycle? = null,
    @SerialName("_p4SavedAt") val p4SavedAt: Long? = null,
)

@Serializable
data class Settings(
    @SerialName("workoutDays") val workoutDays: List<String> = listOf("monday", "wednesday", "friday"),
    @SerialName("restTime") val restTime: Int = 90,
    @SerialName("progressionRate") val progressionRate: Double = 0.02,
    val theme: String = "blue",
    @SerialName("darkMode") val darkMode: Boolean = true,
    @SerialName("muscleCoupling") val muscleCoupling: Boolean = false,
    @SerialName("bottomNavAutoHide") val bottomNavAutoHide: Boolean = false,
    @SerialName("expressMode") val expressMode: Boolean = false,
    val aggression: Double = 1.0,
    val equipment: List<String> = emptyList(),
    @SerialName("trainingMode") val trainingMode: String = "gym",
    @SerialName("sarcasmMode") val sarcasmMode: Boolean = true,
    @SerialName("postpartumMode") val postpartumMode: Boolean = false,
)

@Serializable
data class AgingRisk(
    val muscle: String = "",
    val severity: String = "low",
    val score: Double = 0.0,
    val reason: String = "",
    val ideal: String = "",
    val current: String = "",
    @SerialName("ageAdjust") val ageAdjust: Double? = null,
)

@Serializable
data class MenstrualState(
    @SerialName("lastPeriodStart") val lastPeriodStart: String? = null,
    @SerialName("cycleLength") val cycleLength: Int = 28,
    val symptoms: List<SymptomEntry> = emptyList(),
)

@Serializable
data class SymptomEntry(val date: String = "", val notes: String = "")

@Serializable
data class MomentumBias(
    val value: Double = 0.0,
    @SerialName("decayRate") val decayRate: Double = 0.25,
    @SerialName("lastUpdated") val lastUpdated: String? = null,
    @SerialName("lastTap") val lastTap: String? = null,
)

@Serializable
data class UserCache(
    @SerialName("effectiveBodyWeight") val effectiveBodyWeight: Double? = null,
    @SerialName("ageFactor") val ageFactor: Double? = null,
    @SerialName("genderFactor") val genderFactor: Double? = null,
    @SerialName("experienceFactor") val experienceFactor: Double? = null,
    @SerialName("expDeltaFactor") val expDeltaFactor: Double? = null,
    @SerialName("phaseFactor") val phaseFactor: Double? = null,
    @SerialName("priorBaseMultiplier") val priorBaseMultiplier: Double? = null,
    @SerialName("progBaseMultiplier") val progBaseMultiplier: Double? = null,
    @SerialName("recoveryFactor") val recoveryFactor: Double? = null,
    @SerialName("sessionPriorBase") val sessionPriorBase: Double? = null,
    @SerialName("sessionDeltaBase") val sessionDeltaBase: Double? = null,
)

@Serializable
data class BodyWeightEntry(val date: String = "", val weight: Double = 0.0, val source: String = "entry")

@Serializable
data class GeneticPotential(@SerialName("maxLeanMass") val maxLeanMass: Double = 0.0, @SerialName("sGenetic") val sGenetic: Double = 0.0)

@Serializable
data class Aggregates(
    @SerialName("totalVolumeLast7Days") val totalVolumeLast7Days: Double = 0.0,
    @SerialName("avgRPELast7Days") val avgRPELast7Days: Double = 0.0,
    @SerialName("volumeByMuscleLast60Days") val volumeByMuscleLast60Days: Map<String, Double> = emptyMap(),
)

@Serializable
data class FormulaProbabilities(val epley: Double = 0.34, val brzycki: Double = 0.33, val lombardi: Double = 0.33)

@Serializable
data class WeeklyVolume(@SerialName("weekKey") val weekKey: String = "", @SerialName("totalVolume") val totalVolume: Double = 0.0)

@Serializable
data class DeloadStatus(@SerialName("consecutiveHighFatigue") val consecutiveHighFatigue: Int = 0, val active: Boolean = false)

@Serializable
data class CompetitionInfo(val date: String = "")

@Serializable
data class WorkoutRecord(
    val id: String = "",
    val date: String = "",
    val type: String = "",
    val name: String = "",
    val exercises: List<WorkoutExercise> = emptyList(),
    @SerialName("isDeload") val isDeload: Boolean = false,
    @SerialName("isTaper") val isTaper: Boolean = false,
    @SerialName("activeDates") val activeDates: List<String> = emptyList(),
    @SerialName("draftStartedAt") val draftStartedAt: String? = null,
    @SerialName("lastModifiedAt") val lastModifiedAt: String? = null,
    val readiness: Double? = null,
    @SerialName("dateCompleted") val dateCompleted: String? = null,
    val summary: WorkoutSummary? = null,
    @SerialName("recommendedRest") val recommendedRest: Double? = null,
    @SerialName("_cache") val cache: WorkoutCache? = null,
) {
    val isCompleted: Boolean get() = dateCompleted != null
}

@Serializable
data class WorkoutSummary(
    @SerialName("totalVolume") val totalVolume: Double = 0.0,
    @SerialName("averageRPE") val averageRPE: Double = 0.0,
    @SerialName("completedExercises") val completedExercises: Int = 0,
)

@Serializable
data class WorkoutCache(
    @SerialName("recoveryFactor") val recoveryFactor: Double? = null,
    @SerialName("sessionPriorBase") val sessionPriorBase: Double? = null,
    @SerialName("sessionDeltaBase") val sessionDeltaBase: Double? = null,
)

@Serializable
data class WorkoutExercise(
    val id: String = "",
    val name: String = "",
    @SerialName("muscleGroup") val muscleGroup: List<String> = emptyList(),
    val prescribed: Prescription = Prescription(),
    val actual: ActualPerformance? = null,
    val skipped: Boolean = false,
    @SerialName("progressionNotes") val progressionNotes: String = "",
    val equipment: String = "",
    val instructions: List<String> = emptyList(),
    @SerialName("prescriptionType") val prescriptionType: String = "reps",
    @SerialName("loadDistribution") val loadDistribution: Map<String, Double>? = null,
    @SerialName("noFatigue") val noFatigue: Boolean = false,
    @SerialName("fitnessComponents") val fitnessComponents: List<String> = emptyList(),
    @SerialName("isWarmup") val isWarmup: Boolean = false,
    @SerialName("isComponentDrill") val isComponentDrill: Boolean = false,
    val component: String? = null,
    @SerialName("isCooldown") val isCooldown: Boolean = false,
    @SerialName("defaultDuration") val defaultDuration: Double? = null,
    @SerialName("strengthIndex") val strengthIndex: Double? = null,
) {
    val isLogged: Boolean get() = actual != null || skipped
}

@Serializable
data class Prescription(
    val sets: Int = 3,
    val reps: String = "8-12",
    val weight: Double? = null,
    @SerialName("weightMax") val weightMax: Double? = null,
    val duration: Double? = null,
)

@Serializable
data class ActualPerformance(
    val weight: Double = 0.0,
    val sets: Int = 0,
    val notes: String = "",
    val rpe: JsonElement? = null,               // number[] | number | null
    @SerialName("firstRPE") val firstRPE: Double? = null,
    @SerialName("restEstimate") val restEstimate: Double? = null,
    val reps: List<Double> = emptyList(),
    val volume: Double? = null,
    val durations: List<Double> = emptyList(),
    @SerialName("totalTime") val totalTime: Double? = null,
    val failure: Boolean = false,
    @SerialName("attemptedWeight") val attemptedWeight: Double? = null,
) {
    val rpeList: List<Double> get() = ProJson.rpeToList(rpe)
}

@Serializable
data class ExerciseRecord(
    val history: List<HistoryEntry> = emptyList(),
    @SerialName("tested1RM") val tested1RM: Double? = null,
    @SerialName("testDate") val testDate: String? = null,
    @SerialName("testReps") val testReps: Int? = null,
    @SerialName("testWeight") val testWeight: Double? = null,
    @SerialName("derivedFrom") val derivedFrom: String? = null,
    @SerialName("strengthRatio") val strengthRatio: Double? = null,
    val mu: Double? = null,
    val sigma2: Double? = null,
    val residuals: List<Double> = emptyList(),
    @SerialName("avg_mu_W") val avgMuW: Double? = null,
    @SerialName("techniqueFactor") val techniqueFactor: Double? = null,
    @SerialName("lastUpdate") val lastUpdate: String? = null,
    @SerialName("_wHistory") val wHistory: List<Double> = emptyList(),
    @SerialName("bestWeight") val bestWeight: Double? = null,
    @SerialName("bestReps") val bestReps: Double? = null,
    @SerialName("bestTimes") val bestTimes: Map<String, Double> = emptyMap(),
    @SerialName("restEstimates") val restEstimates: List<Double> = emptyList(),
    @SerialName("nextWeight") val nextWeight: Double? = null,
    @SerialName("nextWeightConfidence") val nextWeightConfidence: Double? = null,
    @SerialName("startingWeight") val startingWeight: Double? = null,
    @SerialName("lastPerformed") val lastPerformed: String? = null,
    @SerialName("lastRIR_uncertainty") val lastRIRUncertainty: Double? = null,
)

@Serializable
data class HistoryEntry(
    val date: String = "",
    val weight: Double = 0.0,
    val sets: Int = 0,
    val rpe: JsonElement? = null,
    val notes: String = "",
    @SerialName("prescriptionType") val prescriptionType: String = "reps",
    val muscles: List<String> = emptyList(),
    @SerialName("firstRPE") val firstRPE: Double? = null,
    @SerialName("restEstimate") val restEstimate: Double? = null,
    val reps: List<Double> = emptyList(),
    val volume: Double? = null,
    val durations: List<Double> = emptyList(),
    @SerialName("totalTime") val totalTime: Double? = null,
    @SerialName("estimated1RM") val estimated1RM: Double? = null,
    @SerialName("isTest") val isTest: Boolean = false,
    val skipped: Boolean = false,
) {
    val rpeList: List<Double> get() = ProJson.rpeToList(rpe)
}

@Serializable
data class FatigueState(
    val fast: Double = 0.0,
    val slow: Double = 0.0,
    @SerialName("lastUpdate") val lastUpdate: String? = null,
)

// ---------------- MP (Masterpiece) engine shapes ----------------

@Serializable
data class GoalCycle(
    val id: String = "",
    @SerialName("startDate") val startDate: String = "",
    @SerialName("endDate") val endDate: String = "",
    @SerialName("difficultyFactor") val difficultyFactor: Double = 1.0,
    @SerialName("cycleNumber") val cycleNumber: Int = 1,
    val goals: List<Goal> = emptyList(),
    @SerialName("previousCycleSummary") val previousCycleSummary: PreviousCycleSummary? = null,
    @SerialName("createdAt") val createdAt: String = "",
    @SerialName("lastUpdated") val lastUpdated: String = "",
    @SerialName("achievementRate") val achievementRate: Double = 0.0,
)

@Serializable
data class PreviousCycleSummary(
    @SerialName("cycleNumber") val cycleNumber: Int = 0,
    val goals: List<PreviousGoal> = emptyList(),
)

@Serializable
data class PreviousGoal(
    val title: String = "", val current: Double = 0.0, val target: Double = 0.0, val status: String = "",
)

@Serializable
data class Goal(
    val id: String = "",
    val key: String = "",
    val title: String = "",
    val component: String = "",
    val metric: String = "est1rm",
    @SerialName("metricArg") val metricArg: String? = null,
    val unit: String = "",
    val baseline: Double = 0.0,
    val target: Double = 0.0,
    val current: Double = 0.0,
    val hint: String = "",
    val history: List<GoalHistoryPoint> = emptyList(),
    val status: String = "on_track",
    val confidence: Double = 50.0,
    val rate: Double? = null,
    @SerialName("projectedDate") val projectedDate: String? = null,
)

@Serializable
data class GoalHistoryPoint(val date: String = "", val value: Double = 0.0)

// ---------------- Library entry (exercises.json) ----------------

@Serializable
data class LibraryExercise(
    val name: String = "",
    val muscles: List<String> = emptyList(),
    val equipment: String = "bodyweight",
    @SerialName("defaultSets") val defaultSets: Int = 3,
    @SerialName("defaultReps") val defaultReps: String = "8-12",
    val progression: String = "",
    val instructions: List<String> = emptyList(),
    @SerialName("strengthIndex") val strengthIndex: Double = 0.5,
    @SerialName("skillFactor") val skillFactor: Double = 0.8,
    @SerialName("genderSuitability") val genderSuitability: String = "both",
    @SerialName("prescriptionType") val prescriptionType: String = "reps",
    @SerialName("fitnessComponents") val fitnessComponents: List<String> = emptyList(),
    @SerialName("defaultDuration") val defaultDuration: Double? = null,
    @SerialName("loadDistribution") val loadDistribution: Map<String, Double>? = null,
    @SerialName("noFatigue") val noFatigue: Boolean = false,
    @SerialName("primaryMuscle") val primaryMuscle: String = "",
    val importance: String = "accessory",
    @SerialName("imageUrl") val imageUrl: String? = null,
) {
    val id: String get() = name.lowercase().replace(Regex("\\s+"), "_")
}

@Serializable
data class BodyweightExercise(
    val name: String = "",
    val muscles: List<String> = emptyList(),
    val equipment: String = "bodyweight",
    @SerialName("defaultSets") val defaultSets: Int = 3,
    @SerialName("defaultReps") val defaultReps: String = "8-12",
    val progression: String = "",
    val instructions: List<String> = emptyList(),
    @SerialName("strengthIndex") val strengthIndex: Double = 0.5,
    @SerialName("skillFactor") val skillFactor: Double = 0.8,
    @SerialName("prescriptionType") val prescriptionType: String = "reps",
    @SerialName("progressionChain") val progressionChain: String = "",
    val level: Int = 1,
    @SerialName("noFatigue") val noFatigue: Boolean = false,
    @SerialName("defaultDuration") val defaultDuration: Double? = null,
    @SerialName("fitnessComponents") val fitnessComponents: List<String> = emptyList(),
    @SerialName("loadDistribution") val loadDistribution: Map<String, Double>? = null,
)
