package com.peakform.fitness.core

import android.content.Context
import com.peakform.fitness.engine.Library
import kotlinx.serialization.json.*

/**
 * Studio core — custom exercise authoring/validation/import-merge, native port of
 * P4.Studio + SEC.validateExercisePayload (PF L55108–55150, 57163–57596).
 * Custom exercises persist under p4_custom_ex and reinject into the library on boot.
 */
@kotlinx.serialization.Serializable
data class StudioDraft(
    val name: String = "",
    val group: String = "",
    val muscles: List<String> = emptyList(),
    val equipment: String = "bodyweight",
    val level: String = "intermediate",
    val countedBy: String = "reps",
    val steps: String = "",
    val cues: String = "",
    val imageUrl: String = "",
)

object Studio {
    const val CUST_KEY = "p4_custom_ex"

    fun loadCustom(ctx: Context): MutableList<JsonObject> = try {
        ProPrefs.get(ctx, CUST_KEY)?.let {
            (ProJson.json.parseToJsonElement(it) as? JsonArray)
                ?.mapNotNull { el -> el as? JsonObject }?.toMutableList()
        } ?: mutableListOf()
    } catch (_: Exception) { mutableListOf() }

    private fun saveCustom(ctx: Context, list: List<JsonObject>) {
        ProPrefs.put(ctx, CUST_KEY, ProJson.json.encodeToString(JsonArray.serializer(), JsonArray(list)))
    }

    /** Legacy validation limits (L55120–55145). Returns an error string or null when valid. */
    fun validate(d: StudioDraft): String? {
        if (d.name.trim().isEmpty()) return "Name is required"
        if (d.name.length > 90) return "Name is capped at 90 characters"
        if (d.group.isBlank()) return "Pick a muscle group"
        if (d.muscles.isEmpty()) return "Pick at least one muscle"
        val steps = d.steps.lines().filter { it.isNotBlank() }
        if (steps.size > 8) return "Steps: max 8 lines"
        if (steps.any { it.length > 220 }) return "Steps: max 220 characters per line"
        val cues = d.cues.lines().filter { it.isNotBlank() }
        if (cues.size > 6) return "Cues: max 6 lines"
        if (cues.any { it.length > 160 }) return "Cues: max 160 characters per line"
        if (d.imageUrl.isNotBlank() && !d.imageUrl.startsWith("https://")) return "Image URL must start with https://"
        return null
    }

    fun addCustom(ctx: Context, d: StudioDraft) {
        val list = loadCustom(ctx)
        val obj = buildJsonObject {
            put("name", d.name.trim())
            put("group", d.group)
            put("muscles", JsonArray(d.muscles.map { JsonPrimitive(it) }))
            put("equipment", d.equipment.ifBlank { "bodyweight" })
            put("level", d.level)
            put("prescriptionType", d.countedBy)
            put("instructions", JsonArray(d.steps.lines().filter { it.isNotBlank() }.map { JsonPrimitive(it) }))
            put("cues", JsonArray(d.cues.lines().filter { it.isNotBlank() }.map { JsonPrimitive(it) }))
            if (d.imageUrl.isNotBlank()) put("imageUrl", d.imageUrl)
            put("custom", true)
        }
        // same name = update in place (legacy merge semantics)
        val idx = list.indexOfFirst { ((it["name"] as? JsonPrimitive)?.contentOrNull ?: "").equals(d.name.trim(), ignoreCase = true) }
        if (idx >= 0) list[idx] = obj else list.add(obj)
        saveCustom(ctx, list)
        Library.reinjectCustom(ctx)
    }

    /** Live paste validation: returns the parsed array size or null. */
    fun quickValidate(paste: String): Int? {
        if (paste.isBlank()) return null
        return try {
            val healed = JsonHeal.heal(paste)
            val el = ProJson.json.parseToJsonElement(healed)
            val arr = JsonHeal.unwrapExerciseArray(el) ?: return null
            arr.size
        } catch (_: Exception) { null }
    }

    /**
     * Import & merge with per-item report (legacy importMerge, L57263–57296 +
     * merge report rows L57514–57521). Same name (case-insensitive) = update; else add.
     */
    fun importAndMerge(ctx: Context, paste: String): List<String> {
        val report = mutableListOf<String>()
        val arr = try {
            val el = ProJson.json.parseToJsonElement(JsonHeal.heal(paste))
            JsonHeal.unwrapExerciseArray(el)
        } catch (_: Exception) { null }
        if (arr == null) return listOf("Import failed: not a valid exercise array")
        val list = loadCustom(ctx)
        var added = 0; var updated = 0; var rejected = 0
        arr.forEach { el ->
            val o = el as? JsonObject ?: run { rejected++; report.add("✗ Rejected: not an object"); return@forEach }
            val raw = JsonHeal.normalizeExerciseFields(o)
            val name = (raw["name"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (name.isBlank() || name.length > 90) { rejected++; report.add("✗ Rejected: ${name.ifBlank { "(no name)" }} (bad name)"); return@forEach }
            val normalized = buildJsonObject {
                put("name", name)
                put("group", (raw["group"] as? JsonPrimitive)?.contentOrNull ?: "other")
                put("muscles", raw["muscles"] as? JsonArray ?: JsonArray(listOf(JsonPrimitive("other"))))
                put("equipment", (raw["equipment"] as? JsonPrimitive)?.contentOrNull ?: "bodyweight")
                put("instructions", raw["instructions"] as? JsonArray ?: JsonArray(emptyList()))
                put("custom", true)
            }
            val idx = list.indexOfFirst { ((it["name"] as? JsonPrimitive)?.contentOrNull ?: "").equals(name, ignoreCase = true) }
            if (idx >= 0) { list[idx] = normalized; updated++; report.add("↻ Updated: $name") }
            else { list.add(normalized); added++; report.add("＋ Added: $name") }
        }
        saveCustom(ctx, list)
        Library.reinjectCustom(ctx)
        report.add("Added $added · Updated $updated · Rejected $rejected")
        return report
    }

    fun exportLibrary(ctx: Context): String {
        val custom = loadCustom(ctx)
        return ProJson.pretty.encodeToString(JsonArray.serializer(), JsonArray(custom))
    }

    /** Offline AI helper: schema prompt with registry keys + 40 sampled names (L57299–57337). */
    fun buildHelperPrompt(ctx: Context): String {
        val groups = runCatching { Library.allMuscleGroups().map { it.name }.sorted() }.getOrDefault(emptyList())
        val names = runCatching {
            Library.allLibraryExercises(
                ProState.data.user.settings.trainingMode
            ).map { it.name }
        }.getOrDefault(emptyList())
        val sample = names.shuffled().take(40)
        return buildString {
            append("Generate 8-12 gym exercises as a strict JSON array. Each object: {")
            append("\"name\" (unique, max 90 chars), \"group\" (one of the allowed group keys below), ")
            append("\"muscles\" (array of allowed muscle keys), \"equipment\" (bodyweight|barbell|dumbbell|machine|cable|kettlebell|band), ")
            append("\"instructions\" (3-6 short steps), optional \"cues\" (max 6), optional \"imageUrl\" (https).\n")
            append("Allowed groups: ").append(groups.joinToString(", ")).append("\n")
            append("Do NOT reuse these existing names: ").append(sample.joinToString("; ")).append("\n")
            append("Return ONLY the JSON array, no commentary, no markdown fences.")
        }
    }
}
