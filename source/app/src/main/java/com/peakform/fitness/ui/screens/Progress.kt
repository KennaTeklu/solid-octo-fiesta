package com.peakform.fitness.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.peakform.fitness.core.ProState
import com.peakform.fitness.engine.Stats
import com.peakform.fitness.ui.*
import com.peakform.fitness.ui.components.*
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Progress — 1:1 port of #progress-section (scr-progress.md):
 * 11-component radar, workout frequency strip + stats, longevity score circle,
 * volume bar chart, strength line chart (top-3 exercises by Epley 1RM),
 * RPE histogram, mastery list, records board, goal cycle detail.
 */

@Composable
fun ProgressScreen(onOpenSection: (String) -> Unit) {
    val c = LocalProColors.current
    val ver = remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val l: () -> Unit = { ver.intValue++ }
        synchronized(ProState.listeners) { ProState.listeners.add(l) }
        onDispose {
            synchronized(ProState.listeners) { ProState.listeners.remove(l) }
        }
    }
    val _verTick = ver.intValue

    val longevity = remember(ver.intValue) { Stats.calculateLongevityScore() }
    val strengthProgress = remember(ver.intValue) { Stats.calculateOverallStrengthProgress() }
    val streak = remember(ver.intValue) { Stats.calculateStreak() }
    val forecast = remember(ver.intValue) { Stats.strengthForecast() }
    val composite = remember(ver.intValue) { Stats.dashboardComposite(null) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(4.dp))
        SectionTitle("fa-chart-line", "Progress Analytics")

        // hero: longevity circle + radar
        GlassCard(padding = PaddingValues(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val color = when {
                    longevity.total >= 80 -> c.ok
                    longevity.total >= 60 -> c.warn
                    else -> c.bad
                }
                ProgressRing(pct = longevity.total.toDouble(), sizeDp = 100, ringWidth = 8, color = color) {
                    Box(Modifier.size(80.dp).clip(RoundedCornerShape(999.dp)).background(c.surface), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${longevity.total}", fontSize = 24.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold, color = color, fontFamily = SpaceGrotesk)
                            Text("/100", fontSize = 10.sp, color = c.text3)
                        }
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("Longevity Score", style = ProType.cardTitle, color = c.text)
                    Text(longevity.status, style = ProType.body2, color = color)
                    Spacer(Modifier.height(6.dp))
                    Text("Overall strength progress ${String.format("%.1f", strengthProgress)}%", style = ProType.small, color = c.text3)
                    Text("$streak day streak", style = ProType.small, color = c.text3)
                }
            }
        }

        // strength forecast (SBD)
        GlassCard(padding = PaddingValues(16.dp)) {
            SectionTitle("fa-weight-hanging", "Strength Forecast", Modifier)
            // BATCH-2: forecastTotal (sum of S/B/D est1RM) + forecastWilks (bodyweight-normalized)
            val sbdTotal = forecast.take(3).sumOf { it.est1RM }
            val bodyweight = ProState.data.user.weight?.takeIf { it > 0 } ?: 1.0
            val wilks = sbdTotal / bodyweight
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(c.surface2).padding(8.dp)) {
                    Column { Text("forecastTotal", fontSize = 9.sp, color = c.text3); Text("${sbdTotal.toInt()} lbs", style = ProType.label, color = c.accent) }
                }
                Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(c.surface2).padding(8.dp)) {
                    Column { Text("forecastWilks", fontSize = 9.sp, color = c.text3); Text(String.format("%.2f", wilks), style = ProType.label, color = c.accent) }
                }
            }
            forecast.take(4).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    com.peakform.fitness.ui.FaIcon("fa-dumbbell", size = 12.sp, tint = c.accent)
                    Spacer(Modifier.width(8.dp))
                    Text(row.lift.replace('_', ' ').replaceFirstChar { it.uppercase() }, style = ProType.body2, color = c.text2, modifier = Modifier.weight(1f))
                    Text("${row.est1RM.toInt()} lbs", style = ProType.label, color = c.text)
                    Spacer(Modifier.width(8.dp))
                    Text("×${String.format("%.2f", row.bodyweightRatio)} BW", style = ProType.small, color = c.text3)
                }
            }
            if (forecast.isEmpty()) Text("Log some workouts to see your forecast.", style = ProType.small, color = c.text3)
        }

        // volume chart
        GlassCard(padding = PaddingValues(16.dp)) {
            SectionTitle("fa-chart-simple", "Volume — Last 8 Workouts", Modifier)
            VolumeBars(ver.intValue)
        }

        // strength trend
        GlassCard(padding = PaddingValues(16.dp)) {
            SectionTitle("fa-arrow-trend-up", "Strength Trend (est. 1RM)", Modifier)
            CompositeChart(points = composite)
        }

        // mastery list
        GlassCard(padding = PaddingValues(16.dp)) {
            SectionTitle("fa-layer-group", "Component Mastery", Modifier)
            val sub = longevity.sub.entries.sortedByDescending { it.value }
            sub.forEach { (name, score) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(name.replace('_', ' ').replaceFirstChar { it.uppercase() }, style = ProType.small, color = c.text2, modifier = Modifier.weight(1f))
                    Box(Modifier.width(90.dp)) { ProgressBar(score, c.accent, height = 7) }
                    Spacer(Modifier.width(8.dp))
                    Text("${score.toInt()}", style = ProType.small, color = c.text, textAlign = TextAlign.End)
                }
            }
            if (sub.isEmpty()) Text("Complete workouts to build your component profile.", style = ProType.small, color = c.text3)
        }

        // SC1: 11-component radar
        GlassCard(padding = PaddingValues(16.dp)) {
            SectionTitle("fa-bullseye", "11-Component Radar", Modifier)
            ComponentRadar(ver.intValue)
        }

        // SC2: workout frequency strip
        GlassCard(padding = PaddingValues(16.dp)) {
            SectionTitle("fa-calendar-week", "Workout Frequency — 8 Weeks", Modifier)
            FrequencyStrip(ver.intValue)
        }

        // SC3: RPE trends histogram
        GlassCard(padding = PaddingValues(16.dp)) {
            SectionTitle("fa-gauge", "RPE Trends", Modifier)
            RpeHistogram(ver.intValue)
        }

        // SC4: records board
        GlassCard(padding = PaddingValues(16.dp)) {
            SectionTitle("fa-trophy", "Records Board", Modifier)
            RecordsBoard()
        }

        // SC5: goal cycle status
        GlassCard(padding = PaddingValues(16.dp)) {
            SectionTitle("fa-flag-checkered", "Goal Cycle", Modifier)
            val gc = ProState.data.user.goalCycle
            if (gc == null || gc.goals.isEmpty()) {
                Text("No goal cycle active. Goals arrive with your next cycle.", style = ProType.small, color = c.text3)
            } else {
                KeyValueRow("Cycle", "#${'$'}{gc.cycleNumber}")
                KeyValueRow("Started", gc.startDate.ifBlank { "—"})
                KeyValueRow("Achievement", "${'$'}{gc.achievementRate.toInt()}%")
                gc.goals.take(4).forEach { g ->
                    KeyValueRow(g.title.ifBlank { g.key.replace('_', ' ') }, "${'$'}{g.current.toInt()} / ${'$'}{g.target.toInt()} ${'$'}{g.unit}")
                }
            }
        }

        // SC7: 3-month projection
        GlassCard(padding = PaddingValues(16.dp)) {
            SectionTitle("fa-arrow-trend-up", "3-Month Projection", Modifier)
            val projected = remember(ver.intValue) { projection3Month(composite) }
            if (projected == null) {
                Text("Log a few workouts to see your projection.", style = ProType.small, color = c.text3)
            } else {
                Text(projected, style = ProType.body2, color = c.text2)
            }
        }

        // BATCH-2: Volume by muscle (last 7 days) — distinct from the last-8-workouts bar chart
        GlassCard(padding = PaddingValues(16.dp)) {
            SectionTitle("fa-dumbbell", "Volume by Muscle — Last 7 Days", Modifier)
            VolumeByMuscle7d(ver.intValue)
        }

        // BATCH-2: Volume trend line chart (canvas volumeChart, line 6505)
        GlassCard(padding = PaddingValues(16.dp)) {
            SectionTitle("fa-chart-line", "Volume Trend (per session)", Modifier)
            VolumeChart(ver.intValue)
        }

        // BATCH-2: 5-year Monte Carlo forecast
        GlassCard(padding = PaddingValues(16.dp)) {
            SectionTitle("fa-dice", "5-Year Forecast (Monte Carlo)", Modifier)
            FiveYearMonteCarlo(ver.intValue)
        }

        // BATCH-2: per-muscle sparklines (last 4 weeks)
        GlassCard(padding = PaddingValues(16.dp)) {
            SectionTitle("fa-wave-square", "Per-Muscle Sparklines (4 weeks)", Modifier)
            MuscleSparklines(ver.intValue)
        }

        P4Button("Back to Dashboard", icon = "fa-arrow-left", style = BtnStyle.GHOST, modifier = Modifier.fillMaxWidth()) {
            onOpenSection("dashboard")
        }
        Spacer(Modifier.height(120.dp))
    }
}

@Composable
fun VolumeBars(ver: Int = 0) {
    val c = LocalProColors.current
    val volumes = remember(ver) {
        ProState.data.workouts.takeLast(8).map { it.summary?.totalVolume ?: 0.0 }
    }
    val maxV = volumes.maxOrNull()?.takeIf { it > 0 } ?: 1.0
    Row(
        Modifier.fillMaxWidth().height(150.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        volumes.forEachIndexed { i, v ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                val barHeight = (120 * (v / maxV)).dp
                val isStub = barHeight < 2.dp
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(if (isStub) 2.dp else barHeight)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (isStub) Brush.verticalGradient(listOf(c.accent.copy(alpha = 0.35f), c.accent.copy(alpha = 0.35f)))
                            else Brush.verticalGradient(listOf(c.accent, c.accent.copy(alpha = 0.45f)))
                        )
                )
                Spacer(Modifier.height(4.dp))
                Text("W${i + 1}", fontSize = 9.sp, color = c.text3)
            }
        }
    }
    if (volumes.isEmpty()) {
        Text("No workout volumes yet.", style = ProType.small, color = c.text3)
    }
}


// ---------------- 1.4 progress additions ----------------

/** SC1: 11-component radar (mpComponentRadar port). */
@Composable
fun ComponentRadar(ver: Int = 0) {
    val c = LocalProColors.current
    val values = remember(ver) {
        val sub = Stats.calculateLongevityScore().sub
        val keys = listOf(
            "cardio_endurance", "strength", "mobility", "balance", "core_stability",
            "power", "agility", "coordination", "flexibility", "muscular_endurance", "reaction_time",
        )
        keys.map { k -> (sub[k] ?: sub.entries.map { it.value }.average().takeIf { !it.isNaN() }?.toInt() ?: 40).toDouble().coerceIn(5.0, 100.0) }
    }
    Canvas(Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(12.dp)).background(c.bg2)) {
        val n = values.size
        if (n < 3) return@Canvas
        val cx = size.width / 2
        val cy = size.height / 2
        val r = min(size.width, size.height) / 2 * 0.78f
        // grid rings
        for (ring in 1..4) {
            val rr = r * ring / 4
            val path = Path()
            for (i in 0..n) {
                val ang = (2 * Math.PI * (i % n) / n) - Math.PI / 2
                val x = cx + cos(ang).toFloat() * rr
                val y = cy + sin(ang).toFloat() * rr
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, c.hairline2, style = Stroke(width = 2f))
        }
        // data polygon
        val dataPath = Path()
        for (i in 0..n) {
            val idx = i % n
            val ang = (2 * Math.PI * idx / n) - Math.PI / 2
            val v = (values[idx].toFloat() / 100f).coerceIn(0.05f, 1f)
            val x = cx + cos(ang).toFloat() * r * v
            val y = cy + sin(ang).toFloat() * r * v
            if (i == 0) dataPath.moveTo(x, y) else dataPath.lineTo(x, y)
        }
        drawPath(dataPath, c.accent.copy(alpha = 0.22f))
        drawPath(dataPath, c.accent, style = Stroke(width = 4f))
    }
    val labels = listOf("Cardio", "Strength", "Mobility", "Balance", "Core", "Power", "Agility", "Coordination", "Flexibility", "Endurance", "Reaction")
    Text(labels.joinToString(" · "), style = ProType.small, color = c.text3, maxLines = 2)
}

/** SC2: workouts per week over 8 weeks. */
@Composable
fun FrequencyStrip(ver: Int = 0) {
    val c = LocalProColors.current
    val weekly = remember(ver) {
        val counts = IntArray(8)
        val now = System.currentTimeMillis()
        ProState.data.workouts.filter { it.isCompleted }.forEach { w ->
            val ms = ProState.utcDayMillis(w.date)
            if (ms > 0) {
                val weeksAgo = ((now - ms) / (7L * 24 * 3600 * 1000)).toInt()
                if (weeksAgo in 0..7) counts[7 - weeksAgo]++
            }
        }
        counts
    }
    val maxV = maxOf(1, weekly.maxOrNull() ?: 1)
    Row(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
        weekly.forEachIndexed { i, n ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                Box(Modifier.fillMaxWidth().height((100 * (n / maxV.toDouble())).dp.coerceAtLeast(2.dp)).clip(RoundedCornerShape(6.dp)).background(if (n > 0) c.accent else c.surface3))
                Spacer(Modifier.height(4.dp))
                Text("W${'$'}{i + 1}", fontSize = 9.sp, color = c.text3)
                Text("${'$'}n", fontSize = 10.sp, color = c.text2)
            }
        }
    }
}

/** SC3: RPE histogram over the last 20 logged sets (1–10). */
@Composable
fun RpeHistogram(ver: Int = 0) {
    val c = LocalProColors.current
    val buckets = remember(ver) {
        val b = IntArray(10)
        ProState.data.workouts.filter { it.isCompleted }.takeLast(20).forEach { w ->
            w.exercises.forEach { ex ->
                ex.actual?.rpeList?.forEach { r ->
                    val idx = (r.toInt()).coerceIn(1, 10) - 1
                    b[idx]++
                }
                ex.actual?.firstRPE?.let { r ->
                    val idx = (r.toInt()).coerceIn(1, 10) - 1
                    b[idx]++
                }
            }
        }
        b
    }
    val maxV = maxOf(1, buckets.maxOrNull() ?: 1)
    Row(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
        buckets.forEachIndexed { i, n ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                Box(Modifier.fillMaxWidth().height((100 * (n / maxV.toDouble())).dp.coerceAtLeast(2.dp)).clip(RoundedCornerShape(4.dp)).background(if (i >= 8) c.bad.copy(alpha = 0.75f) else if (i >= 6) c.warn.copy(alpha = 0.75f) else c.accent))
                Spacer(Modifier.height(4.dp))
                Text("${'$'}{i + 1}", fontSize = 9.sp, color = c.text3)
            }
        }
    }
    Text("Logged sets by RPE (last 20 sessions)", style = ProType.small, color = c.text3)
}

/** SC4: records board — best sets across exercises. */
@Composable
fun RecordsBoard() {
    val c = LocalProColors.current
    val records = remember {
        ProState.data.exercises.entries.mapNotNull { (id, rec) ->
            val best = rec.bestWeight ?: return@mapNotNull null
            if (best <= 0) return@mapNotNull null
            Triple(id, best, rec.bestTimes.size)
        }.sortedByDescending { it.second }.take(8)
    }
    if (records.isEmpty()) {
        Text("No records yet — log some heavy sets.", style = ProType.small, color = c.text3)
        return
    }
    records.forEachIndexed { i, (id, best, _) ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${'$'}{i + 1}.", style = ProType.small, color = c.text3, modifier = Modifier.width(26.dp))
            Text(id.replace('_', ' ').replaceFirstChar { it.uppercase() }, style = ProType.body2, color = c.text, modifier = Modifier.weight(1f), maxLines = 1)
            Text("${'$'}{best.toInt()} lbs", style = ProType.label, color = c.accent)
        }
    }
}

/** SC7: simple linear projection of the composite trend, 3 months out. */
fun projection3MonthPublic(points: List<Stats.CompositePoint>): String? = projection3Month(points)

private fun projection3Month(points: List<Stats.CompositePoint>): String? {
    if (points.size < 6) return null
    val n = points.size
    val lastVal = points.last().value
    val prevVal = points[(n * 3) / 4].value
    val slope = (lastVal - prevVal) / kotlin.math.max(1, n - (n * 3) / 4)
    val projected = (lastVal + slope * 12).coerceIn(0.0, 100.0)
    val direction = when {
        projected > lastVal + 2 -> "trending up"
        projected < lastVal - 2 -> "trending down"
        else -> "holding steady"
    }
    return "Composite score is $direction — projected ${'$'}{projected.toInt()}/100 in 3 months (now ${'$'}{lastVal.toInt()})."
}

// ---------------- BATCH-2 progress additions ----------------

// ---------------- BATCH-2 progress additions ----------------

/** Public wrapper for the longevity circle hero card (for individual screenshot capture). */
@Composable
fun LongevityCircleCard() {
    val c = LocalProColors.current
    val longevity = remember { Stats.calculateLongevityScore() }
    val strengthProgress = remember { Stats.calculateOverallStrengthProgress() }
    val streak = remember { Stats.calculateStreak() }
    GlassCard(padding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val color = when {
                longevity.total >= 80 -> c.ok
                longevity.total >= 60 -> c.warn
                else -> c.bad
            }
            ProgressRing(pct = longevity.total.toDouble(), sizeDp = 100, ringWidth = 8, color = color) {
                Box(Modifier.size(80.dp).clip(RoundedCornerShape(999.dp)).background(c.surface), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${longevity.total}", fontSize = 24.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold, color = color, fontFamily = SpaceGrotesk)
                        Text("/100", fontSize = 10.sp, color = c.text3)
                    }
                }
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text("Longevity Score", style = ProType.cardTitle, color = c.text)
                Text(longevity.status, style = ProType.body2, color = color)
                Spacer(Modifier.height(6.dp))
                Text("Overall strength progress ${String.format("%.1f", strengthProgress)}%", style = ProType.small, color = c.text3)
                Text("$streak day streak", style = ProType.small, color = c.text3)
            }
        }
    }
}

/** Public wrapper for the strength forecast card (for individual screenshot capture). */
@Composable
fun StrengthForecastCard() {
    val c = LocalProColors.current
    val forecast = remember { Stats.strengthForecast() }
    GlassCard(padding = PaddingValues(16.dp)) {
        val sbdTotal = forecast.take(3).sumOf { it.est1RM }
        val bodyweight = ProState.data.user.weight?.takeIf { it > 0 } ?: 1.0
        val wilks = sbdTotal / bodyweight
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(c.surface2).padding(8.dp)) {
                Column { Text("forecastTotal", fontSize = 9.sp, color = c.text3); Text("${sbdTotal.toInt()} lbs", style = ProType.label, color = c.accent) }
            }
            Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(c.surface2).padding(8.dp)) {
                Column { Text("forecastWilks", fontSize = 9.sp, color = c.text3); Text(String.format("%.2f", wilks), style = ProType.label, color = c.accent) }
            }
        }
        forecast.take(4).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                com.peakform.fitness.ui.FaIcon("fa-dumbbell", size = 12.sp, tint = c.accent)
                Spacer(Modifier.width(8.dp))
                Text(row.lift.replace('_', ' ').replaceFirstChar { it.uppercase() }, style = ProType.body2, color = c.text2, modifier = Modifier.weight(1f))
                Text("${row.est1RM.toInt()} lbs", style = ProType.label, color = c.text)
                Spacer(Modifier.width(8.dp))
                Text("×${String.format("%.2f", row.bodyweightRatio)} BW", style = ProType.small, color = c.text3)
            }
        }
        if (forecast.isEmpty()) Text("Log some workouts to see your forecast.", style = ProType.small, color = c.text3)
    }
}

/** Public wrapper for the goal cycle status card (for individual screenshot capture). */
@Composable
fun GoalCycleCard() {
    val c = LocalProColors.current
    GlassCard(padding = PaddingValues(16.dp)) {
        val gc = ProState.data.user.goalCycle
        if (gc == null || gc.goals.isEmpty()) {
            Text("No goal cycle active. Goals arrive with your next cycle.", style = ProType.small, color = c.text3)
        } else {
            KeyValueRow("Cycle", "#${gc.cycleNumber}")
            KeyValueRow("Started", gc.startDate.ifBlank { "—" })
            KeyValueRow("Achievement", "${gc.achievementRate.toInt()}%")
            gc.goals.take(4).forEach { g ->
                KeyValueRow(g.title.ifBlank { g.key.replace('_', ' ') }, "${g.current.toInt()} / ${g.target.toInt()} ${g.unit}")
            }
        }
    }
}

/** Box-Muller transform — generates a standard-normal sample (mean=0, stddev=1).
 * Kotlin's stdlib Random doesn't ship nextGaussian, so we implement it here. */
private fun nextGaussian(rng: kotlin.random.Random): Double {
    var u = 0.0
    var v = 0.0
    while (u == 0.0) u = rng.nextDouble()
    while (v == 0.0) v = rng.nextDouble()
    return kotlin.math.sqrt(-2.0 * kotlin.math.ln(u)) * kotlin.math.cos(2.0 * Math.PI * v)
}

/** Volume by muscle — total volume per major muscle group over the last 7 days. */
@Composable
fun VolumeByMuscle7d(ver: Int = 0) {
    val c = LocalProColors.current
    val byMuscle = remember(ver) {
        val now = System.currentTimeMillis()
        val weekAgo = now - 7L * 24 * 3600 * 1000
        val map = HashMap<String, Double>()
        ProState.data.workouts.filter { it.isCompleted }.forEach { w ->
            val ms = ProState.utcDayMillis(w.date)
            if (ms in weekAgo..now) {
                w.exercises.forEach { ex ->
                    val vol = ex.actual?.volume ?: ((ex.actual?.weight ?: 0.0) * (ex.actual?.sets ?: 0))
                    if (vol > 0) {
                        ex.muscleGroup.forEach { m ->
                            map.merge(m, vol) { a, b -> a + b }
                        }
                    }
                }
            }
        }
        map.entries.sortedByDescending { it.value }.take(10).toList()
    }
    if (byMuscle.isEmpty()) {
        Text("No volume logged in the last 7 days.", style = ProType.small, color = c.text3)
        return
    }
    val maxV = byMuscle.maxOf { it.value }.takeIf { it > 0 } ?: 1.0
    byMuscle.forEach { (muscle, vol) ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(muscle.replace('_', ' ').replaceFirstChar { it.uppercase() }, style = ProType.small, color = c.text2, modifier = Modifier.weight(1f))
            Box(Modifier.width(120.dp)) { ProgressBar((vol / maxV * 100), c.accent, height = 7) }
            Spacer(Modifier.width(8.dp))
            Text("${vol.toInt()} lbs", style = ProType.small, color = c.text, textAlign = TextAlign.End, modifier = Modifier.width(70.dp))
        }
    }
}

/** Volume trend line chart — total volume per workout over time (canvas volumeChart, line 6505). */
@Composable
fun VolumeChart(ver: Int = 0) {
    val c = LocalProColors.current
    val points = remember(ver) {
        ProState.data.workouts.filter { it.isCompleted }.takeLast(20).map { it.summary?.totalVolume ?: 0.0 }
    }
    if (points.size < 2) {
        Text("Log at least 2 workouts to see the trend.", style = ProType.small, color = c.text3)
        return
    }
    val maxV = points.maxOrNull()?.takeIf { it > 0 } ?: 1.0
    Canvas(Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(12.dp)).background(c.bg2)) {
        val n = points.size
        val stepX = if (n > 1) size.width / (n - 1) else size.width
        val path = Path()
        val fillPath = Path()
        points.forEachIndexed { i, v ->
            val x = i * stepX
            val y = size.height - (size.height * (v / maxV).toFloat() * 0.85f) - 8f
            if (i == 0) { path.moveTo(x, y); fillPath.moveTo(x, size.height); fillPath.lineTo(x, y) }
            else { path.lineTo(x, y); fillPath.lineTo(x, y) }
        }
        fillPath.lineTo((n - 1) * stepX, size.height)
        fillPath.close()
        drawPath(fillPath, c.accent.copy(alpha = 0.18f))
        drawPath(path, c.accent, style = Stroke(width = 3f))
    }
    Text("Last ${points.size} sessions · peak ${maxV.toInt()} lbs", style = ProType.small, color = c.text3)
}

/**
 * 5-year Monte Carlo forecast — projects the user's composite strength forward 260 weeks
 * (5 yr × 52 wk/yr) under random weekly perturbation. Runs 1000 scenarios, reports
 * median + 5th/95th percentile of the projected composite score.
 */
@Composable
fun FiveYearMonteCarlo(ver: Int = 0) {
    val c = LocalProColors.current
    val result = remember(ver) {
        val history = Stats.dashboardComposite(null)
        val current = history.lastOrNull()?.value ?: 50.0
        // slope per workout, estimated from history (fallback: +0.05/workout)
        val slope = if (history.size >= 4) {
            val recent = history.takeLast(4)
            (recent.last().value - recent.first().value) / kotlin.math.max(1, recent.size - 1)
        } else 0.05
        val rng = Random(42L)
        val weeks = 260
        val scenarios = 1000
        val projections = DoubleArray(scenarios) {
            var v = current
            for (w in 0 until weeks) {
                // weekly drift = slope × (sessions/week ~ 3) + Gaussian noise (Box-Muller)
                val noise = nextGaussian(rng)
                v += slope * 3 + noise * 0.4
                v = v.coerceIn(0.0, 100.0)
            }
            v
        }
        projections.sort()
        Triple(projections[scenarios / 20], projections[scenarios / 2], projections[scenarios * 19 / 20])
    }
    val (p5, p50, p95) = result
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(c.surface2).padding(8.dp)) {
            Column { Text("5th pctile", fontSize = 9.sp, color = c.text3); Text("${p5.toInt()}", style = ProType.label, color = c.bad) }
        }
        Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(c.accentSoft).padding(8.dp)) {
            Column { Text("Median", fontSize = 9.sp, color = c.text3); Text("${p50.toInt()}", style = ProType.label, color = c.accent) }
        }
        Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(c.surface2).padding(8.dp)) {
            Column { Text("95th pctile", fontSize = 9.sp, color = c.text3); Text("${p95.toInt()}", style = ProType.label, color = c.ok) }
        }
    }
    Text("1000 scenarios × 260 weeks (5 yr). Projected composite score distribution.", style = ProType.small, color = c.text3)
}

/** Per-muscle sparklines — inline 4-week volume trend, one per major muscle group. */
@Composable
fun MuscleSparklines(ver: Int = 0) {
    val c = LocalProColors.current
    val data = remember(ver) {
        val now = System.currentTimeMillis()
        val fourWeeksAgo = now - 28L * 24 * 3600 * 1000
        // group completed workouts into 4 weekly buckets per muscle
        val muscles = ProState.data.workouts
            .filter { it.isCompleted }
            .flatMap { it.exercises }
            .flatMap { it.muscleGroup }
            .distinct()
            .take(8)
        muscles.map { muscle ->
            muscle to IntArray(4) { weekIdx ->
                val weekStart = fourWeeksAgo + weekIdx * 7L * 24 * 3600 * 1000
                val weekEnd = weekStart + 7L * 24 * 3600 * 1000
                ProState.data.workouts.filter { it.isCompleted }.sumOf { w ->
                    val ms = ProState.utcDayMillis(w.date)
                    if (ms in weekStart until weekEnd) {
                        w.exercises.filter { muscle in it.muscleGroup }.sumOf { ex ->
                            ex.actual?.volume ?: ((ex.actual?.weight ?: 0.0) * (ex.actual?.sets ?: 0))
                        }
                    } else 0.0
                }.toInt()
            }
        }
    }
    if (data.isEmpty() || data.all { it.second.sum() == 0 }) {
        Text("No per-muscle volume data in the last 4 weeks.", style = ProType.small, color = c.text3)
        return
    }
    data.forEach { (muscle, weeks) ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(muscle.replace('_', ' ').replaceFirstChar { it.uppercase() }, style = ProType.small, color = c.text2, modifier = Modifier.weight(1f))
            // mini sparkline canvas (80×24)
            Canvas(Modifier.width(80.dp).height(24.dp)) {
                val maxV = weeks.maxOrNull()?.takeIf { it > 0 } ?: 1
                val stepX = size.width / kotlin.math.max(1, weeks.size - 1)
                val path = Path()
                weeks.forEachIndexed { i, v ->
                    val x = i * stepX
                    val y = size.height - (size.height * (v.toFloat() / maxV) * 0.9f) - 2f
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, c.accent, style = Stroke(width = 2f))
            }
            Spacer(Modifier.width(8.dp))
            Text("${weeks.sum()} lbs", style = ProType.small, color = c.text, textAlign = TextAlign.End, modifier = Modifier.width(60.dp))
        }
    }
}
