package app.reader.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.reader.data.db.entity.QueueItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface QueueDao {

    @Query("SELECT * FROM queue ORDER BY position ASC")
    fun observeAll(): Flow<List<QueueItemEntity>>

    @Query("SELECT COUNT(*) FROM queue WHERE doc_id = :docId")
    suspend fun countFor(docId: String): Int

    @Query("SELECT MAX(position) FROM queue")
    suspend fun maxPosition(): Int?

    @Query("UPDATE queue SET position = :position WHERE doc_id = :docId")
    suspend fun setPosition(docId: String, position: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: QueueItemEntity): Long

    @Query("DELETE FROM queue WHERE doc_id = :docId")
    suspend fun remove(docId: String)

    @Query("DELETE FROM queue")
    suspend fun clear()

    @Query("DELETE FROM queue WHERE id = :id")
    suspend fun deleteById(id: Long)
}
