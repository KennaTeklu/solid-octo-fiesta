package app.reader.data.repo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * In-memory bus for delivering pending SEND / VIEW intents from the activity
 * to the Compose navigation layer. The first screen reads [pendingIntents] and
 * dispatches the user to the right destination (paste screen, reader, error
 * toast) and then calls [consume] so the same intent isn't re-handled on
 * recomposition.
 */
object IntakeBus {
    val pendingIntents: StateFlow<Intake?> = MutableStateFlow(null)

    fun offerText(text: String) {
        (pendingIntents as MutableStateFlow).value = Intake.Text(text)
    }

    fun offerDocId(id: String) {
        (pendingIntents as MutableStateFlow).value = Intake.OpenDocument(id)
    }

    fun offerError(message: String) {
        (pendingIntents as MutableStateFlow).value = Intake.Error(message)
    }

    fun consume() {
        (pendingIntents as MutableStateFlow).value = null
    }
}

sealed interface Intake {
    data class Text(val text: String) : Intake
    data class OpenDocument(val docId: String) : Intake
    data class Error(val message: String) : Intake
    data object None : Intake
}
