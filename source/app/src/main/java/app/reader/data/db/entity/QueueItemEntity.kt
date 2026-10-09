package app.reader.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * An entry in the user's reading-list queue. Each document can be queued at
 * most once (UNIQUE on doc_id). [position] is the user-controlled order —
 * smaller comes first. Reorder uses 10-step gaps so a single move can be done
 * without rewriting every row.
 */
@Entity(
    tableName = "queue",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["doc_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["doc_id"], unique = true)]
)
data class QueueItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "doc_id") val docId: String,
    /** Ordering key — smaller = earlier in the queue. */
    val position: Int,
    @ColumnInfo(name = "added_at") val addedAt: Long
)
