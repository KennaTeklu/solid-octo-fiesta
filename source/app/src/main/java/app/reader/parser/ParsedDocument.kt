package app.reader.parser

/**
 * A document the UI and TTS layer work with. The body is split into chapters
 * (most formats have just one; EPUB / PDF / DOCX can have many). Each chapter
 * is split into paragraphs, and each paragraph into sentences.
 *
 * Sentences are the unit the TTS engine actually speaks. We precompute their
 * character offsets inside the parent paragraph so the UI can render the
 * whole paragraph as a single spannable and highlight the active sentence.
 */
data class ParsedDocument(
    val title: String,
    val chapters: List<ParsedChapter>,
    val toc: List<TocEntry>
) {
    val totalSentences: Int get() = chapters.sumOf { it.sentenceCount() }

    fun totalSentencesUpTo(chapter: Int): Int =
        chapters.subList(0, chapter.coerceAtLeast(0)).sumOf { it.sentenceCount() }
}

data class ParsedChapter(
    val title: String?,
    val paragraphs: List<ParsedParagraph>
) {
    fun sentenceCount(): Int = paragraphs.sumOf { it.sentences.size }
}

data class ParsedParagraph(
    val text: String,
    /** Sentence offsets in [text] — strictly increasing character ranges. */
    val sentences: List<SentenceRange>
) {
    val isBlank: Boolean get() = text.isBlank()
}

data class SentenceRange(val start: Int, val endExclusive: Int, val text: String)

data class TocEntry(val title: String, val chapterIndex: Int)
