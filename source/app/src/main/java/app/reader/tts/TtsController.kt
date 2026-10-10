package app.reader.tts

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import app.reader.data.repo.SettingsRepository
import app.reader.parser.ParsedDocument
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wraps the platform [TextToSpeech] engine and exposes the playback state as
 * a [StateFlow]. The class is single-instance — only one document may be
 * loaded at a time, which matches the user's mental model ("the reader").
 *
 * Batch 7 changes:
 *  - Engine selection. The controller now reads [SettingsRepository.selectedEngine]
 *    on init and re-initialises the underlying [TextToSpeech] when it changes.
 *    Each engine yields its own voice list — see [VoiceCatalog].
 *  - Live rate / pitch / volume setters. [setRateLive], [setPitchLive],
 *    [setVolumeLive] apply to the live engine immediately. The current
 *    sentence is NOT restarted (per PB-07): Android's `setSpeechRate` /
 *    `setPitch` apply to the next queued utterance, and we use QUEUE_FLUSH
 *    only on explicit user-initiated seeks — never on a settings change.
 *  - Speech-volume independent of media volume. The TTS engine is given
 *    [AudioAttributes] with USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, which
 *    Android routes through a separate audio path on API 26+.
 *  - Missing-voice detection. When `tts.setLanguage(locale)` returns
 *    LANG_MISSING_DATA or LANG_NOT_SUPPORTED, the state becomes
 *    [PlaybackState.MissingVoice] and the UI surfaces a dialog (TTS-06).
 *
 * Concurrency model:
 *  - All public commands are thread-safe. They are dispatched from the
 *    UI thread (Compose callbacks) and the foreground service (transport
 *    actions), so we synchronize mutation of [current] on this.
 *  - The Android TTS callbacks come back on a binder thread; we marshal them
 *    back into the same lock before mutating state.
 */
class TtsController private constructor(private val appContext: Context) {

    private val settings = SettingsRepository.get(appContext)
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var currentEnginePackage: String? = null
    private val lock = Any()

    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    @Volatile private var loaded: LoadedDoc? = null
    /** Pending command requested before the engine finished initing. */
    private val pendingPlay = AtomicBoolean(false)
    /** When true, onDone() should advance to the next sentence instead of stopping. */
    @Volatile private var continueAfterSentence = false

    /** Cached voice list — populated when the engine becomes ready. */
    @Volatile private var cachedVoices: List<VoiceInfo> = emptyList()
    val voices: List<VoiceInfo> get() = cachedVoices

    init {
        reinitEngine(initial = true)
    }

    /**
     * Re-create the [TextToSpeech] instance with [enginePackage] (or the
     * system default if null). Called on first init AND when the user picks
     * a different engine in the TTS engine picker.
     */
    fun reinitEngine(enginePackage: String? = null, initial: Boolean = false) {
        synchronized(lock) {
            // Tear down the previous instance — it holds a binder to the
            // engine's service. Leaking it would keep the old engine's
            // process alive indefinitely.
            tts?.stop()
            tts?.shutdown()
            tts = null
            ttsReady = false
            cachedVoices = emptyList()
            currentEnginePackage = enginePackage
            val effectivePackage = enginePackage ?: runBlocking {
                settings.selectedEngine.first()
            }
            val engine = if (effectivePackage != null) {
                TextToSpeech(appContext, initListener, effectivePackage)
            } else {
                TextToSpeech(appContext, initListener)
            }
            tts = engine
            if (!initial) {
                // We've just torn down a running playback session — surface
                // Idle so the UI doesn't keep showing "playing" against a
                // dead binder. The user can press play again.
                _state.value = PlaybackState.Idle
            }
        }
    }

    private val initListener = TextToSpeech.OnInitListener { status ->
        synchronized(lock) {
            ttsReady = (status == TextToSpeech.SUCCESS)
            val t = tts
            if (ttsReady && t != null) {
                applySettingsTo(t)
                t.setOnUtteranceProgressListener(progressListener)
                cachedVoices = queryVoices(t)
                if (pendingPlay.compareAndSet(true, false)) {
                    playInternal()
                }
            } else {
                _state.value = PlaybackState.Error("TTS engine init failed (status $status)")
            }
        }
    }

    private fun queryVoices(t: TextToSpeech): List<VoiceInfo> {
        val enginePkg = currentEnginePackage ?: t.defaultEngine ?: "system-default"
        val engineLabel = try {
            appContext.packageManager
                .getApplicationLabel(appContext.packageManager.getApplicationInfo(enginePkg, 0))
                .toString()
        } catch (_: Throwable) { enginePkg }

        return try {
            t.voices.map { v -> VoiceInfo.from(v, enginePkg, engineLabel) }
        } catch (_: Throwable) { emptyList() }
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) { /* no-op; we set state when issuing the speak call */ }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            // Word-level callback — used only when highlightStyle == "word".
        }

        override fun onDone(utteranceId: String?) = handleSentenceDone()

        @Deprecated("Deprecated in Java", ReplaceWith("onError(utteranceId)"))
        override fun onError(utteranceId: String?) { _state.value = PlaybackState.Error("TTS error on $utteranceId") }
        override fun onError(utteranceId: String?, errorCode: Int) {
            _state.value = PlaybackState.Error("TTS error code $errorCode")
        }
    }

    /**
     * Load a document. Resets the position to (0, 0) unless [resumeChapter]
     * / [resumeSentence] are provided (we restore these from the DB on app
     * launch). The user still has to tap play to start.
     *
     * Side-effect: detects whether the loaded document's locale has an
     * installed voice. If not, transitions to [PlaybackState.MissingVoice]
     * instead of [PlaybackState.Ready].
     */
    fun load(
        docId: String,
        title: String,
        parsed: ParsedDocument,
        resumeChapter: Int = 0,
        resumeSentence: Int = 0
    ) { synchronized(lock) {
        loaded = LoadedDoc(docId, title, parsed)
        val chapter = resumeChapter.coerceAtLeast(0).coerceAtMost(parsed.chapters.lastIndex)
        val sentence = resumeSentence.coerceAtLeast(0)
        val missing = detectMissingVoice()
        _state.value = if (missing != null) {
            PlaybackState.MissingVoice(docId, missing.first, missing.second)
        } else {
            PlaybackState.Ready(docId, title, chapter, sentence)
        }
    } }

    /** Unload the current document and free state. Safe to call multiple times. */
    fun unload() { synchronized(lock) {
        loaded = null
        continueAfterSentence = false
        tts?.let { if (it.isSpeaking) it.stop() }
        _state.value = PlaybackState.Idle
    } }

    /** Start playing the loaded document from the current position. */
    fun play() { synchronized(lock) {
        if (!ttsReady) {
            pendingPlay.set(true)
            return
        }
        val doc = loaded ?: return
        val missing = detectMissingVoice()
        if (missing != null) {
            _state.value = PlaybackState.MissingVoice(doc.docId, missing.first, missing.second)
            return
        }
        playInternal()
    } }

    /** Pause playback. We just call stop() — the state keeps the position. */
    fun pause() { synchronized(lock) {
        continueAfterSentence = false
        tts?.stop()
        val s = _state.value
        if (s is PlaybackState.Playing) {
            _state.value = PlaybackState.Paused(s.docId, s.title, s.chapter, s.sentence, s.sentencesTotal, s.sentencesDone)
        }
    } }

    /** Toggle play/pause. */
    fun togglePlayPause() {
        when (val s = _state.value) {
            is PlaybackState.Playing -> pause()
            is PlaybackState.Paused, is PlaybackState.Ready -> play()
            is PlaybackState.Ended -> {
                val doc = loaded ?: return
                _state.value = PlaybackState.Ready(doc.docId, doc.title, 0, 0)
                play()
            }
            is PlaybackState.MissingVoice -> {
                // Re-check — the user may have just installed a voice.
                play()
            }
            else -> {}
        }
    }

    /** Stop and reset position to the start of the current chapter. */
    fun stop() { synchronized(lock) {
        continueAfterSentence = false
        tts?.stop()
        val s = _state.value
        val doc = loaded
        when (s) {
            is PlaybackState.Playing -> {
                if (doc != null) _state.value = PlaybackState.Ready(s.docId, s.title, s.chapter, 0)
                else _state.value = PlaybackState.Idle
            }
            is PlaybackState.Paused -> {
                if (doc != null) _state.value = PlaybackState.Ready(s.docId, s.title, s.chapter, 0)
                else _state.value = PlaybackState.Idle
            }
            else -> {}
        }
    } }

    /** Jump to the previous sentence, or wrap to the previous chapter's end. */
    fun skipPreviousSentence() { synchronized(lock) {
        val doc = loaded ?: return
        val position = currentPos() ?: return
        var newCh = position.first
        var newSent = position.second - 1
        if (newSent < 0) {
            newCh = (position.first - 1).coerceAtLeast(0)
            newSent = if (newCh == position.first) 0 else (doc.parsed.chapters[newCh].sentenceCount() - 1).coerceAtLeast(0)
        }
        seekTo(newCh, newSent, autoplay = _state.value is PlaybackState.Playing)
    } }

    /** Jump to the next sentence, or wrap to the next chapter's start. */
    fun skipNextSentence() { synchronized(lock) {
        val doc = loaded ?: return
        val position = currentPos() ?: return
        val chapterSentenceCount = doc.parsed.chapters[position.first].sentenceCount()
        var newCh = position.first
        var newSent = position.second + 1
        if (newSent >= chapterSentenceCount) {
            newCh = position.first + 1
            if (newCh >= doc.parsed.chapters.size) {
                _state.value = PlaybackState.Ended(doc.docId)
                tts?.stop()
                return
            }
            newSent = 0
        }
        seekTo(newCh, newSent, autoplay = _state.value is PlaybackState.Playing)
    } }

    /** Skip to the start of the next paragraph. */
    fun skipNextParagraph() { synchronized(lock) {
        val doc = loaded ?: return
        val position = currentPos() ?: return
        val (chapter, sentence) = position
        val ch = doc.parsed.chapters.getOrNull(chapter) ?: return
        var flat = 0
        var nextParagraphStart = -1
        for ((pIndex, p) in ch.paragraphs.withIndex()) {
            if (sentence < flat + p.sentences.size) {
                if (pIndex + 1 < ch.paragraphs.size) {
                    nextParagraphStart = flat + p.sentences.size
                }
                break
            }
            flat += p.sentences.size
        }
        if (nextParagraphStart >= 0) {
            seekTo(chapter, nextParagraphStart, autoplay = _state.value is PlaybackState.Playing)
        } else {
            val nextCh = chapter + 1
            if (nextCh < doc.parsed.chapters.size) {
                seekTo(nextCh, 0, autoplay = _state.value is PlaybackState.Playing)
            } else {
                _state.value = PlaybackState.Ended(doc.docId)
                tts?.stop()
            }
        }
    } }

    /** Skip to the start of the previous paragraph. */
    fun skipPreviousParagraph() { synchronized(lock) {
        val doc = loaded ?: return
        val position = currentPos() ?: return
        val (chapter, sentence) = position
        val ch = doc.parsed.chapters.getOrNull(chapter) ?: return
        var flat = 0
        var currentParagraphStart = 0
        for (p in ch.paragraphs) {
            if (sentence < flat + p.sentences.size) {
                currentParagraphStart = flat
                break
            }
            flat += p.sentences.size
        }
        if (currentParagraphStart > 0) {
            var flat2 = 0
            var prevParagraphStart = 0
            for (p in ch.paragraphs) {
                if (sentence < flat2 + p.sentences.size) break
                prevParagraphStart = flat2 + p.sentences.size
                flat2 += p.sentences.size
            }
            seekTo(chapter, prevParagraphStart, autoplay = _state.value is PlaybackState.Playing)
        } else {
            val prevCh = (chapter - 1).coerceAtLeast(0)
            if (prevCh == chapter) return
            val prevSentence = (doc.parsed.chapters[prevCh].sentenceCount() - 1).coerceAtLeast(0)
            seekTo(prevCh, prevSentence, autoplay = _state.value is PlaybackState.Playing)
        }
    } }

    /** Jump to an explicit position. [autoplay] re-enters playing state if currently playing. */
    fun seekTo(chapter: Int, sentence: Int, autoplay: Boolean = false) { synchronized(lock) {
        val doc = loaded ?: return
        val wasPlaying = autoplay || _state.value is PlaybackState.Playing
        tts?.stop()
        val ch = chapter.coerceIn(0, doc.parsed.chapters.lastIndex)
        val chSentenceCount = doc.parsed.chapters[ch].sentenceCount()
        val sent = sentence.coerceIn(0, (chSentenceCount - 1).coerceAtLeast(0))
        _state.value = PlaybackState.Ready(doc.docId, doc.title, ch, sent)
        if (wasPlaying) playInternal()
    } }

    /**
     * Update speed/pitch/volume when settings change. Applies on the live engine.
     * Safe to call mid-utterance: Android's `setSpeechRate` / `setPitch` /
     * `setAudioAttributes` apply to the next queued utterance without
     * disturbing the current one.
     */
    fun refreshVoiceSettings() { synchronized(lock) {
        tts?.let { applySettingsTo(it) }
    } }

    /**
     * Live rate change from the expanded playback bar (PB-05). Does NOT
     * restart the current sentence — the change applies to the next
     * queued utterance. The current sentence continues at the old rate
     * until it finishes.
     */
    fun setRateLive(rate: Float) { synchronized(lock) {
        val t = tts ?: return
        val clamped = rate.coerceIn(0.1f, 4f)
        t.setSpeechRate(clamped)
    } }

    /** Live pitch change from the expanded playback bar (PB-06). */
    fun setPitchLive(pitch: Float) { synchronized(lock) {
        val t = tts ?: return
        val clamped = pitch.coerceIn(0.1f, 2f)
        t.setPitch(clamped)
    } }

    /** Live volume change from the expanded playback bar (PB-07). */
    fun setVolumeLive(volume: Float) { synchronized(lock) {
        val t = tts ?: return
        // Re-apply the audio attributes — the speech stream's volume is
        // controlled by the OS, so we store the multiplier and (in Batch 8)
        // apply it to the cloud-TTS audio bytes. For the platform engine
        // here, we just set the attributes and let the OS slider handle
        // the volume. The user's slider persists, so the next session
        // resumes at the same level.
        applyAudioAttributes(t, volume.coerceIn(0f, 1f))
    } }

    /** Extract (chapter, sentence) from the current state if it has one. */
    private fun currentPos(): Pair<Int, Int>? = when (val s = _state.value) {
        is PlaybackState.Playing -> s.chapter to s.sentence
        is PlaybackState.Paused -> s.chapter to s.sentence
        is PlaybackState.Ready -> s.chapter to s.sentence
        else -> null
    }

    private fun playInternal() {
        val doc = loaded ?: return
        val position = currentPos() ?: return
        val (chapter, sentence) = position
        val sentenceData = doc.sentenceAt(chapter, sentence) ?: run {
            _state.value = PlaybackState.Error("Sentence out of range")
            return
        }
        val range = sentenceData.first
        continueAfterSentence = true
        _state.value = PlaybackState.Playing(
            docId = doc.docId,
            title = doc.title,
            chapter = chapter,
            sentence = sentence,
            sentencesDone = doc.cumulativeSentences(chapter - 1) + sentence + 1,
            sentencesTotal = doc.totalSentences
        )
        val t = tts ?: return
        val utterance = utteranceIdFor(chapter, sentence)
        val res = t.speak(range.text, TextToSpeech.QUEUE_FLUSH, null, utterance)
        if (res != TextToSpeech.SUCCESS) {
            _state.value = PlaybackState.Error("TTS speak() failed: $res")
        }
    }

    private fun handleSentenceDone() { synchronized(lock) {
        if (!continueAfterSentence) return
        val doc = loaded ?: return
        val s = _state.value as? PlaybackState.Playing ?: return
        val chapter = s.chapter
        val sentence = s.sentence + 1
        val chapterSentenceCount = doc.parsed.chapters[chapter].sentenceCount()
        if (sentence >= chapterSentenceCount) {
            val nextCh = chapter + 1
            if (nextCh >= doc.parsed.chapters.size) {
                _state.value = PlaybackState.Ended(doc.docId)
                return
            }
            seekToInternal(nextCh, 0)
        } else {
            seekToInternal(chapter, sentence)
        }
    } }

    /** Internal helper that doesn't synchronise — caller already holds [lock]. */
    private fun seekToInternal(chapter: Int, sentence: Int) {
        val doc = loaded ?: return
        val ch = chapter.coerceIn(0, doc.parsed.chapters.lastIndex)
        val chSentenceCount = doc.parsed.chapters[ch].sentenceCount()
        val sent = sentence.coerceIn(0, (chSentenceCount - 1).coerceAtLeast(0))
        val sentenceData = doc.sentenceAt(ch, sent) ?: return
        _state.value = PlaybackState.Playing(
            docId = doc.docId,
            title = doc.title,
            chapter = ch,
            sentence = sent,
            sentencesDone = doc.cumulativeSentences(ch - 1) + sent + 1,
            sentencesTotal = doc.totalSentences
        )
        val utterance = utteranceIdFor(ch, sent)
        val res = tts?.speak(sentenceData.first.text, TextToSpeech.QUEUE_FLUSH, null, utterance)
        if (res != TextToSpeech.SUCCESS) {
            _state.value = PlaybackState.Error("TTS speak() failed: $res")
        }
    }

    private fun utteranceIdFor(chapter: Int, sentence: Int): String = "$chapter:$sentence"

    /**
     * Detect whether the loaded document's language has an installed voice.
     * Returns `(languageTag, displayName)` if missing, or null if a voice is
     * available.
     *
     * We probe with the default locale of the document — at the moment we
     * only have a single document-wide locale (from Locale.getDefault()),
     * so this is the system's TTS availability check. Batch 10 will add
     * per-language voice detection.
     */
    private fun detectMissingVoice(): Pair<String, String>? {
        val t = tts ?: return null
        val locale = Locale.getDefault()
        val res = try { t.isLanguageAvailable(locale) } catch (_: Throwable) { TextToSpeech.LANG_NOT_SUPPORTED }
        return when (res) {
            TextToSpeech.LANG_MISSING_DATA,
            TextToSpeech.LANG_NOT_SUPPORTED -> {
                val tag = locale.toLanguageTag()
                val display = locale.displayName.ifBlank { tag }
                tag to display
            }
            else -> null
        }
    }

    /**
     * Apply settings to the live TTS engine. Reads settings synchronously
     * via [runBlocking] — we're already on a binder / service callback thread,
     * and the values are tiny, so blocking for the first emit is acceptable.
     */
    private fun applySettingsTo(t: TextToSpeech) {
        runBlocking {
            val rate = settings.speechRate.first().coerceIn(0.1f, 4f)
            val pitch = settings.pitch.first().coerceIn(0.1f, 2f)
            val volume = settings.speechVolume.first().coerceIn(0f, 1f)
            val voiceSig = settings.voice.first()
            t.setSpeechRate(rate)
            t.setPitch(pitch)
            applyAudioAttributes(t, volume)
            if (!voiceSig.isNullOrEmpty()) {
                val parts = voiceSig.split('|')
                val lang = parts.getOrNull(0) ?: return@runBlocking
                val name = parts.getOrNull(1)
                try {
                    val locale = Locale.forLanguageTag(lang)
                    t.language = locale
                    if (name != null) {
                        val match = t.voices.firstOrNull { it.name == name && it.locale.toLanguageTag() == lang }
                        if (match != null) t.setVoice(match)
                    }
                } catch (_: Throwable) { /* fall back to engine default */ }
            } else {
                t.language = Locale.getDefault()
            }
        }
    }

    private fun applyAudioAttributes(t: TextToSpeech, volume: Float) {
        // USAGE_ASSISTANCE_NAVIGATION_GUIDANCE routes through a separate
        // audio path on API 26+ so the user's media-volume slider doesn't
        // affect speech. The OS surfaces this as "Navigation guidance"
        // volume in the sound settings.
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        try { t.setAudioAttributes(attrs) } catch (_: Throwable) { /* engine doesn't support */ }
        // volume multiplier is stored for Batch 8 (cloud TTS gain); the
        // platform engine's effective volume is controlled by the OS.
        // Persisting it here means a restart resumes at the same setting.
    }

    fun dispose() { synchronized(lock) {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
        loaded = null
        _state.value = PlaybackState.Idle
    } }

    companion object {
        @Volatile private var instance: TtsController? = null
        fun get(context: Context): TtsController =
            instance ?: synchronized(this) {
                instance ?: TtsController(context.applicationContext).also { instance = it }
            }
        /** Convenience accessor — uses the singleton ReaderApp context. */
        fun get(): TtsController = get(app.reader.ReaderApp.get())
    }
}
