package com.peakform.fitness

import com.peakform.fitness.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRoundTripTest {

    @Test
    fun `workoutData serializes with legacy field names`() {
        val d = WorkoutData()
        val el = d.toJsonElement() as JsonObject
        val keys = el.keys
        // legacy top-level keys, byte-compatible
        // lastFatigueDecay omitted when null (explicitNulls=false ~= JS undefined semantics)
        assertTrue(keys.containsAll(listOf("user", "workouts", "exercises", "history", "muscleFatigue", "muscleRecentExercises", "syncedToBackend", "couplingMatrix")))
    }

    @Test
    fun `parse legacy Format A export`() {
        val legacy = """
        {"user":{"name":"Alex","birthDate":"1990-06-15","gender":"male","weight":180.0,"height":70.0,
         "experience":"intermediate","goal":"balanced","created":"2025-01-01T00:00:00.000Z",
         "settings":{"workoutDays":["monday","wednesday","friday"],"restTime":90,"progressionRate":0.02,"theme":"blue","darkMode":false,"muscleCoupling":false,"bottomNavAutoHide":false,"expressMode":true,"aggression":1.25,"trainingMode":"mixed"}},
         "workouts":[{"id":"workout_1700000000000","date":"2025-01-02T10:00:00.000Z","type":"full_body_power","name":"Full Body Strength + Power",
          "exercises":[{"id":"barbell_back_squat_high_bar","name":"Barbell Back Squat (high bar)","muscleGroup":["quads"],
           "prescribed":{"sets":4,"reps":"8-12","weight":135.0,"weightMax":225.0},"actual":{"weight":135.0,"sets":4,"notes":"","rpe":[7,8,8,9],"firstRPE":7,"reps":[10,9,8,6],"volume":4410.0},"skipped":false,"progressionNotes":"n","equipment":"barbell","instructions":["do it"],"prescriptionType":"reps"}],
          "isDeload":false,"isTaper":false,"dateCompleted":"2025-01-02T11:00:00.000Z",
          "summary":{"totalVolume":4410.0,"averageRPE":8.0,"completedExercises":1},"recommendedRest":2.0}],
         "exercises":{"barbell_back_squat_high_bar":{"history":[{"date":"2025-01-02T10:00:00.000Z","weight":135.0,"sets":4,"rpe":[7,8,8,9],"notes":"","prescriptionType":"reps","muscles":["quads"],"firstRPE":7,"reps":[10,9,8,6],"volume":4410.0}],"tested1RM":null,"testDate":null,"mu":180.5,"sigma2":400.0,"residuals":[1.5]}},
         "muscleFatigue":{"quads":{"fast":0.3,"slow":0.1,"lastUpdate":"2025-01-02T11:00:00.000Z"}},
         "lastFatigueDecay":"2025-01-02T11:00:00.000Z","muscleRecentExercises":{"quads":["barbell_back_squat_high_bar"]},
         "syncedToBackend":false,"couplingMatrix":{},
         "exportDeviceId":"test-device","exportTimestamp":"2025-01-03T00:00:00.000Z"}
        """.trimIndent()
        val parsed = ProJson.decode(WorkoutData.serializer(), legacy)
        assertTrue(parsed.user.settings.expressMode)
        assertTrue(parsed.user.settings.aggression == 1.25)
        assertTrue(parsed.user.settings.trainingMode == "mixed")
        assertTrue(parsed.workouts.size == 1)
        val ex = parsed.workouts[0].exercises[0]
        assertTrue(ex.actual!!.reps.size == 4)
        assertTrue(ex.actual!!.rpeList.size == 4)
        assertTrue(parsed.exercises.containsKey("barbell_back_squat_high_bar"))
        assertTrue(parsed.exercises["barbell_back_squat_high_bar"]!!.mu == 180.5)
        // round-trip re-encode keeps legacy keys
        val out = parsed.toJsonElement() as JsonObject
        assertTrue(out.containsKey("muscleFatigue"))
        val settings = (out["user"] as JsonObject)["settings"] as JsonObject
        assertTrue(settings.containsKey("expressMode"))
        assertTrue(settings.containsKey("aggression"))
        assertTrue(settings.containsKey("trainingMode"))
        // scalar rpe duality
        val scalar = """{"weight":100.0,"sets":1,"notes":"","rpe":7,"reps":[5],"firstRPE":7}"""
        val a = ProJson.decode(ActualPerformance.serializer(), scalar)
        assertTrue(a.rpeList.firstOrNull() == 7.0)
    }

    @Test
    fun `rpe list vs scalar helpers`() {
        val arr = JsonArray(listOf(JsonPrimitive(6), JsonPrimitive(7)))
        assertTrue(ProJson.rpeToList(arr).size == 2)
        assertTrue(ProJson.rpeToList(JsonPrimitive(8)).size == 1)
        assertTrue(ProJson.rpeToList(null).isEmpty())
    }
}
