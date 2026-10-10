package com.peakform.fitness.core

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.peakform.fitness.ProLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Room store — structural mirror of the legacy Dexie DB "WorkoutApp" v1
 * (workouts{id,date}, exercises{id}, user{key:'main'}, savedWorkout{id:'current'})
 * plus a snapshot vault and a meta table. Rows hold the legacy JSON payloads verbatim,
 * so import/export of legacy bundles is byte-compatible.
 *
 * P1 fix (data loss): there is exactly ONE store now — no 3-way race between
 * IndexedDB, localStorage mirror and vault snapshots. The vault below is explicit,
 * user-initiated or guarded auto-snapshot only; it never auto-restores over live data.
 */

@Entity(tableName = "workouts", indices = [Index("date")])
data class WorkoutRow(@PrimaryKey val id: String, val date: String, val json: String)

@Entity(tableName = "exercises")
data class ExerciseRow(@PrimaryKey val id: String, val json: String)

@Entity(tableName = "user")
data class UserRow(@PrimaryKey val key: String, val json: String)

@Entity(tableName = "savedWorkout")
data class SavedWorkoutRow(@PrimaryKey val id: String, val json: String)

@Entity(tableName = "vault")
data class VaultRow(@PrimaryKey val profileId: String, val at: Long, val json: String)

@Entity(tableName = "meta")
data class MetaRow(@PrimaryKey val key: String, val value: String)

@Dao
interface ProDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putWorkouts(rows: List<WorkoutRow>)
    @Query("SELECT * FROM workouts ORDER BY date ASC") suspend fun allWorkouts(): List<WorkoutRow>
    @Query("SELECT json FROM workouts WHERE id = :id") suspend fun workoutJson(id: String): String?
    @Query("DELETE FROM workouts WHERE id = :id") suspend fun deleteWorkout(id: String)
    @Query("DELETE FROM workouts") suspend fun clearWorkouts()

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putExercises(rows: List<ExerciseRow>)
    @Query("SELECT * FROM exercises") suspend fun allExercises(): List<ExerciseRow>
    @Query("DELETE FROM exercises") suspend fun clearExercises()

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putUser(row: UserRow)
    @Query("SELECT json FROM user WHERE `key` = 'main'") suspend fun userJson(): String?
    @Query("DELETE FROM user") suspend fun clearUser()

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSaved(row: SavedWorkoutRow)
    @Query("SELECT json FROM savedWorkout WHERE id = 'current'") suspend fun savedJson(): String?
    @Query("DELETE FROM savedWorkout") suspend fun clearSaved()

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putVault(row: VaultRow)
    @Query("SELECT * FROM vault WHERE profileId = :pid") suspend fun vaultFor(pid: String): VaultRow?
    @Query("SELECT * FROM vault") suspend fun allVault(): List<VaultRow>
    @Query("DELETE FROM vault") suspend fun clearVault()

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putMeta(row: MetaRow)
    @Query("SELECT value FROM meta WHERE `key` = :k") suspend fun meta(k: String): String?
    @Query("DELETE FROM meta WHERE `key` IN (:keys)") suspend fun deleteMeta(keys: List<String>)

    // BATCH-4B item 2/6: synchronous meta access for per-profile state reads
    // (boot-time reads must not require a coroutine scope). These block on the
    // DAO; only safe off the main thread.
    @Query("SELECT value FROM meta WHERE `key` = :k") fun metaSync(k: String): String?
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putMetaSync(row: MetaRow)
    @Query("DELETE FROM meta WHERE `key` IN (:keys)") fun deleteMetaSync(keys: List<String>)
    @Query("SELECT `key` FROM meta") fun metaKeysSync(): List<String>

    @Query("DELETE FROM workouts") fun c1()
    @Query("DELETE FROM exercises") fun c2()
    @Query("DELETE FROM user") fun c3()
    @Query("DELETE FROM savedWorkout") fun c4()
}

@Database(
    entities = [WorkoutRow::class, ExerciseRow::class, UserRow::class, SavedWorkoutRow::class, VaultRow::class, MetaRow::class],
    version = 1,
    exportSchema = false,
)
abstract class ProDb : RoomDatabase() {
    abstract fun dao(): ProDao

    companion object {
        private val instances = mutableMapOf<String, ProDb>()
        fun get(ctx: Context, dbName: String = "pro.db"): ProDb = synchronized(instances) {
            instances[dbName] ?: Room.databaseBuilder(ctx.applicationContext, ProDb::class.java, dbName)
                .fallbackToDestructiveMigrationOnDowngrade()
                .build().also { instances[dbName] = it }
        }

        /** Close a namespace (profile switch / delete). */
        fun close(dbName: String) {
            synchronized(instances) {
                instances.remove(dbName)?.close()
            }
        }
    }
}

/**
 * SharedPreferences file "p4_ls" mirrors the legacy localStorage keys 1:1
 * (p4_profiles, p4_active_profile, p4_theme, p4_power_settings, deviceId, ...).
 * Key names identical so Complete-Backup-v1 bundles round-trip.
 */
object ProPrefs {
    private var prefs: SharedPreferences? = null
    fun get(ctx: Context): SharedPreferences =
        prefs ?: ctx.applicationContext.getSharedPreferences("p4_ls", Context.MODE_PRIVATE).also { prefs = it }

    fun put(ctx: Context, key: String, value: String) = get(ctx).edit().putString(key, value).apply()
    fun get(ctx: Context, key: String, def: String? = null): String? = get(ctx).getString(key, def)
    fun remove(ctx: Context, key: String) = get(ctx).edit().remove(key).apply()
    fun keys(ctx: Context): Set<String> = get(ctx).all.keys
    fun all(ctx: Context): Map<String, String?> = get(ctx).all.mapValues { it.value?.toString() }
    fun clearAll(ctx: Context) = get(ctx).edit().clear().commit()
}

/** Synchronous-friendly store facade with in-memory cache of the aggregate state. */
class ProStore(private val ctx: Context) {
    val dbName: String = Profiles.dbNameFor(ctx, Profiles.activeId(ctx))
    val db = ProDb.get(ctx, dbName)
    private val mutex = Mutex()

    suspend fun saveAggregate(data: WorkoutData, current: WorkoutRecord?) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val wrows = data.workouts.filter { it.id.isNotBlank() }.map {
                WorkoutRow(it.id, it.date, ProJson.encode(WorkoutRecord.serializer(), it))
            }
            db.dao().putWorkouts(wrows)
            val erows = data.exercises.map { (id, rec) ->
                ExerciseRow(id, ProJson.encode(ExerciseRecord.serializer(), rec))
            }
            db.dao().putExercises(erows)
            db.dao().putUser(UserRow("main", ProJson.encode(UserProfile.serializer(), data.user)))
            if (current != null) {
                db.dao().putSaved(SavedWorkoutRow("current", ProJson.encode(WorkoutRecord.serializer(), current)))
            }
        }
    }

    suspend fun saveCurrentWorkout(current: WorkoutRecord?) = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (current == null) db.dao().clearSaved()
            else db.dao().putSaved(SavedWorkoutRow("current", ProJson.encode(WorkoutRecord.serializer(), current)))
        }
    }

    suspend fun saveWorkoutRecord(rec: WorkoutRecord) = mutex.withLock {
        withContext(Dispatchers.IO) {
            db.dao().putWorkouts(listOf(WorkoutRow(rec.id, rec.date, ProJson.encode(WorkoutRecord.serializer(), rec))))
        }
    }

    suspend fun deleteWorkoutRecord(id: String) = mutex.withLock { withContext(Dispatchers.IO) { db.dao().deleteWorkout(id) } }

    suspend fun snapshotVault(profileId: String, data: WorkoutData, current: WorkoutRecord?) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val bundleObj = linkedMapOf<String, JsonElement>(
                "workoutData" to data.toJsonElement(),
                "currentWorkout" to (current?.let { ProJson.encodeElement(WorkoutRecord.serializer(), it) } ?: JsonNull),
                "at" to JsonPrimitive(System.currentTimeMillis()),
            )
            val bundle = JsonObject(bundleObj)
            db.dao().putVault(VaultRow(profileId, System.currentTimeMillis(), ProJson.json.encodeToString(JsonObject.serializer(), bundle)))
        }
    }

    suspend fun loadVault(profileId: String): JsonObject? = withContext(Dispatchers.IO) {
        db.dao().vaultFor(profileId)?.json?.let { ProJson.json.decodeFromString(JsonObject.serializer(), it) }
    }

    suspend fun wipeAllData() = mutex.withLock {
        withContext(Dispatchers.IO) {
            db.dao().clearWorkouts(); db.dao().clearExercises(); db.dao().clearUser(); db.dao().clearSaved(); db.dao().clearVault()
        }
    }
}
