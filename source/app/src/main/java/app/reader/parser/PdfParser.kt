package app.reader.parser

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayInputStream

/**
 * PDF text extractor — synchronous fallback for v1 paths that still call
 * [DocumentParser.parse] directly (e.g. import-time totals computation,
 * share-intent ingestion that doesn't navigate to the reader).
 *
 * For the reader screen, prefer [PdfStreamingParser] — it parses
 * page-by-page on a background thread and feeds the reader as each page
 * completes. See Batch 2 (PDF-08).
 *
 * If the PDF is image-only (no text layer), [parse] returns a chapter with
 * a friendly error message; the user can open it in another reader.
 */
object PdfParser : DocumentParser {

    override fun parse(input: ParserInput): ParsedDocument {
        val title = input.name?.substringBeforeLast('.') ?: "PDF"
        return try {
            PDDocument.load(ByteArrayInputStream(input.bytes)).use { pd ->
                val stripper = PDFTextStripper().apply {
                    // Preserve paragraph breaks for the splitter.
                    paragraphStart = "\n\n"
                    pageStart = "\n\n"
                }
                val raw = stripper.getText(pd)
                if (raw.isBlank()) {
                    ParsedDocument(
                        title = title,
                        chapters = listOf(ParsedChapter(
                            "No text layer",
                            listOf(ParsedParagraph(
                                "This PDF has no extractable text. It may be a scanned image — OCR is on the v2 roadmap.",
                                emptyList()
                            ))
                        )),
                        toc = emptyList()
                    )
                } else {
                    val chapter = SentenceSplitter.chapterFromText(raw, null)
                    ParsedDocument(title = title, chapters = listOf(chapter), toc = emptyList())
                }
            }
        } catch (t: Throwable) {
            ParsedDocument(
                title = title,
                chapters = listOf(ParsedChapter(
                    "Could not read PDF",
                    listOf(ParsedParagraph(t.message ?: "Unknown error", emptyList()))
                )),
                toc = emptyList()
            )
        }
    }
}
