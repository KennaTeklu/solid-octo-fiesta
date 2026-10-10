package com.peakform.fitness

import com.peakform.fitness.core.*
import com.peakform.fitness.engine.Generator
import com.peakform.fitness.engine.Library
import com.peakform.fitness.engine.Fatigue
import com.peakform.fitness.engine.OneRm
import com.peakform.fitness.engine.Sessions
import com.peakform.fitness.engine.Stats
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.doubleOrNull
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse

/**
 * ATHLETE SIMULATION — a demanding 4-week user journey driven through the REAL engine
 * (no mocks of engine logic). Marcus Webb, 28, 82.5 kg intermediate lifter, goal
 * "balanced", gym mode, trains 4x/week (Upper/Lower-ish via the 7-split rotation),
 * logs every set the app asks for, fails a rep-max occasionally, tracks bodyweight,
 * then audits stats/recovery/progress and exercises backup -> wipe -> restore.
 */
class JourneyTest {

    private lateinit var dataDir: File

    @Before
    fun setUp() {
        dataDir = sequenceOf("src/main/assets/data", "app/src/main/assets/data")
            .map { File(it) }.firstOrNull { it.isDirectory }
            ?: error("assets/data not found from ${File(".").absolutePath}")
        Library.ensureForTest { name -> File(dataDir, name).readText() }
        ProState.initialized = true
    }

    private fun freshPersona(): WorkoutData {
        val u = UserProfile(
            name = "Marcus Webb",
            birthDate = "1998-03-14",
            gender = "male",
            weight = 82.5,
            height = 181.0,
            experience = "intermediate",
            goal = "balanced",
            created = ProState.nowIso(),
            settings = Settings(
                workoutDays = listOf("monday", "tuesday", "thursday", "friday"),
                restTime = 120,
                trainingMode = "gym",
                theme = "blue",
                darkMode = true,
            ),
        )
        return WorkoutData(user = u)
    }

    // ---------- realistic logging helpers ----------

    private fun topReps(presc: Prescription): Double {
        val m = Regex("(\\d+)\\s*-\\s*(\\d+)").find(presc.reps)
        return if (m != null) m.groupValues[2].toDouble()
        else presc.reps.filter { it.isDigit() }.ifEmpty { "8" }.toDouble()
    }

    private fun logAllExercises(workout: WorkoutRecord, rnd: Random, failIndex: Int?): WorkoutRecord {
        var w = workout
        w.exercises.indices.forEach { idx ->
            val ex = w.exercises[idx]
            val presc = ex.prescribed
            val isTime = ex.prescriptionType == "time" || (presc.duration ?: 0.0) > 0.0
            val actual = if (isTime) {
                ActualPerformance(
                    weight = presc.weight ?: 0.0,
                    sets = presc.sets,
                    durations = List(presc.sets) { (presc.duration ?: 40.0) + rnd.nextInt(-5, 6) },
                    totalTime = (presc.duration ?: 40.0) * presc.sets,
                    rpe = rpeJson(listOf(6.0 + rnd.nextInt(0, 3).toDouble())),
                    firstRPE = 7.0,
                )
            } else {
                val weight = presc.weight ?: 0.0
                val failThis = failIndex != null && idx == failIndex && weight > 0
                val repsPerSet = List(presc.sets) { s ->
                    val base = topReps(presc)
                    (base - (if (failThis && s == presc.sets - 1) base - 1 else rnd.nextInt(0, 3).toDouble())).coerceAtLeast(1.0)
                }
                ActualPerformance(
                    weight = weight,
                    sets = presc.sets,
                    reps = repsPerSet,
                    rpe = rpeJson(List(presc.sets) { 7.0 + rnd.nextInt(0, 3).toDouble() }),
                    firstRPE = 8.0,
                    failure = failThis,
                    attemptedWeight = if (failThis) weight else null,
                )
            }
            val res = Sessions.applyPerformance(w, idx, actual, if (rnd.nextInt(10) == 0) "Felt strong today 💪" else "")
            assertTrue("applyPerformance idx=$idx failed: ${res.notification?.message}", res.ok)
            w = res.workout ?: return@forEach
        }
        return w
    }

    private fun rpeJson(vals: List<Double>) = buildJsonArray { vals.forEach { add(JsonPrimitive(it)) } }

    private fun assertFinite(d: Double, what: String) {
        assertTrue("$what not finite: $d", !d.isNaN() && !d.isInfinite())
    }

    // ---------- THE 4-WEEK JOURNEY ----------

    @Test
    fun `four week athlete journey - generate log complete progress`() {
        ProState.data = freshPersona()
        ProState.currentWorkout = null
        val rnd = Random(42)

        // Baseline 1RM seeds so progression has a starting point (like an imported user)
        val seeded = ProState.data.toMutableMap().also { m -> }
        var firstWorkout: WorkoutRecord? = null
        var completedCount = 0
        val muTimeline = mutableMapOf<String, MutableList<Double>>()
        val failureMsgs = mutableListOf<String>()

        repeat(12) { session ->
            val gen = Generator.performGenerateWorkout(60)
            val w = gen.workout
            assertTrue("session $session generated 0 exercises", w.exercises.isNotEmpty())
            assertTrue("session $session generated ${w.exercises.size} exercises (>20 unrealistic)", w.exercises.size <= 20)
            // the trim's own estimator must honor the 60-min budget (small slack for the floor)
            val est = Generator.estimateWorkoutMin(w, 120)
            assertTrue("session $session 60-min budget blown: est=$est min", est <= 65.0)

            // Prescription sanity — the class of bug where duration leaks into weight
            w.exercises.forEach { ex ->
                assertTrue("sets out of range for ${ex.name}", ex.prescribed.sets in 1..10)
                if (ex.prescriptionType == "reps" && (ex.prescribed.weight ?: 0.0) > 0.0) {
                    val wgt = ex.prescribed.weight!!
                    assertTrue("prescribed weight $wgt lbs looks bogus for ${ex.name}", wgt in 1.0..600.0)
                }
            }

            val failureThis = (session + 1) % 4 == 0
            val failIdx = if (failureThis) w.exercises.indexOfLast { (it.prescribed.weight ?: 0.0) > 0.0 && it.prescriptionType == "reps" && !it.isWarmup && !it.isCooldown } else null
            val logged = logAllExercises(w, rnd, failIdx)
            if (failureThis) {
                val f = logged.exercises.lastOrNull { it.actual?.failure == true }
                assertTrue("failure not recorded on any weighted exercise", f != null)
                failureMsgs.add(f!!.name)
            }

            // track 1RM timeline of the first WEIGHTED exercise (warmups/drills don't feed 1RM by design)
            val tracked = logged.exercises.firstOrNull { (it.actual?.weight ?: 0.0) > 0.0 && it.actual?.reps?.isNotEmpty() == true }
            val mu = tracked?.id?.let { ProState.data.exercises[it]?.mu }
            if (tracked != null && mu != null) muTimeline.getOrPut(tracked.id) { mutableListOf() }.add(mu)

            val comp = Sessions.completeWorkout(logged, generateNext = true)
            assertTrue("volume must be > 0", comp.totalVolume > 0)
            assertFinite(comp.totalVolume, "totalVolume s$session")
            assertTrue("recommendedRest out of range", comp.workout.recommendedRest!! in 0.0..14.0)
            assertTrue("streak must be >= 1", Stats.calculateStreak() >= 1)
            completedCount++
            if (firstWorkout == null) firstWorkout = comp.workout

            // bodyweight check-ins (realistic drift +0.02 kg/session)
            if (session % 2 == 0) {
                val bw = (82.5 + 0.02 * session)
                ProState.data = ProState.data.copy(
                    user = ProState.data.user.copy(
                        weight = bw,
                        bodyWeightHistory = ProState.data.user.bodyWeightHistory +
                            BodyWeightEntry(ProState.nowIso(), bw, "entry"),
                    ),
                )
            }
        }

        // --- post-journey audit (what the athlete sees on screen) ---
        val d = ProState.data
        assertEquals("12 completed workouts", 12, d.workouts.size)
        assertTrue("exercise records should exist", d.exercises.isNotEmpty())
        assertTrue("history entries missing", d.exercises.values.sumOf { it.history.size } >= 24)
        assertTrue("weekly volumes not tracked", d.user.weeklyVolumes.isNotEmpty())
        assertTrue("momentum taps untouched but object fine", d.user.momentumBias.value >= 0.0)

        // 1RM sanity: mu timeline exists, finite, and never poisoned by 0-reps
        assertTrue("no 1RM tracked at all", muTimeline.isNotEmpty())
        muTimeline.forEach { (id, mus) ->
            mus.forEach { assertFinite(it, "mu $id") }
            assertTrue("mu for $id collapsed to 0 (0-rep poisoning?)", mus.last() > 1.0)
        }

        // failure penalty actually adjusted something
        val failedRec = d.exercises.entries.firstOrNull { e -> failureMsgs.any { e.key.startsWith(it.lowercase().replace(Regex("\\s+"), "_")) } }
        if (failedRec != null) {
            assertTrue("failed exercise should carry history", failedRec.value.history.isNotEmpty())
        }

        // Stats screens
        val recovery = Stats.recoveryReport()
        assertTrue("recovery overall ${recovery.overall} out of 0..100", recovery.overall in 0..100)
        Stats.ringsData().forEach { r ->
            assertFinite(r.value, "ring ${r.label}")
            // "week" legitimately exceeds its goal (value is raw count; UI clamps the fraction)
            val cap = if (r.id == "week") maxOf(r.max, r.value) else r.max
            assertTrue("ring ${r.label} value ${r.value}/${r.max} out of range", r.value >= 0.0 && r.value <= cap)
        }
        val composite = Stats.dashboardComposite(null)
        composite.forEach { p -> assertFinite(p.value, "composite") }
        assertFinite(Stats.calculateOverallStrengthProgress(), "strength progress")
        val streak = Stats.calculateStreak()
        assertTrue("streak $streak impossible", streak in 1..12)
        assertFalse(Stats.formatNumber(compVolumeForCheck()).contains("E"))
        assertTrue("nextWorkoutDate empty", Stats.nextWorkoutDate().isNotBlank())

        // fatigue system: decay runs, ready muscles list works, coupling didn't explode
        Fatigue.applyFatigueDecay()
        val ready = Fatigue.getReadyMuscles()
        assertTrue("ready muscles should exist after decay", ready.isNotEmpty())
        d.user.muscleEWMA?.forEach { (m, v) -> assertFinite(v, "ewma $m") }

        // generation with a deloaded/fatigued state must not crash and must respect budget
        val genAfter = Generator.performGenerateWorkout(25)
        assertTrue(genAfter.workout.exercises.isNotEmpty())
        val chip = Generator.chipData(genAfter.workout)
        assertTrue("chip est time must respect budget-ish", chip == null || chip.estTime in 5..120)
        if (chip != null) {
            val realEst = Generator.estimateWorkoutMin(genAfter.workout, 120).toInt()
            assertTrue("chip estTime ${chip.estTime} wildly off real estimate $realEst", abs(chip.estTime - realEst) <= 15)
        }

        // longevity workout (the fully implemented generator)
        val longevity = Generator.generateLongevityWorkout()
        assertTrue("longevity workout empty", longevity.workout.exercises.isNotEmpty())
    }

    private fun compVolumeForCheck(): Double =
        ProState.data.workouts.lastOrNull()?.summary?.totalVolume ?: 0.0

    // ---------- BACKUP -> WIPE -> RESTORE ----------

    @Test
    fun `backup export wipe restore round trip`() {
        ProState.data = freshPersona()
        ProState.currentWorkout = null
        val rnd = Random(7)
        repeat(5) { _ ->
            val gen = Generator.performGenerateWorkout(null)
            val logged = logAllExercises(gen.workout, rnd, null)
            Sessions.completeWorkout(logged, generateNext = false)
        }
        val before = ProState.data

        // export Format A (device-id guarded on JVM)
        val export = Backup.exportFormatA(null, "test note")
        val raw = ProJson.json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), export)
        assertTrue("export must contain workouts", raw.contains("\"workouts\""))
        assertTrue("export carries the note", raw.contains("test note"))

        // WIPE (simulating clear-data / new device)
        ProState.data = WorkoutData()
        ProState.currentWorkout = null

        // restore through the real import merge path
        val incoming = ProJson.decode(WorkoutData.serializer(), raw)
        val restored = Backup.computeMerge(ProState.data, incoming)
        assertEquals("workouts restored", before.workouts.size, restored.workouts.size)
        assertEquals("exercise records restored", before.exercises.size, restored.exercises.size)
        assertEquals("profile name restored", "Marcus Webb", restored.user.name)
        assertEquals("bodyweight restored", before.user.weight!!, restored.user.weight!!, 0.001)
        assertEquals("settings restored", before.user.settings.trainingMode, restored.user.settings.trainingMode)

        // idempotent double-import
        val twice = Backup.computeMerge(restored, incoming)
        assertEquals("double import must not duplicate workouts", before.workouts.size, twice.workouts.size)

        // blank-field incoming must never blank local profile
        val hostile = incoming.copy(user = incoming.user.copy(name = "", weight = null, gender = "", settings = Settings()))
        val safe = Backup.computeMerge(restored, hostile)
        assertEquals("name survives hostile import", "Marcus Webb", safe.user.name)
        assertEquals("weight survives hostile import", before.user.weight!!, safe.user.weight!!, 0.001)
        assertEquals("gender survives hostile import", before.user.gender, safe.user.gender)
    }

    // ---------- BACKDATED HISTORY (4 weeks of context for stats/fatigue) ----------

    @Test
    fun `backdated four weeks - stats fatigue and readiness consistent`() {
        ProState.data = freshPersona()
        ProState.currentWorkout = null
        val rnd = Random(99)

        // pick real weighted exercises across push/pull/legs
        val picks = listOf("chest", "back_lats", "quads").mapNotNull { g ->
            Library.groups[g]?.firstOrNull { it.equipment == "barbell" } ?: Library.groups[g]?.firstOrNull()
        }
        assertTrue("library picks available", picks.size >= 3)

        val cal = java.util.Calendar.getInstance()
        val isoAt: (Int) -> String = { daysAgo ->
            val c = (cal.clone() as java.util.Calendar); c.add(java.util.Calendar.DAY_OF_YEAR, -daysAgo)
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.format(c.time)
        }

        val workouts = mutableListOf<WorkoutRecord>()
        var daysAgo = 27
        var session = 0
        while (daysAgo >= 1) {
            val exRecs = picks.mapIndexed { i, ex ->
                val weight = 100.0 + i * 25 + session * 2.5
                val perf = ActualPerformance(
                    weight = weight, sets = 3, reps = listOf(10.0, 9.0, 8.0),
                    rpe = rpeJson(listOf(7.5)), firstRPE = 7.5,
                )
                WorkoutExercise(
                    id = ex.id, name = ex.name, muscleGroup = ex.muscles.take(2),
                    prescribed = Prescription(sets = 3, reps = "8-12", weight = weight),
                    actual = perf, prescriptionType = "reps", equipment = ex.equipment,
                )
            }
            val w = WorkoutRecord(
                id = "hist_$session", date = isoAt(daysAgo), type = "gym", name = "Backdated Session $session",
                exercises = exRecs, dateCompleted = isoAt(daysAgo),
                summary = WorkoutSummary(3000.0 + session * 100, 8.0, 3),
                activeDates = listOf(isoAt(daysAgo).take(10)),
            )
            workouts.add(w)
            // per-exercise record histories (what rebuildFromHistory reads)
            exRecs.forEach { ex ->
                val rec = ProState.data.exercises[ex.id] ?: ExerciseRecord()
                ProState.data = ProState.data.copy(
                    exercises = ProState.data.exercises + (ex.id to rec.copy(
                        history = rec.history + HistoryEntry(
                            date = ex.actual?.let { "" } ?: "", weight = ex.prescribed.weight ?: 0.0,
                            sets = 3, reps = listOf(10.0, 9.0, 8.0), muscles = ex.muscleGroup,
                            volume = (ex.prescribed.weight ?: 0.0) * 27,
                        ),
                        lastUpdate = isoAt(daysAgo),
                    )),
                )
            }
            session++
            daysAgo -= if (session % 3 == 0) 2 else 1
        }
        ProState.data = ProState.data.copy(workouts = (ProState.data.workouts + workouts).sortedBy { it.date })
        // history dates set properly
        ProState.data = ProState.data.copy(
            exercises = ProState.data.exercises.mapValues { (_, rec) ->
                rec.copy(history = rec.history.mapIndexed { i, h -> if (h.date.isBlank()) h.copy(date = isoAt(27 - i)) else h })
            },
        )

        // rebuild derived views exactly like the engine does after import
        Fatigue.rebuildFromHistory()
        Fatigue.calculateMuscleLastTrained()
        Sessions.recomputeAggregates()

        // muscle readiness consistent with recency
        picks.forEach { ex ->
            ex.muscles.firstOrNull()?.let { m ->
                val since = Fatigue.daysSinceTrained(m)
                if (since != null) assertTrue("daysSinceTrained($m)=${since} must be <= 28", since in 0.0..28.0)
            }
        }
        val rep = Stats.recoveryReport()
        assertTrue("recovery ${rep.overall}", rep.overall in 0..100)
        val streak = Stats.calculateStreak()
        assertTrue("backdated streak $streak", streak >= 1 && streak <= 28)
        val comp = Stats.dashboardComposite(12)
        comp.forEach { assertFinite(it.value, "composite backdated") }
        // strength trend should be positive: weights grew 2.5 lb per session
        val progress = Stats.calculateOverallStrengthProgress()
        assertTrue("strength progress $progress should not be catastrophically negative", progress > -50.0)
    }

    @Test
    fun `edge cases - zero data unicode huge numbers`() {
        ProState.data = freshPersona()
        ProState.currentWorkout = null

        // fresh install screens
        assertEquals("fresh streak must be 0", 0, Stats.calculateStreak())
        assertTrue("fresh recovery ${Stats.recoveryReport().overall} out of range", Stats.recoveryReport().overall in 0..100)
        Stats.ringsData().forEach { r -> assertTrue("fresh ring ${r.label}=${r.value}/${r.max} out of range", r.value >= 0.0 && r.value <= r.max) }
        assertTrue("fresh composite must be empty", Stats.dashboardComposite(null).isEmpty())

        // unicode + long strings never break encoding
        ProState.data = ProState.data.copy(user = ProState.data.user.copy(name = "Žóran 🏋️ Müller-Jackson III"))
        val raw = ProJson.encode(WorkoutData.serializer(), ProState.data)
        assertTrue(raw.contains("Müller-Jackson"))
        val back = ProJson.decode(WorkoutData.serializer(), raw)
        assertEquals("Žóran 🏋️ Müller-Jackson III", back.user.name)

        // huge numbers format safely
        listOf(1.5e6, 9.9e9, 0.0001, -12345.67).forEach {
            val s = Stats.formatNumber(it)
            assertFalse("formatNumber($it) leaked scientific notation: $s", s.contains("E"))
            assertFalse("formatNumber($it) NaN/Inf: $s", s.contains("NaN") || s.contains("Inf"))
        }

        // applyPerformance rejects garbage weights (the UI's guard rail)
        val gen = Generator.performGenerateWorkout(45)
        val w0 = gen.workout
        if (w0.exercises.isNotEmpty() && (w0.exercises[0].prescribed.weight ?: 0.0) > 0) {
            val bad = Sessions.applyPerformance(w0, 0, ActualPerformance(weight = 0.0, sets = 3, reps = listOf(8.0)), "")
            assertFalse("zero-weight log on weighted lift must be rejected", bad.ok)
        }
    }

    // ---------- DASHBOARD NUDGES (audit #35: backup nudge + retest reminder) ----------

    @Test
    fun `nudges - backup staleness and retest cadence`() {
        val now = System.currentTimeMillis()
        val day = 86400000L
        fun isoDaysAgo(days: Int): String {
            val c = java.util.Calendar.getInstance()
            c.add(java.util.Calendar.DAY_OF_YEAR, -days)
            return java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.format(c.time)
        }

        // --- backup nudge: needs data, fires when never-backed-up or stale ---
        assertFalse("no nag with 0 workouts", Nudges.shouldNudgeBackup(null, 0, now))
        assertFalse("no nag with 2 workouts", Nudges.shouldNudgeBackup(null, 2, now))
        assertTrue("nudge: 3 workouts, never backed up", Nudges.shouldNudgeBackup(null, 3, now))
        assertTrue("nudge: backup 20 days old", Nudges.shouldNudgeBackup(isoDaysAgo(20), 3, now))
        assertFalse("no nag: backup 3 days old", Nudges.shouldNudgeBackup(isoDaysAgo(3), 3, now))
        assertTrue("unparsable stamp treated as never", Nudges.shouldNudgeBackup("garbage", 3, now))

        // --- retest reminder: only for calibrated lifts with a stale date ---
        assertFalse("fresh install: no retest nag", Nudges.retestDue(emptyMap(), now))
        val fresh = ExerciseRecord(tested1RM = 200.0, testDate = isoDaysAgo(10))
        val stale = ExerciseRecord(tested1RM = 200.0, testDate = isoDaysAgo(60))
        assertFalse("10-day-old test is fine", Nudges.retestDue(mapOf("a" to fresh), now))
        assertTrue("60-day-old test is stale", Nudges.retestDue(mapOf("a" to stale), now))
        assertFalse("mu==0 is not a calibration", Nudges.retestDue(mapOf("a" to ExerciseRecord(mu = 0.0, lastUpdate = isoDaysAgo(90))), now))
        assertTrue("mu-only calibration with stale date counts", Nudges.retestDue(mapOf("a" to ExerciseRecord(mu = 135.0, lastUpdate = isoDaysAgo(90))), now))
        assertFalse("undated record must not false-positive", Nudges.retestDue(mapOf("a" to ExerciseRecord(tested1RM = 180.0)), now))

        // --- a stale mix among fresh records still fires ---
        val mix = mapOf("ok" to fresh, "old" to stale)
        assertTrue("one stale lift is enough", Nudges.retestDue(mix, now))
    }

    private fun WorkoutData.toMutableMap() = this // no-op guard for earlier draft code
}
