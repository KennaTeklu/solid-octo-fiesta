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
import app.reader.parser.SentenceSplitter
import app.reader.tts.PlaybackState
import app.reader.tts.TtsController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
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

    val playback: StateFlow<PlaybackState> = controller.state

    val bookmarks: StateFlow<List<BookmarkEntity>> =
        repo.observeBookmarks(docId).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val fontScale: StateFlow<Float> =
        settings.fontScale.stateIn(viewModelScope, SharingStarted.Eagerly, 1.0f)

    val autoScroll: StateFlow<Boolean> =
        settings.autoScroll.stateIn(viewModelScope, SharingStarted.Eagerly, true)

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
    }

    private fun load() = viewModelScope.launch {
        val opened = repo.open(docId) ?: return@launch
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
