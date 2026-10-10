package app.reader.parser

/**
 * Picks a [DocumentParser] by MIME type / file extension. Returns null if
 * the format isn't supported; the caller can show a friendly error rather
 * than a stack trace.
 */
object ParserRegistry {

    fun forMime(mime: String, name: String? = null): DocumentParser? {
        val lower = mime.lowercase()
        return when {
            lower.contains("text/plain") || lower.contains("text/markdown") -> TextParser
            lower.contains("text/html") || lower.contains("application/xhtml") -> HtmlParser
            lower.contains("epub") -> EpubParser
            lower.contains("pdf") -> PdfParser
            lower.contains("wordprocessing") || lower.contains("msword") -> DocxParser
            else -> forName(name)
        }
    }

    fun forName(name: String?): DocumentParser? {
        val lower = name?.lowercase() ?: return null
        return when {
            lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".csv") || lower.endsWith(".log") -> TextParser
            lower.endsWith(".htm") || lower.endsWith(".html") || lower.endsWith(".xhtml") -> HtmlParser
            lower.endsWith(".epub") -> EpubParser
            lower.endsWith(".pdf") -> PdfParser
            lower.endsWith(".docx") -> DocxParser
            else -> null
        }
    }

    /** Display label for a parser kind, used in the Library list. */
    fun labelForKind(kind: String): String = when (kind) {
        "text" -> "Text"
        "html" -> "HTML"
        "epub" -> "EPUB"
        "pdf" -> "PDF"
        "docx" -> "DOCX"
        else -> "Doc"
    }

    const val KIND_TEXT = "text"
    const val KIND_HTML = "html"
    const val KIND_EPUB = "epub"
    const val KIND_PDF = "pdf"
    const val KIND_DOCX = "docx"
}
