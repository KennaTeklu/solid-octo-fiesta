package app.reader.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import app.reader.data.db.entity.DocumentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {

    @Query("SELECT * FROM documents ORDER BY last_read_at DESC, added_at DESC")
    fun observeAll(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun getById(id: String): DocumentEntity?

    @Query("SELECT * FROM documents WHERE id = :id")
    fun observe(id: String): Flow<DocumentEntity?>

    @Query("SELECT id FROM documents ORDER BY last_read_at DESC LIMIT 1")
    suspend fun latestId(): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(doc: DocumentEntity): Long

    @Update
    suspend fun update(doc: DocumentEntity): Int

    @Query("UPDATE documents SET chapter_index = :chapter, sentence_index = :sentence, last_read_at = :ts WHERE id = :id")
    suspend fun updatePosition(id: String, chapter: Int, sentence: Int, ts: Long)

    @Query("UPDATE documents SET total_sentences = :total, total_chapters = :chapters WHERE id = :id")
    suspend fun updateTotals(id: String, total: Int, chapters: Int)

    @Query("UPDATE documents SET total_sentences = :total, total_chapters = :chapters, last_read_at = :ts WHERE id = :id")
    suspend fun updateTotalsAndTouch(id: String, total: Int, chapters: Int, ts: Long)

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM documents")
    suspend fun count(): Int
}
