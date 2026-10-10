package com.peakform.fitness.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.peakform.fitness.core.Consent
import com.peakform.fitness.core.Health
import com.peakform.fitness.core.ProState
import com.peakform.fitness.ui.*
import com.peakform.fitness.ui.components.*

/**
 * HealthScreen — P4 Health Screen v2 (PF L64638–64930): 7 general questions,
 * conditional follow-ups, joint picker, verdict tiers A/B/C. Tier C = soft lock
 * (browse free, generation blocked until clearance recorded in Settings).
 *
 * BATCH-4B item 8: a brief intro phase ("intro") now precedes the questions
 * the first time the screen is opened for a profile. After completion,
 * `p4_health_intro_seen` (per-profile Room meta) is set and the intro never
 * shows again for that profile. Re-running the screen still works — the user
 * lands directly on the questions on subsequent opens.
 */
@Composable
fun HealthScreenFlow(
    onDone: (tier: String) -> Unit,
    onCancel: (() -> Unit)? = null,
    embedded: Boolean = false,
    startPhase: String = "general",
) {
    val c = LocalProColors.current
    val ctx = LocalContext.current
    // BATCH-4B item 8: surface the intro the first time per profile.
    val hasSeenIntro = remember { Health.hasSeenIntro(ctx) }
    var phase by remember { mutableStateOf(if (hasSeenIntro) startPhase else "intro") }
    var answers by remember { mutableStateOf(mapOf<String, Boolean>()) }
    var followup by remember { mutableStateOf(mapOf<String, Boolean>()) }
    var joints by remember { mutableStateOf(listOf<String>()) }
    val gen = remember { Health.questions(ctx) }
    val fupDefs = remember { Health.followUps(ctx) }
    val jointList = remember { Health.joints(ctx) }
    val triggered = Health.triggeredFollowUps(answers)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (!embedded) Spacer(Modifier.height(8.dp))
        Text(
            when (phase) {
                "intro" -> "Health check"
                "general" -> "Health check"
                "followup" -> "A few follow-ups"
                "joints" -> "Any problem joints?"
                else -> "Your result"
            },
            style = ProType.brand, color = c.text,
        )
        Text(
            when (phase) {
                "intro" -> "A 7-question PAR-Q — once per profile."
                "general" -> "Tap the ones that apply to you. 7 questions, once."
                "followup" -> "Just to size the risk properly."
                "joints" -> "Multi-select. We'll auto-skip movements that load them heavily."
                else -> ""
            },
            style = ProType.body2, color = c.text2,
        )
        Spacer(Modifier.height(2.dp))

        when (phase) {
            "intro" -> {
                // BATCH-4B item 8: brief intro before the questions, the first time per profile.
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(c.accentSoft)
                        .border(1.dp, c.accentLine, RoundedCornerShape(16.dp))
                        .padding(16.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FaIcon("fa-notes-medical", size = 18.sp, tint = c.accent)
                            Spacer(Modifier.width(10.dp))
                            Text("What this is", style = ProType.cardTitle, color = c.text)
                        }
                        Text(
                            "Pro uses your answers to choose exercises that fit you — and to flag when a doctor should sign off before you train. There are 7 short questions, plus a follow-up or two if anything applies, plus a quick joint picker. Nothing leaves this device.",
                            style = ProType.body2, color = c.text2,
                        )
                        Text(
                            "You can stop at any time. Your tier (Clear / Clear with modification / Sign-off required) appears at the end, and you can re-run this screen from Settings later if your situation changes.",
                            style = ProType.body2, color = c.text2,
                        )
                        Text(
                            "This is general fitness guidance, not medical care.",
                            style = ProType.small, color = c.text3,
                        )
                    }
                }
                P4Button("Let's go", icon = "fa-arrow-right", style = BtnStyle.PRIMARY, modifier = Modifier.fillMaxWidth(), onClick = {
                    Health.markIntroSeen(ctx)
                    phase = "general"
                })
            }
            "general" -> {
                gen.forEach { q ->
                    val on = answers[q.id] == true
                    Row(Modifier.fillMaxWidth().clickable { answers = answers + (q.id to !on) }
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (on) c.accentSoft else c.surface)
                        .border(1.dp, if (on) c.accentLine else c.hairline, RoundedCornerShape(12.dp))
                        .padding(13.dp), verticalAlignment = Alignment.Top) {
                        FaIcon(if (on) "fa-circle-check" else "fa-circle", size = 15.sp, tint = if (on) c.accent else c.text3)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(q.text, style = ProType.body2, color = c.text)
                            Text(if (q.important) "Important" else "Worth noting", fontSize = 10.sp, color = if (q.important) c.warn else c.text3)
                        }
                    }
                }
                P4Button("Continue", style = BtnStyle.PRIMARY, modifier = Modifier.fillMaxWidth(), onClick = {
                    phase = if (triggered.isEmpty()) "joints" else "followup"
                })
            }
            "followup" -> {
                triggered.forEach { key ->
                    fupDefs[key]?.forEach { f ->
                        val on = followup[f.id] == true
                        Row(Modifier.fillMaxWidth().clickable { followup = followup + (f.id to !on) }
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (on) c.accentSoft else c.surface)
                            .border(1.dp, if (on) c.accentLine else c.hairline, RoundedCornerShape(12.dp))
                            .padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                            FaIcon(if (on) "fa-circle-check" else "fa-circle", size = 15.sp, tint = if (on) c.accent else c.text3)
                            Spacer(Modifier.width(10.dp))
                            Text(f.text, style = ProType.body2, color = c.text)
                        }
                    }
                }
                P4Button("Continue", style = BtnStyle.PRIMARY, modifier = Modifier.fillMaxWidth(), onClick = { phase = "joints" })
            }
            "joints" -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    jointList.take(4).forEach { j ->
                        JointChip(j, j in joints) { joints = if (j in joints) joints - j else joints + j }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    jointList.drop(4).forEach { j ->
                        JointChip(j, j in joints) { joints = if (j in joints) joints - j else joints + j }
                    }
                }
                P4Button("See my result", style = BtnStyle.PRIMARY, modifier = Modifier.fillMaxWidth(), onClick = {
                    val (tier, _) = Health.computeTier(answers, followup)
                    Health.saveScreen(ctx, Health.ScreenData(answers, followup, joints, tier, ProState.nowIso()))
                    if (tier != "A") {
                        Consent.record(ctx, "waiver", "Health screen «$tier» — waiver re-accepted")
                    }
                    phase = "result"
                })
            }
            "result" -> {
                val tier = Health.tier(ctx) ?: "A"
                val (tcol, tlabel, tbody) = when (tier) {
                    "A" -> Triple(c.ok, "Clear to train", "You reported nothing that needs a doctor's sign-off. Start light, use good form, stop if something hurts.")
                    "B" -> Triple(c.warn, "Clear with modification", "Some answers need programming changes. Pro will modify exercises for the conditions you reported.")
                    else -> Triple(c.bad, "Sign-off required", "Based on your answers, a doctor should clear you first. You can still browse the app; workout generation stays locked until you record clearance in Settings.")
                }
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(tcol.copy(alpha = 0.12f)).border(2.dp, tcol, RoundedCornerShape(16.dp)).padding(16.dp)) {
                    Column {
                        Text(tlabel, style = ProType.cardTitle, color = tcol)
                        Spacer(Modifier.height(6.dp))
                        Text(tbody, style = ProType.body2, color = c.text2)
                    }
                }
                if (tier != "A") {
                    Text("Not locked out. You can still explore the app.", style = ProType.small, color = c.text3)
                }
                P4Button(if (tier == "C") "Explore app until cleared" else "Done", style = BtnStyle.PRIMARY, modifier = Modifier.fillMaxWidth(), onClick = { onDone(tier) })
                if (onCancel != null) {
                    P4Button("Adjust answers & try again", style = BtnStyle.GHOST, modifier = Modifier.fillMaxWidth(), onClick = onCancel)
                }
            }
        }
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun JointChip(label: String, selected: Boolean, onToggle: () -> Unit) {
    val c = LocalProColors.current
    Box(Modifier.clip(RoundedCornerShape(999.dp))
        .background(if (selected) c.accentSoft else c.surface2)
        .border(1.dp, if (selected) c.accentLine else c.hairline2, RoundedCornerShape(999.dp))
        .clickable { onToggle() }
        .padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(label, fontSize = 12.sp, color = if (selected) c.accent else c.text2)
    }
}
