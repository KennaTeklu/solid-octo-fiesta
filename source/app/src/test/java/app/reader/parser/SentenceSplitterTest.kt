package app.reader.parser

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * Unit tests for [SentenceSplitter] and the streaming parser helpers.
 *
 * These tests run on the JVM (no Android framework). The full PDFBox
 * extraction path is exercised on-device via instrumented tests; here
 * we verify the splitter and sidecar JSON helpers.
 */
class SentenceSplitterTest {

    @Test
    fun splitSentences_singleSentence_returnsOneRange() {
        val ranges = SentenceSplitter.splitSentences("Hello world.")
        assertEquals(1, ranges.size)
        assertEquals("Hello world.", ranges[0].text)
    }

    @Test
    fun splitSentences_multipleSentences_returnsEach() {
        val ranges = SentenceSplitter.splitSentences(
            "First sentence. Second one! And a third?"
        )
        assertEquals(3, ranges.size)
        assertTrue(ranges[0].text.startsWith("First"))
        assertTrue(ranges[1].text.startsWith("Second"))
        assertTrue(ranges[2].text.startsWith("And a third"))
    }

    @Test
    fun splitParagraphs_blankLineBoundary() {
        val parts = SentenceSplitter.splitParagraphs(
            "Para one line one.\n\nPara two line one."
        )
        assertEquals(2, parts.size)
    }

    @Test
    fun splitParagraphs_singleLineWrapping_fallsBackToNewlineSplit() {
        val parts = SentenceSplitter.splitParagraphs("Line one.\nLine two.\nLine three.")
        assertEquals(3, parts.size)
    }

    @Test
    fun chapterFromText_emptyString_returnsEmptyChapter() {
        val ch = SentenceSplitter.chapterFromText("")
        assertTrue(ch.paragraphs.isEmpty() || ch.paragraphs.all { it.isBlank })
    }

    @Test
    fun chapterFromText_multipleParagraphs_preservesCount() {
        val ch = SentenceSplitter.chapterFromText(
            "First paragraph with multiple words.\n\nSecond paragraph also multiple words."
        )
        assertEquals(2, ch.paragraphs.size)
        assertTrue(ch.paragraphs[0].sentences.isNotEmpty())
    }
}
