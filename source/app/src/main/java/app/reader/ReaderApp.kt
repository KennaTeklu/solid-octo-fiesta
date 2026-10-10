package app.reader

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import app.reader.data.db.ReaderDatabase
import app.reader.data.repo.CloudKeyRepository
import app.reader.data.repo.DocumentRepository
import app.reader.data.repo.SettingsRepository

/**
 * Application bootstrap. Creates the playback notification channel and
 * initialises the PDF text-extraction library. No analytics, no crash
 * reporters, no third-party init.
 */
class ReaderApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        PDFBoxResourceLoader.init(applicationContext)
        createPlaybackChannel()
        _database = ReaderDatabase.get(this)
        _settings = SettingsRepository.get(this)
        _docs = DocumentRepository.get(this)
        _cloudKeys = CloudKeyRepository.get(this)
    }

    private fun createPlaybackChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_PLAYBACK,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_PLAYBACK = "playback"

        @Volatile
        private var instance: ReaderApp? = null

        @Volatile
        private var _database: ReaderDatabase? = null
        @Volatile
        private var _settings: SettingsRepository? = null
        @Volatile
        private var _docs: DocumentRepository? = null
        @Volatile
        private var _cloudKeys: CloudKeyRepository? = null

        fun get(): ReaderApp = instance!!
        val database: ReaderDatabase get() = _database!!
        val settingsRepository: SettingsRepository get() = _settings!!
        val documentRepository: DocumentRepository get() = _docs!!
        val cloudKeyRepository: CloudKeyRepository get() = _cloudKeys!!
    }
}
