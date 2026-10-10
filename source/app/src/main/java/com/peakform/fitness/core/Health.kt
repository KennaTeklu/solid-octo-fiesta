package com.peakform.fitness.core

import android.content.Context
import com.peakform.fitness.ProLog
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

private val strBoolMap = MapSerializer(String.serializer(), Boolean.serializer())
private val strList = ListSerializer(String.serializer())

/**
 * Health — P4 Health Screen v2 port (PF L64638–64930).
 * 7 general questions, conditional follow-ups, 8-joint picker, verdict tiers A/B/C.
 * Tier C is a SOFT lock: browsing stays free, workout generation is blocked until the
 * user records doctor clearance in Settings (confirmClearance).
 */
object Health {

    // ---- storage keys (legacy K map, L64645–64650) ----
    // BATCH-4B items 2 & 8: moved to per-profile Room `meta` table via ProfileState,
    // so each profile keeps its own health-screen answers / tier / clearance.
    const val K_SCREENED = ProfileState.K_HEALTH_SCREENED
    const val K_TIER = ProfileState.K_HEALTH_TIER
    const val K_GENERAL = ProfileState.K_HEALTH_GENERAL
    const val K_FOLLOWUP = ProfileState.K_HEALTH_FOLLOWUP
    const val K_JOINTS = ProfileState.K_HEALTH_JOINTS
    const val K_UNLOCKED = ProfileState.K_HEALTH_UNLOCKED
    const val K_DECLARED = ProfileState.K_HEALTH_DECLARED
    /** BATCH-4B item 8: per-profile flag — has the user seen the health-screen intro? */
    const val K_INTRO_SEEN = ProfileState.K_HEALTH_INTRO_SEEN

    data class GenQuestion(val id: String, val important: Boolean, val text: String)
    data class FollowUp(val id: String, val text: String)
    data class ScreenData(
        val general: Map<String, Boolean> = emptyMap(),
        val followup: Map<String, Boolean> = emptyMap(),
        val joints: List<String> = emptyList(),
        val tier: String? = null,
        val screenedAt: String? = null,
    )

    fun questions(ctx: Context): List<GenQuestion> = try {
        val raw = ctx.assets.open("data/health_screen.json").bufferedReader().use { it.readText() }
        val obj = ProJson.json.parseToJsonElement(raw).jsonObject
        obj["gen"]!!.jsonArray.map { q ->
            val o = q.jsonObject
            GenQuestion(
                id = (o["id"] as JsonPrimitive).content,
                important = (o["r"] as JsonPrimitive).content == "1",
                text = (o["t"] as JsonPrimitive).content,
            )
        }
    } catch (_: Exception) { emptyList() }

    fun joints(ctx: Context): List<String> = try {
        val raw = ctx.assets.open("data/health_screen.json").bufferedReader().use { it.readText() }
        val obj = ProJson.json.parseToJsonElement(raw).jsonObject
        obj["joints"]!!.jsonArray.map { (it as JsonPrimitive).content }
    } catch (_: Exception) { emptyList() }

    fun followUps(ctx: Context): Map<String, List<FollowUp>> = try {
        val raw = ctx.assets.open("data/health_screen.json").bufferedReader().use { it.readText() }
        val obj = ProJson.json.parseToJsonElement(raw).jsonObject
        val fup = obj["fup"]!!.jsonObject
        fup.mapValues { (_, arr) ->
            arr.jsonArray.map { q ->
                val o = q.jsonObject
                FollowUp((o["id"] as JsonPrimitive).content, (o["t"] as JsonPrimitive).content)
            }
        }
    } catch (_: Exception) { emptyMap() }

    /** Which follow-up groups trigger from the general answers (legacy trigger logic). */
    fun triggeredFollowUps(answers: Map<String, Boolean>): Set<String> {
        val out = mutableSetOf<String>()
        if (answers["g1"] == true || answers["g2"] == true || answers["g3"] == true) out.add("fu_heart")
        if (answers["g5"] == true) out.add("fu_meds")
        if (answers["g6"] == true) out.add("fu_preg")
        return out
    }

    /**
     * Verdict tier port of legacy computeTier (L64708–64734).
     * A = Clear to train · B = Clear with modification · C = Sign-off required.
     */
    fun computeTier(
        general: Map<String, Boolean>,
        followup: Map<String, Boolean>,
    ): Pair<String, List<String>> {
        val why = mutableListOf<String>()
        var tier = "A"
        fun bump(t: String, reason: String) {
            if (t == "C" || (t == "B" && tier == "A")) tier = t
            why.add(reason)
        }
        // C conditions: chest pain, dizziness, physician restriction, recent cardiac event,
        // med change last month, missing obstetric clearance
        if (general["g2"] == true) bump("C", "Chest pain at rest or during activity")
        if (general["g3"] == true) bump("C", "Dizziness or fainting")
        if (general["g7"] == true) bump("C", "A doctor said you should not exercise")
        if (general["g1"] == true && followup["h1"] == true) bump("C", "Doctor-limited activity not well controlled")
        if (general["g1"] == true && followup["h2"] == true) bump("C", "Cardiac event or procedure in the last 3 months")
        if (general["g5"] == true && followup["m1"] == true) bump("C", "Medication started or changed in the last month")
        if (general["g6"] == true && followup["p1"] != true) bump("C", "No obstetric clearance recorded")
        // B conditions
        if (general["g1"] == true && tier == "A") bump("B", "Stable heart condition or blood pressure — train with modification")
        if (general["g4"] == true && tier == "A") bump("B", "Bone, joint or back problem — modifications apply")
        if (general["g5"] == true && tier == "A") bump("B", "Medication-managed condition — monitor intensity")
        if (general["g6"] == true && tier == "A") bump("B", "Prenatal / postpartum — prenatal-safe programming only")
        return tier to why
    }

    // ---- persistence ----
    // BATCH-4B item 2: writes go through ProfileState (per-profile Room meta)
    // so each profile keeps its own health-screen answers.
    fun saveScreen(ctx: Context, data: ScreenData) {
        ProfileState.put(ctx, K_SCREENED, ProState.nowIso())
        ProfileState.put(ctx, K_TIER, data.tier ?: "A")
        ProfileState.put(ctx, K_GENERAL, ProJson.json.encodeToString(strBoolMap, data.general))
        ProfileState.put(ctx, K_FOLLOWUP, ProJson.json.encodeToString(strBoolMap, data.followup))
        ProfileState.put(ctx, K_JOINTS, ProJson.json.encodeToString(strList, data.joints))
        if (data.general.values.any { it }) ProfileState.put(ctx, K_DECLARED, ProState.nowIso())
    }

    fun loadScreen(ctx: Context): ScreenData {
        val general = decodeMap(ctx, K_GENERAL)
        return ScreenData(
            general = general,
            followup = decodeMap(ctx, K_FOLLOWUP),
            joints = try {
                ProfileState.get(ctx, K_JOINTS)?.let {
                    ProJson.json.decodeFromString(strList, it)
                } ?: emptyList()
            } catch (_: Exception) { emptyList() },
            tier = ProfileState.get(ctx, K_TIER),
            screenedAt = ProfileState.get(ctx, K_SCREENED),
        )
    }

    private fun decodeMap(ctx: Context, key: String): Map<String, Boolean> = try {
        ProfileState.get(ctx, key)?.let {
            ProJson.json.decodeFromString(strBoolMap, it)
        } ?: emptyMap()
    } catch (_: Exception) { emptyMap() }

    fun isScreened(ctx: Context): Boolean = !ProfileState.get(ctx, K_SCREENED).isNullOrBlank()
    fun tier(ctx: Context): String? = ProfileState.get(ctx, K_TIER)
    fun chosenJoints(ctx: Context): List<String> = loadScreen(ctx).joints

    /** BATCH-4B item 8: per-profile flag — has the user seen the health-screen intro? */
    fun hasSeenIntro(ctx: Context): Boolean = ProfileState.get(ctx, K_INTRO_SEEN) == "true"
    fun markIntroSeen(ctx: Context) { ProfileState.put(ctx, K_INTRO_SEEN, "true") }

    /** Tier C soft lock: generation blocked until clearance recorded (L64834–64837). */
    fun isGenerationLocked(ctx: Context): Boolean =
        tier(ctx) == "C" && ProfileState.get(ctx, K_UNLOCKED).isNullOrBlank()

    fun confirmClearance(ctx: Context) {
        ProfileState.put(ctx, K_UNLOCKED, ProState.nowIso())
        Consent.record(ctx, "health_clearance", "Workout generation unlocked — doctor clearance self-recorded")
    }

    /**
     * Re-screen triggers (shouldScreen, L64688–64706):
     *  - "layoff": last workout older than 90 days
     *  - "new-condition": a declared condition exists but no screening saved
     *  - first boot for any user with history but no screening
     */
    fun shouldScreen(ctx: Context): String? {
        if (!isScreened(ctx)) {
            return if (ProState.data.workouts.isNotEmpty()) "history" else "fresh"
        }
        val last = ProState.data.workouts.lastOrNull()?.date?.let { ProState.utcDayMillis(it) } ?: 0L
        if (last in 1..(System.currentTimeMillis() - 90L * 24 * 3600 * 1000)) return "layoff"
        return null
    }

    /** Legacy joint → heavily-loaded muscles mapping used to steer generation. */
    val JOINT_MUSCLES: Map<String, List<String>> = mapOf(
        "Shoulder" to listOf("deltoids", "trapezius", "rotator_cuff", "upper_back"),
        "Elbow" to listOf("biceps", "triceps", "forearms"),
        "Wrist" to listOf("forearms"),
        "Lower back" to listOf("erector_spinae", "lower_back", "lumbar"),
        "Hip" to listOf("glutes", "hip_flexors", "adductors"),
        "Knee" to listOf("quadriceps", "hamstrings", "calves"),
        "Ankle" to listOf("calves", "tibialis", "achilles"),
        "Neck" to listOf("trapezius", "neck"),
    )

    /** True when the exercise heavily loads a chosen (injured) joint region. */
    fun hitsProtectedJoint(exerciseMuscles: List<String>, protectedJoints: List<String>): Boolean {
        if (protectedJoints.isEmpty()) return false
        val protectedMuscles = protectedJoints.flatMap { JOINT_MUSCLES[it] ?: emptyList() }.toSet()
        return exerciseMuscles.any { it.lowercase() in protectedMuscles }
    }
}
