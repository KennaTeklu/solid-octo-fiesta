package com.peakform.fitness.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import com.peakform.fitness.core.ProJson
import com.peakform.fitness.core.ProPrefs
import com.peakform.fitness.ProLog
import com.peakform.fitness.ui.*
import com.peakform.fitness.ui.components.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * LegalCenter — 13 verbatim legacy documents (P4_LEGAL_DOCS) with tab strip,
 * effective/meta line, consent history panel (last 8) and "Export consent log".
 */
data class LegalDoc(val id: String, val short: String, val title: String, val sections: List<Pair<String, String>>)
object LegalDocs {
    private var cache: List<LegalDoc> = emptyList()

    fun load(ctx: android.content.Context): List<LegalDoc> {
        if (cache.isNotEmpty()) return cache
        cache = try {
            val raw = ctx.assets.open("data/legal_docs.json").bufferedReader().use { it.readText() }
            val obj = ProJson.json.parseToJsonElement(raw).jsonObject
            obj.map { (id, el) ->
                val o = el.jsonObject
                LegalDoc(
                    id = id,
                    short = (o["short"] as? JsonPrimitive)?.contentOrNull ?: id,
                    title = (o["title"] as? JsonPrimitive)?.contentOrNull ?: id,
                    sections = (o["sections"] as? JsonArray ?: JsonArray(emptyList())).mapNotNull { s ->
                        val so = s as? JsonObject ?: return@mapNotNull null
                        val h = (so["h"] as? JsonPrimitive)?.contentOrNull ?: ""
                        val paras = (so["p"] as? JsonArray ?: JsonArray(emptyList()))
                            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                            .joinToString("\n\n")
                        h to paras
                    },
                )
            }.sortedBy { it.id }
        } catch (e: Exception) {
            ProLog.e("LEGAL", "docs load failed: ${e.message}")
            emptyList()
        }
        return cache
    }
}

@Composable
fun LegalCenterScreen(onClose: () -> Unit) {
    val c = LocalProColors.current
    val ctx = LocalContext.current
    val docs = remember { LegalDocs.load(ctx) }
    var selected by remember { mutableIntStateOf(0) }
    val doc = docs.getOrNull(selected)

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            FaIcon("fa-scale-balanced", size = 17.sp, tint = c.accent)
            Spacer(Modifier.width(10.dp))
            Text("Legal Center", style = ProType.brand, color = c.text, modifier = Modifier.weight(1f))
            Text("${docs.size} documents", style = ProType.small, color = c.text3)
        }
        Text("Effective ${Consent.VERSION} · v1.0 · Operated by try4ever.com", style = ProType.small, color = c.text3)

        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            docs.forEachIndexed { i, d ->
                val active = i == selected
                Box(Modifier.clip(RoundedCornerShape(999.dp))
                    .background(if (active) c.accentSoft else c.surface2)
                    .border(1.dp, if (active) c.accentLine else c.hairline2, RoundedCornerShape(999.dp))
                    .clickable { selected = i }
                    .padding(horizontal = 11.dp, vertical = 7.dp)) {
                    Text(d.short, fontSize = 11.5.sp, color = if (active) c.accent else c.text2, maxLines = 1)
                }
            }
        }

        doc?.let { d ->
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(d.title, style = ProType.cardTitle, color = c.text)
                d.sections.forEach { (heading, body) ->
                    if (heading.isNotBlank()) Text(heading, style = ProType.label, color = c.text)
                    if (body.isNotBlank()) Text(body, style = ProType.small, color = c.text2)
                }
            }
        }

        // consent history panel
        Column(Modifier.clip(RoundedCornerShape(14.dp)).background(c.surface).border(1.dp, c.hairline, RoundedCornerShape(14.dp)).padding(12.dp)) {
            Text("Consent history", style = ProType.label, color = c.text)
            Spacer(Modifier.height(6.dp))
            val recents = remember { Consent.recentJson(ctx, 8) }
            if (recents.isEmpty()) {
                Text("No consent records yet.", style = ProType.small, color = c.text3)
            } else {
                recents.forEach { el ->
                    val o = el as? JsonObject ?: return@forEach
                    val at = ((o["at"] as? JsonPrimitive)?.contentOrNull ?: "").replace("T", " ").take(16)
                    val kind = (o["kind"] as? JsonPrimitive)?.contentOrNull ?: ""
                    Text("$at · $kind", style = ProType.small, color = c.text3)
                }
            }
            Spacer(Modifier.height(8.dp))
            var exported by remember { mutableStateOf(false) }
            if (exported) Text("Consent log exported to app storage", style = ProType.small, color = c.ok)
            P4Button("Export consent log", icon = "fa-download", style = BtnStyle.SECONDARY, minHeight = 36) {
                ProPrefs.put(ctx, "p4_consent_export", Consent.exportLog(ctx))
                exported = true
            }
        }

        P4Button("Close", style = BtnStyle.GHOST, modifier = Modifier.fillMaxWidth().padding(bottom = 100.dp), onClick = onClose)
    }
}
