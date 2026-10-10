package app.reader.ui.settings

import android.content.Intent
import android.speech.tts.TextToSpeech
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.reader.ReaderApp
import app.reader.data.repo.CloudKeyRepository
import app.reader.data.repo.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel : ViewModel() {
    private val settings = ReaderApp.settingsRepository
    private val cloudKeys = ReaderApp.cloudKeyRepository

    val theme = settings.theme.stateIn(viewModelScope, SharingStarted.Eagerly, "system")
    val dynamicColor = settings.dynamicColor.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val fontScale = settings.fontScale.stateIn(viewModelScope, SharingStarted.Eagerly, 1.0f)
    val keepScreenOn = settings.keepScreenOn.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val autoScroll = settings.autoScroll.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val highlightStyle = settings.highlightStyle.stateIn(viewModelScope, SharingStarted.Eagerly, "sentence")
    val pauseAtChapter = settings.pauseAtChapter.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // --- Voice group ---
    val speechRate = settings.speechRate.stateIn(viewModelScope, SharingStarted.Eagerly, 1.0f)
    val pitch = settings.pitch.stateIn(viewModelScope, SharingStarted.Eagerly, 1.0f)
    val speechVolume = settings.speechVolume.stateIn(viewModelScope, SharingStarted.Eagerly, 1.0f)
    val voice = settings.voice.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val selectedEngine = settings.selectedEngine.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // --- Advanced subsection ---
    val rateMin = settings.rateMin.stateIn(viewModelScope, SharingStarted.Eagerly, 0.5f)
    val rateMax = settings.rateMax.stateIn(viewModelScope, SharingStarted.Eagerly, 3.0f)
    val rateStep = settings.rateStep.stateIn(viewModelScope, SharingStarted.Eagerly, 0.25f)

    // --- Cloud voice keys (encrypted) ---
    val openaiKey = cloudKeys.observe(CloudKeyRepository.Key.OPENAI_API_KEY)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val azureKey = cloudKeys.observe(CloudKeyRepository.Key.AZURE_API_KEY)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val pollyKey = cloudKeys.observe(CloudKeyRepository.Key.POLLY_API_KEY)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val customUrl = cloudKeys.observe(CloudKeyRepository.Key.CUSTOM_ENDPOINT_URL)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val customKey = cloudKeys.observe(CloudKeyRepository.Key.CUSTOM_ENDPOINT_KEY)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val customModel = cloudKeys.observe(CloudKeyRepository.Key.CUSTOM_ENDPOINT_MODEL)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun setTheme(value: String) = viewModelScope.launch { settings.setTheme(value) }
    fun setDynamicColor(value: Boolean) = viewModelScope.launch { settings.setDynamicColor(value) }
    fun setFontScale(value: Float) = viewModelScope.launch { settings.setFontScale(value) }
    fun setKeepScreenOn(value: Boolean) = viewModelScope.launch { settings.setKeepScreenOn(value) }
    fun setAutoScroll(value: Boolean) = viewModelScope.launch { settings.setAutoScroll(value) }
    fun setHighlightStyle(value: String) = viewModelScope.launch { settings.setHighlightStyle(value) }
    fun setPauseAtChapter(value: Boolean) = viewModelScope.launch { settings.setPauseAtChapter(value) }
    fun setSpeechRate(value: Float) = viewModelScope.launch { settings.setSpeechRate(value) }
    fun setPitch(value: Float) = viewModelScope.launch { settings.setPitch(value) }
    fun setSpeechVolume(value: Float) = viewModelScope.launch { settings.setSpeechVolume(value) }
    fun setVoice(value: String?) = viewModelScope.launch { settings.setVoice(value) }
    fun setRateMin(value: Float) = viewModelScope.launch { settings.setRateMin(value) }
    fun setRateMax(value: Float) = viewModelScope.launch { settings.setRateMax(value) }
    fun setRateStep(value: Float) = viewModelScope.launch { settings.setRateStep(value) }

    fun setOpenaiKey(value: String) = viewModelScope.launch { cloudKeys.set(CloudKeyRepository.Key.OPENAI_API_KEY, value) }
    fun setAzureKey(value: String) = viewModelScope.launch { cloudKeys.set(CloudKeyRepository.Key.AZURE_API_KEY, value) }
    fun setPollyKey(value: String) = viewModelScope.launch { cloudKeys.set(CloudKeyRepository.Key.POLLY_API_KEY, value) }
    fun setCustomUrl(value: String) = viewModelScope.launch { cloudKeys.set(CloudKeyRepository.Key.CUSTOM_ENDPOINT_URL, value) }
    fun setCustomKey(value: String) = viewModelScope.launch { cloudKeys.set(CloudKeyRepository.Key.CUSTOM_ENDPOINT_KEY, value) }
    fun setCustomModel(value: String) = viewModelScope.launch { cloudKeys.set(CloudKeyRepository.Key.CUSTOM_ENDPOINT_MODEL, value) }

    fun previewVoice() {
        // Use a short-lived TTS instance so the preview doesn't disturb
        // real playback (which is owned by the singleton TtsController).
        // We apply the user's current rate / pitch / volume settings so the
        // preview reflects what they'll actually hear.
        viewModelScope.launch {
            val rate = settings.speechRate.first()
            val pitch = settings.pitch.first()
            val text = "This is a sample sentence in your selected voice."
            // Kotlin forbids referencing a val inside its own initializer
            // lambda, so we declare a nullable var first and let the lambda
            // capture it by reference.
            var previewTts: TextToSpeech? = null
            previewTts = TextToSpeech(ReaderApp.get()) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val t = previewTts ?: return@TextToSpeech
                    t.setSpeechRate(rate.coerceIn(0.1f, 4f))
                    t.setPitch(pitch.coerceIn(0.1f, 2f))
                    t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "settings_preview")
                }
            }
        }
    }

    fun openSystemTts(): Intent =
        Intent("com.android.settings.TTS_SETTINGS")
}
