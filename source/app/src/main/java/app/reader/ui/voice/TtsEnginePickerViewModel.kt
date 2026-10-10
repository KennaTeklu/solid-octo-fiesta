package app.reader.ui.voice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.reader.ReaderApp
import app.reader.data.repo.SettingsRepository
import app.reader.tts.TtsController
import app.reader.tts.TtsEngineInfo
import app.reader.tts.TtsEngineRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Backs the TTS engine picker (TTS-05). Lists every installed Android TTS
 * engine (Google TTS, Samsung TTS, etc.). Selecting one persists the choice
 * and re-initialises the [TtsController] — which in turn repopulates the
 * voice list shown in [VoicePickerScreen].
 *
 * EXPERT-MAP #13 (TTS Engine Integration): engines are queried via the
 * `android.intent.action.TTS_DATA_SERVICE` intent. On API 30+ this requires
 * the `<queries>` block in AndroidManifest.xml.
 */
class TtsEnginePickerViewModel : ViewModel() {

    private val settings = ReaderApp.settingsRepository
    private val controller = TtsController.get()

    val selectedEngine: StateFlow<String?> =
        settings.selectedEngine.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _engines = MutableStateFlow<List<TtsEngineInfo>>(emptyList())
    val engines: StateFlow<List<TtsEngineInfo>> = _engines.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _engines.value = TtsEngineRegistry.list(ReaderApp.get())
        }
    }

    fun select(pkg: String?) {
        viewModelScope.launch {
            settings.setSelectedEngine(pkg)
            controller.reinitEngine(pkg)
            // Voice selection from the previous engine no longer applies.
            settings.setVoice(null)
        }
    }
}
