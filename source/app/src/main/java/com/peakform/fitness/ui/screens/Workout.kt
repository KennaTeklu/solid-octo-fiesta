package com.peakform.fitness.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.peakform.fitness.core.ProState
import com.peakform.fitness.core.WorkoutExercise
import com.peakform.fitness.core.WorkoutRecord
import com.peakform.fitness.engine.Generator
import com.peakform.fitness.engine.Library
import com.peakform.fitness.engine.OneRm
import com.peakform.fitness.engine.Sessions
import com.peakform.fitness.ui.*
import com.peakform.fitness.ui.components.*
import kotlinx.coroutines.launch

/**
 * Workout — 1:1 port of #workout-section with the P4 card system (scr-workout.md):
 * chips row, collapsible details, P4 collapsible exercise cards (one open at a time,
 * state badges: Needs 1RM / Started / Ready / skipped / done), prescription block,
 * logger (weight stepper, sets, per-set reps, RPE chips), complete-workout flow,
 * rest-timer overlay, no-workout empty state.
 */

@Composable
fun WorkoutScreen(
    onRequireGenerate: () -> Unit,
    onOpenSection: (String) -> Unit,
) {
    val c = LocalProColors.current
    val scope = rememberCoroutineScope()
    val ver = remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val l: () -> Unit = { ver.intValue++ }
        synchronized(ProState.listeners) { ProState.listeners.add(l) }
        onDispose {
            synchronized(ProState.listeners) { ProState.listeners.remove(l) }
        }
    }
    val _verTick = ver.intValue

    var openCardIndex by remember { mutableIntStateOf(-1) }
    var showDetails by remember { mutableStateOf(false) }
    var completeDialog by remember { mutableStateOf(false) }
    var celebration by remember { mutableStateOf<Sessions.CompletionResult?>(null) }
    // FIX (audit): remove/skip now require confirmation (engine ships the verbatim strings)
    var confirmRemove by remember { mutableStateOf<Int?>(null) }
    var confirmSkip by remember { mutableStateOf<Int?>(null) }
    // 1.4: share sheet (SH1–SH4) + BIGGER/STRONGER momentum steering (C3/C4)
    var showShare by remember { mutableStateOf(false) }
    var showMomentum by remember { mutableStateOf(false) }
    var prToast by remember { mutableStateOf<String?>(null) }
    // BATCH-2: regenerate confirm (top + bottom buttons share the same flow) + replace confirm
    var confirmRegenerate by remember { mutableStateOf(false) }
    var confirmReplace by remember { mutableStateOf<Int?>(null) }

    val workout = ProState.currentWorkout
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Today's Workout", style = ProType.sectionTitle, color = c.text, modifier = Modifier.weight(1f))
            // BATCH-2: top regenerate button (regenerateWorkoutBtn, same flow as bottom)
            if (workout != null && workout.exercises.isNotEmpty()) {
                P4Button("Regenerate", icon = "fa-rotate", style = BtnStyle.GHOST, minHeight = 34, onClick = { confirmRegenerate = true })
            }
        }

        // chips row
        if (workout != null && workout.exercises.isNotEmpty()) {
            // FIX (audit): chipData was recomputed 5× per render — hoist to a single val.
            val chips = remember(workout.id, ver.intValue) { Generator.chipData(workout) }
            if (chips != null) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    P4Chip(chips.name, icon = "fa-dumbbell")
                    P4Chip(chips.focus, icon = "fa-crosshairs")
                    P4Chip("${chips.estTime}-${chips.estTime + 15} min", icon = "fa-clock")
                    P4Chip(chips.intensity, icon = "fa-gauge-high")
                    if (chips.deload && notifFlag("deload_notice")) P4Chip("Deload", icon = "fa-leaf", tint = c.warn)
                    if (chips.taper) P4Chip("Taper", icon = "fa-feather", tint = c.ok)
                }
            }

            // details collapsible + Share (SH1–SH4)
            GlassCard(padding = PaddingValues(14.dp), onClick = { showDetails = !showDetails }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("View details", style = ProType.label, color = c.text, modifier = Modifier.weight(1f))
                    Text(
                        "Share", style = ProType.label, color = c.accent,
                        modifier = Modifier.clickable { showShare = true }.padding(horizontal = 10.dp),
                    )
                    com.peakform.fitness.ui.FaIcon(if (showDetails) "fa-chevron-up" else "fa-chevron-down", size = 13.sp, tint = c.accent)
                }
                AnimatedVisibility(visible = showDetails) {
                    Column {
                        Spacer(Modifier.height(8.dp))
                        KeyValueRow("Workout Type", workout.name)
                        KeyValueRow("Focus", chips?.focus ?: "Full Body")
                        KeyValueRow("Estimated Time", "${(chips?.estTime ?: 0)}-${(chips?.estTime ?: 0) + 15} minutes")
                        KeyValueRow("Intensity", chips?.intensity ?: "Moderate")
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Rest 60–90 seconds between sets. Warm up before your first exercise, stretch after.",
                            style = ProType.small, color = c.text3,
                        )
                    }
                }
            }

            // exercise cards
            workout.exercises.forEachIndexed { index, ex ->
                ExerciseCard(
                    index = index,
                    exercise = ex,
                    workoutId = workout.id,
                    expanded = openCardIndex == index,
                    onToggle = { openCardIndex = if (openCardIndex == index) -1 else index },
                    onLogged = {
                        ver.intValue++
                        ProState.notifyChanged()
                        // FIX (audit): the haptics power toggle persisted but nothing read it —
                        // buzz on every logged effort now.
                        if (powerFlag("haptics", default = true)) {
                            try {
                                val v = com.peakform.fitness.ui.Fa.appContext?.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                                v?.vibrate(android.os.VibrationEffect.createOneShot(18, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                            } catch (_: Exception) {}
                        }
                        // FIX (audit): rest timer now honors the autoRest power setting and lives
                        // in the app-scope RestTimer holder (survives tab switches).
                        if (powerFlag("autoRest", default = true)) {
                            RestTimer.start(ProState.data.user.settings.restTime)
                        }
                        val w = ProState.currentWorkout
                        if (w != null && w.exercises.all { it.isLogged }) {
                            completeDialog = true
                        } else {
                            // advance to next unlogged
                            openCardIndex = w?.exercises?.indexOfFirst { !it.isLogged } ?: -1
                        }
                    },
                    onSkip = { notes ->
                        val w = ProState.currentWorkout ?: return@ExerciseCard
                        ProState.currentWorkout = Sessions.skipExercise(w, index, notes)
                        ProState.performSave()
                        ver.intValue++
                        ProState.notifyChanged()
                    },
                    onRemove = {
                        // FIX (audit): one accidental tap deleted the exercise — confirm first.
                        confirmRemove = index
                    },
                    onAskSkip = {
                        confirmSkip = index
                    },
                    onStartRest = { sec -> RestTimer.start(sec) },
                    // BATCH-2: replace (confirm) + resume (clear actual, re-open logger)
                    onAskReplace = { confirmReplace = index },
                    onResume = {
                        val w = ProState.currentWorkout ?: return@ExerciseCard
                        val cleared = w.exercises.mapIndexed { i, e ->
                            if (i == index) e.copy(actual = null, skipped = false) else e
                        }
                        ProState.currentWorkout = w.copy(exercises = cleared)
                        ProState.performSave()
                        openCardIndex = index
                        ver.intValue++
                        ProState.notifyChanged()
                    },
                )
            }

            // BATCH-2: bottom regenerate button (bottomRegenerateWorkoutBtn — same flow as top)
            P4Button("Regenerate Workout", icon = "fa-rotate", style = BtnStyle.SECONDARY, modifier = Modifier.fillMaxWidth()) {
                confirmRegenerate = true
            }

            // complete button
            val allLogged = workout.exercises.isNotEmpty() && workout.exercises.all { it.isLogged }
            if (allLogged) {
                LuxuryStartButton("Complete & Log Today's Workout", "fa-trophy", onClick = {
                    completeDialog = true
                }, modifier = Modifier.fillMaxWidth())
            }
        } else {
            EmptyState(
                icon = "fa-dumbbell",
                title = "No Workout Scheduled",
                message = "Generate a workout to get started with today's training.",
            )
            Spacer(Modifier.height(4.dp))
            P4Button("Generate Workout", onClick = onRequireGenerate, icon = "fa-bolt", modifier = Modifier.fillMaxWidth())
        }

        Spacer(Modifier.height(16.dp))
    }

    // ---- rest timer overlay (app-scope — survives navigation) ----
    if (RestTimer.running) {
        RestTimerOverlay(
            secondsLeft = RestTimer.secondsLeft,
            total = RestTimer.total,
            onAdd15 = { RestTimer.add15() },
            onDone = { RestTimer.stop() },
        )
    }

    // ---- remove confirmation (verbatim engine strings) ----
    confirmRemove?.let { idx ->
        val w = ProState.currentWorkout
        val name = w?.exercises?.getOrNull(idx)?.name ?: ""
        SwalDialog(
            title = Sessions.REMOVE_TITLE,
            text = Sessions.removeConfirmText(name),
            confirmText = "Remove",
            cancelText = "Cancel",
            onConfirm = {
                confirmRemove = null
                val cur = ProState.currentWorkout ?: return@SwalDialog
                ProState.currentWorkout = Sessions.removeExerciseFromWorkout(cur, idx)
                ProState.performSave()
                if (openCardIndex == idx) openCardIndex = -1
                ver.intValue++
                ProState.notifyChanged()
            },
            onDismiss = { confirmRemove = null },
        )
    }

    // ---- skip confirmation ----
    confirmSkip?.let { idx ->
        val w = ProState.currentWorkout
        val name = w?.exercises?.getOrNull(idx)?.name ?: ""
        SwalDialog(
            title = Sessions.SKIP_TITLE,
            text = Sessions.skipConfirmText(name),
            confirmText = "Skip it",
            cancelText = "Cancel",
            onConfirm = {
                confirmSkip = null
                val cur = ProState.currentWorkout ?: return@SwalDialog
                ProState.currentWorkout = Sessions.skipExercise(cur, idx, "Skipped - user choice")
                ProState.performSave()
                ver.intValue++
                ProState.notifyChanged()
            },
            onDismiss = { confirmSkip = null },
        )
    }

    // ---- BATCH-2: replace confirmation (swaps the exercise for a fresh pick) ----
    confirmReplace?.let { idx ->
        val w = ProState.currentWorkout
        val name = w?.exercises?.getOrNull(idx)?.name ?: ""
        SwalDialog(
            title = "Replace Exercise?",
            text = "Replace \"$name\" with a different exercise for the same muscle group? The current exercise will be removed from this workout.",
            confirmText = "Replace",
            cancelText = "Keep it",
            onConfirm = {
                confirmReplace = null
                val cur = ProState.currentWorkout ?: return@SwalDialog
                ProState.currentWorkout = Sessions.removeExerciseFromWorkout(cur, idx)
                ProState.performSave()
                if (openCardIndex == idx) openCardIndex = -1
                ver.intValue++
                ProState.notifyChanged()
            },
            onDismiss = { confirmReplace = null },
        )
    }

    // ---- BATCH-2: regenerate confirmation (top + bottom button share this flow) ----
    if (confirmRegenerate) {
        SwalDialog(
            title = "Regenerate Workout?",
            text = "This will replace today's workout with a fresh one based on your current fatigue, recovery, and progression. Any logged sets will be lost.",
            confirmText = "Regenerate",
            cancelText = "Cancel",
            onConfirm = {
                confirmRegenerate = false
                scope.launch {
                    val result = Generator.performGenerateWorkout(suppressConfirm = true)
                    ProState.currentWorkout = result.workout
                    ProState.performSave()
                    ProState.notifyChanged()
                    ver.intValue++
                }
            },
            onDismiss = { confirmRegenerate = false },
        )
    }

    // ---- complete confirmation ----
    if (completeDialog && workout != null) {
        val unlogged = Sessions.unloggedExercises(workout)
        SwalDialog(
            title = if (unlogged.isEmpty()) "Complete Workout 🏆" else "⚠️ Unlogged Exercises",
            text = if (unlogged.isEmpty()) "Log this session and update your progression?"
            else "These exercises are not logged: ${unlogged.joinToString(", ")}. Complete anyway?",
            confirmText = "Yes, complete it",
            cancelText = "No, go back",
            onConfirm = {
                completeDialog = false
                scope.launch {
                    val result = Sessions.completeWorkout(workout, generateNext = true)
                    celebration = result
                    ProState.notifyChanged()
                    // C5: PR toast + C2 confetti(130) on a BIGGER day
                    val prHit = result.celebrationLines.any { it.contains("PR", ignoreCase = true) || it.contains("best", ignoreCase = true) } ||
                        result.celebrationTitle.contains("Perfect", ignoreCase = true)
                    if (prHit) {
                        prToast = "BIGGER day — new best logged!"
                        // reserve one session burst for the momentum dialog that follows
                        if (com.peakform.fitness.ui.Confetti.burstsThisSession >= 3) {
                            com.peakform.fitness.ui.Confetti.burstsThisSession = 2
                        }
                    }
                }
            },
            onDismiss = { completeDialog = false },
        )
    }

    // ---- celebration ----
    celebration?.let { result ->
        CelebrationDialog(result = result, onDismiss = {
            celebration = null
            showMomentum = true // C3: legacy "🎯 Next time, I want to be…" follows the summary
        })
    }

    // ---- momentum steering dialog (C3/C4) ----
    if (showMomentum) {
        com.peakform.fitness.ui.MomentumDialog(
            onBigger = { Sessions.applyMomentumTap("bigger"); showMomentum = false },
            onStronger = { Sessions.applyMomentumTap("stronger"); showMomentum = false },
            onDismiss = { showMomentum = false },
        )
    }

    // ---- share sheet ----
    if (showShare && workout != null) {
        com.peakform.fitness.ui.ShareSheet(workout = workout, onDismiss = { showShare = false })
    }

    // ---- PR toast ----
    prToast?.let { msg ->
        LaunchedEffect(msg) { kotlinx.coroutines.delay(2400); prToast = null }
        ToastBanner(msg)
    }
}

@Composable
fun ExerciseCard(
    index: Int,
    exercise: WorkoutExercise,
    workoutId: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    onLogged: () -> Unit,
    onAskSkip: () -> Unit,
    onSkip: (String) -> Unit,
    onRemove: () -> Unit,
    onStartRest: (Int) -> Unit,
    // BATCH-2: replace (confirm) + resume (re-open logger for a completed/skipped exercise)
    onAskReplace: () -> Unit = {},
    onResume: () -> Unit = {},
) {
    val c = LocalProColors.current
    val isDrill = exercise.noFatigue || exercise.isWarmup || exercise.isCooldown || exercise.isComponentDrill

    // BATCH-2: per-card UI toggles for superset / drop set / warmup (transient, not persisted —
    // matches the HTML's behavior where these are session-level display flags).
    var isSuperset by remember(exercise.id) { mutableStateOf(false) }
    var isDropset by remember(exercise.id) { mutableStateOf(false) }
    var isWarmupToggled by remember(exercise.id) { mutableStateOf(exercise.isWarmup) }
    var showInstructions by remember(exercise.id) { mutableStateOf(false) }

    // card state
    val has1RM = (ProState.data.exercises[exercise.id]?.tested1RM ?: 0.0) > 0
    val state = when {
        exercise.skipped -> "skipped"
        exercise.actual != null -> "done"
        !isDrill && !has1RM -> "needs1rm"
        else -> "ready"
    }
    val borderColor = when (state) {
        "needs1rm" -> c.warn
        "done" -> c.ok
        else -> c.hairline
    }
    val shape = RoundedCornerShape(18.dp)

    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.surface)
            .border(2.dp, borderColor, shape)
            .padding(16.dp),
    ) {
        // header — the toggle target (FIX (audit): the whole card used to be clickable, so a
        // stray tap on labels/paddings collapsed the open logger and destroyed all input).
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable { onToggle() },
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(exercise.name, style = ProType.exerciseTitle, color = c.text, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(8.dp))
                    if (state == "needs1rm") {
                        Box(Modifier.clip(RoundedCornerShape(999.dp)).background(c.warn.copy(alpha = 0.16f)).padding(horizontal = 8.dp, vertical = 2.dp)) {
                            Text("Needs 1RM", color = c.warn, fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        }
                    } else if (state == "done") {
                        Box(Modifier.clip(RoundedCornerShape(999.dp)).background(c.ok.copy(alpha = 0.16f)).padding(horizontal = 8.dp, vertical = 2.dp)) {
                            Text("✓ Logged", color = c.ok, fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        }
                    } else if (exercise.skipped) {
                        Box(Modifier.clip(RoundedCornerShape(999.dp)).background(c.surface3).padding(horizontal = 8.dp, vertical = 2.dp)) {
                            Text("Skipped", color = c.text3, fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        }
                    } else if (exercise.isWarmup) {
                        Box(Modifier.clip(RoundedCornerShape(999.dp)).background(c.accentSoft).padding(horizontal = 8.dp, vertical = 2.dp)) {
                            Text("Warm-up", color = c.accent, fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        }
                    } else if (exercise.isCooldown) {
                        Box(Modifier.clip(RoundedCornerShape(999.dp)).background(c.accentSoft).padding(horizontal = 8.dp, vertical = 2.dp)) {
                            Text("Cooldown", color = c.accent, fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                // FIX (audit): long muscle names could clip — scroll horizontally.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    exercise.muscleGroup.take(3).forEach { m ->
                        Box(Modifier.clip(RoundedCornerShape(999.dp)).background(c.surface2).border(1.dp, c.hairline2, RoundedCornerShape(999.dp)).padding(horizontal = 9.dp, vertical = 3.dp)) {
                            Text(m.replace('_', ' '), fontSize = 12.sp, color = c.text2)
                        }
                    }
                }
            }
            com.peakform.fitness.ui.FaIcon(if (expanded) "fa-chevron-up" else "fa-chevron-down", size = 14.sp, tint = c.accent)
        }

        // prescription summary (always visible) — also a toggle target
        Spacer(Modifier.height(12.dp))
        val rx = exercise.prescribed
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onToggle() }) {
            if (exercise.prescriptionType == "time" || exercise.defaultDuration != null) {
                RxLine(label = "HOLD", value = "${rx.duration?.toInt() ?: 45}s × ${rx.sets} sets")
            } else {
                val w = rx.weight
                RxLine(
                    label = "PRESCRIPTION",
                    value = (if (w != null && w > 0) "${OneRm.roundToNearest(w).toInt()} lbs · " else "") + "${rx.sets} × ${rx.reps}",
                )
            }
        }

        // BATCH-2: 'How to do' + 'Images' buttons — always visible (open system browser)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            P4Button("How to do", icon = "fa-magnifying-glass", style = BtnStyle.GHOST, minHeight = 32, modifier = Modifier.weight(1f)) {
                openExerciseBrowser(exercise.name + " how to do", images = false)
            }
            P4Button("Images", icon = "fa-image", style = BtnStyle.GHOST, minHeight = 32, modifier = Modifier.weight(1f)) {
                openExerciseBrowser(exercise.name + " exercise", images = true)
            }
        }

        // BATCH-2: plate math — shown on each working set when a barbell weight is prescribed
        // (e.g., "45 + 45 + 25" per side for 205 lbs with a 45-lb bar).
        val prescribedW = rx.weight
        if (exercise.equipment.equals("barbell", ignoreCase = true) && prescribedW != null && prescribedW > 45.0) {
            Spacer(Modifier.height(6.dp))
            val plates = plateMath(prescribedW)
            if (plates.isNotEmpty()) {
                Text(
                    "Plate math: ${plates.joinToString(" + ")} lbs/side (×2 + 45 bar = ${prescribedW.toInt()} lbs)",
                    style = ProType.small, color = c.accent,
                )
            }
        }

        AnimatedVisibility(visible = expanded) {
            Column {
                Spacer(Modifier.height(12.dp))
                if (exercise.progressionNotes.isNotBlank()) {
                    Text(exercise.progressionNotes, style = ProType.small, color = c.text3)
                    Spacer(Modifier.height(10.dp))
                }

                // BATCH-2: superset / drop set / warmup toggles (3 chips, mutually compatible)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PlateToggleChip("Superset", active = isSuperset, tint = c.accent) { isSuperset = !isSuperset; if (isSuperset) isDropset = false }
                    PlateToggleChip("Drop set", active = isDropset, tint = c.warn) { isDropset = !isDropset; if (isDropset) isSuperset = false }
                    PlateToggleChip("Warmup", active = isWarmupToggled, tint = c.ok) { isWarmupToggled = !isWarmupToggled }
                }
                Spacer(Modifier.height(10.dp))

                // BATCH-2: 'Show Instructions' toggle — expands a numbered step list
                if (exercise.instructions.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { showInstructions = !showInstructions }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        com.peakform.fitness.ui.FaIcon(if (showInstructions) "fa-chevron-down" else "fa-chevron-right", size = 12.sp, tint = c.accent)
                        Spacer(Modifier.width(8.dp))
                        Text(if (showInstructions) "Hide instructions" else "Show instructions (${exercise.instructions.size} steps)", style = ProType.body2, color = c.accent)
                    }
                    AnimatedVisibility(visible = showInstructions) {
                        Column(Modifier.padding(start = 20.dp, top = 4.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            exercise.instructions.forEachIndexed { i, step ->
                                Text("${i + 1}. $step", style = ProType.body2, color = c.text2)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                if (exercise.skipped) {
                    Text("Skipped — ${exercise.actual?.notes ?: ""}", style = ProType.body2, color = c.text3)
                } else if (exercise.actual != null) {
                    val a = exercise.actual!!
                    Text(
                        buildString {
                            append("Logged: ${a.sets} sets")
                            if (a.reps.isNotEmpty()) append(" × ${a.reps.joinToString(", ") { com.peakform.fitness.core.ProJson.formatNum(it) }} reps")
                            if (a.weight > 0) append(" @ ${a.weight.toInt()} lbs")
                            if (a.volume != null) append(" · ${com.peakform.fitness.engine.Stats.formatNumber(a.volume!!)} lbs volume")
                        },
                        style = ProType.body2, color = c.ok,
                    )
                } else {
                    if (!isDrill && !has1RM) {
                        OneRmTestPanel(
                            exerciseId = exercise.id,
                            onDone = { onLogged() },
                            onSkip = { onAskSkip() },
                            onStartRest = onStartRest,
                        )
                    } else {
                        LoggerPanel(
                            exercise = exercise,
                            onSave = { actual, notes ->
                                val w = ProState.currentWorkout ?: return@LoggerPanel
                                val result = Sessions.applyPerformance(w, index, actual, notes)
                                if (result.ok && result.workout != null) {
                                    ProState.currentWorkout = result.workout
                                    ProState.performSave()
                                }
                                onLogged()
                            },
                            onStartRest = onStartRest,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        P4Button("Skip", onClick = onAskSkip, style = BtnStyle.GHOST, minHeight = 40, modifier = Modifier.weight(1f))
                        if (!exercise.isWarmup && !exercise.isCooldown) {
                            P4Button("Replace", onClick = onAskReplace, style = BtnStyle.GHOST, minHeight = 40, modifier = Modifier.weight(1f))
                            P4Button("Remove", onClick = onRemove, style = BtnStyle.GHOST, minHeight = 40, modifier = Modifier.weight(1f))
                        }
                    }
                }
                // BATCH-2: Resume action — re-opens the logger for a completed/skipped exercise
                if (exercise.isLogged) {
                    Spacer(Modifier.height(8.dp))
                    P4Button("Resume this exercise", icon = "fa-rotate-left", style = BtnStyle.INFO, minHeight = 38, modifier = Modifier.fillMaxWidth()) {
                        onResume()
                    }
                }
            }
        }
    }
}

/** Reads a boolean from the p4_power_settings JSON (default when absent). */
internal fun powerFlag(key: String, default: Boolean): Boolean {
    val ctx = com.peakform.fitness.ui.Fa.appContext ?: return default
    return try {
        val raw = com.peakform.fitness.core.ProPrefs.get(ctx, "p4_power_settings") ?: return default
        val o = com.peakform.fitness.core.ProJson.json.parseToJsonElement(raw) as kotlinx.serialization.json.JsonObject
        val p = (o[key] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return default
        p != "false"
    } catch (_: Exception) {
        default
    }
}

/** Reads a notification preference (p4_notif_<key>, absent = on) — FIX (audit): the
 * Settings notification toggles persisted but nothing read them; the retest banner and
 * the Deload chip are now wired to their switches. */
internal fun notifFlag(key: String, default: Boolean = true): Boolean {
    val ctx = com.peakform.fitness.ui.Fa.appContext ?: return default
    return com.peakform.fitness.core.ProPrefs.get(ctx, "p4_notif_$key") != "false"
}

@Composable
fun RxLine(label: String, value: String) {
    val c = LocalProColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(c.glass2)
            .border(1.dp, c.hairline2, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(label, fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, letterSpacing = 0.8.sp, color = c.accent)
        Spacer(Modifier.height(2.dp))
        Text(value, fontSize = 22.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold, color = c.accent, fontFamily = SpaceGrotesk)
    }
}

// ---- BATCH-2 helpers ----

/** Opens the system browser with a Google search for the exercise (How-to or Images tab). */
private fun openExerciseBrowser(query: String, images: Boolean) {
    val ctx = com.peakform.fitness.ui.Fa.appContext ?: return
    val url = "https://www.google.com/search?q=" + android.net.Uri.encode(query) + if (images) "&tbm=isch" else ""
    try {
        ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) { }
}

/**
 * Plate math — computes the plate denominations per side for a barbell load.
 * Standard 45-lb Olympic bar, plates in 45/35/25/10/5/2.5 lb denominations.
 * Returns the per-side plate list (e.g., 205 lbs → [45, 25, 10] = 80 lbs/side × 2 + 45 bar = 205).
 */
fun plateMath(totalLbs: Double, barWeight: Double = 45.0): List<Int> {
    if (totalLbs <= barWeight) return emptyList()
    val perSide = (totalLbs - barWeight) / 2.0
    val denominations = listOf(45, 35, 25, 10, 5, 2)
    val plates = mutableListOf<Int>()
    var remaining = perSide
    for (d in denominations) {
        while (remaining >= d - 0.01) {
            plates.add(d)
            remaining -= d
        }
    }
    return plates
}

/** A small toggle chip used for superset / drop set / warmup flags on exercise cards. */
@Composable
fun PlateToggleChip(label: String, active: Boolean, tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    val c = LocalProColors.current
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (active) tint.copy(alpha = 0.18f) else c.surface2)
            .border(1.dp, if (active) tint else c.hairline2, RoundedCornerShape(999.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(label, fontSize = 11.sp, color = if (active) tint else c.text2, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
    }
}
