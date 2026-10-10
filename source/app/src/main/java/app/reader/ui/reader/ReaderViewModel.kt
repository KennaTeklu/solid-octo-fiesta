package app.reader.ui.reader

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.reader.data.db.entity.BookmarkEntity
import app.reader.data.db.entity.DocumentEntity
import app.reader.data.repo.DocumentRepository
import app.reader.data.repo.OpenedDocument
import app.reader.data.repo.SettingsRepository
import app.reader.parser.ParsedDocument
import app.reader.parser.PdfStreamingParser
import app.reader.parser.ParserRegistry
import app.reader.tts.PlaybackState
import app.reader.tts.TtsController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ReaderViewModel(
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val repo = app.reader.ReaderApp.documentRepository
    private val settings = app.reader.ReaderApp.settingsRepository
    val controller = TtsController.get()

    private val docId: String = savedStateHandle["docId"] ?: ""

    private val _doc = MutableStateFlow<OpenedDocument?>(null)
    val doc: StateFlow<OpenedDocument?> = _doc.asStateFlow()

    /** Live PDF streaming state — non-null only while a PDF parse is in flight or just finished. */
    val pdfStream: StateFlow<PdfStreamingParser.State> = PdfStreamingParser.state

    val playback: StateFlow<PlaybackState> = controller.state

    val bookmarks: StateFlow<List<BookmarkEntity>> =
        repo.observeBookmarks(docId).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val fontScale: StateFlow<Float> =
        settings.fontScale.stateIn(viewModelScope, SharingStarted.Eagerly, 1.0f)

    val autoScroll: StateFlow<Boolean> =
        settings.autoScroll.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    // Batch 7 — live voice settings surfaced to the playback bar.
    val speechRate: StateFlow<Float> =
        settings.speechRate.stateIn(viewModelScope, SharingStarted.Eagerly, 1.0f)
    val pitch: StateFlow<Float> =
        settings.pitch.stateIn(viewModelScope, SharingStarted.Eagerly, 1.0f)
    val speechVolume: StateFlow<Float> =
        settings.speechVolume.stateIn(viewModelScope, SharingStarted.Eagerly, 1.0f)
    val rateMin: StateFlow<Float> =
        settings.rateMin.stateIn(viewModelScope, SharingStarted.Eagerly, 0.5f)
    val rateMax: StateFlow<Float> =
        settings.rateMax.stateIn(viewModelScope, SharingStarted.Eagerly, 3.0f)
    val rateStep: StateFlow<Float> =
        settings.rateStep.stateIn(viewModelScope, SharingStarted.Eagerly, 0.25f)

    /**
     * Live rate change from the expanded playback bar. Persists to settings
     * AND applies to the running TTS engine immediately — without
     * restarting the current sentence. The current sentence finishes at
     * the old rate; the next sentence picks up the new rate.
     */
    fun setRateLive(rate: Float) {
        viewModelScope.launch {
            settings.setSpeechRate(rate)
            controller.setRateLive(rate)
        }
    }

    /** Live pitch change from the expanded playback bar. */
    fun setPitchLive(pitch: Float) {
        viewModelScope.launch {
            settings.setPitch(pitch)
            controller.setPitchLive(pitch)
        }
    }

    /** Live volume change from the expanded playback bar. */
    fun setVolumeLive(volume: Float) {
        viewModelScope.launch {
            settings.setSpeechVolume(volume)
            controller.setVolumeLive(volume)
        }
    }

    init {
        load()
        viewModelScope.launch {
            // When the sentence index changes, persist it to the DB.
            playback.distinctUntilChangedBy { sentenceMarker(it) }
                .collectLatest { state ->
                    val ch = state.chapter
                    val sent = state.sentence
                    if (ch >= 0 && sent >= 0) {
                        repo.updatePosition(docId, ch, sent)
                    }
                }
        }
        viewModelScope.launch {
            // Drive the reader from streaming PDF progress: as each page
            // completes, swap the partial [ParsedDocument] into _doc so the
            // reader body updates. The first page makes the reader usable
            // before the whole file has parsed.
            pdfStream.collect { state ->
                when (state) {
                    is PdfStreamingParser.State.Loading -> {
                        if (state.docId != docId) return@collect
                        if (state.partial.chapters.firstOrNull()?.paragraphs?.isNotEmpty() == true) {
                            feedPartial(state.partial, state.title)
                        }
                    }
                    is PdfStreamingParser.State.Done -> {
                        if (state.docId != docId) return@collect
                        feedPartial(state.partial, state.title)
                        // Persist the real totals now that we know them.
                        viewModelScope.launch {
                            repo.updateTotalsAndTouch(
                                docId,
                                state.partial.totalSentences,
                                state.partial.chapters.size
                            )
                            PdfStreamingParser.clearSidecar(docId)
                        }
                    }
                    is PdfStreamingParser.State.Error -> {
                        if (state.docId != docId) return@collect
                    }
                    PdfStreamingParser.State.Idle -> Unit
                }
            }
        }
    }

    /** Swap a new (partial or full) [ParsedDocument] into the reader + TTS controller. */
    private fun feedPartial(parsed: ParsedDocument, title: String) {
        val prev = _doc.value
        // Preserve the user's current (chapter, sentence) so swapping in a
        // longer chapter list doesn't yank them back to the start.
        val resumeChapter = prev?.entity?.chapterIndex ?: 0
        val resumeSentence = prev?.entity?.sentenceIndex ?: 0
        val entity = prev?.entity?.copy(
            title = title,
            totalSentences = parsed.totalSentences,
            totalChapters = parsed.chapters.size
        ) ?: DocumentEntity(
            id = docId,
            title = title,
            mime = "application/pdf",
            filePath = null,
            sourceUri = null,
            sourceText = null,
            addedAt = System.currentTimeMillis(),
            lastReadAt = System.currentTimeMillis(),
            totalSentences = parsed.totalSentences,
            totalChapters = parsed.chapters.size,
            chapterIndex = 0,
            sentenceIndex = 0,
            parserKind = ParserRegistry.KIND_PDF,
            size = 0
        )
        val opened = OpenedDocument(entity, parsed)
        _doc.value = opened
        repo.cacheParsed(docId, parsed)
        controller.load(
            docId = docId,
            title = title,
            parsed = parsed,
            resumeChapter = resumeChapter,
            resumeSentence = resumeSentence
        )
    }

    private fun load() = viewModelScope.launch {
        val opened = repo.open(docId) ?: return@launch
        // If it's a PDF and we don't already have a parsed result cached,
        // start the streaming parser and let the collector above feed the
        // reader as pages come in. If the cached parse is already complete
        // (e.g. we're revisiting a previously-parsed PDF), just use it.
        if (opened.entity.parserKind == ParserRegistry.KIND_PDF &&
            opened.parsed.chapters.firstOrNull()?.paragraphs.isNullOrEmpty()
        ) {
            // Re-fetch as bytes and start streaming.
            val bytes = repo.openBytes(docId) ?: return@launch
            _doc.value = OpenedDocument(opened.entity, opened.parsed)
            PdfStreamingParser.start(
                docId = docId,
                title = opened.entity.title,
                bytes = bytes.bytes,
                mime = opened.entity.mime,
                resumeFromSidecar = true
            )
            return@launch
        }
        _doc.value = opened
        controller.load(
            docId = docId,
            title = opened.entity.title,
            parsed = opened.parsed,
            resumeChapter = opened.entity.chapterIndex,
            resumeSentence = opened.entity.sentenceIndex
        )
    }

    fun toggleBookmark(): Boolean {
        val s = playback.value
        val ch = s.chapter
        val sent = s.sentence
        val opened = _doc.value ?: return false
        val text = currentSentenceText(opened.parsed, ch, sent) ?: return false
        var added = false
        viewModelScope.launch {
            added = repo.toggleBookmark(docId, ch, sent, text)
        }
        return added
    }

    fun jumpToBookmark(b: BookmarkEntity) {
        controller.seekTo(b.chapterIndex, b.sentenceIndex, autoplay = playback.value is PlaybackState.Playing)
    }

    fun jumpToChapter(chapter: Int) {
        controller.seekTo(chapter, 0, autoplay = playback.value is PlaybackState.Playing)
    }

    fun deleteBookmark(id: Long) = viewModelScope.launch { repo.deleteBookmark(id) }

    fun togglePlayPause() {
        // Bring the foreground service up before playing so playback survives
        // the screen going off.
        if (playback.value !is PlaybackState.Playing) {
            app.reader.service.PlaybackService.start(app.reader.ReaderApp.get())
        }
        controller.togglePlayPause()
    }

    fun skipNextSentence() = controller.skipNextSentence()
    fun skipPrevSentence() = controller.skipPreviousSentence()
    fun skipNextParagraph() = controller.skipNextParagraph()
    fun skipPrevParagraph() = controller.skipPreviousParagraph()
    fun stop() {
        controller.stop()
        // Service tears itself down on Idle/Ended.
    }

    override fun onCleared() {
        super.onCleared()
        // Cancel any in-flight parse so the next doc doesn't race with this
        // one. The sidecar stays on disk so we can resume on reopen.
        PdfStreamingParser.cancel(docId)
        // The controller is shared (singleton), so the VM should NOT dispose of it.
        // The foreground service owns the lifecycle for the playback session.
    }

    // --- Helpers ---

    private fun sentenceMarker(s: PlaybackState): Pair<Int, Int> = when (s) {
        is PlaybackState.Playing -> s.chapter to s.sentence
        is PlaybackState.Paused -> s.chapter to s.sentence
        is PlaybackState.Ready -> s.chapter to s.sentence
        else -> -1 to -1
    }

    private fun currentSentenceText(parsed: ParsedDocument, chapter: Int, sentence: Int): String? {
        val ch = parsed.chapters.getOrNull(chapter) ?: return null
        var flat = 0
        for (p in ch.paragraphs) {
            if (sentence < flat + p.sentences.size) {
                return p.sentences[sentence - flat].text
            }
            flat += p.sentences.size
        }
        return null
    }
}

/** Convenience getters used by the reader UI to keep the playback-state switch tidy. */
val PlaybackState.chapter: Int get() = when (this) {
    is PlaybackState.Playing -> chapter
    is PlaybackState.Paused -> chapter
    is PlaybackState.Ready -> chapter
    else -> 0
}

val PlaybackState.sentence: Int get() = when (this) {
    is PlaybackState.Playing -> sentence
    is PlaybackState.Paused -> sentence
    is PlaybackState.Ready -> sentence
    else -> 0
}

val PlaybackState.isPlaying: Boolean get() = this is PlaybackState.Playing
