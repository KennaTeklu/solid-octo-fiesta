package com.peakform.fitness.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.peakform.fitness.core.Exporter
import com.peakform.fitness.ui.*
import com.peakform.fitness.ui.components.*

/**
 * FeedbackScreen — BATCH-2A feature 5: the app had no feedback UI, so a minimal
 * one (textarea + submit). Submitted entries land in the feedback log which the
 * Settings → Data & backup "Export feedback" button exports as JSON.
 */
@Composable
fun FeedbackScreen(onClose: () -> Unit) {
    val c = LocalProColors.current
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("") }
    var toast by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            FaIcon("fa-comment-dots", size = 17.sp, tint = c.accent)
            Spacer(Modifier.width(10.dp))
            Text("Feedback", style = ProType.brand, color = c.text, modifier = Modifier.weight(1f))
            Text("close", style = ProType.small, color = c.accent, modifier = Modifier.clickable { onClose() })
        }
        Text("What worked, what broke, what you want next. Feedback stays on this device unless you export it.", style = ProType.small, color = c.text3)
        ProTextField(value = text, onValueChange = { text = it }, placeholder = "Your feedback…", singleLine = false)
        P4Button("Submit feedback", icon = "fa-paper-plane", style = BtnStyle.PRIMARY, modifier = Modifier.fillMaxWidth(), enabled = text.isNotBlank()) {
            Exporter.addFeedback(ctx, text, page = "feedback-screen")
            text = ""
            toast = "Feedback saved — export it from Settings → Data & backup"
        }
        val entries = remember { Exporter.readFeedback(ctx) }
        if (entries.isNotEmpty()) {
            Text("${entries.size} saved ${if (entries.size == 1) "entry" else "entries"}", style = ProType.small, color = c.text3)
            entries.take(10).forEach { e ->
                GlassCard(padding = PaddingValues(12.dp)) {
                    Text(e.text, style = ProType.small, color = c.text2)
                    Spacer(Modifier.height(4.dp))
                    Text(e.at, fontSize = 10.sp, color = c.text3)
                }
            }
        }
        Spacer(Modifier.height(110.dp))
    }
    toast?.let { t ->
        LaunchedEffect(t) { kotlinx.coroutines.delay(2600); toast = null }
        ToastBanner(t)
    }
}
