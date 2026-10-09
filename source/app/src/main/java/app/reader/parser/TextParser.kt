package app.reader.parser

import java.nio.charset.StandardCharsets

/** Plain-text parser — UTF-8 / UTF-16 / Latin-1 tolerant. */
object TextParser : DocumentParser {

    override fun parse(input: ParserInput): ParsedDocument {
        val text = decode(input.bytes)
        val title = (input.name?.substringBeforeLast('.') ?: "Pasted text").take(80)
        val chapter = SentenceSplitter.chapterFromText(text, title)
        return ParsedDocument(
            title = title,
            chapters = listOf(chapter),
            toc = emptyList()
        )
    }

    /** Decode a byte array with a sensible default; detect BOM if present. */
    private fun decode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        // BOMs
        when {
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
                return String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8)
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE)
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)
        }
        // Fallback: UTF-8 (covers plain ASCII and most modern files)
        return try { String(bytes, StandardCharsets.UTF_8) }
        catch (_: Throwable) { String(bytes, Charsets.ISO_8859_1) }
    }
}
