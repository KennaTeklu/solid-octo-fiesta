package app.reader.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media.app.NotificationCompat.MediaStyle
import app.reader.MainActivity
import app.reader.R
import app.reader.ReaderApp
import app.reader.data.repo.SettingsRepository
import app.reader.tts.PlaybackState
import app.reader.tts.TtsController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Foreground playback service. Owns the [TtsController] lifecycle and a
 * [MediaSessionCompat] so the user gets lock-screen / notification transport
 * controls (play, pause, skip-sentence, stop).
 *
 * Why [MediaSessionCompat] over Media3's MediaSessionService? Media3's
 * MediaSessionService requires a [androidx.media3.common.Player]
 * implementation, and TTS doesn't fit the Player timeline model cleanly
 * (no buffer, no seek-bar). MediaSessionCompat gives us transport buttons
 * without forcing a Player-shaped adapter — less code, fewer bugs.
 *
 * Lifecycle:
 *  - [ACTION_START] : bring the service to the foreground and start observing state.
 *  - [ACTION_STOP] : tear everything down.
 *  - Transport actions ([ACTION_PLAY], etc.) : forward to the TtsController.
 *  - When state transitions to [PlaybackState.Ended] or [PlaybackState.Idle],
 *    we move the service to a "paused foreground" state for ~1.5s then stopSelf.
 */
class PlaybackService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observerJob: Job? = null
    private var settingsJob: Job? = null
    private lateinit var mediaSession: MediaSessionCompat

    private val controller by lazy { TtsController.get(this) }

    override fun onCreate() {
        super.onCreate()
        mediaSession = MediaSessionCompat(this, "reader-playback").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS or
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS
            )
            setCallback(mediaCallback)
            isActive = true
        }
        observeSettings()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startForegroundIfNeeded()
            ACTION_PLAY -> controller.play()
            ACTION_PAUSE -> controller.pause()
            ACTION_PLAY_PAUSE -> controller.togglePlayPause()
            ACTION_NEXT_SENTENCE -> controller.skipNextSentence()
            ACTION_PREV_SENTENCE -> controller.skipPreviousSentence()
            ACTION_NEXT_PARAGRAPH -> controller.skipNextParagraph()
            ACTION_PREV_PARAGRAPH -> controller.skipPreviousParagraph()
            ACTION_STOP -> {
                controller.stop()
                stopForegroundCompat()
                stopSelf()
            }
        }
        // Subscribe to state updates so the notification reflects reality.
        startObserving()
        return START_STICKY
    }

    private fun startForegroundIfNeeded() {
        val notification = buildNotification(state = controller.state.value, canCancel = false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startObserving() {
        if (observerJob?.isActive == true) return
        observerJob = scope.launch {
            controller.state.collectLatest { state ->
                updateNotification(state)
                updateMediaSession(state)
                if (state is PlaybackState.Ended || state is PlaybackState.Idle) {
                    kotlinx.coroutines.delay(1500)
                    stopForegroundCompat()
                    stopSelf()
                }
            }
        }
    }

    private fun observeSettings() {
        val settings = SettingsRepository.get(this)
        settingsJob?.cancel()
        settingsJob = scope.launch {
            combine(
                settings.speechRate,
                settings.pitch,
                settings.speechVolume,
                settings.voice
            ) { _, _, _, _ -> Unit }.collect {
                controller.refreshVoiceSettings()
            }
        }
    }

    private fun updateNotification(state: PlaybackState) {
        val notification = buildNotification(state, canCancel = true)
        val nm = NotificationManagerCompat.from(this)
        try { nm.notify(NOTIFICATION_ID, notification) } catch (_: SecurityException) { /* ignore */ }
    }

    private fun updateMediaSession(state: PlaybackState) {
        val builder = PlaybackStateCompat.Builder()
        val actions = when (state) {
            is PlaybackState.Playing -> PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
            is PlaybackState.Paused, is PlaybackState.Ready -> PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
            else -> PlaybackStateCompat.ACTION_STOP
        }
        builder.setActions(actions)
        val stateCode = when (state) {
            is PlaybackState.Playing -> PlaybackStateCompat.STATE_PLAYING
            is PlaybackState.Paused -> PlaybackStateCompat.STATE_PAUSED
            is PlaybackState.Ready -> PlaybackStateCompat.STATE_PAUSED
            is PlaybackState.Loading -> PlaybackStateCompat.STATE_BUFFERING
            is PlaybackState.Ended -> PlaybackStateCompat.STATE_STOPPED
            is PlaybackState.MissingVoice -> PlaybackStateCompat.STATE_ERROR
            is PlaybackState.Error -> PlaybackStateCompat.STATE_ERROR
            PlaybackState.Idle -> PlaybackStateCompat.STATE_NONE
        }
        builder.setState(stateCode, 0L, 1.0f)
        mediaSession.setPlaybackState(builder.build())

        if (state is PlaybackState.Playing) {
            val metadata = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, state.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Reader")
                .putLong(MediaMetadataCompat.METADATA_KEY_NUM_TRACKS, state.sentencesTotal.toLong())
                .putLong(MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER, state.sentencesDone.toLong())
                .build()
            mediaSession.setMetadata(metadata)
        } else if (state is PlaybackState.Paused) {
            val metadata = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, state.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Reader")
                .putLong(MediaMetadataCompat.METADATA_KEY_NUM_TRACKS, state.total.toLong())
                .putLong(MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER, state.done.toLong())
                .build()
            mediaSession.setMetadata(metadata)
        }
    }

    private fun buildNotification(state: PlaybackState, canCancel: Boolean): android.app.Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val title = when (state) {
            is PlaybackState.Playing -> state.title
            is PlaybackState.Paused -> state.title
            is PlaybackState.Ready -> state.title
            is PlaybackState.Ended -> "Finished"
            is PlaybackState.Loading -> "Loading…"
            is PlaybackState.MissingVoice -> "Voice missing"
            is PlaybackState.Error -> "Playback error"
            PlaybackState.Idle -> "Reader"
        }

        val builder = NotificationCompat.Builder(this, ReaderApp.CHANNEL_PLAYBACK)
            .setContentTitle(title)
            .setContentText(subtitleFor(state))
            .setSmallIcon(R.drawable.ic_status)
            .setContentIntent(contentIntent)
            .setOngoing(state is PlaybackState.Playing || state is PlaybackState.Paused)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(R.drawable.ic_prev, getString(R.string.playback_skip_prev_sentence), pending(ACTION_PREV_SENTENCE))
            .addAction(
                if (state is PlaybackState.Playing) R.drawable.ic_pause else R.drawable.ic_play,
                if (state is PlaybackState.Playing) getString(R.string.playback_pause) else getString(R.string.playback_play),
                pending(ACTION_PLAY_PAUSE)
            )
            .addAction(R.drawable.ic_next, getString(R.string.playback_skip_next_sentence), pending(ACTION_NEXT_SENTENCE))
            .addAction(R.drawable.ic_stop, getString(R.string.playback_stop), pending(ACTION_STOP))

        val style = MediaStyle()
            .setMediaSession(mediaSession.sessionToken)
            .setShowActionsInCompactView(0, 1, 2)
        builder.setStyle(style)

        return builder.build()
    }

    private fun subtitleFor(state: PlaybackState): String = when (state) {
        is PlaybackState.Playing -> "Sentence ${state.sentencesDone}/${state.sentencesTotal}"
        is PlaybackState.Paused -> "Paused — sentence ${state.done}/${state.total}"
        is PlaybackState.Ready -> "Ready"
        is PlaybackState.Loading -> "Loading…"
        is PlaybackState.Ended -> "Reached the end"
        is PlaybackState.MissingVoice -> "Voice missing for ${state.languageDisplayName}"
        is PlaybackState.Error -> state.message
        PlaybackState.Idle -> ""
    }

    private fun pending(action: String): PendingIntent =
        PendingIntent.getService(
            this, action.hashCode(),
            Intent(this, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        observerJob?.cancel()
        settingsJob?.cancel()
        mediaSession.isActive = false
        mediaSession.release()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private val mediaCallback = object : MediaSessionCompat.Callback() {
        override fun onPlay() = controller.play()
        override fun onPause() = controller.pause()
        override fun onSkipToNext() = controller.skipNextSentence()
        override fun onSkipToPrevious() = controller.skipPreviousSentence()
        override fun onStop() { controller.stop(); stopForegroundCompat(); stopSelf() }
    }

    companion object {
        const val NOTIFICATION_ID = 4242
        const val ACTION_START = "app.reader.action.START"
        const val ACTION_PLAY = "app.reader.action.PLAY"
        const val ACTION_PAUSE = "app.reader.action.PAUSE"
        const val ACTION_PLAY_PAUSE = "app.reader.action.PLAY_PAUSE"
        const val ACTION_STOP = "app.reader.action.STOP"
        const val ACTION_NEXT_SENTENCE = "app.reader.action.NEXT_SENTENCE"
        const val ACTION_PREV_SENTENCE = "app.reader.action.PREV_SENTENCE"
        const val ACTION_NEXT_PARAGRAPH = "app.reader.action.NEXT_PARAGRAPH"
        const val ACTION_PREV_PARAGRAPH = "app.reader.action.PREV_PARAGRAPH"

        fun start(context: Context) {
            val intent = Intent(context, PlaybackService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
