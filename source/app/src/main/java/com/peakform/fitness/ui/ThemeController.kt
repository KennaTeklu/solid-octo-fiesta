package com.peakform.fitness.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.peakform.fitness.core.ProPrefs

/**
 * ThemeController — runtime dark/light + accent + Material You state.
 *
 * BATCH-4B item 1 root cause: previously `accent()` was a plain function and
 * `p4_dynamic` was read out of SharedPreferences as a one-shot non-observed
 * String. When `p4_dynamic` was on (even briefly), `ProTheme(dynamic = true)`
 * would discard the user's chosen accent in favour of the Material You
 * palette — so tapping a swatch highlighted it but did not recolor the app.
 *
 * Structural fix:
 *  - every piece of theme state lives here as Compose state (`dark`,
 *    `accentId`, `dynamic`); all read through these properties, not via a
 *    function call or a prefs lookup.
 *  - `set(mode, accent, ctx)` and `setDynamic(on, ctx)` persist to prefs
 *    themselves — no caller can forget to write `p4_theme` / `p4_dynamic`.
 *  - Material You defaults OFF so the user's selected accent wins by default.
 *    When Material You is ON, the Settings card surfaces a hint and dims the
 *    accent palette so the user understands why their color doesn't apply.
 */
object ThemeController {
    var dark by mutableStateOf(true)
        private set
    var accentId by mutableStateOf("blue")
        private set
    /** Material You flag — observable; defaults OFF so the user's chosen accent wins. */
    var dynamic by mutableStateOf(false)
        private set

    fun accent(): AccentDef = ACCENTS.firstOrNull { it.id == accentId } ?: ACCENTS[0]

    /** Cold-start hydration: read `p4_theme` JSON + `p4_dynamic` once. */
    fun load(initial: String?) {
        val raw = initial ?: return
        try {
            val o = com.peakform.fitness.core.ProJson.json.parseToJsonElement(raw) as? kotlinx.serialization.json.JsonObject ?: return
            val m = (o["mode"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            val a = (o["accent"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            if (m == "light") dark = false
            if (m == "dark") dark = true
            if (a != null && ACCENTS.any { it.id == a }) accentId = a
        } catch (_: Exception) {}
    }

    fun loadDynamic(initial: String?) {
        dynamic = initial == "on"
    }

    /**
     * Apply mode + accent to runtime state AND persist to `p4_theme`.
     * The caller no longer needs to write `p4_theme` themselves — they cannot forget.
     */
    fun set(mode: String, accent: String, ctx: Context? = null) {
        dark = mode != "light"
        if (ACCENTS.any { it.id == accent }) accentId = accent
        ctx?.let { ProPrefs.put(it, "p4_theme", currentJson()) }
    }

    /** Toggle Material You; persists to `p4_dynamic` and refreshes the runtime flag. */
    fun setDynamic(on: Boolean, ctx: Context? = null) {
        dynamic = on
        ctx?.let { ProPrefs.put(it, "p4_dynamic", if (on) "on" else "off") }
    }

    /** Persist the current mode + accent to `p4_theme` via ProPrefs. */
    fun persist(ctx: Context) {
        ProPrefs.put(ctx, "p4_theme", currentJson())
    }

    /** The JSON blob to write to `p4_theme`. */
    fun currentJson(): String = """{"mode":"${if (dark) "dark" else "light"}","accent":"$accentId"}"""
}
