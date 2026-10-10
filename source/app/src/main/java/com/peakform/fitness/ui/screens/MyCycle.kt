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
import com.peakform.fitness.core.*
import com.peakform.fitness.engine.Sessions
import com.peakform.fitness.ui.*
import com.peakform.fitness.ui.components.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MyCycle — Menstrual Cycle Tracker (PF periodModal L6772–6793 + savePeriodData L36439):
 * last period start, cycle length, current phase + load multiplier, phase message bank.
 */
@Composable
fun MyCycleScreen(onClose: () -> Unit) {
    val c = LocalProColors.current
    val ctx = LocalContext.current
    val user = ProState.data.user
    var lastStart by remember { mutableStateOf(user.menstrual.lastPeriodStart ?: "") }
    var cycleLen by remember { mutableStateOf(user.menstrual.cycleLength.toString()) }
    var toast by remember { mutableStateOf<String?>(null) }

    val dayInCycle: Int = remember(lastStart, cycleLen) {
        if (lastStart.isBlank()) 0
        else (((System.currentTimeMillis() - ProState.utcDayMillis(lastStart)) / (24 * 3600 * 1000)).toInt() + 1)
            .let { if (it <= 0) 0 else it }
    }
    val len = cycleLen.toIntOrNull()?.coerceIn(20, 45) ?: 28
    val phase: String = when {
        dayInCycle <= 0 -> ""
        dayInCycle <= 5 -> "menstrual"
        dayInCycle <= len / 2 -> "follicular"
        dayInCycle == len / 2 + 1 -> "ovulatory"
        else -> "luteal"
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            FaIcon("fa-calendar-days", size = 17.sp, tint = c.accent)
            Spacer(Modifier.width(10.dp))
            Text("My Cycle", style = ProType.brand, color = c.text, modifier = Modifier.weight(1f))
            Text("close", style = ProType.small, color = c.accent, modifier = Modifier.clickable { onClose() })
        }
        Text("Phase-aware coaching adjusts load: menstrual 0.6× · follicular 1.0× · ovulatory 1.05× · luteal 0.8×.", style = ProType.small, color = c.text3)

        GlassCard(padding = PaddingValues(14.dp)) {
            Text("Last period start (YYYY-MM-DD)", style = ProType.small, color = c.text3)
            ProTextField(value = lastStart, onValueChange = { lastStart = it }, placeholder = "2026-09-01")
            Spacer(Modifier.height(8.dp))
            Text("Cycle length (days)", style = ProType.small, color = c.text3)
            ProTextField(value = cycleLen, onValueChange = { cycleLen = it.filter { ch -> ch.isDigit() }.take(2) }, placeholder = "28")
            Spacer(Modifier.height(10.dp))
            P4Button("Save", icon = "fa-save", modifier = Modifier.fillMaxWidth()) {
                if (lastStart.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
                    ProState.data = ProState.data.copy(user = ProState.data.user.copy(
                        menstrual = MenstrualState(lastStart, len, ProState.data.user.menstrual.symptoms),
                    ))
                    ProState.saveWorkoutData()
                    toast = "Cycle saved"
                } else toast = "Enter the date as YYYY-MM-DD"
            }
        }

        if (phase.isNotBlank()) {
            val label = when (phase) {
                "menstrual" -> "Menstrual"
                "follicular" -> "Follicular"
                "ovulatory" -> "Ovulatory"
                else -> "Luteal"
            }
            GlassCard(padding = PaddingValues(14.dp)) {
                Text("Day $dayInCycle — $label phase", style = ProType.cardTitle, color = c.text)
                Spacer(Modifier.height(4.dp))
                Text("Load multiplier ${MessageBank.phaseMultiplier(phase)}×", style = ProType.body2, color = c.accent)
                Spacer(Modifier.height(6.dp))
                Text(MessageBank.pick(ctx, phase), style = ProType.small, color = c.text2)
            }
            val expectedIn = len - ((dayInCycle - 1) % len)
            Text("Next period expected in $expectedIn day(s).", style = ProType.small, color = c.text3)
        }

        P4Button("Close", style = BtnStyle.GHOST, modifier = Modifier.fillMaxWidth().padding(bottom = 96.dp), onClick = onClose)
        toast?.let { t ->
            LaunchedEffect(t) { kotlinx.coroutines.delay(2400); toast = null }
            ToastBanner(t)
        }
    }
}
