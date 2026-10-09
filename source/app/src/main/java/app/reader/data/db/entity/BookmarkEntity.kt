package app.reader.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A saved position inside a document. The user can drop a bookmark at the
 * current sentence and jump back to it later from the Bookmarks sheet.
 */
@Entity(
    tableName = "bookmarks",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["doc_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("doc_id")]
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "doc_id") val docId: String,
    /** Optional label the user typed; null means use an excerpt as the label. */
    val name: String?,
    /** Short text excerpt for the bookmarks list UI. */
    val excerpt: String,
    @ColumnInfo(name = "chapter_index") val chapterIndex: Int,
    @ColumnInfo(name = "sentence_index") val sentenceIndex: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long
)
