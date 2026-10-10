package com.peakform.fitness.ui

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Typeface
import android.util.Log
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

/**
 * FONT PIPELINE — hardened after the v1.1 launch crash
 * `Resources$NotFoundException: Font resource ID #0x7f05000f could not be
 * retrieved` (Compose ResourceFont, Blocking strategy, first layout on
 * SM-A145M / Android 14).
 *
 * Root-cause class: v1.1 resolved res/font entries BY RESOURCE ID at
 * text-layout time; a resource table whose font entries were rewritten by
 * the aapt2 "optimize" pass (resources.arsc name/path collapsing) fails
 * blocking font resolution on some devices and kills the app before any UI.
 *
 * Hardening — three independent layers:
 *  1. BUILD — no minify, no shrinkResources, android.enableResourceOptimizations
 *     =false, TTFs stored uncompressed; resources.arsc stays plain and
 *     deterministic (gradle.properties + app/build.gradle.kts).
 *  2. RUNTIME — Compose fonts are created from assets/fonts TTFs BY PATH
 *     (Font(path, assetManager, weight)): no font resource IDs exist in the
 *     rendering path at all, so the crash class is eliminated by design.
 *     res/font keeps byte-identical TTF copies for the resource-table audit
 *     (no XML family descriptors — those are the ID-resolved path).
 *  3. DIAGNOSIS — launch audit: every asset font is pre-resolved via
 *     runCatching (magic-byte check + Typeface.createFromAsset); a missing
 *     font degrades to a system fallback and is logged under `FontAudit`:
 *     `adb logcat -s FontAudit` prints a per-font OK/FALLBACK table.
 */
object FontVault {

    private const val TAG = "FontAudit"
    private const val DIR = "fonts/"

    private class Entry(val key: String, val file: String, val weight: FontWeight, val style: FontStyle = FontStyle.Normal)

    private val bodyEntries = listOf(
        Entry("inter_400", "inter_400.ttf", FontWeight.W400),
        Entry("inter_500", "inter_500.ttf", FontWeight.W500),
        Entry("inter_600", "inter_600.ttf", FontWeight.W600),
        Entry("inter_700", "inter_700.ttf", FontWeight.W700),
    )
    private val displayEntries = listOf(
        Entry("spacegrotesk_500", "spacegrotesk_500.ttf", FontWeight.W500),
        Entry("spacegrotesk_600", "spacegrotesk_600.ttf", FontWeight.W600),
        Entry("spacegrotesk_700", "spacegrotesk_700.ttf", FontWeight.W700),
    )
    private val monoEntries = listOf(
        Entry("jetbrainsmono_400", "jetbrainsmono_400.ttf", FontWeight.W400),
        Entry("jetbrainsmono_500", "jetbrainsmono_500.ttf", FontWeight.W500),
        Entry("jetbrainsmono_700", "jetbrainsmono_700.ttf", FontWeight.W700),
    )
    private val iconEntries = listOf(
        Entry("fa_solid", "fa_solid.ttf", FontWeight.W400),
        Entry("fa_regular", "fa_regular.ttf", FontWeight.W400),
        Entry("fa_brands", "fa_brands.ttf", FontWeight.W400),
    )

    /** System fallbacks — set at object creation so pre-init renders never crash. */
    @Volatile var body: FontFamily = FontFamily.SansSerif; private set
    @Volatile var display: FontFamily = FontFamily.SansSerif; private set
    @Volatile var mono: FontFamily = FontFamily.Monospace; private set
    @Volatile var faSolid: FontFamily = FontFamily.Default; private set
    @Volatile var faRegular: FontFamily = FontFamily.Default; private set
    @Volatile var faBrands: FontFamily = FontFamily.Default; private set

    @Volatile private var initialized = false

    /** Called once from ProApp.onCreate — BEFORE any Compose content. */
    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val assets = context.applicationContext.assets
        val ok = mutableListOf<String>()
        val bad = mutableListOf<String>()

        fun resolve(entries: List<Entry>, okFb: FontFamily, badFb: FontFamily): FontFamily {
            val fonts = entries.mapNotNull { e ->
                audit(assets, e, ok, bad)
                try {
                    Font(DIR + e.file, assets, e.weight, e.style)
                } catch (t: Throwable) {
                    Log.w(TAG, "compose-font ${e.key} failed: ${t.message}")
                    null
                }
            }
            return if (fonts.size == entries.size) FontFamily(*fonts.toTypedArray()) else badFb
        }

        body = resolve(bodyEntries, FontFamily.SansSerif, FontFamily.SansSerif)
        display = resolve(displayEntries, FontFamily.SansSerif, FontFamily.SansSerif)
        mono = resolve(monoEntries, FontFamily.Monospace, FontFamily.Monospace)
        faSolid = resolve(iconEntries.filter { it.key == "fa_solid" }, FontFamily.Default, FontFamily.Default)
        faRegular = resolve(iconEntries.filter { it.key == "fa_regular" }, FontFamily.Default, FontFamily.Default)
        faBrands = resolve(iconEntries.filter { it.key == "fa_brands" }, FontFamily.Default, FontFamily.Default)

        val status = ok.sorted().joinToString("") { "✓${it} " } + "| " + bad.sorted().joinToString("") { "✗${it} " }
        Log.i(TAG, "fonts[${ok.size}/${ok.size + bad.size}] $status")
    }

    /** Magic-byte check + real Typeface load; appends to ok/bad for the summary line. */
    private fun audit(assets: AssetManager, e: Entry, ok: MutableList<String>, bad: MutableList<String>) {
        try {
            assets.open(DIR + e.file).use { s ->
                val head = ByteArray(4)
                if (s.read(head) != 4) throw IllegalStateException("short read")
                val valid = (head[0].toInt() == 0 && head[1].toInt() == 1 && head[2].toInt() == 0 && head[3].toInt() == 0) ||
                    head[0] == 't'.code.toByte() || head[0] == 'O'.code.toByte()
                if (!valid) throw IllegalStateException("bad magic ${head.joinToString(" ") { "%02x".format(it) }}")
            }
            runCatching { Typeface.createFromAsset(assets, DIR + e.file) }
                .onFailure { throw IllegalStateException("typeface: ${it.message}") }
            ok.add(e.key)
        } catch (t: Throwable) {
            bad.add(e.key)
            Log.w(TAG, "FALLBACK ${e.key}: ${t.message}")
        }
    }
}
