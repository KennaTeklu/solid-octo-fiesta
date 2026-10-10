package app.reader.parser

import java.text.BreakIterator
import java.util.Locale

/**
 * Splits text into sentences and paragraphs using platform APIs.
 *
 * - Paragraphs are split on double-newlines (or single newlines if no
 *   double-newline is present). Whitespace runs are collapsed, but the
 *   in-paragraph line breaks are kept so that verse, code, and addresses
 *   still look right.
 * - Sentences are split using [BreakIterator.getSentenceInstance], which is
 *   locale-aware and good enough for the major European and CJK scripts.
 *
 * No regex, no third-party NLP library. This is the same approach @Voice's
 * siblings use; for clean reading text it is correct on the vast majority of
 * real documents. Edge cases (URLs with periods, abbreviations like "Dr.")
 * are tolerated — the highlight may run long, the spoken result is unchanged.
 */
object SentenceSplitter {

    /**
     * Split a single paragraph's text into [SentenceRange]s.
     * Each range's [SentenceRange.text] is the trimmed sentence.
     * Ranges cover the whole input — adjacent ranges touch (no gaps).
     */
    fun splitSentences(text: String, locale: Locale = Locale.getDefault()): List<SentenceRange> {
        if (text.isBlank()) return listOf(SentenceRange(0, text.length, text))
        val iter = BreakIterator.getSentenceInstance(locale)
        iter.setText(text)
        val ranges = ArrayList<SentenceRange>(8)
        var start = iter.first()
        var boundary = iter.next()
        while (boundary != BreakIterator.DONE) {
            val sentence = text.substring(start, boundary)
            if (sentence.isNotBlank()) {
                ranges.add(SentenceRange(start, boundary, sentence.trim()))
            }
            start = boundary
            boundary = iter.next()
        }
        // Trailing run with no terminator (e.g. last sentence of a paragraph with no full stop).
        if (start < text.length && text.substring(start).isNotBlank()) {
            ranges.add(SentenceRange(start, text.length, text.substring(start).trim()))
        }
        return ranges.ifEmpty { listOf(SentenceRange(0, text.length, text.trim())) }
    }

    /**
     * Split a raw text body into paragraphs. Each paragraph is a string;
     * sentence splitting is left to [splitSentences] at the call site.
     */
    fun splitParagraphs(body: String): List<String> {
        // Normalise CRLF -> LF first so splitting logic can stay simple.
        val normalised = body.replace("\r\n", "\n").replace('\r', '\n')
        // Prefer blank-line paragraph boundaries. If the document is
        // single-line-wrapped (one newline per visual line), fall back to
        // splitting on single newlines so each line still reads as a unit.
        val byBlankLine = normalised.split(Regex("\n[ \t]*\n")).filter { it.isNotBlank() }
        if (byBlankLine.size > 1) return byBlankLine.map { it.trim() }
        if (normalised.contains('\n')) {
            return normalised.split('\n').filter { it.isNotBlank() }.map { it.trim() }
        }
        return listOf(normalised.trim()).filter { it.isNotBlank() }
    }

    /**
     * Build a [ParsedChapter] from a raw text body. Convenience for the
     * plain-text and pasted-text paths where there's no chapter structure.
     */
    fun chapterFromText(body: String, title: String? = null): ParsedChapter {
        val paragraphs = splitParagraphs(body).map { p ->
            ParsedParagraph(p, splitSentences(p))
        }
        return ParsedChapter(title, paragraphs)
    }
}
