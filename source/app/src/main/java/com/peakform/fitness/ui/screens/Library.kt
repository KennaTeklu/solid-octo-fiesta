package com.peakform.fitness.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.peakform.fitness.core.LibraryExercise
import com.peakform.fitness.core.ProState
import com.peakform.fitness.core.ProPrefs
import com.peakform.fitness.core.ProJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import com.peakform.fitness.engine.Library
import com.peakform.fitness.engine.OneRm
import com.peakform.fitness.engine.Stats
import com.peakform.fitness.ui.*
import com.peakform.fitness.ui.components.*

/**
 * Library — upgraded to the full legacy experience (PF L45886–45963, 60026–60108, 6692–6719):
 * 6 group-by options, Expand/Collapse All, fitness-component chips, clickable muscle tags →
 * Muscle Wiki, "How to do"/"Images" browser buttons, Tested 1RM / Est. 1RM / Last / Next /
 * Done-Nx / New card lines, persisted collapse state (p4_library_collapsed).
 *
 * HOTFIX-1.1 Fix 1: converted from Column(verticalScroll)+forEach to LazyColumn
 * with key+contentType on every item. Only visible items compose now — the original
 * eager layout of all 1,413 cards was the ANR root cause (5s+ main-thread block).
 * Group headers render by default; cards only compose when their group is expanded
 * AND scrolled into view. Collapsing a group removes its items from the LazyColumn
 * item list (state cleanup), matching the HTML's streamGroupCards contract.
 */
@Composable
fun LibraryScreen(onAddToWorkout: (LibraryExercise) -> Unit) {
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
    val _verTick = ver.intValue
    var search by remember { mutableStateOf("") }
    var groupBy by remember { mutableIntStateOf(0) }
    var openDetail by remember { mutableStateOf<LibraryExercise?>(null) }
    var wikiFor by remember { mutableStateOf<String?>(null) }
    var componentFilter by remember { mutableStateOf<String?>(null) }

    // LB10: persisted collapse state (legacy p4_library_collapsed, PF L36798–36806)
    var collapsedGroups by remember(Library.groups) {
        mutableStateOf(
            try {
                ProPrefs.get(ctx, "p4_library_collapsed")?.let {
                    ProJson.json.decodeFromString(ListSerializer(String.serializer()), it).toSet()
                } ?: Library.groups.keys.toSet()
            } catch (_: Exception) { Library.groups.keys.toSet() }
        )
    }
    fun persistCollapse() {
        ProPrefs.put(ctx, "p4_library_collapsed",
            ProJson.json.encodeToString(ListSerializer(String.serializer()), collapsedGroups.toList()))
    }

    val mode = ProState.data.user.settings.trainingMode
    val all = remember(ver.intValue, mode) { Library.allLibraryExercises(mode).map { Library.augmented(it) } }

    // LB1: six group-by options (legacy select, PF L6699–6706)
    val groupings = listOf(
        "muscleGroup" to "Muscle",
        "equipment" to "Equipment",
        "difficulty" to "Difficulty",
        "performed" to "Performed",
        "category" to "Category",
        "component" to "Component",
    )
    val filtered = remember(search, all, componentFilter) {
        var list = if (search.length < 2) all
        else {
            val q = search.lowercase()
            all.filter { ex ->
                ex.name.lowercase().contains(q) ||
                    ex.muscles.any { it.lowercase().contains(q) } ||
                    ex.equipment.lowercase().contains(q) ||
                    ex.primaryMuscle.lowercase().contains(q)
            }
        }
        // LB3: component chips filter (legacy mpLibraryFilteredList)
        if (componentFilter != null) {
            list = list.filter { ex -> ex.fitnessComponents.contains(componentFilter) }
        }
        list
    }

    val grouped: List<Pair<String, List<LibraryExercise>>> = remember(filtered, groupBy) {
        when (groupings[groupBy].first) {
            "equipment" -> filtered.groupBy { it.equipment }.toSortedMap().map { (k, v) -> k.replaceFirstChar { it.uppercase() } to v }
            "difficulty" -> {
                fun diff(sf: Double) = if (sf < 0.5) "Hard" else if (sf < 0.7) "Medium" else "Easy"
                listOf("Hard", "Medium", "Easy").mapNotNull { label ->
                    val list = filtered.filter { diff(it.skillFactor) == label }
                    if (list.isEmpty()) null else label to list
                }
            }
            "performed" -> listOf("Done" to true, "New" to false).mapNotNull { (label, done) ->
                val list = filtered.filter { (ProState.data.exercises[it.id]?.history?.isNotEmpty() ?: false) == done }
                if (list.isEmpty()) null else label to list
            }
            "category" -> {
                // Muscle category: Major / Longevity & Joint / Hands / Feet (legacy 4 groups)
                fun cat(primary: String): String = when {
                    listOf("forearms", "grip", "hands", "wrist").any { primary.contains(it) } -> "Grip & Hand"
                    listOf("calves", "ankle", "foot", "tibialis").any { primary.contains(it) } -> "Foot & Ankle"
                    listOf("neck", "trapezius", "spine", "back", "posture").any { primary.contains(it) } -> "Longevity & Joint"
                    else -> "Major"
                }
                listOf("Major", "Longevity & Joint", "Grip & Hand", "Foot & Ankle").mapNotNull { label ->
                    val list = filtered.filter { cat(it.primaryMuscle.ifBlank { it.muscles.firstOrNull() ?: "" }) == label }
                    if (list.isEmpty()) null else label to list
                }
            }
            "component" -> {
                // The 11 fitness components
                val comps = filtered.flatMap { it.fitnessComponents }.distinct().sorted()
                comps.mapNotNull { comp ->
                    val list = filtered.filter { it.fitnessComponents.contains(comp) }
                    if (list.isEmpty()) null else comp.replace('_', ' ').replaceFirstChar { it.uppercase() } to list
                }
            }
            else -> filtered.groupBy { it.primaryMuscle.ifBlank { "other" } }
                .toList()
                .sortedByDescending { it.second.size }
                .map { (k, v) -> k.replace('_', ' ').replaceFirstChar { it.uppercase() } to v }
        }
    }

    val cap = if (search.isNotBlank()) 200 else 60
    // HOTFIX-1.1 Fix 1: hoist `components` OUT of the LazyColumn DSL — remember() is @Composable
    // and the LazyListScope is not a composable scope.
    val components = remember(all) { all.flatMap { it.fitnessComponents }.distinct().sorted() }
    // HOTFIX-1.1 Fix 1: LazyColumn + key + contentType on every item.
    // Only visible items compose. Group headers always render; cards compose only
    // when their group is expanded AND scrolled into view.
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---- sticky search + group-by + expand/collapse + component chips header ----
        item(key = "hdr_title", contentType = "header") {
            SectionTitle("fa-book-open", "Exercise Library")
            Text("${all.size} Movements Available", style = ProType.small, color = c.text3)
        }
        item(key = "hdr_search", contentType = "header") {
            ProTextField(value = search, onValueChange = { search = it }, placeholder = "Search exercises or muscles...")
        }
        item(key = "hdr_groupby", contentType = "header") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                groupings.chunked(3).forEach { rowDefs ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowDefs.forEach { (key, label) ->
                            val idx = groupings.indexOfFirst { it.first == key }
                            val active = groupBy == idx
                            Box(
                                Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(if (active) c.accentSoft else c.surface2)
                                    .border(1.dp, if (active) c.accentLine else c.hairline2, RoundedCornerShape(999.dp))
                                    .clickable { groupBy = idx }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(label, fontSize = 12.sp, color = if (active) c.accent else c.text2, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
        item(key = "hdr_expand_collapse", contentType = "header") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                P4Button("Expand All", style = BtnStyle.SECONDARY, minHeight = 34, modifier = Modifier.weight(1f)) {
                    collapsedGroups = emptySet(); persistCollapse()
                }
                P4Button("Collapse All", style = BtnStyle.SECONDARY, minHeight = 34, modifier = Modifier.weight(1f)) {
                    collapsedGroups = grouped.map { it.first }.toSet(); persistCollapse()
                }
            }
        }
        // component filter chips (only rendered if non-empty)
        if (components.isNotEmpty()) {
            item(key = "hdr_components", contentType = "header") {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.clip(RoundedCornerShape(999.dp)).background(if (componentFilter == null) c.accentSoft else c.surface2)
                        .border(1.dp, if (componentFilter == null) c.accentLine else c.hairline2, RoundedCornerShape(999.dp))
                        .clickable { componentFilter = null }.padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Text("All", fontSize = 10.sp, color = if (componentFilter == null) c.accent else c.text2)
                    }
                    components.forEach { comp ->
                        val active = componentFilter == comp
                        Box(Modifier.clip(RoundedCornerShape(999.dp)).background(if (active) c.accentSoft else c.surface2)
                            .border(1.dp, if (active) c.accentLine else c.hairline2, RoundedCornerShape(999.dp))
                            .clickable { componentFilter = if (active) null else comp }.padding(horizontal = 10.dp, vertical = 6.dp)) {
                            Text(comp.replace('_', ' ').replaceFirstChar { it.uppercase() }, fontSize = 10.sp, color = if (active) c.accent else c.text2, maxLines = 1)
                        }
                    }
                }
            }
        }

        // ---- groups + cards (lazy) ----
        grouped.forEach { (group, list) ->
            val isCollapsed = group in collapsedGroups
            // group header — always rendered
            item(key = "grp_$group", contentType = "groupHeader") {
                GlassCard(padding = PaddingValues(0.dp)) {
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            collapsedGroups = if (isCollapsed) collapsedGroups - group else collapsedGroups + group
                            persistCollapse()
                        }.padding(horizontal = 16.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(group, style = ProType.cardTitle, color = c.text, modifier = Modifier.weight(1f))
                        Text("(${list.size})", style = ProType.small, color = c.text3)
                        Spacer(Modifier.width(8.dp))
                        com.peakform.fitness.ui.FaIcon(if (isCollapsed) "fa-chevron-down" else "fa-chevron-up", size = 12.sp, tint = c.accent)
                    }
                }
            }
            // cards — only added to the LazyColumn item list when group is expanded.
            // Collapsing the group removes the items entirely (memory freed, matches HTML streamGroupCards).
            if (!isCollapsed) {
                items(
                    items = list.take(cap),
                    key = { ex -> "card_${ex.id}" },
                    contentType = { "card" },
                ) { ex ->
                    LibraryCard(
                        ex = ex,
                        onOpen = { openDetail = ex },
                        onAdd = { onAddToWorkout(ex) },
                        onMuscleTap = { wikiFor = it },
                    )
                }
                if (list.size > cap) {
                    item(key = "more_$group", contentType = "more") {
                        Text("Showing $cap of ${list.size} — refine search", style = ProType.small, color = c.text3)
                    }
                }
            }
        }

        if (filtered.isEmpty()) {
            item(key = "empty", contentType = "empty") {
                EmptyState("fa-magnifying-glass", "No exercises match your search", "Try a different term or clear the filter.")
            }
        }
        item(key = "bottom_spacer", contentType = "spacer") {
            Spacer(Modifier.height(120.dp))
        }
    }

    openDetail?.let { ex ->
        ExerciseDetailSheet(exercise = ex, onClose = { openDetail = null }, onAdd = {
            onAddToWorkout(ex)
            openDetail = null
        })
    }
    wikiFor?.let { muscle ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { wikiFor = null },
            containerColor = c.glass2,
            shape = RoundedCornerShape(22.dp),
            title = {},
            text = { MuscleWikiSheet(muscleKey = muscle, onClose = { wikiFor = null }) },
            confirmButton = {},
            dismissButton = {},
        )
    }
}

@Composable
fun LibraryCard(
    ex: LibraryExercise,
    onOpen: () -> Unit,
    onAdd: () -> Unit,
    onMuscleTap: (String) -> Unit = {},
) {
    val c = LocalProColors.current
    val record = ProState.data.exercises[ex.id]
    val difficulty = if (ex.skillFactor < 0.5) "Hard" else if (ex.skillFactor < 0.7) "Medium" else "Easy"
    val diffColor = when (difficulty) {
        "Hard" -> c.bad
        "Medium" -> c.warn
        else -> c.ok
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(c.surface2)
            .border(1.dp, c.hairline, RoundedCornerShape(14.dp))
            .clickable { onOpen() }
            .padding(13.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(ex.name, style = ProType.label, color = c.text, modifier = Modifier.weight(1f), fontSize = 14.sp)
            // LB9: Done Nx / New badges
            val doneCount = record?.history?.size ?: 0
            Box(Modifier.clip(RoundedCornerShape(999.dp)).background(if (doneCount > 0) c.ok.copy(alpha = 0.15f) else c.accentSoft).padding(horizontal = 8.dp, vertical = 2.dp)) {
                Text(if (doneCount > 0) "✔ Done N$doneCount" else "New", fontSize = 10.sp, color = if (doneCount > 0) c.ok else c.accent, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            }
            Spacer(Modifier.width(6.dp))
            Box(Modifier.clip(RoundedCornerShape(999.dp)).background(diffColor.copy(alpha = 0.15f)).padding(horizontal = 8.dp, vertical = 2.dp)) {
                Text(difficulty, fontSize = 10.sp, color = diffColor, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(6.dp))
        // LB4: clickable muscle tags → Muscle Wiki
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ex.muscles.take(3).forEach { m ->
                Box(Modifier.clip(RoundedCornerShape(999.dp)).background(c.surface3).clickable { onMuscleTap(m) }.padding(horizontal = 8.dp, vertical = 2.dp)) {
                    Text(m.replace('_', ' '), fontSize = 10.sp, color = c.accent)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "${ex.defaultSets} × ${ex.defaultReps} · ${ex.equipment.replaceFirstChar { it.uppercase() }}",
            style = ProType.small, color = c.text3,
        )
        // LB7: tested 1RM
        val tested = record?.tested1RM
        if (tested != null && tested > 0) {
            Spacer(Modifier.height(4.dp))
            Text("🏆 Tested 1RM: ${tested.toInt()} lbs", style = ProType.small, color = c.ok)
        }
        val mu = record?.mu
        if (mu != null && mu > 0) {
            Spacer(Modifier.height(4.dp))
            Text("Est. 1RM: ${mu.toInt()} lbs", style = ProType.small, color = c.accent)
        }
        // LB8: Last / Next progression lines
        val lastEntry = record?.history?.lastOrNull { !it.skipped }
        if (lastEntry != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Last: ${lastEntry.weight.toInt()} lbs × ${lastEntry.sets}",
                style = ProType.small, color = c.text3,
            )
        }
        val next = record?.nextWeight
        if (next != null && next > 0) {
            Spacer(Modifier.height(4.dp))
            Text("↑ Next: ${next.toInt()} lbs", style = ProType.small, color = c.warn)
        }
        Spacer(Modifier.height(10.dp))
        // LB5/LB6: How to do + Images (browser intents — no INTERNET permission)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            P4Button("How to do", icon = "fa-magnifying-glass", style = BtnStyle.GHOST, minHeight = 32, modifier = Modifier.weight(1f)) {
                openBrowser(ex.name + " how to do")
            }
            P4Button("Images", icon = "fa-image", style = BtnStyle.GHOST, minHeight = 32, modifier = Modifier.weight(1f)) {
                openBrowser(ex.name + " exercise", images = true)
            }
        }
        Spacer(Modifier.height(6.dp))
        P4Button("Add to Workout", icon = "fa-plus", style = BtnStyle.INFO, minHeight = 36, modifier = Modifier.fillMaxWidth()) {
            onAdd()
        }
    }
}

private fun openBrowser(query: String, images: Boolean = false) {
    val ctx = com.peakform.fitness.ui.Fa.appContext ?: return
    val url = "https://www.google.com/search?q=" + android.net.Uri.encode(query) + if (images) "&tbm=isch" else ""
    try {
        ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
    } catch (_: Exception) { }
}

@Composable
fun ExerciseDetailSheet(exercise: LibraryExercise, onClose: () -> Unit, onAdd: () -> Unit) {
    com.peakform.fitness.ui.ExerciseDetailDialog(exercise = exercise, onClose = onClose, onAdd = onAdd)
}
