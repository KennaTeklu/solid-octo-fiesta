package com.peakform.fitness.core

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import com.peakform.fitness.ProLog

/**
 * VoiceLog — native improvement NA12: voice logging via the SYSTEM RecognizerIntent
 * (no RECORD_AUDIO permission — the speech dialog belongs to the system assistant).
 * Returns transcribed text; parsing into sets/reps heuristics lives in parse().
 */
object VoiceLog {

    fun intent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PROMPT, "Say it like: bench press, 3 sets of 8 at 135")
    }

    fun available(activity: Activity): Boolean =
        activity.packageManager.queryIntentActivities(intent(), 0).isNotEmpty()

    /** Heuristic parse: "bench press 3 sets of 8 at 135" → (name, sets, reps, weight). */
    data class Parsed(val name: String?, val sets: Int?, val reps: Int?, val weight: Double?)

    fun parse(text: String): Parsed {
        val lower = text.lowercase()
        val sets = Regex("(\\d+)\\s*(?:sets?|x)").find(lower)?.groupValues?.get(1)?.toIntOrNull()
        val reps = Regex("(?:sets?\\s*of\\s*|x\\s*)(\\d+)").find(lower)?.groupValues?.get(1)?.toIntOrNull()
        val weight = Regex("(?:at\\s*)?(\\d+(?:\\.\\d+)?)\\s*(?:lbs?|pounds?|kg)?\\s*$").find(lower.trim())
            ?.groupValues?.get(1)?.toDoubleOrNull()
        val name = lower
            .replace(Regex("\\d+\\s*(?:sets?|x|reps?|of|at|lbs?|pounds?|kg)"), " ")
            .replace(Regex("\\b\\d+(?:\\.\\d+)?\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .takeIf { it.isNotBlank() }
        return Parsed(name, sets, reps, weight)
    }
}
