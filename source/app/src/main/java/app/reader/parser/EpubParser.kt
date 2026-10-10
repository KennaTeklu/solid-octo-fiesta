package app.reader.parser

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Minimal EPUB parser — no external dependency beyond jsoup.
 *
 * EPUB is a zip with: (1) a `mimetype` marker, (2) an `OEBPS/content.opf`
 * manifest listing all chapter files, and (3) an `OEBPS/toc.ncx` (or the
 * nav XHTML in EPUB 3) listing the chapter titles in reading order.
 *
 * We:
 *   1. Read the OPF to discover the spine (reading order).
 *   2. Read the NCX or nav to extract TOC titles for the spine items.
 *   3. Walk each spine XHTML with the same logic as HtmlParser, producing
 *      one chapter per spine entry.
 */
object EpubParser : DocumentParser {

    override fun parse(input: ParserInput): ParsedDocument {
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(input.stream()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (!entry.isDirectory) {
                    val bytes = zis.readBytes()
                    entries[entry.name] = bytes
                }
                zis.closeEntry()
            }
        }

        // 1. Find the OPF path from META-INF/container.xml
        val containerBytes = entries["META-INF/container.xml"]
            ?: entries["meta-inf/container.xml"]
            ?: return errorDoc(input.name ?: "EPUB")
        val containerDoc = Jsoup.parse(String(containerBytes, Charsets.UTF_8), "", org.jsoup.parser.Parser.xmlParser())
        val opfPath = containerDoc.selectFirst("rootfile")?.attr("full-path")
            ?: return errorDoc(input.name ?: "EPUB")
        val opfDoc = Jsoup.parse(
            String(entries[opfPath] ?: return errorDoc(input.name ?: "EPUB"), Charsets.UTF_8),
            "",
            org.jsoup.parser.Parser.xmlParser()
        )

        // 2. Build the manifest (id -> href) and the spine (ordered list of ids).
        val manifest = mutableMapOf<String, String>()
        for (item in opfDoc.select("item")) {
            val id = item.attr("id")
            val href = item.attr("href")
            if (id.isNotEmpty() && href.isNotEmpty()) {
                manifest[id] = resolvePath(opfPath, href)
            }
        }
        val spineIds = opfDoc.select("itemref").map { it.attr("idref") }.filter { it.isNotEmpty() }
        if (spineIds.isEmpty()) return errorDoc(input.name ?: "EPUB")

        // 3. TOC — prefer EPUB3 nav, fall back to NCX.
        val tocTitles = readToc(entries, opfPath, opfDoc, spineIds)

        // 4. Title — try <dc:title> in the OPF metadata.
        val docTitle = opfDoc.selectFirst("metadata > title")?.text()?.takeIf { it.isNotBlank() }
            ?: input.name?.substringBeforeLast('.') ?: "EPUB"

        // 5. Walk each spine item — one ParsedChapter per file.
        val chapters = ArrayList<ParsedChapter>(spineIds.size)
        val toc = ArrayList<TocEntry>(spineIds.size)
        for ((index, id) in spineIds.withIndex()) {
            val href = manifest[id] ?: continue
            val bytes = entries[href] ?: continue
            val title = tocTitles[id] ?: "Chapter ${index + 1}"
            val paragraphs = ArrayList<ParsedParagraph>()
            walk(Jsoup.parse(String(bytes, Charsets.UTF_8)).body(), paragraphs)
            chapters.add(ParsedChapter(title, paragraphs))
            toc.add(TocEntry(title, index))
        }
        return ParsedDocument(docTitle, chapters, toc)
    }

    private fun walk(node: Element, out: MutableList<ParsedParagraph>) {
        for (child in node.children()) {
            val tag = child.tagName()
            val heading = when (tag) {
                "h1", "h2", "h3", "h4", "h5", "h6" -> true
                else -> false
            }
            when {
                heading || tag == "p" || tag == "li" || tag == "blockquote" || tag == "figcaption" || tag == "dt" || tag == "dd" || tag == "caption" -> {
                    val text = child.text().trim()
                    if (text.isNotEmpty()) {
                        out.add(ParsedParagraph(text, SentenceSplitter.splitSentences(text)))
                    }
                }
                tag == "img" || tag == "svg" || tag == "script" || tag == "style" || tag == "head" || tag == "form" -> { /* skip */ }
                else -> walk(child, out)
            }
        }
    }

    private fun readToc(
        entries: Map<String, ByteArray>,
        opfPath: String,
        opfDoc: Document,
        spineIds: List<String>
    ): Map<String, String> {
        // EPUB 3 nav
        val navHref = opfDoc.select("item").firstOrNull { it.attr("properties").contains("nav") }?.attr("href")
        if (navHref != null) {
            val resolved = resolvePath(opfPath, navHref)
            val navBytes = entries[resolved]
            if (navBytes != null) {
                val doc = Jsoup.parse(String(navBytes, Charsets.UTF_8))
                // nav > ol > li > a (link), nested for sub-toc
                val byHref = LinkedHashMap<String, String>()
                for (a in doc.select("nav a[href]")) {
                    val href = a.attr("href")
                    if (href.isNotEmpty() && !href.startsWith("#")) {
                        val label = a.text().trim()
                        if (label.isNotEmpty()) {
                            val resolvedHref = resolvePath(navHref, href.substringBefore('#'))
                            // Map spine href -> label by matching path.
                            byHref[resolvedHref] = label
                        }
                    }
                }
                if (byHref.isNotEmpty()) {
                    val result = LinkedHashMap<String, String>()
                    for (id in spineIds) {
                        // We need the spine href to match the nav href.
                        // Walk the OPF again to find the href for the id.
                        val spineHref = opfDoc.select("item[id=$id]").first()?.attr("href")?.let { resolvePath(opfPath, it) }
                        if (spineHref != null) {
                            result[id] = byHref[spineHref] ?: byHref.values.firstOrNull() ?: "Chapter"
                        }
                    }
                    if (result.isNotEmpty()) return result
                }
            }
        }
        // EPUB 2 NCX
        val ncxId = opfDoc.selectFirst("spine")?.attr("toc")
        if (ncxId != null) {
            val ncxPath = opfDoc.select("item[id=$ncxId]").first()?.attr("href")?.let { resolvePath(opfPath, it) }
            if (ncxPath != null) {
                val ncxBytes = entries[ncxPath]
                if (ncxBytes != null) {
                    val doc = Jsoup.parse(String(ncxBytes, Charsets.UTF_8), "", org.jsoup.parser.Parser.xmlParser())
                    val bySrc = LinkedHashMap<String, String>()
                    for (point in doc.select("navPoint")) {
                        val label = point.selectFirst("text")?.text()?.trim() ?: continue
                        val src = point.selectFirst("content")?.attr("src")?.substringBefore('#') ?: continue
                        if (label.isNotEmpty()) {
                            bySrc[resolvePath(ncxPath, src)] = label
                        }
                    }
                    if (bySrc.isNotEmpty()) {
                        val result = LinkedHashMap<String, String>()
                        for (id in spineIds) {
                            val spineHref = opfDoc.select("item[id=$id]").first()?.attr("href")?.let { resolvePath(opfPath, it) }
                            if (spineHref != null) {
                                result[id] = bySrc[spineHref] ?: "Chapter"
                            }
                        }
                        return result
                    }
                }
            }
        }
        return emptyMap()
    }

    private fun resolvePath(base: String, href: String): String {
        if (href.startsWith("/")) return href.trimStart('/')
        val baseDir = base.substringBeforeLast('/', "")
        return if (baseDir.isEmpty()) href else "$baseDir/$href"
    }

    private fun errorDoc(name: String): ParsedDocument =
        ParsedDocument(name, listOf(ParsedChapter("Could not read EPUB", listOf())), emptyList())
}
