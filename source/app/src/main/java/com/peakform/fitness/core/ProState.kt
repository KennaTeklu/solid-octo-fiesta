package com.peakform.fitness.core

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.peakform.fitness.ProLog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.contentOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * ProState — single source of truth (structural fix for the legacy 3-store race).
 * Mirrors the legacy semantics of loadAllData()/saveWorkoutData()/performSave():
 *  - aggregate workoutData in memory
 *  - currentWorkout draft kept separately (savedWorkout row) + emergency backup pref
 *  - event-driven saves; draft saves debounced 200ms; visibilitychange → immediate flush
 * The vault NEVER auto-restores over live data (P4 fix): snapshots are taken on a
 * guarded schedule and restored only via explicit user action.
 */
object ProState {
    @Volatile var data: WorkoutData = WorkoutData()
    @Volatile var currentWorkout: WorkoutRecord? = null
    @Volatile var initialized: Boolean = false
    @Volatile var activeProfileId: String = Profiles.DEFAULT

    private var store: ProStore? = null
    private var ctx: Context? = null
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val saveMutex = Mutex()
    private var draftDebounceJob: kotlinx.coroutines.Job? = null
    /** BATCH-4B item 2 race fix: track the saveWorkoutData coroutine so flush()
     *  can join it before reopenStore() closes the old store's DB. */
    @Volatile private var saveJob: kotlinx.coroutines.Job? = null

    val listeners = mutableListOf<() -> Unit>()

    fun init(context: Context) {
        if (store != null) return
        ctx = context.applicationContext
        activeProfileId = Profiles.activeId(context)
        store = ProStore(context)
    }

    /** Profile switch: drop the old store handle so the next access opens the new namespace. */
    fun reopenStore(context: Context) {
        val old = store
        ctx = context.applicationContext
        activeProfileId = Profiles.activeId(context)
        old?.let { ProDb.close(it.dbName) }
        store = null
        data = WorkoutData()
        currentWorkout = null
        initialized = false
        store = ProStore(context)
    }

    fun notifyChanged() {
        val ls = synchronized(listeners) { listeners.toList() }
        ls.forEach { try { it() } catch (_: Exception) {} }
    }

    // ---------- time helpers (legacy uses local ISO strings; day-diffs UTC-midnight) ----------
    fun nowIso(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date())

    fun todayLocal(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    fun utcDayMillis(iso: String): Long = try {
        val d = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val p = if (iso.length >= 19) iso.substring(0, 19) else iso.substring(0, kotlin.math.min(10, iso.length))
        val dayOnly = if (iso.length >= 10 && !iso.contains('T')) iso.substring(0, 10) else null
        (if (dayOnly != null) SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(dayOnly) else d.parse(p))?.time ?: 0L
    } catch (_: Exception) { 0L }

    // ---------- hydration (translation of loadAllData) ----------
    suspend fun loadAll() {
        val s = store ?: return
        val workouts = mutableListOf<WorkoutRecord>()
        val exercises = mutableMapOf<String, ExerciseRecord>()
        var user: UserProfile? = null
        var saved: WorkoutRecord? = null
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            s.db.dao().allWorkouts().forEach { w ->
                try { workouts.add(ProJson.decode(WorkoutRecord.serializer(), w.json)) } catch (e: Exception) { ProLog.e("STORE", "bad workout row ${w.id}: ${e.message}") }
            }
            s.db.dao().allExercises().forEach { e ->
                try { exercises[e.id] = ProJson.decode(ExerciseRecord.serializer(), e.json) } catch (_: Exception) {}
            }
            s.db.dao().userJson()?.let { uj ->
                try { user = ProJson.decode(UserProfile.serializer(), uj) } catch (e: Exception) { ProLog.e("STORE", "bad user row: ${e.message}") }
            }
            s.db.dao().savedJson()?.let { sj ->
                try { saved = ProJson.decode(WorkoutRecord.serializer(), sj) } catch (_: Exception) {}
            }
        }

        val defaultUser = UserProfile(
            created = nowIso(),
            experience = "intermediate",
            settings = Settings(darkMode = false),
        )
        val mergedUser = if (user != null) mergeUser(defaultUser, user!!) else defaultUser

        val d = WorkoutData(
            user = mergedUser,
            workouts = workouts.sortedBy { it.date },
            exercises = exercises,
        )
        data = d

        // Emergency backup arbitration (legacy scoreWorkout)
        val emergency = readEmergencyBackup()
        var best: WorkoutRecord? = null
        var bestScore = -1.0
        if (saved != null && saved!!.exercises.isNotEmpty()) {
            best = saved; bestScore = scoreWorkout(saved!!)
        }
        if (emergency != null && emergency.exercises.isNotEmpty()) {
            val es = scoreWorkout(emergency)
            if (es > bestScore) { best = emergency; bestScore = es }
        }
        best = best?.let { sanitizeDraft(it) }
        if (best != null && best.exercises.isNotEmpty() && best.exercises.all { it.isLogged }) {
            // completed drafts are never restored
            best = null
        }
        currentWorkout = best
        initialized = true
        ProLog.i("STORE", "loaded: workouts=${d.workouts.size} exercises=${d.exercises.size} saved=${saved != null} emergency=${emergency != null} draft=${best != null}")
        notifyChanged()
    }

    private fun mergeUser(defaults: UserProfile, loaded: UserProfile): UserProfile {
        // Keep loaded values; fill any missing (null/blank) scalars from defaults.
        return loaded.copy(
            created = loaded.created.ifBlank { defaults.created },
            experience = loaded.experience.ifBlank { defaults.experience },
            goal = loaded.goal.ifBlank { defaults.goal },
            gender = loaded.gender.ifBlank { defaults.gender },
            settings = mergeSettings(defaults.settings, loaded.settings),
        )
    }

    /** Settings merge — structural fix for the legacy saveSettings() wipe bug. */
    fun mergeSettings(base: Settings, loaded: Settings): Settings = loaded

    private fun scoreWorkout(w: WorkoutRecord): Double {
        var score = 0.0
        val ts = w.lastModifiedAt?.let { try { utcDayMillis(it) } catch (_: Exception) { 0L } } ?: 0L
        score += ts / 1000.0
        score += w.exercises.count { it.actual != null && !it.skipped } * 10000.0
        if (w.dateCompleted != null) score = -1e9
        return score
    }

    private fun sanitizeDraft(w: WorkoutRecord): WorkoutRecord {
        val exs = w.exercises.mapIndexed { idx, ex ->
            when {
                ex.actual != null -> ex
                else -> ex
            }
        }
        return w.copy(
            activeDates = w.activeDates,
            lastModifiedAt = w.lastModifiedAt ?: w.date.ifBlank { nowIso() },
            id = w.id.ifBlank { "workout_${System.currentTimeMillis()}" },
            exercises = exs,
        )
    }

    // ---------- saves (translation of saveWorkoutData / performSave) ----------
    fun saveWorkoutData() {
        saveJob = scope.launch {
            val s = store ?: return@launch
            val d = data
            s.saveAggregate(d, null)
            ProPrefs.put(ctx!!, "workoutData", ProJson.encode(WorkoutData.serializer(), d)) // legacy mirror key
        }
    }

    fun saveCurrentWorkoutToStorage() {
        val cw = currentWorkout ?: return
        scope.launch {
            store?.saveCurrentWorkout(cw)
        }
    }

    fun saveWorkoutDraftImmediately() {
        val cw = currentWorkout ?: return
        var w = cw
        if (w.draftStartedAt == null) {
            w = w.copy(draftStartedAt = nowIso(), activeDates = w.activeDates)
        }
        w = w.copy(lastModifiedAt = nowIso())
        val today = todayLocal()
        if (!w.activeDates.contains(today)) {
            w = w.copy(activeDates = w.activeDates + today)
        }
        currentWorkout = w
        draftDebounceJob?.cancel()
        draftDebounceJob = scope.launch {
            delay(200)
            performSave()
        }
    }

    fun performSave() {
        val cw = currentWorkout ?: return
        saveCurrentWorkoutToStorage()
        // timestamped emergency backup (legacy workoutEmergencyBackup v2)
        // BATCH-4B item 2: moved to per-profile Room meta so a draft in profile A
        // never crosses over into profile B on switch.
        val c = ctx ?: return  // JVM tests / pre-init: in-memory only, never crash
        ProfileState.put(c, ProfileState.K_EMERGENCY_BACKUP, ProJson.json.encodeToString(
            JsonObject.serializer(),
            JsonObject(mutableMapOf(
                "workout" to ProJson.encodeElement(WorkoutRecord.serializer(), cw),
                "timestamp" to JsonPrimitive(System.currentTimeMillis()),
                "version" to JsonPrimitive(2),
            ))
        ))
    }

    /** Flush pending saves (called on onStop / before profile switch).
     *  BATCH-4B item 2 race fix: blocks until the in-flight saveWorkoutData
     *  coroutine finishes, so the next reopenStore() can safely close the old
     *  store's DB without a "connection pool has been closed" race. */
    fun flush() {
        draftDebounceJob?.cancel()
        draftDebounceJob = null
        if (currentWorkout != null) performSave()
        saveWorkoutData()
        kotlinx.coroutines.runBlocking { saveJob?.join() }
    }

    private fun readEmergencyBackup(): WorkoutRecord? {
        val c = ctx ?: return null
        val raw = ProfileState.get(c, ProfileState.K_EMERGENCY_BACKUP) ?: return null
        return try {
            val obj = ProJson.json.decodeFromString(JsonObject.serializer(), raw)
            val wEl = obj["workout"] ?: return null
            ProJson.decodeElement(WorkoutRecord.serializer(), wEl)
        } catch (_: Exception) { null }
    }

    fun clearEmergencyBackup() {
        ctx?.let { ProfileState.remove(it, ProfileState.K_EMERGENCY_BACKUP) }
    }

    fun newWorkoutId(): String = "workout_${System.currentTimeMillis()}"

    fun deviceId(): String {
        val c = ctx ?: return "unknown"
        var id = ProPrefs.get(c, "deviceId")
        if (id == null) {
            id = UUID.randomUUID().toString()
            ProPrefs.put(c, "deviceId", id)
        }
        return id
    }

    // ---------- export file naming (legacy format) ----------
    private val monthNames = arrayOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

    fun exportFileName(prefix: String, note: String? = null): String {
        val first = data.user.name.trim().ifBlank { "User" }.split(Regex("\\s+"))[0]
        val cal = java.util.Calendar.getInstance()
        val mon = monthNames[cal.get(java.util.Calendar.MONTH)]
        val day = cal.get(java.util.Calendar.DAY_OF_MONTH)
        val yr = cal.get(java.util.Calendar.YEAR)
        return "${prefix}_${first}_${mon}_${day}_${yr}.json"
    }
}

/**
 * ProfileRegistry — reads/writes the SAME SharedPreferences file ("registry") the original
 * native shell used, so profiles created in the old app are enumerated for migration and
 * remain meaningful after upgrade.
 * JSON shape (pro_profiles_v2): {profiles:[{id,name,createdAt,slot,port?}], active, askOnBoot, seq}
 */
object ProfileRegistry {
    data class Profile(val id: String, val name: String, val emoji: String?, val createdAt: Long, val slot: Int)

    /** Raw registry JSON for the full-app-bundle export (feature 10). */
    fun rawJson(ctx: Context): String? {
        val sp = ctx.getSharedPreferences("registry", Context.MODE_PRIVATE)
        return sp.getString("pro_profiles_v2", null) ?: sp.getString("profiles", null)
    }

    fun read(ctx: Context): Triple<List<Profile>, String?, Boolean> {
        val sp = ctx.getSharedPreferences("registry", Context.MODE_PRIVATE)
        val raw = sp.getString("pro_profiles_v2", null) ?: sp.getString("profiles", null)
        var profiles = listOf<Profile>()
        var active: String? = sp.getString("active", null)
        var ask = sp.getBoolean("askOnBoot", false)
        if (raw != null) {
            try {
                val obj = ProJson.json.decodeFromString(JsonObject.serializer(), raw)
                val arr = obj["profiles"] as? JsonArray
                if (arr != null) {
                    profiles = arr.mapNotNull { el ->
                        val o = el as? JsonObject ?: return@mapNotNull null
                        Profile(
                            id = (o["id"] as? JsonPrimitive)?.content ?: return@mapNotNull null,
                            name = (o["name"] as? JsonPrimitive)?.content ?: "Profile",
                            emoji = (o["emoji"] as? JsonPrimitive)?.content,
                            createdAt = (o["createdAt"] as? JsonPrimitive)?.doubleOrNull?.toLong() ?: 0L,
                            slot = (o["slot"] as? JsonPrimitive)?.intOrNull ?: 0,
                        )
                    }
                }
                (obj["active"] as? JsonPrimitive)?.content?.let { if (active == null) active = it }
                (obj["askOnBoot"] as? JsonPrimitive)?.booleanOrNull?.let { ask = it }
            } catch (e: Exception) {
                ProLog.e("REGISTRY", "failed to parse pro_profiles_v2: ${e.message}")
            }
        }
        return Triple(profiles, active, ask)
    }

    fun write(ctx: Context, profiles: List<Profile>, active: String?, askOnBoot: Boolean) {
        val sp = ctx.getSharedPreferences("registry", Context.MODE_PRIVATE)
        val arr = JsonArray(profiles.map { p ->
            JsonObject(mutableMapOf(
                "id" to JsonPrimitive(p.id),
                "name" to JsonPrimitive(p.name),
                "emoji" to (p.emoji?.let { JsonPrimitive(it) } ?: JsonNull),
                "createdAt" to JsonPrimitive(p.createdAt),
                "slot" to JsonPrimitive(p.slot),
            ))
        })
        val obj = JsonObject(mutableMapOf(
            "profiles" to arr,
            "active" to (active?.let { JsonPrimitive(it) } ?: JsonNull),
            "askOnBoot" to JsonPrimitive(askOnBoot),
            "seq" to JsonPrimitive(profiles.size),
        ))
        sp.edit().putString("pro_profiles_v2", ProJson.json.encodeToString(JsonObject.serializer(), obj))
            .putString("active", active ?: "")
            .putBoolean("askOnBoot", askOnBoot).commit()
    }
}
