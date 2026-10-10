package com.peakform.fitness.ui.screens

import android.content.Intent
import android.net.Uri
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
import com.peakform.fitness.core.ProJson
import com.peakform.fitness.ProLog
import com.peakform.fitness.ui.*
import com.peakform.fitness.ui.components.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * MuscleWiki — local muscle knowledge base (assets/data/muscles.json) with
 * Wikipedia lookup and Google web/images fallbacks via the BROWSER (no INTERNET
 * permission — the browser does the fetching; parity with legacy showMuscleImage
 * PF L36694–36755 while keeping the native privacy posture).
 */
object MuscleKB {
    private var cache: Map<String, JsonObject> = emptyMap()

    fun load(ctx: android.content.Context): Map<String, JsonObject> {
        if (cache.isNotEmpty()) return cache
        cache = try {
            val raw = ctx.assets.open("data/muscles.json").bufferedReader().use { it.readText() }
            val obj = ProJson.json.parseToJsonElement(raw).jsonObject
            obj.mapValues { (_, v) -> v as? JsonObject ?: JsonObject(emptyMap()) }
        } catch (e: Exception) {
            ProLog.e("WIKI", "muscles load failed: ${e.message}")
            emptyMap()
        }
        return cache
    }

    /** Local KB blurb for a muscle key (display name, category, notes). */
    fun blurb(ctx: android.content.Context, muscleKey: String): Triple<String, String, String>? {
        val kb = load(ctx)
        val direct = kb[muscleKey] ?: kb.entries.firstOrNull {
            it.key.replace('_', ' ').equals(muscleKey.replace('_', ' '), ignoreCase = true)
        }?.value ?: return null
        val display = (direct["display"] as? JsonPrimitive)?.contentOrNull
            ?: (direct["name"] as? JsonPrimitive)?.contentOrNull ?: muscleKey
        val category = (direct["category"] as? JsonPrimitive)?.contentOrNull ?: ""
        val notes = (direct["notes"] as? JsonPrimitive)?.contentOrNull ?: ""
        return Triple(display, category, notes)
    }
}

@Composable
fun MuscleWikiSheet(muscleKey: String, onClose: () -> Unit) {
    val c = LocalProColors.current
    val ctx = LocalContext.current
    val blurb = remember(muscleKey) { MuscleKB.blurb(ctx, muscleKey) }
    val display = blurb?.first ?: muscleKey.replace('_', ' ').replaceFirstChar { it.uppercase() }
    val wikiTitle = display.replace(' ', '_')
    val wikiUrl = "https://en.wikipedia.org/wiki/$wikiTitle"
    val webUrl = "https://www.google.com/search?q=${Uri.encode("$display muscle anatomy")}"
    val imgUrl = "https://www.google.com/search?q=${Uri.encode("$display muscle anatomy")}&tbm=isch"

    fun open(url: String) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            ProLog.w("WIKI", "no browser: ${e.message}")
        }
    }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FaIcon("fa-magnifying-glass", size = 15.sp, tint = c.accent)
            Spacer(Modifier.width(8.dp))
            Text(display, style = ProType.cardTitle, color = c.text, modifier = Modifier.weight(1f))
            Text("close", style = ProType.small, color = c.accent, modifier = Modifier.clickable { onClose() })
        }
        if (blurb != null) {
            if (blurb.second.isNotBlank()) Text("Category: ${blurb.second.replace('_', ' ')}", style = ProType.small, color = c.text3)
            if (blurb.third.isNotBlank()) Text(blurb.third, style = ProType.body2, color = c.text2)
        }
        Text("🔎 Searching Wikipedia for muscle anatomy… tap a source below", style = ProType.small, color = c.text3)
        P4Button("📖 Read on Wikipedia", icon = "fa-book", style = BtnStyle.INFO, modifier = Modifier.fillMaxWidth()) { open(wikiUrl) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            P4Button("🔍 Google Search", style = BtnStyle.SECONDARY, modifier = Modifier.weight(1f)) { open(webUrl) }
            P4Button("📷 Google Images", style = BtnStyle.SECONDARY, modifier = Modifier.weight(1f)) { open(imgUrl) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 10.dp)) {
            P4Button("👍 Helpful", style = BtnStyle.GHOST, modifier = Modifier.weight(1f)) { onClose() }
        }
    }
}
