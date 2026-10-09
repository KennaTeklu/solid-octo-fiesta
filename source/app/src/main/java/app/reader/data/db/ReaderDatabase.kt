package app.reader.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import app.reader.data.db.dao.BookmarkDao
import app.reader.data.db.dao.DocumentDao
import app.reader.data.db.dao.QueueDao
import app.reader.data.db.entity.BookmarkEntity
import app.reader.data.db.entity.DocumentEntity
import app.reader.data.db.entity.QueueItemEntity

/**
 * The single Room database backing the app: documents, bookmarks, and the
 * reading-list queue. No network, no sync, no migrations beyond the
 * destructive fallback when we change the schema in v1.x.
 */
@Database(
    entities = [
        DocumentEntity::class,
        BookmarkEntity::class,
        QueueItemEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class ReaderDatabase : RoomDatabase() {
    abstract fun documents(): DocumentDao
    abstract fun bookmarks(): BookmarkDao
    abstract fun queue(): QueueDao

    companion object {
        @Volatile
        private var instance: ReaderDatabase? = null

        fun get(context: Context): ReaderDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ReaderDatabase::class.java,
                    "reader.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
