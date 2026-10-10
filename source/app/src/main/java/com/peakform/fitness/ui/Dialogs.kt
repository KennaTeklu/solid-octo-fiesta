package com.peakform.fitness.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.peakform.fitness.core.StreakFire
import com.peakform.fitness.core.WorkoutRecord
import com.peakform.fitness.ui.components.BtnStyle
import com.peakform.fitness.ui.components.P4Button

/** Local ZXing QR rendering — see ShareSheet.kt for the canonical definition. */

/**
 * Shared dialogs, implemented as in-composition glass dialogs (same idiom as the
 * MoreSheet) so they inherit the app theme fully. Copy is verbatim legacy.
 */

/** H2/ST3/ST4 — honor-system streak recovery (PF L59587–59663). */
@Composable
fun StreakRecoveryDialog(onDismiss: () -> Unit, onShare: () -> Unit, onRelight: () -> Unit) {
    val c = LocalProColors.current
    GlassDialogHost(onDismiss = onDismiss) {
        Text("❄️ " + StreakFire.RECOVERY_TITLE, style = ProType.cardTitle, color = c.text)
        Spacer(Modifier.height(8.dp))
        Text(StreakFire.RECOVERY_BODY, style = ProType.body2, color = c.text2)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            P4Button("Share", style = BtnStyle.SECONDARY, onClick = onShare)
            P4Button(StreakFire.RECOVERY_CONFIRM, style = BtnStyle.PRIMARY, onClick = onRelight)
        }
    }
}

/** I1 — pre-import confirmation, verbatim legacy copy (PF processImport L37667–37681). */
@Composable
fun ImportWarningDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val c = LocalProColors.current
    GlassDialogHost(onDismiss = onDismiss) {
        Text("Import Data", style = ProType.cardTitle, color = c.text)
        Spacer(Modifier.height(8.dp))
        Text("This will replace all your current data. Continue?", style = ProType.body2, color = c.text2)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            P4Button("Yes, overwrite", style = BtnStyle.DANGER, onClick = onConfirm)
            P4Button("Cancel", style = BtnStyle.SECONDARY, onClick = onDismiss)
        }
    }
}

/** C3 — momentum steering modal (PF L41130–41173). */
@Composable
fun MomentumDialog(onBigger: () -> Unit, onStronger: () -> Unit, onDismiss: () -> Unit) {
    val c = LocalProColors.current
    GlassDialogHost(onDismiss = onDismiss) {
        Text("🎯 Next time, I want to be…", style = ProType.cardTitle, color = c.text)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            P4Button("💪 BIGGER", style = BtnStyle.PRIMARY, onClick = onBigger)
            Spacer(Modifier.width(10.dp))
            P4Button("🔥 STRONGER", style = BtnStyle.INFO, onClick = onStronger)
        }
        Spacer(Modifier.height(8.dp))
        Text("(This will influence your next workout)", style = ProType.small, color = c.text3)
    }
}

/**
 * BATCH-2A feature 8 — pre-import guard (P4.Studio.guardImport): confirm before
 * ANY import; only the red DANGER button proceeds.
 */
@Composable
fun ImportGuardDialog(detail: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val c = LocalProColors.current
    GlassDialogHost(onDismiss = onDismiss) {
        Text("⚠️ Import data?", style = ProType.cardTitle, color = c.text)
        Spacer(Modifier.height(8.dp))
        Text(detail, style = ProType.body2, color = c.text2)
        Spacer(Modifier.height(8.dp))
        Text("A guarded snapshot of your current data is taken first, so you can always restore.", style = ProType.small, color = c.text3)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            P4Button("Yes, import", style = BtnStyle.DANGER, onClick = onConfirm)
            P4Button("Cancel", style = BtnStyle.SECONDARY, onClick = onDismiss)
        }
    }
}

/** V2 — snapshot restore confirmation. */
@Composable
fun RestoreConfirmDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val c = LocalProColors.current
    GlassDialogHost(onDismiss = onDismiss) {
        Text("Restore snapshot?", style = ProType.cardTitle, color = c.text)
        Spacer(Modifier.height(8.dp))
        Text(
            "“$name” will replace your current workouts, exercises and profile stats. Your current state is snapshotted first.",
            style = ProType.body2, color = c.text2,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            P4Button("Restore", style = BtnStyle.PRIMARY, onClick = onConfirm)
            P4Button("Cancel", style = BtnStyle.SECONDARY, onClick = onDismiss)
        }
    }
}

/** SH1–SH4 — workout share: see ShareSheet.kt for the canonical definition (QR + share text + system share + copy). */

/** LB detail — exercise detail sheet as a glass dialog. */
@Composable
fun ExerciseDetailDialog(
    exercise: com.peakform.fitness.core.LibraryExercise,
    onClose: () -> Unit,
    onAdd: () -> Unit,
) {
    val c = LocalProColors.current
    GlassDialogHost(onDismiss = onClose, scrollable = true) {
        Text(exercise.name, style = ProType.cardTitle, color = c.text)
        Spacer(Modifier.height(8.dp))
        Text("Muscles: ${exercise.muscles.joinToString(", ") { it.replace('_', ' ') }}", style = ProType.small, color = c.text2)
        Text("Equipment: ${exercise.equipment} · Sets: ${exercise.defaultSets} × ${exercise.defaultReps}", style = ProType.small, color = c.text2)
        if (exercise.progression.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text("Progression: ${exercise.progression}", style = ProType.small, color = c.text3)
        }
        if (exercise.instructions.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("How to do it", style = ProType.label, color = c.text)
            exercise.instructions.forEachIndexed { i, step ->
                Text("${i + 1}. ${step.replace(Regex("<[^>]*>"), "")}", style = ProType.small, color = c.text2, modifier = Modifier.padding(vertical = 2.dp))
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            P4Button("Add to Workout", onClick = onAdd, style = BtnStyle.PRIMARY, minHeight = 40)
            P4Button("Close", onClick = onClose, style = BtnStyle.SECONDARY, minHeight = 40)
        }
    }
}

/** In-composition modal host: scrim + centered glass card (MoreSheet idiom). */
@Composable
fun GlassDialogHost(
    onDismiss: () -> Unit,
    scrollable: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalProColors.current
    Box(
        Modifier
            .fillMaxSize()
            .background(c.bg.copy(alpha = 0.66f))
            .clickable { onDismiss() }
            .pointerInput(Unit) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(c.glass2)
                .border(1.dp, c.hairline2, RoundedCornerShape(22.dp))
                .clickable { }
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(20.dp)
                .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier),
            content = content,
        )
    }
}
