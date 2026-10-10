package com.peakform.fitness.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.peakform.fitness.core.*
import com.peakform.fitness.ui.*
import com.peakform.fitness.ui.components.*

/**
 * Onboarding — 5-step first-run wizard, native port of P4.Onboard (PF L56508–56707):
 * 1 Welcome+name · 2 Birth date+gender (skippable) · 3 Reading level · 4 Theme · 5 Consent.
 * Finish mirrors legacy finish(): profile write + theme + vocab + consents + confetti(140).
 */
@Composable
fun OnboardingWizard(onFinished: () -> Unit) {
    val c = LocalProColors.current
    val ctx = LocalContext.current
    val draft = OnboardDraft.fromPrefs(ctx)
    var step by remember { mutableIntStateOf(draft.step) }
    var name by remember { mutableStateOf(draft.name) }
    var birth by remember { mutableStateOf(draft.birth) }
    var gender by remember { mutableStateOf(draft.gender) }
    var level by remember { mutableIntStateOf(Vocab.currentLevel(ctx)) }
    var dark by remember { mutableStateOf(ThemeController.dark) }
    var accentId by remember { mutableStateOf(ThemeController.accentId) }
    var c1 by remember { mutableStateOf(false) }
    var c2 by remember { mutableStateOf(false) }
    var c3 by remember { mutableStateOf(false) }
    var c4 by remember { mutableStateOf(false) }
    var guardianNeeded by remember { mutableStateOf(false) }

    fun persistDraft() {
        ProPrefs.put(ctx, "p4_ob_draft", """{"step":$step,"name":${esc(name)},"birth":${esc(birth)},"gender":${esc(gender)}}""")
    }

    val isMinor = ageFrom(birth) in 13..17
    val isUnder13 = ageFrom(birth) in 1..12
    LaunchedEffect(isMinor, isUnder13) {
        guardianNeeded = isMinor || isUnder13
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(18.dp))
        Text("Step ${step + 1} of 5", style = ProType.small, color = c.text3, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Row(Modifier.fillMaxWidth().padding(horizontal = 60.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(5) { i ->
                Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(if (i <= step) c.accent else c.surface3))
            }
        }
        Spacer(Modifier.height(8.dp))

        when (step) {
            0 -> {
                Text("Two minutes, once.", style = ProType.brand, color = c.text)
                Text("Let's set up your training space. Everything stays on this device.", style = ProType.body2, color = c.text2)
                Spacer(Modifier.height(10.dp))
                ProTextField(value = name, onValueChange = { name = it.take(40) }, placeholder = "First name or nickname")
                Text("Stays on this app on your device. No account, no email, no server.", style = ProType.small, color = c.text3)
            }
            1 -> {
                Text("When were you born?", style = ProType.brand, color = c.text)
                Text("Age keeps your programming and safety checks honest.", style = ProType.body2, color = c.text2)
                Spacer(Modifier.height(10.dp))
                ProTextField(value = birth, onValueChange = { birth = it }, placeholder = "YYYY-MM-DD")
                if (isUnder13) {
                    Text("You must be at least 13 to use Pro. Ask a parent to contact us.", style = ProType.small, color = c.bad)
                } else if (isMinor) {
                    Text("Under 18 — use Pro with guardian approval.", style = ProType.small, color = c.warn)
                }
                Spacer(Modifier.height(8.dp))
                Text("Gender (optional)", style = ProType.small, color = c.text3)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("unspecified" to "Not now", "male" to "Male", "female" to "Female").forEach { (v, l) ->
                        val active = gender == v
                        Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (active) c.accentSoft else c.surface2)
                            .border(1.dp, if (active) c.accentLine else c.hairline2, RoundedCornerShape(10.dp))
                            .clickable { gender = v }.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                            Text(l, fontSize = 12.sp, color = if (active) c.accent else c.text2)
                        }
                    }
                }
            }
            2 -> {
                Text("How should Pro talk to you?", style = ProType.brand, color = c.text)
                Text("From super simple to grandiloquent — you pick the words.", style = ProType.body2, color = c.text2)
                Spacer(Modifier.height(10.dp))
                Vocab.LEVELS.forEach { l ->
                    val active = level == l.id
                    Row(Modifier.fillMaxWidth().clickable {
                        level = l.id
                        Vocab.setLevel(ctx, l.id)
                    }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(16.dp).clip(CircleShape).background(if (active) c.accent else c.surface3).border(1.dp, c.hairline2, CircleShape))
                        Spacer(Modifier.width(10.dp))
                        Text("L${l.id} — ${l.name}", style = ProType.body2, color = if (active) c.accent else c.text2)
                    }
                }
                Text(sampleSentence(level), style = ProType.small, color = c.text3)
            }
            3 -> {
                Text("Pick your look.", style = ProType.brand, color = c.text)
                Text("Dark or light, then one of 16 accents.", style = ProType.body2, color = c.text2)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf("dark" to "fa-moon", "light" to "fa-sun").forEach { (m, ic) ->
                        val active = (if (dark) "dark" else "light") == m
                        Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (active) c.accentSoft else c.surface2)
                            .border(2.dp, if (active) c.accent else c.hairline2, RoundedCornerShape(10.dp))
                            .clickable {
                                dark = m == "dark"
                                ThemeController.set(if (dark) "dark" else "light", accentId, ctx)
                            }.padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                FaIcon(ic, size = 13.sp, tint = if (active) c.accent else c.text2)
                                Spacer(Modifier.width(6.dp))
                                Text(m.replaceFirstChar { it.uppercase() }, fontSize = 13.sp, color = if (active) c.accent else c.text2)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                ACCENTS.chunked(8).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                        row.forEach { a ->
                            Box(Modifier.size(30.dp).clip(CircleShape).background(if (dark) a.darkHex else a.lightHex)
                                .border(if (accentId == a.id) 3.dp else 1.dp, if (accentId == a.id) c.text else c.hairline2, CircleShape)
                                .clickable {
                                    accentId = a.id
                                    ThemeController.set(if (dark) "dark" else "light", a.id, ctx)
                                })
                        }
                    }
                }
            }
            4 -> {
                Text("The important part.", style = ProType.brand, color = c.text)
                Text("Three agreements to train safe.", style = ProType.body2, color = c.text2)
                Spacer(Modifier.height(10.dp))
                ConsentCheckbox(
                    label = "I am at least ${if (guardianNeeded) "a minor using this with guardian approval" else "18 or older"} and I accept the Terms and Privacy Policy. My data stays in this app on my device.",
                    checked = c1, onToggle = { c1 = !c1 },
                )
                ConsentCheckbox(
                    label = "Health check: I feel OK to exercise, or a doctor has told me it is safe. I will stop if I feel pain, dizziness or shortness of breath, and I will talk to a professional about my health. This app is not medical care.",
                    checked = c2, onToggle = { c2 = !c2 },
                )
                ConsentCheckbox(
                    label = "Safety agreement: I train at my own risk, start light, use good form, and I will not blame try4ever.com if I get hurt following general fitness guidance. (Full text: Liability Waiver)",
                    checked = c3, onToggle = { c3 = !c3 },
                )
                if (guardianNeeded) {
                    ConsentCheckbox(
                        label = "Guardian approval: I am the parent/guardian (or I have their permission) and they accept these terms for me.",
                        checked = c4, onToggle = { c4 = !c4 },
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (step > 0) {
                P4Button("Back", onClick = { step--; persistDraft() }, style = BtnStyle.GHOST)
            }
            if (step == 1) {
                P4Button("Skip for now", onClick = { birth = ""; gender = "unspecified"; step = 2 }, style = BtnStyle.SECONDARY, modifier = Modifier.weight(1f))
            }
            P4Button(
                if (step == 4) "I agree — let's go" else "Next",
                icon = if (step == 4) "fa-check" else "fa-arrow-right",
                style = BtnStyle.PRIMARY,
                modifier = Modifier.weight(1f),
                enabled = when (step) {
                    0 -> name.trim().length >= 1
                    1 -> birth.isBlank() || (ageFrom(birth) >= 13 && birth.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
                    4 -> c1 && c2 && c3 && (!guardianNeeded || c4)
                    else -> true
                },
                onClick = {
                    when {
                        step < 4 -> { step++; persistDraft() }
                        else -> {
                            OnboardDraft.finish(ctx, name.trim(), birth.ifBlank { null }, gender, level, if (dark) "dark" else "light", accentId)
                            onFinished()
                        }
                    }
                },
            )
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun ConsentCheckbox(label: String, checked: Boolean, onToggle: () -> Unit) {
    val c = LocalProColors.current
    Row(Modifier.fillMaxWidth().clickable { onToggle() }.padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(22.dp).clip(RoundedCornerShape(6.dp))
                .background(if (checked) c.accent else c.surface2)
                .border(2.dp, if (checked) c.accent else c.hairline2, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) FaIcon("fa-check", size = 12.sp, tint = c.onAccent)
        }
        Spacer(Modifier.width(10.dp))
        Text(label, style = ProType.small, color = c.text2)
    }
}

private fun esc(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

fun ageFrom(birth: String): Int {
    if (!birth.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) return -1
    return try {
        val cal = java.util.Calendar.getInstance()
        val by = birth.substring(0, 4).toInt()
        val bm = birth.substring(5, 7).toInt() - 1
        var age = cal.get(java.util.Calendar.YEAR) - by
        if (cal.get(java.util.Calendar.MONTH) < bm) age--
        age
    } catch (_: Exception) { -1 }
}

private fun sampleSentence(level: Int): String {
    val word = when {
        level <= 2 -> "muscle growth"
        level <= 4 -> "hypertrophy (muscle growth)"
        level <= 6 -> "hypertrophy"
        level <= 8 -> "myofibrillar hypertrophy"
        else -> "sarcoplasmic and myofibrillar hypertrophy"
    }
    return "This move builds $word."
}
