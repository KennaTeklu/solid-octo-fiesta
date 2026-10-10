package app.reader.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A document the user has opened at least once. The text body lives in either
 * [filePath] (a file copied into app-private storage) or [sourceText] (pasted
 * inline — no source file). At most one of them is non-null.
 *
 * [chapterIndex] and [sentenceIndex] are the user's last reading position —
 * restored when the doc is reopened so the user picks up where they stopped.
 */
@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey val id: String,
    val title: String,
    val mime: String,
    /** Absolute path inside the app's files-dir/documents/ — null for pasted text. */
    val filePath: String?,
    /** Original content URI string, kept purely so we can show a "source" hint. */
    val sourceUri: String?,
    /** Inline pasted text — null when the document was imported from a file. */
    val sourceText: String?,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    @ColumnInfo(name = "last_read_at") val lastReadAt: Long,
    /** Sentence count for the whole doc, computed once at parse time. */
    @ColumnInfo(name = "total_sentences") val totalSentences: Int,
    /** Total chapter count — 1 for plain text/paste, 1+ for EPUB/PDF/DOCX. */
    @ColumnInfo(name = "total_chapters") val totalChapters: Int,
    @ColumnInfo(name = "chapter_index") val chapterIndex: Int,
    @ColumnInfo(name = "sentence_index") val sentenceIndex: Int,
    /** Mime-style type hint the parser uses to pick a decoder. */
    val parserKind: String,
    /** File size in bytes (0 for pasted text). */
    val size: Long
)
