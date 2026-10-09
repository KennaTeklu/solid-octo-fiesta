package app.reader.parser

import java.io.InputStream

/** Functional interface each format parser implements. */
fun interface DocumentParser {
    fun parse(input: ParserInput): ParsedDocument
}

/**
 * Inputs passed to a parser. [bytes] is loaded lazily so a format that needs
 * the raw stream (EPUB zip, DOCX zip) can read it without buffering twice.
 */
class ParserInput(
    val bytes: ByteArray,
    val mime: String,
    val name: String?,
    val stream: () -> InputStream
)
