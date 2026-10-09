package app.reader.parser

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayInputStream

/**
 * PDF text extractor — wraps the TomRoush PDFBox-Android port. The native
 * libraries for ARM64 are bundled by the `com.tom-roush:pdfbox-android`
 * dependency, so this works fully offline.
 *
 * For v1 we extract the entire PDF as one chapter. The sentence splitter
 * handles paragraph boundaries, so the user can scrub by sentence inside
 * the document. Page-aware chunking (one chapter per page) is intentionally
 * avoided because PDFs are visually paginated but logically continuous —
 * one chapter gives a smoother read.
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
