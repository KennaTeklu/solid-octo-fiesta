package app.reader.parser

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * DOCX text extractor — pure platform, no Apache POI dependency.
 *
 * A DOCX is a zip containing `word/document.xml`. Inside that XML, paragraphs
 * are `<w:p>` elements and text runs are `<w:t>` elements (with `<w:tab>`
 * becoming a tab and `<w:br type="page"/>` triggering a chapter break).
 *
 * We walk the XML with the platform XmlPullParser (no external lib), collect
 * text runs into paragraph strings, and split page breaks into separate
 * chapters so the user can navigate via the TOC.
 *
 * Headings (`w:pStyle w:val="Heading1"`) become TOC entries.
 */
object DocxParser : DocumentParser {

    override fun parse(input: ParserInput): ParsedDocument {
        val title = input.name?.substringBeforeLast('.') ?: "Document"
        var documentBytes: ByteArray? = null
        ZipInputStream(input.stream()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (entry.name.equals("word/document.xml", ignoreCase = true)) {
                    documentBytes = zis.readBytes()
                    break
                }
                zis.closeEntry()
            }
        }
        if (documentBytes == null) {
            return ParsedDocument(
                title = title,
                chapters = listOf(ParsedChapter(
                    "Could not read DOCX",
                    listOf(ParsedParagraph("The archive does not contain a word/document.xml entry.", emptyList()))
                )),
                toc = emptyList()
            )
        }

        val paragraphs = ArrayList<ParsedParagraph>()
        val toc = ArrayList<TocEntry>()
        val chapterBreaks = ArrayList<Int>() // paragraph indices where a new chapter begins

        try {
            val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }
            val parser = factory.newPullParser()
            parser.setInput(documentBytes!!.inputStream(), "UTF-8")

            val paragraphBuffer = StringBuilder()
            var inParagraph = false
            var paragraphStyle: String? = null
            var sawPageBreakInThisParagraph = false
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "w:p" -> {
                            inParagraph = true
                            paragraphBuffer.setLength(0)
                            paragraphStyle = null
                            sawPageBreakInThisParagraph = false
                        }
                        "w:pPr" -> {
                            // The next w:pStyle child sets the paragraph style.
                        }
                        "w:pStyle" -> {
                            paragraphStyle = parser.getAttributeValue(null, "val")
                        }
                        "w:t" -> {
                            if (inParagraph) paragraphBuffer.append(parser.nextText())
                        }
                        "w:tab" -> if (inParagraph) paragraphBuffer.append('\t')
                        "w:br" -> {
                            val brType = parser.getAttributeValue(null, "type")
                            if (brType == "page") sawPageBreakInThisParagraph = true
                            else if (inParagraph) paragraphBuffer.append('\n')
                        }
                        "w:cr" -> if (inParagraph) paragraphBuffer.append('\n')
                    }
                } else if (event == XmlPullParser.END_TAG && parser.name == "w:p") {
                    val text = paragraphBuffer.toString().trim()
                    if (text.isNotEmpty()) {
                        paragraphs.add(ParsedParagraph(text, SentenceSplitter.splitSentences(text)))
                        if (paragraphStyle?.startsWith("Heading") == true) {
                            toc.add(TocEntry(text, paragraphs.size - 1))
                        }
                    }
                    if (sawPageBreakInThisParagraph) {
                        chapterBreaks.add(paragraphs.size)
                    }
                    inParagraph = false
                }
                event = parser.next()
            }
        } catch (t: Throwable) {
            return ParsedDocument(
                title = title,
                chapters = listOf(ParsedChapter(
                    "Could not read DOCX",
                    listOf(ParsedParagraph(t.message ?: "XML parse error", emptyList()))
                )),
                toc = emptyList()
            )
        }

        // If no explicit page breaks, ship as one chapter.
        val chapters = if (chapterBreaks.isEmpty()) {
            listOf(ParsedChapter(null, paragraphs))
        } else {
            val result = ArrayList<ParsedChapter>()
            var start = 0
            for (breakAt in chapterBreaks) {
                if (breakAt > start) {
                    result.add(ParsedChapter(null, paragraphs.subList(start, breakAt)))
                }
                start = breakAt
            }
            if (start < paragraphs.size) {
                result.add(ParsedChapter(null, paragraphs.subList(start, paragraphs.size)))
            }
            result
        }

        return ParsedDocument(
            title = title,
            chapters = chapters,
            toc = toc
        )
    }
}
