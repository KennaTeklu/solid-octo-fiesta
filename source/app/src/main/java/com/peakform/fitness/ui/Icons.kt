package com.peakform.fitness.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.peakform.fitness.R
import com.peakform.fitness.ProLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * FaIcon — renders FontAwesome 6 glyphs natively from the bundled FA TTFs.
 * Map: assets/data/fa_map.json (2408 entries) class → codepoint.
 * Prefix rules: fab→brands font, far→regular font, everything else solid.
 */
object Fa {
    val map: Map<String, String> by lazy {
        try {
            val ctx = appContext ?: return@lazy emptyMap()
            val raw = ctx.assets.open("data/fa_map.json").bufferedReader().use { it.readText() }
            val obj = Json.parseToJsonElement(raw) as JsonObject
            obj.mapValues { it.value.jsonPrimitive.content }
        } catch (e: Exception) {
            ProLog.e("FA", "fa_map load failed: ${e.message}")
            emptyMap()
        }
    }

    @Volatile var appContext: Context? = null

    val BRANDS = setOf("fa-x-twitter", "fa-facebook", "fa-whatsapp", "fa-telegram-plane", "fa-reddit-alien")
    val REGULAR = setOf("fa-calendar")
    val SOLID: FontFamily get() = FontVault.faSolid
    val BRANDS_FONT: FontFamily get() = FontVault.faBrands
    val REGULAR_FONT: FontFamily get() = FontVault.faRegular

    fun glyph(cls: String): String? {
        val cp = map[cls] ?: return null
        val code = cp.toIntOrNull(16) ?: return null
        return String(Character.toChars(code))
    }

    fun familyFor(cls: String): FontFamily = when {
        BRANDS.contains(cls) -> BRANDS_FONT
        REGULAR.contains(cls) -> REGULAR_FONT
        else -> SOLID
    }
}

/** FontAwesome icon rendered as text glyph. */
@Composable
fun FaIcon(cls: String, modifier: Modifier = Modifier, size: TextUnit = 16.sp, tint: Color = LocalProColors.current.text, contentDescription: String? = null) {
    val ch = remember(cls) { Fa.glyph(cls) }
    Box(modifier) {
        if (ch != null) {
            Text(
                text = ch,
                fontFamily = Fa.familyFor(cls),
                fontWeight = FontWeight.Normal,
                fontSize = size,
                color = tint,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
            )
        } else {
            // Unknown glyph → invisible placeholder keeps layout stable
            Text(" ", fontSize = size)
        }
    }
}

@Composable
fun FaIcon(cls: String, modifier: Modifier = Modifier, size: Dp, tint: Color) {
    FaIcon(cls, modifier, with(androidx.compose.ui.platform.LocalDensity.current) { size.toSp() }, tint)
}
