package app.reader.parser

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * HTML parser. Uses jsoup to walk the DOM, extract clean paragraphs, and
 * strip scripts, styles, and other non-content tags.
 *
 * Headings (h1..h6) become both TocEntries (when the document has more than
 * one heading) and paragraph separators in the body — so the user hears the
 * heading before the body that follows.
 */
object HtmlParser : DocumentParser {

    override fun parse(input: ParserInput): ParsedDocument {
        val html = String(input.bytes, Charsets.UTF_8)
        val doc = Jsoup.parse(html)
        // Title: first <title>, else first <h1>, else file name.
        val title = doc.selectFirst("title")?.text()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("h1")?.text()?.takeIf { it.isNotBlank() }
            ?: input.name?.substringBeforeLast('.') ?: "Web page"

        val paragraphs = ArrayList<ParsedParagraph>()
        val toc = ArrayList<TocEntry>()

        val body = doc.body()
        walk(body, paragraphs, toc, headingLevel = -1)

        // If only one heading and no other structure, don't show a TOC.
        val finalToc = if (toc.size > 1) toc else emptyList()
        val chapter = ParsedChapter(null, paragraphs)
        return ParsedDocument(title = title, chapters = listOf(chapter), toc = finalToc)
    }

    private fun walk(node: Node, out: MutableList<ParsedParagraph>, toc: MutableList<TocEntry>, headingLevel: Int) {
        for (child in node.childNodes()) {
            when (child) {
                is Element -> {
                    val tag = child.tagName()
                    val headingRank = headingRank(tag)
                    if (headingRank != null) {
                        val text = child.text().trim()
                        if (text.isNotEmpty()) {
                            val paragraph = ParsedParagraph(text, SentenceSplitter.splitSentences(text))
                            out.add(paragraph)
                            toc.add(TocEntry(text, 0))
                        }
                        continue
                    }
                    when (tag) {
                        "script", "style", "noscript", "iframe", "svg", "form", "button", "head" -> {
                            // skip entirely
                        }
                        "p", "li", "blockquote", "td", "th", "figcaption", "dt", "dd", "caption" -> {
                            val text = child.text().trim()
                            if (text.isNotEmpty()) {
                                out.add(ParsedParagraph(text, SentenceSplitter.splitSentences(text)))
                            }
                        }
                        "br" -> { /* br alone doesn't carry text; ignore */ }
                        else -> walk(child, out, toc, headingLevel)
                    }
                }
                is TextNode -> {
                    // Bare text outside a paragraph (rare, but happens with legacy HTML).
                    val text = child.text().trim()
                    if (text.isNotEmpty()) {
                        out.add(ParsedParagraph(text, SentenceSplitter.splitSentences(text)))
                    }
                }
            }
        }
    }

    private fun headingRank(tag: String): Int? = when (tag) {
        "h1" -> 1
        "h2" -> 2
        "h3" -> 3
        "h4" -> 4
        "h5" -> 5
        "h6" -> 6
        else -> null
    }
}
