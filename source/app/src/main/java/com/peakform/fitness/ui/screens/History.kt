package com.peakform.fitness.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.peakform.fitness.core.ProState
import com.peakform.fitness.core.ProJson
import com.peakform.fitness.core.WorkoutRecord
import com.peakform.fitness.core.WorkoutExercise
import com.peakform.fitness.core.ActualPerformance
import com.peakform.fitness.core.HistoryEntry
import com.peakform.fitness.engine.Stats
import com.peakform.fitness.ui.*
import com.peakform.fitness.ui.components.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * History — 1:1 port of #history-section (cautious-enigma index (24).html L6424–L6459),
 * extended with the Batch-3 spec:
 *   • Chronological session list — newest first, grouped by month
 *   • Full-text search across session notes and exercise names
 *   • Per-session notes — inline display + edit
 *   • PR markers — badge on sessions where a personal record was set
 *   • Fix/edit past log entries — tap a past session, edit sets/reps/weight, save
 *   • Exercise history detail — tap an exercise in a past session, see its full history
 *
 * Performance: LazyColumn with stable keys + contentType on every item. For very
 * large histories (hundreds of sessions), the month grouping + search filter are
 * memoized on `(ver, filter, searchDebounced)` only — not recomputed on scroll.
 * LazyColumn virtualizes the window natively, so only visible rows compose.
 */

enum class HistFilter(val label: String) {
    ALL("All"), WEEK("This Week"), MONTH("This Month"), YEAR("This Year"), LONGEVITY("Longevity"),
}

val LONGEVITY_MUSCLES = setOf(
    "neck", "deep_neck", "rhomboids", "rear_delts",
    "feet_ankles", "hand_intrinsics", "foot_intrinsics", "forearms",
)

private val df = SimpleDateFormat("MMM d, yyyy", Locale.US)
private val monthFmt = SimpleDateFormat("MMMM yyyy", Locale.US)

@Composable
fun HistoryScreen(onOpenSection: (String) -> Unit) {
    val c = LocalProColors.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val ver = remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val l: () -> Unit = { ver.intValue++ }
        synchronized(ProState.listeners) { ProState.listeners.add(l) }
        onDispose {
            synchronized(ProState.listeners) { ProState.listeners.remove(l) }
        }
    }
    @Suppress("unused") val _verTick = ver.intValue

    var filter by remember { mutableStateOf(HistFilter.ALL) }
    var expandedId by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<WorkoutRecord?>(null) }
    var noteTarget by remember { mutableStateOf<Pair<WorkoutRecord, Int>?>(null) }
    var fixLogTarget by remember { mutableStateOf<Pair<WorkoutRecord, Int>?>(null) }
    var exerciseHistoryTarget by remember { mutableStateOf<WorkoutExercise?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }

    // ---- search box ----
    var searchInput by remember { mutableStateOf("") }
    // debounce the search: only re-filter 280ms after the user stops typing
    var searchDebounced by remember { mutableStateOf("") }
    LaunchedEffect(searchInput) {
        delay(280)
        searchDebounced = searchInput.trim()
    }

    // ---- heavy state memoized on (ver, filter, searchDebounced) — NOT on scroll ----
    val prSessions = remember(ver.intValue) { computePrSessions() }
    val grouped = remember(ver.intValue, filter, searchDebounced) {
        buildGroupedSessions(filter, searchDebounced)
    }
    val totalCount = remember(ver.intValue) {
        ProState.data.workouts.count { it.dateCompleted != null }
    }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 130.dp),
    ) {
        // ---------- 1. Header + filter tabs ----------
        item(key = "hist_header", contentType = "header") {
            Spacer(Modifier.height(4.dp))
            SectionTitle("fa-history", "Workout History")
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HistFilter.entries.forEach { f ->
                    TabPills(
                        listOf(f.label),
                        if (filter == f) 0 else -1,
                        onSelect = { filter = f },
                        modifier = Modifier.width(IntrinsicSize.Min),
                    )
                }
            }
        }

        // ---------- 2. Full-text search box ----------
        item(key = "hist_search", contentType = "search") {
            ProTextField(
                value = searchInput,
                onValueChange = { searchInput = it },
                placeholder = "Search exercises or notes…",
                singleLine = true,
            )
            if (searchDebounced.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Matching ${grouped.sumOf { it.second.size }} of $totalCount sessions",
                    style = ProType.small,
                    fontSize = 11.sp,
                    color = c.text3,
                )
            }
        }

        // ---------- 3. Empty state ----------
        if (grouped.isEmpty()) {
            item(key = "hist_empty", contentType = "empty") {
                EmptyState(
                    "fa-calendar",
                    if (searchDebounced.isNotEmpty()) "No matches"
                    else "No workouts yet",
                    if (searchDebounced.isNotEmpty())
                        "Try a different search term or clear the box."
                    else "Completed workouts appear here.",
                )
            }
        }

        // ---------- 4. Sessions grouped by month (newest first) ----------
        grouped.forEach { (monthKey, sessions) ->
            item(key = "hist_month_$monthKey", contentType = "monthHeader") {
                MonthHeader(monthKey, sessions.size)
            }
            items(
                items = sessions,
                key = { w -> "hist_session_${w.id}" },
                contentType = { "session" },
            ) { w ->
                HistoryRow(
                    workout = w,
                    isPr = w.id in prSessions,
                    expanded = expandedId == w.id,
                    onToggle = { expandedId = if (expandedId == w.id) null else w.id },
                    onDelete = { deleteTarget = w },
                    onNoteSaved = { ver.intValue++ },
                    onEditNotes = { idx -> noteTarget = w to idx },
                    onEditLog = { idx -> fixLogTarget = w to idx },
                    onExerciseTap = { ex -> exerciseHistoryTarget = ex },
                )
            }
        }

        // ---------- 5. Footer ----------
        item(key = "hist_footer", contentType = "footer") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    P4Button(
                        "Export CSV",
                        icon = "fa-file-csv",
                        style = BtnStyle.SECONDARY,
                        modifier = Modifier.weight(1f),
                    ) {
                        val (csv, rows) = buildHistoryCsv()
                        try {
                            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/csv"
                                putExtra(android.content.Intent.EXTRA_TEXT, csv)
                            }
                            ctx.startActivity(
                                android.content.Intent.createChooser(send, "Export workout history CSV"),
                            )
                        } catch (_: Exception) {
                        }
                        toast = "CSV exported — $rows rows"
                    }
                    P4Button(
                        "Back to Dashboard",
                        icon = "fa-arrow-left",
                        style = BtnStyle.GHOST,
                        modifier = Modifier.weight(1f),
                    ) { onOpenSection("dashboard") }
                }
            }
        }
    }

    // ---------- dialogs ----------
    deleteTarget?.let { target ->
        SwalDialog(
            title = "Delete \"${target.name}\"?",
            text = "This action cannot be undone.",
            confirmText = "Yes, delete",
            cancelText = "Cancel",
            onConfirm = {
                ProState.data = ProState.data.copy(
                    workouts = ProState.data.workouts.filterNot { it.id == target.id },
                )
                ProState.scope.launch {
                    com.peakform.fitness.core.ProStore(ctx).deleteWorkoutRecord(target.id)
                }
                ProState.saveWorkoutData()
                ProState.notifyChanged()
                deleteTarget = null
                ver.intValue++
            },
            onDismiss = { deleteTarget = null },
        )
    }

    noteTarget?.let { (w, idx) ->
        val ex = w.exercises.getOrNull(idx)
        if (ex?.actual == null) {
            noteTarget = null
        } else {
            NoteEditDialog(
                exerciseName = ex.name,
                initial = ex.actual?.notes ?: "",
                onSave = { note ->
                    val updated = w.copy(
                        exercises = w.exercises.mapIndexed { i, e ->
                            if (i == idx) e.copy(actual = e.actual?.copy(notes = note)) else e
                        },
                    )
                    ProState.data = ProState.data.copy(
                        workouts = ProState.data.workouts.map { if (it.id == w.id) updated else it },
                    )
                    ProState.saveWorkoutData()
                    ProState.notifyChanged()
                    noteTarget = null
                    ver.intValue++
                    toast = "Notes saved"
                },
                onDismiss = { noteTarget = null },
            )
        }
    }

    fixLogTarget?.let { (w, idx) ->
        val ex = w.exercises.getOrNull(idx)
        if (ex?.actual == null) {
            fixLogTarget = null
        } else {
            FixLogDialog(
                workout = w,
                exerciseIndex = idx,
                onSave = { newActual ->
                    val updated = w.copy(
                        exercises = w.exercises.mapIndexed { i, e ->
                            if (i == idx) e.copy(actual = newActual) else e
                        },
                        lastModifiedAt = ProState.nowIso(),
                    )
                    ProState.data = ProState.data.copy(
                        workouts = ProState.data.workouts.map { if (it.id == w.id) updated else it },
                    )
                    ProState.saveWorkoutData()
                    ProState.notifyChanged()
                    fixLogTarget = null
                    ver.intValue++
                    toast = "Log entry updated"
                },
                onDismiss = { fixLogTarget = null },
            )
        }
    }

    exerciseHistoryTarget?.let { ex ->
        ExerciseHistoryDialog(
            exercise = ex,
            onDismiss = { exerciseHistoryTarget = null },
        )
    }

    toast?.let { t ->
        LaunchedEffect(t) {
            kotlinx.coroutines.delay(2600)
            toast = null
        }
        ToastBanner(t)
    }
}

// ----------------------------------------------------------------------
// Data shaping helpers
// ----------------------------------------------------------------------

private fun buildGroupedSessions(
    filter: HistFilter,
    search: String,
): List<Pair<String, List<WorkoutRecord>>> {
    val all = ProState.data.workouts
        .filter { it.dateCompleted != null }
        .sortedByDescending { ProState.utcDayMillis(it.date) }
    val now = System.currentTimeMillis()
    val filtered = when (filter) {
        HistFilter.ALL -> all
        HistFilter.WEEK -> all.filter { now - ProState.utcDayMillis(it.date) <= 7L * 86400000 }
        HistFilter.MONTH -> all.filter { now - ProState.utcDayMillis(it.date) <= 31L * 86400000 }
        HistFilter.YEAR -> all.filter { now - ProState.utcDayMillis(it.date) <= 365L * 86400000 }
        HistFilter.LONGEVITY -> all.filter { w ->
            w.type == "longevity_day" || w.type == "mobility_flexibility" ||
                w.exercises.any { ex -> ex.muscleGroup.any { m -> m in LONGEVITY_MUSCLES } }
        }
    }
    val searched = if (search.isBlank()) filtered else filtered.filter { w ->
        // session notes
        w.exercises.any { it.actual?.notes?.contains(search, ignoreCase = true) == true } ||
            // exercise names
            w.exercises.any { it.name.contains(search, ignoreCase = true) } ||
            // workout name
            w.name.contains(search, ignoreCase = true)
    }
    // group by month (YYYY-MM key for sortability, label rendered as "October 2026")
    return searched
        .groupBy {
            val ms = ProState.utcDayMillis(it.date)
            if (ms <= 0L) "Unknown" else SimpleDateFormat("yyyy-MM", Locale.US).format(Date(ms))
        }
        .toList()
        .sortedByDescending { it.first }
}

/**
 * PR detection — walk sessions oldest→newest and track the running best
 * per exercise (weight, then reps-at-weight). A session is marked PR if
 * any exercise in it set a new running best at the time of completion.
 */
private fun computePrSessions(): Set<String> {
    val sorted = ProState.data.workouts
        .filter { it.dateCompleted != null }
        .sortedBy { ProState.utcDayMillis(it.date) }
    val best = mutableMapOf<String, Double>()  // exerciseId -> best effective weight (weight × (1+reps/30))
    val prs = mutableSetOf<String>()
    for (w in sorted) {
        var sessionIsPr = false
        for (ex in w.exercises) {
            val a = ex.actual ?: continue
            if (ex.skipped) continue
            val maxRep = a.reps.maxOrNull() ?: 0.0
            if (maxRep <= 0.0) continue
            val effective = a.weight * (1.0 + maxRep / 30.0)  // Epley-style relative effort
            val prevBest = best[ex.id]
            if (prevBest == null || effective > prevBest) {
                best[ex.id] = effective
                sessionIsPr = true
            }
        }
        if (sessionIsPr) prs.add(w.id)
    }
    return prs
}

/** Build the history CSV — header + one row per exercise per workout. */
private fun buildHistoryCsv(): Pair<String, Int> {
    val sb = StringBuilder("date,name,type,exercise,weight,sets,reps,rpe,volume,skipped,notes")
    var rows = 0
    ProState.data.workouts.forEach { w ->
        w.exercises.forEach { ex ->
            val a = ex.actual
            val volume = a?.volume ?: (a?.weight ?: 0.0) * (a?.reps?.sum() ?: 0.0)
            val fields = listOf(
                w.date,
                w.name,
                w.type,
                ex.name,
                a?.let { ProJson.formatNum(it.weight) } ?: "",
                a?.sets?.toString() ?: "",
                a?.reps?.joinToString(";") { ProJson.formatNum(it) } ?: "",
                a?.rpeList?.joinToString(";") { ProJson.formatNum(it) } ?: "",
                ProJson.formatNum(volume),
                ex.skipped.toString(),
                a?.notes ?: "",
            )
            sb.append('\n').append(fields.joinToString(",") { csvField(it) })
            rows++
        }
    }
    return sb.toString() to rows
}

private fun csvField(raw: String): String = "\"" + raw.replace("\"", "\"\"") + "\""

// ----------------------------------------------------------------------
// Subcomponents
// ----------------------------------------------------------------------

@Composable
private fun MonthHeader(monthKey: String, count: Int) {
    val c = LocalProColors.current
    val label = try {
        val parsed = SimpleDateFormat("yyyy-MM", Locale.US).parse(monthKey)
        if (parsed != null) SimpleDateFormat("MMMM yyyy", Locale.US).format(parsed) else monthKey
    } catch (_: Exception) { monthKey }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FaIcon("fa-calendar-days", size = 12.sp, tint = c.accent)
        Spacer(Modifier.width(6.dp))
        Text(label, style = ProType.label, color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        Text(
            "$count session${if (count == 1) "" else "s"}",
            style = ProType.small,
            fontSize = 11.sp,
            color = c.text3,
        )
    }
    HorizontalHairline()
}

@Composable
fun HistoryRow(
    workout: WorkoutRecord,
    expanded: Boolean,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onNoteSaved: () -> Unit = {},
    onEditNotes: (Int) -> Unit = {},
    onEditLog: (Int) -> Unit = {},
    onExerciseTap: (WorkoutExercise) -> Unit = {},
    isPr: Boolean = false,
) {
    val c = LocalProColors.current
    val dayMillis = ProState.utcDayMillis(workout.date)
    val dateLabel = if (dayMillis == 0L) workout.date.take(10)
    else try { df.format(Date(dayMillis)) } catch (_: Exception) { workout.date.take(10) }

    GlassCard(padding = PaddingValues(0.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onToggle() }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FaIcon(
                if (expanded) "fa-chevron-up" else "fa-chevron-down",
                size = 12.sp,
                tint = c.text3,
            )
            Spacer(Modifier.width(12.dp))
            Text(dateLabel, style = ProType.label, color = c.text)
            Spacer(Modifier.width(12.dp))
            Text(
                workout.name,
                style = ProType.body2,
                color = c.text2,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // PR marker — badge on sessions where a personal record was set
            if (isPr) {
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(c.warn.copy(alpha = 0.18f))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FaIcon("fa-trophy", size = 9.sp, tint = c.warn)
                        Spacer(Modifier.width(3.dp))
                        Text("PR", fontSize = 9.5.sp, color = c.warn, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(40.dp)
                    .clickable(onClick = onDelete),
                contentAlignment = Alignment.Center,
            ) {
                FaIcon("fa-trash", size = 13.sp, tint = c.bad, contentDescription = "Delete workout")
            }
        }
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
                HorizontalHairline()
                Spacer(Modifier.height(10.dp))
                val summary = workout.summary
                if (summary != null) {
                    KeyValueRow("Total Volume", Stats.formatNumber(summary.totalVolume) + " lbs")
                    KeyValueRow("Average RPE", ProJson.formatNum(summary.averageRPE))
                    KeyValueRow("Completed", "${summary.completedExercises} exercises")
                    Spacer(Modifier.height(8.dp))
                }
                workout.exercises.forEachIndexed { exIdx, ex ->
                    Column(Modifier.padding(vertical = 4.dp)) {
                        // Tap exercise name → exercise history detail
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                ex.name,
                                style = ProType.label,
                                color = c.accent,
                                fontSize = 14.sp,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { onExerciseTap(ex) },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            FaIcon("fa-chevron-right", size = 9.sp, tint = c.text3)
                        }
                        val rx = ex.prescribed
                        val actual = ex.actual
                        val actualStr = when {
                            ex.skipped -> "Skipped"
                            actual == null -> "—"
                            actual.durations.isNotEmpty() ->
                                "${actual.durations.joinToString(", ") { ProJson.formatNum(it) }}s"
                            actual.failure ->
                                "Failed @ ${actual.attemptedWeight?.toInt() ?: actual.weight.toInt()} lbs"
                            else -> buildString {
                                append(
                                    "${ProJson.formatNum(actual.weight)} lbs, " +
                                        "${actual.reps.joinToString(", ") { ProJson.formatNum(it) }} reps",
                                )
                                val rpes = actual.rpeList
                                if (rpes.isNotEmpty()) append(", RPE ${ProJson.formatNum(rpes.average())}")
                            }
                        }
                        Text(
                            "Prescribed ${rx.sets} × ${rx.reps}" +
                                "${if ((rx.weight ?: 0.0) > 0) " @ ${rx.weight?.toInt()} lbs" else ""} — Actual: $actualStr",
                            style = ProType.small,
                            color = c.text3,
                        )
                        if (actual != null) {
                            val note = actual.notes
                            if (!note.isNullOrBlank()) {
                                Text(
                                    "\uD83D\uDCDD $note",
                                    style = ProType.small,
                                    color = c.text2,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                            Row(
                                Modifier
                                    .heightIn(min = 32.dp)
                                    .padding(top = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                // Notes edit
                                Box(
                                    Modifier
                                        .clickable { onEditNotes(exIdx) }
                                        .padding(vertical = 4.dp),
                                    contentAlignment = Alignment.CenterStart,
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        FaIcon("fa-pen", size = 11.sp, tint = c.accent)
                                        Spacer(Modifier.width(5.dp))
                                        Text(
                                            if (note.isNullOrBlank()) "Add notes" else "Edit notes",
                                            fontSize = 11.sp,
                                            color = c.accent,
                                        )
                                    }
                                }
                                Spacer(Modifier.width(8.dp))
                                // Fix / edit past log entry (sets/reps/weight)
                                Box(
                                    Modifier
                                        .clickable { onEditLog(exIdx) }
                                        .padding(vertical = 4.dp),
                                    contentAlignment = Alignment.CenterStart,
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        FaIcon("fa-wrench", size = 11.sp, tint = c.warn)
                                        Spacer(Modifier.width(5.dp))
                                        Text("Fix log", fontSize = 11.sp, color = c.warn)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NoteEditDialog(
    exerciseName: String,
    initial: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = LocalProColors.current
    var text by remember { mutableStateOf(initial) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.glass2,
        shape = RoundedCornerShape(24.dp),
        title = { Text("Notes — $exerciseName", style = ProType.cardTitle, color = c.text) },
        text = {
            Column {
                Text(
                    "How did this exercise feel? Notes are saved with the workout and included in the CSV export.",
                    style = ProType.small,
                    color = c.text3,
                )
                Spacer(Modifier.height(8.dp))
                ProTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = "e.g. grip gave out on the last set",
                    singleLine = false,
                )
            }
        },
        confirmButton = {
            P4Button("Save notes", onClick = { onSave(text.trim()) }, style = BtnStyle.PRIMARY, minHeight = 40)
        },
        dismissButton = {
            P4Button("Cancel", onClick = onDismiss, style = BtnStyle.SECONDARY, minHeight = 40)
        },
    )
}

/**
 * FixLogDialog — edit a past log entry (sets / reps / weight / RPE).
 * Mirrors the legacy #fixLogModal in the HTML app (cautious-enigma L6844).
 * Saving updates the workout record and triggers a save; the warning text
 * "Editing logged data will update your 1RM estimate and future prescriptions"
 * is preserved verbatim.
 */
@Composable
fun FixLogDialog(
    workout: WorkoutRecord,
    exerciseIndex: Int,
    onSave: (ActualPerformance) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = LocalProColors.current
    val ex = workout.exercises.getOrNull(exerciseIndex) ?: run { onDismiss(); return }
    val initial = ex.actual ?: run { onDismiss(); return }

    var weight by remember { mutableStateOf(ProJson.formatNum(initial.weight)) }
    var repsText by remember {
        mutableStateOf(initial.reps.joinToString(",") { ProJson.formatNum(it) })
    }
    var sets by remember { mutableStateOf(initial.sets.toString()) }
    var rpeText by remember {
        mutableStateOf(initial.rpeList.joinToString(",") { ProJson.formatNum(it) })
    }
    var notes by remember { mutableStateOf(initial.notes ?: "") }
    var failure by remember { mutableStateOf(initial.failure) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.glass2,
        shape = RoundedCornerShape(24.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FaIcon("fa-wrench", size = 14.sp, tint = c.warn)
                Spacer(Modifier.width(8.dp))
                Text("Fix Log — ${ex.name}", style = ProType.cardTitle, color = c.text)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Edit the logged values for this exercise. Saving will update the workout record.",
                    style = ProType.small,
                    color = c.text3,
                )
                Text("Weight (lbs)", style = ProType.small, color = c.text2, fontSize = 11.sp)
                ProTextField(weight, { weight = it }, placeholder = "e.g. 135", singleLine = true)
                Text("Reps (comma-separated)", style = ProType.small, color = c.text2, fontSize = 11.sp)
                ProTextField(repsText, { repsText = it }, placeholder = "e.g. 8,8,6", singleLine = true)
                Text("Sets", style = ProType.small, color = c.text2, fontSize = 11.sp)
                ProTextField(sets, { sets = it.filter { it.isDigit() } }, placeholder = "3", singleLine = true)
                Text("RPE (comma-separated, optional)", style = ProType.small, color = c.text2, fontSize = 11.sp)
                ProTextField(rpeText, { rpeText = it }, placeholder = "8,8.5,9", singleLine = true)
                Text("Notes", style = ProType.small, color = c.text2, fontSize = 11.sp)
                ProTextField(notes, { notes = it }, placeholder = "optional notes", singleLine = false)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(
                        checked = failure,
                        onCheckedChange = { failure = it },
                    )
                    Text("Failure set", style = ProType.small, color = c.text2, fontSize = 11.sp)
                }
                Text(
                    "\u26A0\uFE0F Editing logged data will update your 1RM estimate and future prescriptions.",
                    style = ProType.small,
                    fontSize = 10.sp,
                    color = c.warn,
                )
            }
        },
        confirmButton = {
            P4Button(
                "Save & Recalculate",
                onClick = {
                    val w = weight.toDoubleOrNull() ?: initial.weight
                    val reps = repsText.split(",").mapNotNull { it.trim().toDoubleOrNull() }
                    val rpes = rpeText.split(",").mapNotNull { it.trim().toDoubleOrNull() }
                    val s = sets.toIntOrNull() ?: reps.size.coerceAtLeast(1)
                    val volume = w * reps.sum()
                    val newRpe = if (rpes.isEmpty()) null
                    else kotlinx.serialization.json.JsonPrimitive(rpes.first())
                    val newRpeEl: kotlinx.serialization.json.JsonElement = if (rpes.isEmpty()) {
                        initial.rpe ?: kotlinx.serialization.json.JsonNull
                    } else if (rpes.size == 1) {
                        kotlinx.serialization.json.JsonPrimitive(rpes.first())
                    } else {
                        kotlinx.serialization.json.JsonArray(rpes.map { kotlinx.serialization.json.JsonPrimitive(it) })
                    }
                    val updated = initial.copy(
                        weight = w,
                        reps = if (reps.isEmpty()) initial.reps else reps,
                        sets = s,
                        rpe = newRpeEl,
                        notes = notes,
                        failure = failure,
                        volume = volume,
                    )
                    onSave(updated)
                },
                style = BtnStyle.SUCCESS,
                minHeight = 40,
            )
        },
        dismissButton = {
            P4Button("Cancel", onClick = onDismiss, style = BtnStyle.SECONDARY, minHeight = 40)
        },
    )
}

/**
 * ExerciseHistoryDialog — shows the full per-exercise history over time
 * (date, weight, reps, RPE, volume, 1RM estimate). Reads from
 * `ProState.data.exercises[exercise.id].history` (the append-only log
 * updated by `Sessions.updateExerciseHistory`).
 */
@Composable
fun ExerciseHistoryDialog(
    exercise: WorkoutExercise,
    onDismiss: () -> Unit,
) {
    val c = LocalProColors.current
    val record = remember(exercise.id) {
        ProState.data.exercises[exercise.id]
    }
    val history = record?.history ?: emptyList()
    val bestWeight = record?.bestWeight
    val bestReps = record?.bestReps

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.glass2,
        shape = RoundedCornerShape(24.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FaIcon("fa-clock-rotate-left", size = 14.sp, tint = c.accent)
                Spacer(Modifier.width(8.dp))
                Text(exercise.name, style = ProType.cardTitle, color = c.text)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // PR row
                if (bestWeight != null && bestWeight > 0) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(c.warn.copy(alpha = 0.12f))
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FaIcon("fa-trophy", size = 13.sp, tint = c.warn)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "All-time best: ${ProJson.formatNum(bestWeight)} lbs" +
                                (bestReps?.let { " × ${ProJson.formatNum(it)} reps" } ?: ""),
                            style = ProType.label,
                            color = c.text,
                            fontSize = 12.sp,
                        )
                    }
                }
                if (history.isEmpty()) {
                    Text(
                        "No history recorded yet for this exercise.",
                        style = ProType.small,
                        color = c.text3,
                    )
                } else {
                    Text(
                        "${history.size} sessions logged",
                        style = ProType.small,
                        color = c.text3,
                        fontSize = 11.sp,
                    )
                    // Show most recent 8 entries
                    history.takeLast(8).reversed().forEach { entry: HistoryEntry ->
                        HistoryEntryRow(entry)
                    }
                    if (history.size > 8) {
                        Text(
                            "+ ${history.size - 8} older sessions",
                            style = ProType.small,
                            fontSize = 10.sp,
                            color = c.text3,
                        )
                    }
                }
            }
        },
        confirmButton = {
            P4Button("Close", onClick = onDismiss, style = BtnStyle.SECONDARY, minHeight = 40)
        },
    )
}

@Composable
private fun HistoryEntryRow(entry: HistoryEntry) {
    val c = LocalProColors.current
    val dateLabel = try {
        val ms = ProState.utcDayMillis(entry.date)
        if (ms > 0) df.format(Date(ms)) else entry.date.take(10)
    } catch (_: Exception) { entry.date.take(10) }
    val rpes = entry.rpeList
    val repsStr = entry.reps.joinToString(",") { ProJson.formatNum(it) }
    val est1Rm = entry.estimated1RM
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(dateLabel, style = ProType.small, fontSize = 10.sp, color = c.text3, modifier = Modifier.width(80.dp))
        Text(
            "${ProJson.formatNum(entry.weight)} lbs",
            style = ProType.small,
            fontSize = 11.sp,
            color = c.text,
            modifier = Modifier.width(56.dp),
        )
        Text(
            repsStr,
            style = ProType.small,
            fontSize = 11.sp,
            color = c.text2,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (rpes.isNotEmpty()) {
            Text(
                "RPE ${ProJson.formatNum(rpes.average())}",
                style = ProType.small,
                fontSize = 10.sp,
                color = c.accent,
                modifier = Modifier.width(56.dp),
                textAlign = TextAlign.End,
            )
        }
        if (est1Rm != null && est1Rm > 0) {
            Text(
                "1RM ${ProJson.formatNum(est1Rm)}",
                style = ProType.small,
                fontSize = 10.sp,
                color = c.warn,
                modifier = Modifier.width(60.dp),
                textAlign = TextAlign.End,
            )
        }
    }
}

@Composable
fun ToastBanner(text: String) {
    val c = LocalProColors.current
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(c.glass2)
                .border(1.dp, c.hairline2, RoundedCornerShape(14.dp))
                .padding(13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FaIcon("fa-circle-info", size = 14.sp, tint = c.accent)
            Spacer(Modifier.width(8.dp))
            Text(text, style = ProType.body2, color = c.text)
        }
    }
}
