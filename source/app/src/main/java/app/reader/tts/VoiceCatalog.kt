package app.reader.tts

import android.speech.tts.Voice
import java.util.Locale

/**
 * Lightweight view over a platform [Voice]. Stable, parcel-free, and trivially
 * testable — we never pass the Android [Voice] object across composable
 * recompositions because [Voice] holds binder references.
 *
 * Signature format: `<langTag>|<voiceName>|<enginePackage>`.
 * The engine part was added in Batch 7 because two engines (e.g. Google TTS
 * and Samsung TTS) can ship voices with identical names — including only
 * `langTag|voiceName` would make two distinct voices collide. EXPERT-MAP
 * #14 (Custom TTS Engine Author): voice identity must be unique per engine.
 */
data class VoiceInfo(
    val languageTag: String,
    val name: String,
    val enginePackage: String,
    val engineLabel: String,
    val localeDisplayName: String,
    val quality: Int,
    val latency: Int,
    val needsNetwork: Boolean
) {
    val signature: String get() = "$languageTag|$name|$enginePackage"

    companion object {
        fun from(v: Voice, enginePackage: String, engineLabel: String): VoiceInfo {
            val lang = v.locale?.toLanguageTag() ?: "und"
            val display = v.locale?.displayName?.ifBlank { lang } ?: lang
            return VoiceInfo(
                languageTag = lang,
                name = v.name,
                enginePackage = enginePackage,
                engineLabel = engineLabel,
                localeDisplayName = display,
                quality = v.quality,
                latency = v.latency,
                needsNetwork = v.isNetworkConnectionRequired
            )
        }

        /**
         * Best-effort parse of a signature back into its parts. Used by the
         * controller when applying a saved selection to the live engine —
         * we can't trust the persisted string to match anything in the
         * current voice list (the user may have uninstalled the engine),
         * so missing parts must be tolerated.
         */
        fun parseSignature(sig: String): Triple<String?, String?, String?> {
            val parts = sig.split('|')
            return Triple(
                parts.getOrNull(0)?.takeIf { it.isNotBlank() },
                parts.getOrNull(1)?.takeIf { it.isNotBlank() },
                parts.getOrNull(2)?.takeIf { it.isNotBlank() }
            )
        }
    }
}

/**
 * A grouping of voices under one language tag, for display in the voice
 * picker. Within each language, voices are sorted alphabetically by name.
 */
data class VoiceGroup(
    val languageTag: String,
    val languageDisplayName: String,
    val voices: List<VoiceInfo>
)

/**
 * A group of voices per engine — the second axis the picker exposes.
 * Within each engine, voices are grouped by language.
 */
data class EngineVoiceGroup(
    val enginePackage: String,
    val engineLabel: String,
    val languageGroups: List<VoiceGroup>
)

object VoiceCatalog {

    /** Group by language, returning the list ordered by display name. */
    fun groupByLanguage(voices: List<VoiceInfo>): List<VoiceGroup> {
        return voices
            .groupBy { it.languageTag }
            .map { (langTag, list) ->
                VoiceGroup(
                    languageTag = langTag,
                    languageDisplayName = list.firstOrNull()?.localeDisplayName ?: langTag,
                    voices = list.sortedWith(
                        compareBy({ it.engineLabel.lowercase(Locale.ROOT) }, { it.name.lowercase(Locale.ROOT) })
                    )
                )
            }
            .sortedBy { it.languageDisplayName.lowercase(Locale.ROOT) }
    }

    /** Group by engine, then within each engine by language. */
    fun groupByEngine(voices: List<VoiceInfo>): List<EngineVoiceGroup> {
        return voices
            .groupBy { it.enginePackage }
            .map { (pkg, list) ->
                EngineVoiceGroup(
                    enginePackage = pkg,
                    engineLabel = list.firstOrNull()?.engineLabel ?: pkg,
                    languageGroups = groupByLanguage(list)
                )
            }
            .sortedBy { it.engineLabel.lowercase(Locale.ROOT) }
    }

    /** Filter by a search query (matches on name, language, or engine label). */
    fun search(voices: List<VoiceInfo>, query: String): List<VoiceInfo> {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) return voices
        return voices.filter {
            it.name.lowercase(Locale.ROOT).contains(q) ||
                it.languageTag.lowercase(Locale.ROOT).contains(q) ||
                it.localeDisplayName.lowercase(Locale.ROOT).contains(q) ||
                it.engineLabel.lowercase(Locale.ROOT).contains(q)
        }
    }
}
