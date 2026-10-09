package app.reader.data.repo

import android.content.Context
import android.net.Uri
import app.reader.ReaderApp
import app.reader.data.db.ReaderDatabase
import app.reader.data.db.entity.DocumentEntity
import app.reader.parser.DocumentParser
import app.reader.parser.ParserInput
import app.reader.parser.ParserRegistry
import app.reader.parser.ParsedDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * The single point of access for documents. Bridges Room, the file system
 * (app-private storage), and the format parsers.
 *
 * The flow is:
 *   1. [importFromUri] / [importPastedText] copy the source into app-private
 *      storage (when needed) and create a [DocumentEntity] row.
 *   2. [open] parses the source on demand and caches the result in memory
 *      so the reader screen doesn't re-parse on every recomposition.
 *   3. Position, bookmark, and queue mutations go straight to Room.
 */
class DocumentRepository private constructor(private val context: Context) {

    private val db = ReaderDatabase.get(context)
    private val parserCache = HashMap<String, ParsedDocument>()

    suspend fun importFromUri(uri: Uri, mime: String, title: String?): Result<String> = withContext(Dispatchers.IO) {
        try {
            val (bytes, suggestedName, actualMime) = readUri(uri, mime)
            val parser = ParserRegistry.forMime(actualMime, suggestedName)
                ?: return@withContext Result.failure(IllegalArgumentException("Unsupported file type: $actualMime"))
            val kind = parserKind(parser)
            val safeTitle = (title?.takeIf { it.isNotBlank() } ?: suggestedName?.substringBeforeLast('.') ?: "Untitled").take(120)
            val docId = UUID.randomUUID().toString()
            val storedFile: File? = if (bytes.size < INLINE_LIMIT && kind == "text") {
                // Small text files are easier to keep inline; we still want a
                // path so we can save pasted-text edits later. Use inline
                // sourceText so we don't write to disk.
                null
            } else {
                val dir = File(context.filesDir, "documents").apply { if (!exists()) mkdirs() }
                val ext = suggestedName?.substringAfterLast('.', "") ?: ""
                val name = if (ext.isBlank()) docId else "$docId.$ext"
                File(dir, name).apply { writeBytes(bytes) }
            }
            val parsed = parser.parse(ParserInput(bytes, actualMime, suggestedName, { bytes.inputStream() }))
            parserCache[docId] = parsed
            val entity = DocumentEntity(
                id = docId,
                title = safeTitle,
                mime = actualMime,
                filePath = storedFile?.absolutePath,
                sourceUri = uri.toString(),
                sourceText = if (storedFile == null) String(bytes, Charsets.UTF_8) else null,
                addedAt = System.currentTimeMillis(),
                lastReadAt = System.currentTimeMillis(),
                totalSentences = parsed.totalSentences,
                totalChapters = parsed.chapters.size,
                chapterIndex = 0,
                sentenceIndex = 0,
                parserKind = kind,
                size = bytes.size.toLong()
            )
            db.documents().upsert(entity)
            Result.success(docId)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    suspend fun importPastedText(text: String, title: String? = null): Result<String> = withContext(Dispatchers.IO) {
        try {
            val docId = UUID.randomUUID().toString()
            val safeTitle = (title?.takeIf { it.isNotBlank() } ?: "Pasted text").take(120)
            val parser = app.reader.parser.TextParser
            val parsed = parser.parse(ParserInput(text.toByteArray(Charsets.UTF_8), "text/plain", safeTitle, { text.byteInputStream() }))
            parserCache[docId] = parsed
            val entity = DocumentEntity(
                id = docId,
                title = safeTitle,
                mime = "text/plain",
                filePath = null,
                sourceUri = null,
                sourceText = text,
                addedAt = System.currentTimeMillis(),
                lastReadAt = System.currentTimeMillis(),
                totalSentences = parsed.totalSentences,
                totalChapters = parsed.chapters.size,
                chapterIndex = 0,
                sentenceIndex = 0,
                parserKind = "text",
                size = text.length.toLong()
            )
            db.documents().upsert(entity)
            Result.success(docId)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /** Open a document: read entity, parse (or use cache), return both. */
    suspend fun open(docId: String): OpenedDocument? = withContext(Dispatchers.IO) {
        val entity = db.documents().getById(docId) ?: return@withContext null
        val parsed = parserCache.getOrPut(docId) {
            val parser = ParserRegistry.forMime(entity.mime, entity.filePath?.substringAfterLast(File.separator))
                ?: app.reader.parser.TextParser
            val bytes = when {
                entity.filePath != null -> File(entity.filePath).readBytes()
                entity.sourceText != null -> entity.sourceText.toByteArray(Charsets.UTF_8)
                else -> ByteArray(0)
            }
            parser.parse(ParserInput(bytes, entity.mime, entity.title, { bytes.inputStream() }))
        }
        OpenedDocument(entity, parsed)
    }

    fun observeRecent(): Flow<List<DocumentEntity>> = db.documents().observeAll()

    fun observeDocument(id: String): Flow<DocumentEntity?> = db.documents().observe(id)

    suspend fun getById(id: String): DocumentEntity? = db.documents().getById(id)

    suspend fun latestId(): String? = db.documents().latestId()

    suspend fun updatePosition(id: String, chapter: Int, sentence: Int) {
        db.documents().updatePosition(id, chapter, sentence, System.currentTimeMillis())
    }

    suspend fun deleteDocument(id: String) {
        val entity = db.documents().getById(id) ?: return
        entity.filePath?.let { File(it).delete() }
        parserCache.remove(id)
        db.documents().delete(id)
    }

    // --- Bookmarks ---
    fun observeBookmarks(docId: String): Flow<List<app.reader.data.db.entity.BookmarkEntity>> =
        db.bookmarks().observeFor(docId)

    suspend fun toggleBookmark(docId: String, chapter: Int, sentence: Int, excerpt: String): Boolean {
        val exists = db.bookmarks().existsAtPosition(docId, chapter, sentence)
        if (exists) {
            // Remove all bookmarks at this exact position (in practice just one).
            val list = db.bookmarks().observeFor(docId)
            // The DAO observeFor returns a Flow — but we want a snapshot. Do it the simple way:
            // We'll just delete by an ad-hoc query via a small dedicated method.
            // For now, fall back to issuing a direct SQL through supportSQLite.
            db.openHelper.writableDatabase.execSQL(
                "DELETE FROM bookmarks WHERE doc_id = ? AND chapter_index = ? AND sentence_index = ?",
                arrayOf(docId, chapter, sentence)
            )
            return false
        } else {
            db.bookmarks().insert(
                app.reader.data.db.entity.BookmarkEntity(
                    docId = docId,
                    name = null,
                    excerpt = excerpt.take(120),
                    chapterIndex = chapter,
                    sentenceIndex = sentence,
                    createdAt = System.currentTimeMillis()
                )
            )
            return true
        }
    }

    suspend fun deleteBookmark(id: Long) = db.bookmarks().delete(id)

    // --- Queue / Reading list ---
    fun observeQueue(): Flow<List<app.reader.data.db.entity.QueueItemEntity>> = db.queue().observeAll()

    suspend fun addToQueue(docId: String) {
        if (db.queue().countFor(docId) > 0) return
        val next = (db.queue().maxPosition() ?: 0) + 10
        db.queue().insert(
            app.reader.data.db.entity.QueueItemEntity(
                docId = docId,
                position = next,
                addedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun removeFromQueue(docId: String) = db.queue().remove(docId)

    suspend fun clearQueue() = db.queue().clear()

    suspend fun moveQueueItem(docId: String, newPosition: Int) = db.queue().setPosition(docId, newPosition)

    // --- Storage size ---
    fun storageUsedBytes(): Long {
        val dir = File(context.filesDir, "documents")
        if (!dir.exists()) return 0
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    // --- Internals ---
    private fun parserKind(parser: DocumentParser): String = when (parser) {
        app.reader.parser.TextParser -> ParserRegistry.KIND_TEXT
        app.reader.parser.HtmlParser -> ParserRegistry.KIND_HTML
        app.reader.parser.EpubParser -> ParserRegistry.KIND_EPUB
        app.reader.parser.PdfParser -> ParserRegistry.KIND_PDF
        app.reader.parser.DocxParser -> ParserRegistry.KIND_DOCX
        else -> "doc"
    }

    private fun readUri(uri: Uri, mime: String): Triple<ByteArray, String?, String> {
        val resolver = context.contentResolver
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("Cannot open $uri")
        val suggestedName = queryDisplayName(uri)
        val actualMime = resolver.getType(uri) ?: mime
        return Triple(bytes, suggestedName, actualMime)
    }

    private fun queryDisplayName(uri: Uri): String? {
        val cursor = context.contentResolver.query(uri, null, null, null, null) ?: return null
        cursor.use {
            if (!it.moveToFirst()) return null
            val idx = it.getColumnIndex("_display_name")
            if (idx < 0) return null
            return it.getString(idx)
        }
    }

    companion object {
        private const val INLINE_LIMIT = 16 * 1024
        private val cache = HashMap<Context, DocumentRepository>()
        fun get(context: Context): DocumentRepository =
            cache.getOrPut(context.applicationContext) { DocumentRepository(context.applicationContext) }

        /** Convenience for the app's singleton instance. */
        fun get(): DocumentRepository = ReaderApp.documentRepository
    }
}

/** Snapshot returned by [DocumentRepository.open]. */
data class OpenedDocument(
    val entity: DocumentEntity,
    val parsed: ParsedDocument
)
