package app.reader.ui.voice

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Missing-voice prompt (Batch 7 — TTS-06).
 *
 * Shown when the user tries to read a language that has no installed TTS
 * voice. Two offers: open the Play Store to install a voice, or pick an
 * alternative voice (which routes to [VoicePickerScreen]).
 *
 * @param languageDisplayName Human-readable name of the missing language.
 * @param onPickAlternative Called when the user chooses to pick another voice.
 *        The host should dismiss this dialog and navigate to the voice picker.
 * @param onDismiss Called when the user dismisses the dialog. The host should
 *        keep playback paused and let the user resolve the issue later.
 */
@Composable
fun MissingVoiceDialog(
    languageDisplayName: String,
    onPickAlternative: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("No voice for $languageDisplayName") },
        text = {
            Column {
                Text(
                    "Reader needs a TTS voice for $languageDisplayName to read this document aloud. " +
                        "No installed voice matches the document's language.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Tap \"Install voice\" to open the Play Store and install a $languageDisplayName voice, " +
                        "or tap \"Pick another voice\" to use a different language's voice instead.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=$languageDisplayName+TTS+voice")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try { context.startActivity(intent) } catch (_: Throwable) {
                        val webIntent = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://play.google.com/store/search?q=${Uri.encode("$languageDisplayName TTS voice")}")
                        )
                        webIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        try { context.startActivity(webIntent) } catch (_: Throwable) {}
                    }
                }) { Text("Install voice", fontWeight = FontWeight.SemiBold) }
                TextButton(onClick = onPickAlternative) { Text("Pick another voice") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
