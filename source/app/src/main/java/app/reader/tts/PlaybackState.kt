package app.reader.tts

import app.reader.parser.ParsedDocument
import app.reader.parser.SentenceRange

/**
 * The state of the playback engine, surfaced to the UI and to the
 * notification via a StateFlow.
 *
 * - [Idle] : no document loaded.
 * - [Loading] : a document is being parsed / voice is being loaded.
 * - [Ready] : parsed, ready to play, currently paused at the position.
 * - [Playing] : TTS is reading aloud.
 * - [Paused] : TTS was playing, now paused.
 * - [Ended] : the document finished; we are parked at the end.
 * - [Error] : something went wrong (e.g. no voice installed).
 */
sealed interface PlaybackState {
    data object Idle : PlaybackState
    data class Loading(val docId: String?) : PlaybackState
    data class Ready(val docId: String, val title: String, val chapter: Int, val sentence: Int) : PlaybackState
    data class Playing(
        val docId: String,
        val title: String,
        val chapter: Int,
        val sentence: Int,
        /** Total sentences up to and including the current one — for progress %. */
        val sentencesDone: Int,
        val sentencesTotal: Int
    ) : PlaybackState
    data class Paused(val docId: String, val title: String, val chapter: Int, val sentence: Int, val total: Int, val done: Int) : PlaybackState
    data class Ended(val docId: String) : PlaybackState
    data class Error(val message: String) : PlaybackState
}

/**
 * Lightweight snapshot of the loaded document, kept in the controller so it
 * doesn't have to re-parse on every skip.
 */
data class LoadedDoc(
    val docId: String,
    val title: String,
    val parsed: ParsedDocument
) {
    fun sentenceAt(chapter: Int, sentence: Int): Pair<SentenceRange, String>? {
        if (chapter !in parsed.chapters.indices) return null
        val ch = parsed.chapters[chapter]
        val flat = ArrayList<Pair<SentenceRange, String>>()
        for (p in ch.paragraphs) for (s in p.sentences) flat += s to p.text
        if (sentence !in flat.indices) return null
        return flat[sentence]
    }

    /** Total number of sentences up to and including [chapter]. */
    fun cumulativeSentences(chapter: Int): Int {
        var total = 0
        for (i in 0..chapter.coerceAtLeast(0)) {
            if (i < parsed.chapters.size) total += parsed.chapters[i].sentenceCount()
        }
        return total
    }

    val totalSentences: Int get() = parsed.totalSentences
}
