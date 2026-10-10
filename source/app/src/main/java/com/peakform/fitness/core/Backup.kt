package com.peakform.fitness.core

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import com.peakform.fitness.ProLog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Backup — full import/export compatibility with the legacy formats:
 *  - Format A      : {...workoutData, exportDeviceId, exportTimestamp}
 *  - Complete-v1   : PeakForm-Complete-Backup-v1 {__meta, localStorage, indexedDB.p4_vault, liveWorkoutData}
 *  - try4ever      : older P4 bundle (tolerated on import)
 * Import is additive-safe: a guarded snapshot is taken first (P4 fix for the destructive
 * loadData() clear), then data is merged by the documented priority order.
 */
object Backup {
    const val FORMAT_V1 = "PeakForm-Complete-Backup-v1"

    /**
     * BATCH-2A P0 fix (part 2): walk the export tree and normalize special
     * floating-point values (NaN / -NaN / Infinity / -Infinity in any primitive,
     * including the double form kotlinx produces for a non-finite Double) to JsonNull.
     * Without this the JSON writer emits bare NaN/Infinity tokens, which the HTML app
     * and every strict JSON parser reject on import.
     */
    private fun sanitize(el: JsonElement): JsonElement = when (el) {
        is JsonObject -> JsonObject(el.mapValues { sanitize(it.value) })
        is JsonArray -> JsonArray(el.map { sanitize(it) })
        is JsonPrimitive -> {
            val s = el.contentOrNull
            if (s == "NaN" || s == "-NaN" || s == "Infinity" || s == "-Infinity") JsonNull else el
        }
        else -> el
    }

    fun exportFormatA(ctx: Context?, note: String? = null): JsonObject {
        val d = ProState.data
        val obj = d.toJsonElement().jsonObject.toMutableMap()
        obj["exportDeviceId"] = JsonPrimitive(ProState.deviceId())
        obj["exportTimestamp"] = JsonPrimitive(ProState.nowIso())
        if (note != null) obj["backupNote"] = JsonPrimitive(note)
        obj["_p4SavedAt"] = JsonPrimitive(System.currentTimeMillis())
        return sanitize(JsonObject(obj)) as JsonObject
    }

    fun exportCompleteV1(ctx: Context): JsonObject {
        val d = ProState.data
        val lsKeys = mutableMapOf<String, JsonElement>()
        for ((k, v) in ProPrefs.all(ctx)) lsKeys[k] = JsonPrimitive(v ?: "")
        val vaultRows = mutableListOf<JsonElement>()
        kotlinx.coroutines.runBlocking {
            val v = ProStore(ctx).db.dao().allVault()
            v.forEach { row ->
                try {
                    val inner = ProJson.json.decodeFromString(JsonObject.serializer(), row.json)
                    vaultRows.add(JsonObject(mutableMapOf("at" to JsonPrimitive(row.at), "data" to (inner["workoutData"] ?: JsonNull))))
                } catch (_: Exception) {}
            }
        }
        val totalVolume = d.workouts.sumOf { it.summary?.totalVolume ?: 0.0 }
        val meta = buildJsonObject {
            put("appVersion", "4.0.0-native-1.2.1")
            put("exportDate", ProState.nowIso())
            put("exportTimestamp", System.currentTimeMillis())
            put("profileCount", ProfileRegistry.read(ctx).first.size)
            put("workoutCount", d.workouts.size)
            put("totalVolume", totalVolume)
            put("deviceInfo", buildJsonObject {
                put("userAgent", "Pro/1.2.1 Android native")
                put("platform", "android")
                put("language", java.util.Locale.getDefault().toLanguageTag())
            })
        }
        val bundle = buildJsonObject {
            put("__format", FORMAT_V1)
            put("__meta", meta)
            put("localStorage", JsonObject(lsKeys))
            put("indexedDB", buildJsonObject {
                put("p4_vault", JsonObject(vaultRows.mapIndexed { i, el -> "snap_$i" to el }.toMap()))
            })
            put("liveWorkoutData", d.toJsonElement())
        }
        return sanitize(bundle) as JsonObject
    }

    /** Result of an import. BATCH-2A feature 11: carries the merge-report detail. */
    data class ImportResult(
        val ok: Boolean, val format: String, val workouts: Int, val exercises: Int, val message: String,
        val workoutsAdded: Int = 0, val exercisesAdded: Int = 0, val duplicates: Int = 0,
        val rejected: List<String> = emptyList(),
    )

    /** Merge report (feature 11) — format detected, adds, duplicates, rejections with reasons. */
    data class MergeReport(
        val format: String,
        val workoutsAdded: Int,
        val exercisesAdded: Int,
        val duplicates: Int,
        val rejected: List<String>,
    )

    /**
     * BATCH-2A feature 12: format detection incl. Format B — the older P4 bundle
     * tagged { "kind": "try4ever-fitness-backup", ...sibling fields }.
     */
    fun detectFormat(root: JsonObject): String {
        val f = (root["__format"] as? JsonPrimitive)?.contentOrNull
        if (f == FORMAT_V1) return FORMAT_V1
        val kind = (root["kind"] as? JsonPrimitive)?.contentOrNull
        if (kind == "try4ever-fitness-backup") return "try4ever-fitness-backup (Format B)"
        if (root["workouts"] != null || root["exercises"] != null || root["user"] != null) return "Format A"
        if (root["workoutData"] != null) return "Format A (workoutData wrapper)"
        return "?"
    }

    /**
     * Where the workout data lives in a bundle — factored out of import() so the
     * Format B sibling-field layout is covered by the same tested path.
     */
    fun locateWorkoutDataSource(root: JsonObject): JsonObject? {
        val format = detectFormat(root)
        val direct = root["workoutData"] as? JsonObject
        return when (format) {
            FORMAT_V1 -> (root["liveWorkoutData"] as? JsonObject)
                ?: (root["localStorage"]?.let { ls -> (ls as? JsonObject)?.get("p4_profile_data_active") as? JsonObject })
                ?: direct
            "try4ever-fitness-backup (Format B)" -> direct
                ?: ((root["data"] as? JsonObject)?.get("workoutData") as? JsonObject)
                ?: (root["workouts"] != null || root["exercises"] != null || root["user"] != null).let { if (it) root else null }
            else -> if (root["workouts"] != null || root["exercises"] != null || root["user"] != null) root else direct
        }
    }

    /**
     * Import from arbitrary JSON. Detects the format. Snapshot first, merge second.
     * Priority order (per worklog spec): liveWorkoutData/inline workoutData > workouts+exercises.
     */
    suspend fun import(ctx: Context, raw: String): ImportResult {
        // BATCH-2A P0 fix: heal before parsing — the HTML export writes string-form
        // special floats ("averageRPE": "NaN") that JsonHeal normalizes to null first.
        val root = try { ProJson.json.parseToJsonElement(JsonHeal.heal(raw)).jsonObject } catch (e: Exception) {
            return ImportResult(false, "?", 0, 0, "Not a valid JSON backup file")
        }
        // 1. guarded snapshot of current state before anything is touched
        ProStore(ctx).snapshotVault("pre-import", ProState.data, ProState.currentWorkout)
        ProLog.i("BACKUP", "pre-import snapshot taken")

        val format = detectFormat(root)
        var source: JsonObject? = locateWorkoutDataSource(root)
        val lsBundle = root["localStorage"] as? JsonObject
        if (source == null) {
            // Maybe a pure localStorage-only bundle (profile data key)
            source = root["workoutData"] as? JsonObject
        }
        if (source == null) return ImportResult(false, format, 0, 0, "No workout data found in bundle")

        val incoming = try {
            ProJson.decode(WorkoutData.serializer(), source.toString())
        } catch (e: Exception) {
            ProLog.e("BACKUP", "parse of workoutData failed: ${e.message}")
            return ImportResult(false, format, 0, 0, "Backup payload unreadable: ${e.message?.take(120)}")
        }

        // 2. merge: additive for workouts/exercises; user merged field-wise (blank incoming
        //    fields never blank out local data — P4 loadData() destructive-import fix)
        val cur = ProState.data
        val report = reportMerge(cur, incoming)
        val merged = computeMerge(cur, incoming)
        ProState.data = merged

        // 3. restore localStorage bundle keys (p4_* settings, profiles, etc.)
        if (lsBundle != null) {
            for ((k, v) in lsBundle) {
                if (k == "workoutEmergencyBackup") continue
                val s = (v as? JsonPrimitive)?.contentOrNull ?: continue
                ProPrefs.put(ctx, k, s)
            }
        }

        // 4. persist everything
        ProState.saveWorkoutData()
        EngineHooks.rebuildDerived()
        ProState.notifyChanged()
        ProLog.i("BACKUP", "imported format=$format workouts=${incoming.workouts.size} exercises=${incoming.exercises.size}")
        return ImportResult(true, format, incoming.workouts.size, incoming.exercises.size,
            "Imported ${report.workoutsAdded} new workouts (${report.duplicates} duplicates) and ${report.exercisesAdded} exercise records",
            workoutsAdded = report.workoutsAdded, exercisesAdded = report.exercisesAdded,
            duplicates = report.duplicates, rejected = report.rejected)
    }

    /**
     * Pure, testable merge with per-item report (feature 11): counts genuinely new
     * workouts/exercises, duplicates (with reason) and malformed entries (with reason).
     */
    fun reportMerge(cur: WorkoutData, incoming: WorkoutData): MergeReport {
        val rejected = mutableListOf<String>()
        val existingKeys = cur.workouts.map { it.id.ifBlank { it.date + it.name } }.toSet()
        var addedW = 0; var dupW = 0
        incoming.workouts.forEach { w ->
            val key = w.id.ifBlank { w.date + w.name }
            when {
                w.id.isBlank() && w.date.isBlank() -> rejected.add("workout \"${w.name.ifBlank { "(unnamed)" }}\" rejected: missing id and date")
                key in existingKeys -> dupW++
                else -> addedW++
            }
        }
        var addedE = 0
        incoming.exercises.forEach { (k, v) ->
            when {
                k.isBlank() -> rejected.add("exercise record rejected: missing id")
                cur.exercises.containsKey(k) -> {} // merge, not an add
                else -> addedE++
            }
        }
        return MergeReport(detectFormat(incoming.toJsonElement() as? JsonObject ?: JsonObject(emptyMap())), addedW, addedE, dupW, rejected)
    }

    /**
     * Pure, testable merge of an incoming backup payload into the current state.
     * - workouts/exercises: additive, dedup by id (or date+name), newest-first order preserved
     * - workouts with NO id AND NO date are rejected (unidentifiable — matches reportMerge)
     * - user: incoming wins, but blank incoming scalars keep local values; settings merged field-wise
     */
    fun computeMerge(cur: WorkoutData, incoming: WorkoutData): WorkoutData {
        val mergedWorkouts = (incoming.workouts.filterNot { it.id.isBlank() && it.date.isBlank() } + cur.workouts)
            .distinctBy { it.id.ifBlank { it.date + it.name } }
            .sortedBy { it.date }
        val mergedExercises = mutableMapOf<String, ExerciseRecord>()
        cur.exercises.forEach { (k, v) -> mergedExercises[k] = v }
        incoming.exercises.forEach { (k, v) ->
            val old = mergedExercises[k]
            mergedExercises[k] = if (old == null) v else mergeExerciseRecord(old, v)
        }
        val u = incoming.user; val c = cur.user
        val mergedUser = u.copy(
            name = u.name.ifBlank { c.name },
            gender = u.gender.ifBlank { c.gender },
            experience = u.experience.ifBlank { c.experience },
            goal = u.goal.ifBlank { c.goal },
            created = u.created.ifBlank { c.created },
            weight = u.weight ?: c.weight,
            height = u.height ?: c.height,
            birthDate = u.birthDate ?: c.birthDate,
            bodyWeightHistory = if (u.bodyWeightHistory.size >= c.bodyWeightHistory.size) u.bodyWeightHistory else c.bodyWeightHistory,
            settings = mergeSettingsFieldwise(c.settings, u.settings),
        )
        return cur.copy(
            user = mergedUser,
            workouts = mergedWorkouts,
            exercises = mergedExercises,
        )
    }

    /** Field-wise settings merge: strings/collections merge (blanks never wipe); booleans take incoming (legacy decode semantics). */
    fun mergeSettingsFieldwise(cur: Settings, inc: Settings): Settings = Settings(
        workoutDays = inc.workoutDays.ifEmpty { cur.workoutDays },
        restTime = if (inc.restTime > 0) inc.restTime else cur.restTime,
        progressionRate = if (inc.progressionRate != 0.02) inc.progressionRate else cur.progressionRate,
        theme = inc.theme.ifBlank { cur.theme },
        darkMode = inc.darkMode,
        muscleCoupling = inc.muscleCoupling,
        bottomNavAutoHide = inc.bottomNavAutoHide,
        expressMode = inc.expressMode,
        aggression = if (inc.aggression != 1.0) inc.aggression else cur.aggression,
        equipment = inc.equipment.ifEmpty { cur.equipment },
        trainingMode = inc.trainingMode.ifBlank { cur.trainingMode },
        sarcasmMode = inc.sarcasmMode,
        postpartumMode = inc.postpartumMode,
    )

    private fun mergeExerciseRecord(old: ExerciseRecord, inc: ExerciseRecord): ExerciseRecord {
        val hist = (old.history + inc.history).distinctBy { it.date + "|" + it.weight + "|" + it.reps.size }
        return if (old.lastUpdate != null && inc.lastUpdate != null && old.lastUpdate >= inc.lastUpdate) old.copy(history = hist)
        else inc.copy(history = hist, startingWeight = inc.startingWeight ?: old.startingWeight)
    }

    // ---------- BATCH-2A feature 10: full app bundle (export + import) ----------

    const val BUNDLE_V1 = "PeakForm-App-Bundle-v1"

    /**
     * Full app bundle: ONE file holding workouts, exercises, library (custom
     * exercises), profiles, snapshots, settings/preferences, consent log, feedback
     * log and the custom phrasebook — everything needed to restore the app elsewhere.
     */
    fun exportBundleV1(ctx: Context): JsonObject {
        val snapshotIds = mutableListOf<String>()
        kotlinx.coroutines.runBlocking {
            Snapshots.list(ctx).forEach { snapshotIds.add(it.id) }
        }
        val snapshots = mutableMapOf<String, JsonElement>()
        kotlinx.coroutines.runBlocking {
            snapshotIds.forEach { id ->
                ProStore(ctx).db.dao().meta(id)?.let { raw ->
                    try { snapshots[id] = ProJson.json.parseToJsonElement(raw) } catch (_: Exception) {}
                }
            }
        }
        val bundle = buildJsonObject {
            put("__format", JsonPrimitive(BUNDLE_V1))
            put("exportedAt", JsonPrimitive(ProState.nowIso()))
            put("deviceName", JsonPrimitive(android.os.Build.MODEL))
            put("workoutData", ProState.data.toJsonElement())
            ProState.currentWorkout?.let { put("currentWorkout", ProJson.encodeElement(WorkoutRecord.serializer(), it)) }
            put("library", buildJsonObject {
                put("customExercises", ProJson.json.parseToJsonElement(Studio.exportLibrary(ctx)))
            })
            put("profiles", ProJson.json.parseToJsonElement(ProfileRegistry.rawJson(ctx) ?: "{}"))
            put("snapshots", JsonObject(snapshots))
            put("settings", JsonObject(ProPrefs.all(ctx).mapValues { JsonPrimitive(it.value ?: "") }))
            put("consentLog", ProJson.json.parseToJsonElement(Consent.exportLog(ctx)))
            put("feedbackLog", ProJson.json.parseToJsonElement(Exporter.feedbackJson(ctx)))
            put("vocabCustom", JsonPrimitive(ProPrefs.get(ctx, Vocab.CUSTOM_KEY) ?: ""))
        }
        return sanitize(bundle) as JsonObject
    }

    data class BundleImportResult(val ok: Boolean, val message: String, val details: List<String> = emptyList())

    /** Restore a full app bundle: guarded (pre-import snapshot first), then everything. */
    fun importBundleV1(ctx: Context, raw: String): BundleImportResult {
        val root = try { ProJson.json.parseToJsonElement(JsonHeal.heal(raw)).jsonObject } catch (e: Exception) {
            return BundleImportResult(false, "Not a valid app bundle: ${e.message?.take(100)}")
        }
        val format = (root["__format"] as? JsonPrimitive)?.contentOrNull
        if (format != BUNDLE_V1) return BundleImportResult(false, "Not an app bundle (found format: ${format ?: "unknown"})")
        val details = mutableListOf<String>()

        // guarded: snapshot before anything is touched (same contract as Backup.import)
        kotlinx.coroutines.runBlocking { ProStore(ctx).snapshotVault("pre-import", ProState.data, ProState.currentWorkout) }

        // 1. live workout data (restores — a bundle is a full restore, snapshot above is the safety net)
        (root["workoutData"] as? JsonObject)?.let { wd ->
            try {
                val restored = ProJson.decode(WorkoutData.serializer(), wd.toString())
                ProState.data = restored
                details.add("workouts restored: ${restored.workouts.size}")
                (root["currentWorkout"] as? JsonObject)?.let { cw ->
                    try { ProState.currentWorkout = ProJson.decode(WorkoutRecord.serializer(), cw.toString()) } catch (_: Exception) {}
                }
            } catch (e: Exception) { details.add("workoutData rejected: ${e.message?.take(80)}") }
        } ?: details.add("no workoutData in bundle")

        // 2. custom exercise library — merge, never replace
        ((root["library"] as? JsonObject)?.get("customExercises") as? JsonArray)?.let { arr ->
            val json = ProJson.json.encodeToString(JsonArray.serializer(), arr)
            val before = Studio.loadCustom(ctx).size
            val report = Studio.importAndMerge(ctx, json)
            val summary = report.lastOrNull() ?: "library merged"
            details.add("library: before $before — $summary")
        }

        // 3. profiles registry
        (root["profiles"] as? JsonObject)?.let { profiles ->
            try {
                val reg = (profiles["profiles"] as? JsonArray)
                val active = (profiles["active"] as? JsonPrimitive)?.contentOrNull
                if (reg != null) {
                    val list = reg.mapNotNull { el -> el as? JsonObject }.map { o ->
                        ProfileRegistry.Profile(
                            id = (o["id"] as? JsonPrimitive)?.content ?: "",
                            name = (o["name"] as? JsonPrimitive)?.content ?: "Profile",
                            emoji = (o["emoji"] as? JsonPrimitive)?.content,
                            createdAt = (o["createdAt"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L,
                            slot = (o["slot"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0,
                        )
                    }.filter { it.id.isNotBlank() }
                    if (list.isNotEmpty()) {
                        ProfileRegistry.write(ctx, list, active, (profiles["askOnBoot"] as? JsonPrimitive)?.content == "true")
                        details.add("profiles restored: ${list.size}")
                    }
                }
            } catch (e: Exception) { details.add("profiles rejected: ${e.message?.take(80)}") }
        }

        // 4. vault snapshots — imported under their own keys, listed in the Snapshots screen
        (root["snapshots"] as? JsonObject)?.let { snaps ->
            var n = 0
            snaps.forEach { (key, el) ->
                try {
                    kotlinx.coroutines.runBlocking {
                        ProStore(ctx).db.dao().putMeta(
                            MetaRow(if (key.startsWith("snapshot_")) key else "snapshot_$key",
                                ProJson.json.encodeToString(JsonElement.serializer(), el)),
                        )
                    }
                    n++
                } catch (_: Exception) {}
            }
            if (n > 0) details.add("snapshots restored: $n")
        }

        // 5. settings/preferences (localStorage mirror keys)
        (root["settings"] as? JsonObject)?.let { settings ->
            var n = 0
            settings.forEach { (k, v) ->
                if (k != "workoutEmergencyBackup") {
                    (v as? JsonPrimitive)?.contentOrNull?.let { ProPrefs.put(ctx, k, it); n++ }
                }
            }
            if (n > 0) details.add("settings keys restored: $n")
        }

        // 6. consent log — merged (newest first), capped at the Consent.CAP
        (root["consentLog"] as? JsonArray)?.let { arr ->
            try {
                val incoming = arr.mapNotNull { el -> el as? JsonObject }.mapNotNull { o ->
                    val at = (o["at"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    val kind = (o["kind"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    val detail = (o["detail"] as? JsonPrimitive)?.contentOrNull ?: ""
                    val version = (o["version"] as? JsonPrimitive)?.contentOrNull ?: Consent.VERSION
                    Consent.Entry(at, kind, detail, version)
                }
                if (incoming.isNotEmpty()) {
                    val existing = Consent.readLog(ctx)
                    val merged = (existing + incoming).distinctBy { it.at + "|" + it.kind }.take(200)
                    ProPrefs.put(ctx, Consent.LOG_KEY, ProJson.json.encodeToString(
                        kotlinx.serialization.builtins.ListSerializer(Consent.Entry.serializer()), merged))
                    details.add("consent entries restored: ${incoming.size}")
                }
            } catch (e: Exception) { details.add("consent log rejected: ${e.message?.take(80)}") }
        }

        // 7. feedback log
        (root["feedbackLog"] as? JsonObject)?.let { fb ->
            ((fb["entries"] as? JsonArray))?.let { arr ->
                if (arr.isNotEmpty()) {
                    ProPrefs.put(ctx, Exporter.FEEDBACK_KEY, ProJson.json.encodeToString(JsonArray.serializer(), arr))
                    details.add("feedback entries restored: ${arr.size}")
                }
            }
        }

        // 8. custom phrasebook
        ((root["vocabCustom"] as? JsonPrimitive)?.contentOrNull)?.takeIf { it.isNotBlank() }?.let { vocab ->
            ProPrefs.put(ctx, Vocab.CUSTOM_KEY, vocab)
            Vocab.reloadCustom(ctx)
            details.add("phrasebook restored")
        }

        // persist everything live
        ProState.saveWorkoutData()
        kotlinx.coroutines.runBlocking { EngineHooks.rebuildDerived() }
        ProState.notifyChanged()
        return BundleImportResult(true, "App bundle restored — ${details.size} sections", details)
    }

    // ---------- SAF helpers ----------
    fun writeTextToUri(ctx: Context, uri: Uri, text: String): Boolean = try {
        ctx.contentResolver.openOutputStream(uri, "wt")?.use { os ->
            os.write(text.toByteArray())
        }
        true
    } catch (e: Exception) {
        ProLog.e("BACKUP", "write failed: ${e.message}")
        false
    }

    fun writeJsonToUri(ctx: Context, uri: Uri, json: JsonObject): Boolean = try {
        ctx.contentResolver.openOutputStream(uri, "wt")?.use { os ->
            os.write(ProJson.pretty.encodeToString(JsonObject.serializer(), json).toByteArray())
        }
        true
    } catch (e: Exception) {
        ProLog.e("BACKUP", "write failed: ${e.message}")
        false
    }

    fun readJsonFromUri(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
    } catch (e: Exception) {
        ProLog.e("BACKUP", "read failed: ${e.message}")
        null
    }
}

/** Hooks the engine exposes to the store layer (set at boot by EngineInitializer). */
object EngineHooks {
    var rebuildDerived: suspend () -> Unit = {}
}
