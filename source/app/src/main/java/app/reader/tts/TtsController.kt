package app.reader.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
    private val lock = Any()

    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    @Volatile private var loaded: LoadedDoc? = null
    /** Pending command requested before the engine finished initing. */
    private val pendingPlay = AtomicBoolean(false)
    /** When true, onDone() should advance to the next sentence instead of stopping. */
    @Volatile private var continueAfterSentence = false

    init {
        val engine = TextToSpeech(appContext) { status ->
            synchronized(lock) {
                ttsReady = (status == TextToSpeech.SUCCESS)
                val t = tts
                if (ttsReady && t != null) {
                    applySettingsTo(t)
                    t.setOnUtteranceProgressListener(progressListener)
                    if (pendingPlay.compareAndSet(true, false)) {
                        playInternal()
                    }
                } else {
                    _state.value = PlaybackState.Error("TTS engine init failed (status $status)")
                }
            }
        }
        tts = engine
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
        _state.value = PlaybackState.Ready(docId, title, chapter, sentence)
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
            // Find the paragraph index for the current sentence, then move to the start of the previous paragraph.
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

    /** Update speed/pitch when settings change. Applies on the live engine. */
    fun refreshVoiceSettings() { synchronized(lock) {
        tts?.let { applySettingsTo(it) }
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
     * Apply settings to the live TTS engine. Reads settings synchronously
     * via [runBlocking] — we're already on a binder / service callback thread,
     * and the values are tiny, so blocking for the first emit is acceptable.
     */
    private fun applySettingsTo(t: TextToSpeech) {
        runBlocking {
            val rate = settings.speechRate.first().coerceIn(0.1f, 4f)
            val pitch = settings.pitch.first().coerceIn(0.1f, 2f)
            val voiceSig = settings.voice.first()
            t.setSpeechRate(rate)
            t.setPitch(pitch)
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
