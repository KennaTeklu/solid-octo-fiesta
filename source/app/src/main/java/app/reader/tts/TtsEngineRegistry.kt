package app.reader.tts

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Lists the TTS engines installed on the device. On API 30+ this requires a
 * `<queries>` declaration in the manifest for the
 * `android.intent.action.TTS_DATA_SERVICE` action — see AndroidManifest.xml
 * for that block.
 *
 * Each engine is identified by its package name (stable across reboots, so
 * we can persist a selection).
 *
 * EXPERT-MAP #13 (TTS Engine Integration) — engines are the top-level
 * "voice source": each one returns a different list of voices when the
 * TextToSpeech instance is constructed with `engine = packageName`.
 * Switching engine reinitialises the controller; switching voice within
 * an engine does not.
 */
data class TtsEngineInfo(
    val packageName: String,
    val label: String,
    val isDefault: Boolean
) {
    companion object {
        /** System default engine package, queried on first use. */
        fun defaultEnginePackage(context: Context): String? =
            try { TextToSpeech(context) { /* discard */ }.defaultEngine }
            catch (_: Throwable) { null }
    }
}

object TtsEngineRegistry {

    fun list(context: Context): List<TtsEngineInfo> {
        val pm = context.packageManager
        val intent = Intent("android.intent.action.TTS_DATA_SERVICE")
        // TTS_DATA_SERVICE is a service action — engines declare <service>
        // with this intent-filter. We must query services, not activities.
        val resolved: List<ResolveInfo> =
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentServices(intent, 0) ?: emptyList()
            }
        val defaultPkg = TtsEngineInfo.defaultEnginePackage(context)
        return resolved
            .mapNotNull { ri ->
                val pkg = ri.serviceInfo?.packageName ?: return@mapNotNull null
                val label = ri.loadLabel(pm).toString().ifBlank { pkg }
                TtsEngineInfo(
                    packageName = pkg,
                    label = label,
                    isDefault = pkg == defaultPkg
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase(Locale.ROOT) }
    }
}
