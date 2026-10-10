package com.peakform.fitness.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.peakform.fitness.core.ActualPerformance
import com.peakform.fitness.core.ProJson
import com.peakform.fitness.core.ProState
import com.peakform.fitness.core.WorkoutExercise
import com.peakform.fitness.engine.OneRm
import com.peakform.fitness.engine.Sessions
import com.peakform.fitness.ui.*
import com.peakform.fitness.ui.components.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray

/**
 * Logger + 1RM test panel + rest timer + Swal-style dialogs (scr-workout.md §7-9, §14).
 */

// ------------------------------------------------------------------
// Logger — weight stepper (±2.5), sets stepper, per-set reps, RPE chips 1-10,
// notes; builds ActualPerformance and calls onSave.
// ------------------------------------------------------------------
@Composable
fun LoggerPanel(
    exercise: WorkoutExercise,
    onSave: (ActualPerformance, String) -> Unit,
    onStartRest: (Int) -> Unit,
) {
    val c = LocalProColors.current
    val isTime = exercise.prescriptionType == "time" && exercise.defaultDuration != null
    val rx = exercise.prescribed

    var weight by remember(exercise.id) { mutableDoubleStateOf(rx.weight ?: 0.0) }
    var sets by remember(exercise.id) { mutableIntStateOf(rx.sets.coerceIn(1, 12)) }
    var repsPerSet by remember(exercise.id) { mutableStateOf(List(12) { 0.0 }) }
    var durationsPerSet by remember(exercise.id) { mutableStateOf(List(12) { 0.0 }) }
    var rpe by remember(exercise.id) { mutableDoubleStateOf(7.0) }
    var notes by remember(exercise.id) { mutableStateOf("") }
    var failure by remember(exercise.id) { mutableStateOf(false) }
    var repText by remember(exercise.id) { mutableStateOf("") }
    var timeText by remember(exercise.id) { mutableStateOf("") }
    // FIX (audit): failure-attempt weight now has its OWN field — it used to overwrite the
    // main weight stepper (clearing the prescription mid-log).
    var attemptedText by remember(exercise.id) { mutableStateOf("") }
    var saveError by remember(exercise.id) { mutableStateOf<String?>(null) }

    fun parseReps(): List<Double> =
        repText.split(Regex("[,\\s]+")).mapNotNull { it.trim().toDoubleOrNull() }.filter { it >= 0 }

    fun parseTimes(): List<Double> =
        timeText.split(Regex("[,\\s]+")).mapNotNull { it.trim().toDoubleOrNull() }.filter { it >= 0 }

    Column {
        if (isTime) {
            Text("Duration per set (seconds, comma-separated)", style = ProType.small, color = c.text3)
            Spacer(Modifier.height(6.dp))
            ProTextField(value = timeText, onValueChange = { timeText = it }, placeholder = "e.g. 45, 40, 38")
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Sets completed", style = ProType.label, color = c.text, modifier = Modifier.weight(1f))
                StepButton("−") { if (sets > 1) sets-- }
                Text("$sets", style = ProType.cardTitle, color = c.text, modifier = Modifier.padding(horizontal = 14.dp))
                StepButton("+") { if (sets < 12) sets++ }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Weight (lbs)", style = ProType.label, color = c.text, modifier = Modifier.weight(1f))
                StepButton("−") { weight = OneRm.roundToNearest((weight - 2.5).coerceAtLeast(0.0)) }
                Text(
                    if (weight == 0.0) "BW" else "${OneRm.roundToNearest(weight).toInt()}",
                    style = ProType.cardTitle, color = if (weight == 0.0) c.text3 else c.text, modifier = Modifier.padding(horizontal = 14.dp),
                )
                StepButton("+") { weight = OneRm.roundToNearest(weight + 2.5) }
            }
            Spacer(Modifier.height(10.dp))
            Text("Reps per set (comma-separated)", style = ProType.small, color = c.text3)
            Spacer(Modifier.height(6.dp))
            ProTextField(value = repText, onValueChange = { repText = it }, placeholder = "e.g. 10, 9, 8")
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Sets completed", style = ProType.label, color = c.text, modifier = Modifier.weight(1f))
                StepButton("−") { if (sets > 1) sets-- }
                Text("$sets", style = ProType.cardTitle, color = c.text, modifier = Modifier.padding(horizontal = 14.dp))
                StepButton("+") { if (sets < 12) sets++ }
            }
            Spacer(Modifier.height(12.dp))
        }

        Text("RPE — how hard was it?", style = ProType.small, color = c.text3)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            for (i in 1..10) {
                val active = rpe == i.toDouble()
                val color = rpeColor(i.toDouble(), c)
                Box(
                    Modifier
                        .weight(1f)
                        .height(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (active) color else c.surface2)
                        .border(1.dp, if (active) color else c.hairline2, RoundedCornerShape(10.dp))
                        .clickable { rpe = i.toDouble() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("$i", color = if (active) Color.White else c.text2, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }
        Text(
            // FIX (audit): RIR range was inverted ("3–2" at RPE 7).
            "RIR: ${9 - rpe.toInt()}–${10 - rpe.toInt()} reps in reserve",
            style = ProType.small, color = c.text3, modifier = Modifier.padding(top = 4.dp),
        )

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProSwitch(checked = failure, onToggle = { failure = !failure })
            Spacer(Modifier.width(10.dp))
            Text("Could not do any rep (failure)", style = ProType.small, color = c.text3)
        }
        if (failure) {
            Spacer(Modifier.height(6.dp))
            Text("What weight did you attempt?", style = ProType.small, color = c.text3)
            ProTextField(value = attemptedText, onValueChange = { attemptedText = it }, placeholder = "lbs")
        }

        Spacer(Modifier.height(10.dp))
        Text("Notes", style = ProType.small, color = c.text3)
        Spacer(Modifier.height(6.dp))
        ProTextField(value = notes, onValueChange = { notes = it }, placeholder = "How did it feel?", singleLine = false)

        saveError?.let {
            Text(it, color = c.bad, style = ProType.small)
            Spacer(Modifier.height(6.dp))
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            P4Button(
                "Save",
                style = BtnStyle.PRIMARY,
                icon = "fa-check",
                modifier = Modifier.weight(1f),
                onClick = {
                    val parsedReps = parseReps()
                    val parsedTimes = parseTimes()
                    // FIX (audit): empty reps silently saved sets × 0.0 reps and poisoned the
                    // Kalman 1RM with a 0-rep observation; failure saved without a weight.
                    // The engine documents "caller validates input" — now the caller does.
                    val err = when {
                        isTime && parsedTimes.isEmpty() -> "Enter the duration for at least one set (e.g. 45, 40)."
                        !isTime && !failure && parsedReps.isEmpty() -> "Enter the reps you completed (e.g. 10, 9, 8)."
                        !isTime && failure && (attemptedText.toDoubleOrNull() ?: 0.0) <= 0.0 -> "Enter the weight you attempted."
                        else -> null
                    }
                    if (err != null) { saveError = err; return@P4Button }
                    saveError = null
                    val actual = if (isTime) {
                        ActualPerformance(
                            weight = 0.0,
                            sets = if (parsedTimes.isNotEmpty()) parsedTimes.size else sets,
                            notes = notes,
                            rpe = JsonPrimitive(rpe),
                            firstRPE = rpe,
                            durations = parsedTimes,
                            totalTime = parsedTimes.sum().takeIf { it > 0 },
                        )
                    } else if (failure) {
                        ActualPerformance(
                            weight = attemptedText.toDoubleOrNull() ?: 0.0, sets = 0, notes = notes.ifBlank { "Could not do any rep" },
                            rpe = JsonPrimitive(rpe), firstRPE = rpe, failure = true, attemptedWeight = attemptedText.toDoubleOrNull() ?: 0.0,
                        )
                    } else {
                        val used = if (parsedReps.size >= sets) parsedReps.take(sets) else parsedReps + List(sets - parsedReps.size) { parsedReps.lastOrNull() ?: 0.0 }
                        ActualPerformance(
                            weight = weight,
                            sets = sets,
                            notes = notes,
                            rpe = JsonArray(used.map { JsonPrimitive(rpe) }),
                            firstRPE = rpe,
                            reps = used,
                            volume = weight * used.sum(),
                        )
                    }
                    onSave(actual, notes)
                },
            )
        }
        Spacer(Modifier.height(2.dp))
        P4Button("Start rest timer", style = BtnStyle.GHOST, icon = "fa-stopwatch", modifier = Modifier.fillMaxWidth()) {
            onStartRest(ProState.data.user.settings.restTime)
        }
    }
}

private fun rpeColor(rpe: Double, c: com.peakform.fitness.ui.ProColors): Color = when {
    rpe <= 4 -> c.ok
    rpe <= 6 -> Color(0xFFA3E635)
    rpe <= 7 -> c.warn
    rpe <= 9 -> Color(0xFFFB923C)
    else -> c.bad
}

// ------------------------------------------------------------------
// Rest timer — app-scope singleton so the countdown survives tab switches
// (FIX (audit): screen-local state was disposed by Crossfade and the timer
// silently vanished on navigation).
// ------------------------------------------------------------------
object RestTimer {
    var secondsLeft by mutableIntStateOf(0)
    var total by mutableIntStateOf(0)
    val running: Boolean get() = secondsLeft > 0
    private var job: kotlinx.coroutines.Job? = null

    fun start(sec: Int) {
        total = sec
        secondsLeft = sec
        job?.cancel()
        job = ProState.scope.launch {
            while (secondsLeft > 0) {
                kotlinx.coroutines.delay(1000)
                if (secondsLeft > 0) secondsLeft--
            }
        }
    }

    fun add15() { secondsLeft += 15 }

    fun stop() {
        job?.cancel()
        job = null
        secondsLeft = 0
    }
}

// ------------------------------------------------------------------
// 1RM test panel — "Find Your 1-Rep Max" (scr-workout.md §7)
// ------------------------------------------------------------------
@Composable
fun OneRmTestPanel(exerciseId: String, onDone: () -> Unit, onSkip: () -> Unit, onStartRest: (Int) -> Unit) {
    val c = LocalProColors.current
    val exName = exerciseId.replace('_', ' ')
    var successWeight by remember { mutableStateOf("") }
    var successReps by remember { mutableStateOf("") }
    var attempted by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    GlassCard(padding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            com.peakform.fitness.ui.FaIcon("fa-bolt", size = 14.sp, tint = c.warn)
            Spacer(Modifier.width(8.dp))
            Text("Find Your 1-Rep Max", style = ProType.label, color = c.warn)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Warm up, then lift a weight you can move for 1–5 clean reps. Enter the weight and reps — the app estimates your true max and calibrates all future prescriptions.",
            style = ProType.small, color = c.text3,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Weight used (lbs)", style = ProType.small, color = c.text3)
                Spacer(Modifier.height(4.dp))
                ProTextField(value = successWeight, onValueChange = { successWeight = it }, placeholder = "135")
            }
            Column(Modifier.weight(1f)) {
                Text("Reps completed (1-5)", style = ProType.small, color = c.text3)
                Spacer(Modifier.height(4.dp))
                ProTextField(value = successReps, onValueChange = { successReps = it }, placeholder = "3")
            }
        }
        if (error != null) {
            Spacer(Modifier.height(6.dp))
            Text(error!!, color = c.bad, style = ProType.small)
        }
        Spacer(Modifier.height(12.dp))
        P4Button("Save 1RM Test", style = BtnStyle.PRIMARY, modifier = Modifier.fillMaxWidth()) {
            val w = successWeight.toDoubleOrNull()
            val r = successReps.toDoubleOrNull()?.toInt()
            when {
                w == null || w <= 0 -> error = "Enter the weight you lifted."
                r == null || r < 1 || r > 15 -> error = "Reps must be between 1 and 15 for accurate 1RM estimation."
                else -> {
                    OneRm.processTestResult(exerciseId, w, r)
                    onStartRest(ProState.data.user.settings.restTime)
                    onDone()
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Couldn't do any rep?", style = ProType.small, color = c.text3)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            P4Button("Try very light weight", style = BtnStyle.GHOST, modifier = Modifier.weight(1f)) {
                val w = attempted.toDoubleOrNull()
                if (w != null && w > 0) {
                    OneRm.processTestResult(exerciseId, w, 1)
                    onDone()
                } else error = "Enter the weight you attempted."
            }
            P4Button("Skip this exercise for today", style = BtnStyle.GHOST, modifier = Modifier.weight(1f)) {
                // FIX (audit): this used to call onDone() — only the rest timer fired and the
                // exercise re-rendered its own Needs-1RM panel forever. Actually skip it.
                onSkip()
            }
        }
        Spacer(Modifier.height(6.dp))
        ProTextField(value = attempted, onValueChange = { attempted = it }, placeholder = "Attempted weight (lbs)")
        Spacer(Modifier.height(2.dp))
        Text("Test: $exName", style = ProType.small, color = c.text3)
    }
}

// ------------------------------------------------------------------
// Rest timer overlay (P4.Power #p4RestTimer)
// ------------------------------------------------------------------
@Composable
fun RestTimerOverlay(secondsLeft: Int, total: Int, onAdd15: () -> Unit, onDone: () -> Unit) {
    val c = LocalProColors.current
    Box(
        Modifier
            .fillMaxSize()
            .padding(bottom = 110.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Row(
            Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(c.glass2)
                .border(1.dp, c.accentLine, RoundedCornerShape(20.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "%d:%02d".format(secondsLeft / 60, secondsLeft % 60),
                    style = ProType.monoTimer, fontSize = 26.sp, color = c.accent,
                )
                Text("REST", fontSize = 10.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, letterSpacing = 1.2.sp, color = c.text3)
            }
            P4Button("+15s", onClick = onAdd15, style = BtnStyle.SECONDARY, minHeight = 36)
            P4Button("Done", onClick = onDone, style = BtnStyle.PRIMARY, minHeight = 36)
        }
    }
}

// ------------------------------------------------------------------
// Inputs + dialogs
// ------------------------------------------------------------------
@Composable
fun ProTextField(value: String, onValueChange: (String) -> Unit, placeholder: String = "", singleLine: Boolean = true) {
    val c = LocalProColors.current
    androidx.compose.material3.OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = singleLine,
        placeholder = { Text(placeholder, color = c.text3, style = ProType.body2) },
        textStyle = ProType.body.copy(color = c.text),
        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
            focusedContainerColor = c.surface,
            unfocusedContainerColor = c.surface2,
            focusedBorderColor = c.accent,
            unfocusedBorderColor = c.hairline2,
            focusedTextColor = c.text,
            unfocusedTextColor = c.text,
            cursorColor = c.accent,
        ),
        shape = RoundedCornerShape(10.dp),
    )
}

@Composable
fun ProSwitch(checked: Boolean, onToggle: () -> Unit) {
    val c = LocalProColors.current
    Switch(c = c, checked = checked, onToggle = onToggle)
}

/** SweetAlert2-skinned dialog: glass card, 28px radius. */
@Composable
fun SwalDialog(
    title: String,
    text: String,
    confirmText: String,
    cancelText: String? = null,
    onConfirm: () -> Unit,
    onDismiss: (() -> Unit)? = null,
) {
    val c = LocalProColors.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { onDismiss?.invoke() },
        containerColor = c.glass2,
        shape = RoundedCornerShape(28.dp),
        title = { Text(title, style = ProType.cardTitle, color = c.text) },
        text = { Text(text, style = ProType.body2, color = c.text2) },
        confirmButton = {
            P4Button(confirmText, onClick = onConfirm, style = BtnStyle.PRIMARY, minHeight = 40)
        },
        dismissButton = {
            if (cancelText != null) P4Button(cancelText, onClick = { onDismiss?.invoke() }, style = BtnStyle.SECONDARY, minHeight = 40)
        },
    )
}

/** Post-workout celebration dialog with BIGGER/STRONGER steering taps. */
@Composable
fun CelebrationDialog(result: Sessions.CompletionResult, onDismiss: () -> Unit) {
    val c = LocalProColors.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.glass2,
        shape = RoundedCornerShape(28.dp),
        title = { Text(result.celebrationTitle, style = ProType.cardTitle, fontSize = 22.sp, color = c.text) },
        text = {
            Column {
                result.celebrationLines.forEach { Text(it, style = ProType.body2, color = c.text2) }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                P4Button("💪 BIGGER", style = BtnStyle.SECONDARY, minHeight = 40) {
                    Sessions.applyMomentumTap("bigger")
                    onDismiss()
                }
                P4Button("🔥 STRONGER", style = BtnStyle.PRIMARY, minHeight = 40) {
                    Sessions.applyMomentumTap("stronger")
                    onDismiss()
                }
            }
        },
        dismissButton = {
            P4Button("Later", style = BtnStyle.GHOST, minHeight = 40, onClick = onDismiss)
        },
    )
}
