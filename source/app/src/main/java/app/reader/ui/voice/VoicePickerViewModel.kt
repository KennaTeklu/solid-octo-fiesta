package app.reader.ui.voice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.reader.ReaderApp
import app.reader.data.repo.SettingsRepository
import app.reader.tts.TtsController
import app.reader.tts.TtsEngineInfo
import app.reader.tts.TtsEngineRegistry
import app.reader.tts.VoiceCatalog
import app.reader.tts.VoiceInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Backs the full voice picker (TTS-01, TTS-04, VOICE-01..VOICE-04).
 *
 * The voice list comes from [TtsController.voices], which is re-populated
 * whenever the engine changes. This VM exposes:
 *  - [voices] : all installed voices for the current engine.
 *  - [selectedEngine] : the current engine package.
 *  - [engines] : all installed TTS engines.
 *  - [selectedVoice] : the currently selected voice signature.
 *  - [query] : the live search query.
 *  - [groupByEngine] : toggle between "by language" and "by engine".
 *
 * Selection is persisted via [SettingsRepository.setVoice]; the controller
 * picks it up via the foreground service's settings observer.
 */
class VoicePickerViewModel : ViewModel() {

    private val settings = ReaderApp.settingsRepository
    private val controller = TtsController.get()

    val voices: List<VoiceInfo> get() = controller.voices

    val selectedVoice: StateFlow<String?> =
        settings.voice.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val selectedEngine: StateFlow<String?> =
        settings.selectedEngine.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _groupByEngine = MutableStateFlow(false)
    val groupByEngine: StateFlow<Boolean> = _groupByEngine.asStateFlow()

    private val _engines = MutableStateFlow<List<TtsEngineInfo>>(emptyList())
    val engines: StateFlow<List<TtsEngineInfo>> = _engines.asStateFlow()

    init {
        refreshEngines()
    }

    fun refreshEngines() {
        viewModelScope.launch {
            _engines.value = TtsEngineRegistry.list(ReaderApp.get())
        }
    }

    fun setQuery(q: String) { _query.value = q }
    fun toggleGrouping() { _groupByEngine.value = !_groupByEngine.value }

    fun selectVoice(signature: String?) {
        viewModelScope.launch { settings.setVoice(signature) }
    }

    fun selectEngine(pkg: String?) {
        viewModelScope.launch {
            settings.setSelectedEngine(pkg)
            // Re-init the TTS engine so the new voice list is available.
            controller.reinitEngine(pkg)
            // Voice selection may no longer be valid under the new engine.
            // Clear it so the engine default is used until the user picks again.
            settings.setVoice(null)
        }
    }
}
