package app.reader.data.repo

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "reader_settings")

/**
 * User preferences backed by DataStore. Everything is offline and local.
 *
 * Why DataStore over Room? Each value is a single primitive; there's no
 * relational shape to model, and DataStore handles the IO scheduler + atomic
 * writes for us.
 */
class SettingsRepository private constructor(private val ds: DataStore<Preferences>) {

    val theme: Flow<String> = ds.data.map { it[KEY_THEME] ?: "system" }
    val dynamicColor: Flow<Boolean> = ds.data.map { it[KEY_DYNAMIC] ?: true }
    val fontScale: Flow<Float> = ds.data.map { it[KEY_FONT_SCALE] ?: 1.0f }
    val keepScreenOn: Flow<Boolean> = ds.data.map { it[KEY_KEEP_SCREEN_ON] ?: true }
    val autoScroll: Flow<Boolean> = ds.data.map { it[KEY_AUTOSCROLL] ?: true }
    val highlightStyle: Flow<String> = ds.data.map { it[KEY_HIGHLIGHT] ?: "sentence" }
    val pauseAtChapter: Flow<Boolean> = ds.data.map { it[KEY_PAUSE_AT_CHAPTER] ?: false }

    /** Speech rate, 0.5 = half speed, 2.0 = double. */
    val speechRate: Flow<Float> = ds.data.map { it[KEY_RATE] ?: 1.0f }
    val pitch: Flow<Float> = ds.data.map { it[KEY_PITCH] ?: 1.0f }
    /** Selected voice signature, or null to use the engine default. */
    val voice: Flow<String?> = ds.data.map { it[KEY_VOICE] }

    suspend fun setTheme(value: String) = ds.edit { it[KEY_THEME] = value }
    suspend fun setDynamicColor(value: Boolean) = ds.edit { it[KEY_DYNAMIC] = value }
    suspend fun setFontScale(value: Float) = ds.edit { it[KEY_FONT_SCALE] = value }
    suspend fun setKeepScreenOn(value: Boolean) = ds.edit { it[KEY_KEEP_SCREEN_ON] = value }
    suspend fun setAutoScroll(value: Boolean) = ds.edit { it[KEY_AUTOSCROLL] = value }
    suspend fun setHighlightStyle(value: String) = ds.edit { it[KEY_HIGHLIGHT] = value }
    suspend fun setPauseAtChapter(value: Boolean) = ds.edit { it[KEY_PAUSE_AT_CHAPTER] = value }
    suspend fun setSpeechRate(value: Float) = ds.edit { it[KEY_RATE] = value }
    suspend fun setPitch(value: Float) = ds.edit { it[KEY_PITCH] = value }
    suspend fun setVoice(value: String?) = ds.edit {
        if (value == null) it.remove(KEY_VOICE) else it[KEY_VOICE] = value
    }

    companion object {
        private val KEY_THEME = stringPreferencesKey("theme")
        private val KEY_DYNAMIC = booleanPreferencesKey("dynamic_color")
        private val KEY_FONT_SCALE = floatPreferencesKey("font_scale")
        private val KEY_KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        private val KEY_AUTOSCROLL = booleanPreferencesKey("autoscroll")
        private val KEY_HIGHLIGHT = stringPreferencesKey("highlight_style")
        private val KEY_PAUSE_AT_CHAPTER = booleanPreferencesKey("pause_at_chapter")
        private val KEY_RATE = floatPreferencesKey("speech_rate")
        private val KEY_PITCH = floatPreferencesKey("pitch")
        private val KEY_VOICE = stringPreferencesKey("voice")

        @Volatile
        private var instance: SettingsRepository? = null
        fun get(context: Context): SettingsRepository =
            instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext.dataStore).also { instance = it }
            }
    }
}
