package app.reader.ui.settings

import android.content.Intent
import android.provider.Settings
import android.speech.tts.TextToSpeech
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.reader.data.repo.AppTheme
import app.reader.data.repo.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale

class SettingsViewModel : ViewModel() {
    private val settings = app.reader.ReaderApp.settingsRepository

    val theme = settings.theme.stateIn(viewModelScope, SharingStarted.Eagerly, "system")
    val dynamicColor = settings.dynamicColor.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val fontScale = settings.fontScale.stateIn(viewModelScope, SharingStarted.Eagerly, 1.0f)
    val keepScreenOn = settings.keepScreenOn.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val autoScroll = settings.autoScroll.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val highlightStyle = settings.highlightStyle.stateIn(viewModelScope, SharingStarted.Eagerly, "sentence")
    val pauseAtChapter = settings.pauseAtChapter.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val speechRate = settings.speechRate.stateIn(viewModelScope, SharingStarted.Eagerly, 1.0f)
    val pitch = settings.pitch.stateIn(viewModelScope, SharingStarted.Eagerly, 1.0f)
    val voice = settings.voice.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val tts = TextToSpeech(app.reader.ReaderApp.get()) {}

    data class VoiceOption(val signature: String, val label: String, val locale: Locale)
    val voices: List<VoiceOption> = run {
        try {
            tts.voices.map { v ->
                val loc = v.locale
                VoiceOption(
                    signature = "${loc.toLanguageTag()}|${v.name}",
                    label = "${loc.displayName} — ${v.name}",
                    locale = loc
                )
            }.sortedBy { it.label }
        } catch (_: Throwable) { emptyList() }
    }

    fun setTheme(value: String) = viewModelScope.launch { settings.setTheme(value) }
    fun setDynamicColor(value: Boolean) = viewModelScope.launch { settings.setDynamicColor(value) }
    fun setFontScale(value: Float) = viewModelScope.launch { settings.setFontScale(value) }
    fun setKeepScreenOn(value: Boolean) = viewModelScope.launch { settings.setKeepScreenOn(value) }
    fun setAutoScroll(value: Boolean) = viewModelScope.launch { settings.setAutoScroll(value) }
    fun setHighlightStyle(value: String) = viewModelScope.launch { settings.setHighlightStyle(value) }
    fun setPauseAtChapter(value: Boolean) = viewModelScope.launch { settings.setPauseAtChapter(value) }
    fun setSpeechRate(value: Float) = viewModelScope.launch { settings.setSpeechRate(value) }
    fun setPitch(value: Float) = viewModelScope.launch { settings.setPitch(value) }
    fun setVoice(value: String?) = viewModelScope.launch { settings.setVoice(value) }

    fun previewVoice() {
        val text = "This is a sample sentence in your selected voice."
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "preview")
    }

    fun openSystemTts(): Intent =
        Intent("com.android.settings.TTS_SETTINGS")
}
