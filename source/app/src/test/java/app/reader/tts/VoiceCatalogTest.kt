package app.reader.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM-side unit tests for the Batch 7 voice catalog helpers.
 *
 * These verify signature parsing and the grouping / search helpers
 * without touching the Android [android.speech.tts.TextToSpeech] engine —
 * which can't be instantiated on the JVM.
 */
class VoiceCatalogTest {

    @Test
    fun parseSignature_threeParts_returnsAllThree() {
        val (lang, name, engine) = VoiceInfo.parseSignature("en-US|en-US-x-sfg|com.google.android.tts")
        assertEquals("en-US", lang)
        assertEquals("en-US-x-sfg", name)
        assertEquals("com.google.android.tts", engine)
    }

    @Test
    fun parseSignature_missingEngine_returnsNullEngine() {
        // Older persisted signatures (Batch 2) didn't carry the engine part.
        val (lang, name, engine) = VoiceInfo.parseSignature("en-US|en-US-x-sfg")
        assertEquals("en-US", lang)
        assertEquals("en-US-x-sfg", name)
        assertNull(engine)
    }

    @Test
    fun parseSignature_blankPart_treatedAsNull() {
        val (lang, name, engine) = VoiceInfo.parseSignature("en-US||com.google.android.tts")
        assertEquals("en-US", lang)
        assertNull(name)
        assertEquals("com.google.android.tts", engine)
    }

    @Test
    fun groupByLanguage_groupsVoicesByLanguageTag() {
        val voices = listOf(
            voice("en-US", "en-A", "com.google.android.tts", "Google TTS"),
            voice("en-US", "en-B", "com.google.android.tts", "Google TTS"),
            voice("es-ES", "es-A", "com.samsung.SMT", "Samsung TTS"),
            voice("fr-FR", "fr-A", "com.samsung.SMT", "Samsung TTS")
        )
        val groups = VoiceCatalog.groupByLanguage(voices)
        assertEquals(3, groups.size)
        // English group has two voices
        val englishGroup = groups.first { it.languageTag == "en-US" }
        assertEquals(2, englishGroup.voices.size)
    }

    @Test
    fun groupByEngine_groupsByEnginePackageFirstThenLanguage() {
        val voices = listOf(
            voice("en-US", "en-A", "com.google.android.tts", "Google TTS"),
            voice("es-ES", "es-A", "com.samsung.SMT", "Samsung TTS"),
            voice("en-US", "en-B", "com.samsung.SMT", "Samsung TTS")
        )
        val groups = VoiceCatalog.groupByEngine(voices)
        assertEquals(2, groups.size)
        val googleGroup = groups.first { it.enginePackage == "com.google.android.tts" }
        assertEquals(1, googleGroup.languageGroups.size)
        assertEquals(1, googleGroup.languageGroups.first().voices.size)
        val samsungGroup = groups.first { it.enginePackage == "com.samsung.SMT" }
        assertEquals(2, samsungGroup.languageGroups.size)
    }

    @Test
    fun search_matchesOnVoiceName() {
        val voices = listOf(
            voice("en-US", "en-US-x-sfg", "com.google.android.tts", "Google TTS"),
            voice("en-US", "en-US-x-tpc", "com.google.android.tts", "Google TTS"),
            voice("es-ES", "es-ES-x-tpc", "com.samsung.SMT", "Samsung TTS")
        )
        val matches = VoiceCatalog.search(voices, "sfg")
        assertEquals(1, matches.size)
        assertEquals("en-US-x-sfg", matches[0].name)
    }

    @Test
    fun search_matchesOnEngineLabel() {
        val voices = listOf(
            voice("en-US", "en-A", "com.google.android.tts", "Google TTS"),
            voice("en-US", "en-B", "com.samsung.SMT", "Samsung TTS")
        )
        val matches = VoiceCatalog.search(voices, "samsung")
        assertEquals(1, matches.size)
        assertEquals("com.samsung.SMT", matches[0].enginePackage)
    }

    @Test
    fun search_emptyQuery_returnsAll() {
        val voices = listOf(
            voice("en-US", "en-A", "com.google.android.tts", "Google TTS"),
            voice("es-ES", "es-A", "com.samsung.SMT", "Samsung TTS")
        )
        val matches = VoiceCatalog.search(voices, "")
        assertEquals(2, matches.size)
    }

    private fun voice(
        lang: String,
        name: String,
        enginePkg: String,
        engineLabel: String
    ): VoiceInfo = VoiceInfo(
        languageTag = lang,
        name = name,
        enginePackage = enginePkg,
        engineLabel = engineLabel,
        localeDisplayName = lang,
        quality = 400,
        latency = 0,
        needsNetwork = false
    )
}
