package app.reader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import app.reader.data.repo.DocumentRepository
import app.reader.data.repo.IntakeBus
import app.reader.data.repo.AppTheme
import app.reader.ui.nav.ReaderNavHost
import app.reader.ui.theme.ReaderTheme
import kotlinx.coroutines.launch

/**
 * Single activity host. Routes SEND / VIEW intents to the nav graph
 * via the [IntakeBus] so the UI layer can react.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            val themeKey by ReaderApp.settingsRepository.theme.collectAsState(initial = "system")
            val initialIntake by IntakeBus.pendingIntents.collectAsState()
            val theme = when (themeKey) {
                "light" -> AppTheme.Light
                "dark" -> AppTheme.Dark
                else -> AppTheme.System
            }
            ReaderTheme(theme = theme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ReaderNavHost(initialIntake = initialIntake)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** Decode an incoming SEND / VIEW intent into a pending intake the UI consumes. */
    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return
        when (action) {
            Intent.ACTION_SEND -> {
                val type = intent.type ?: "text/plain"
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                val uri: Uri? = intent.getParcelableExtra(Intent.EXTRA_STREAM)
                if (text != null && text.isNotBlank()) {
                    IntakeBus.offerText(text)
                } else if (uri != null) {
                    lifecycleScope.launch {
                        DocumentRepository.get(this@MainActivity).importFromUri(
                            uri = uri,
                            mime = type,
                            title = null
                        ).fold(
                            onSuccess = { IntakeBus.offerDocId(it) },
                            onFailure = { IntakeBus.offerError(it.message ?: "Failed to import") }
                        )
                    }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val list: ArrayList<Uri> = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM) ?: ArrayList()
                lifecycleScope.launch {
                    var firstId: String? = null
                    list.forEach { uri ->
                        DocumentRepository.get(this@MainActivity).importFromUri(uri, intent.type ?: "*/*", null)
                            .onSuccess { if (firstId == null) firstId = it }
                    }
                    firstId?.let { IntakeBus.offerDocId(it) }
                }
            }
            Intent.ACTION_VIEW -> {
                val uri = intent.data ?: return
                lifecycleScope.launch {
                    DocumentRepository.get(this@MainActivity).importFromUri(
                        uri = uri,
                        mime = intent.type ?: "*/*",
                        title = null
                    ).fold(
                        onSuccess = { IntakeBus.offerDocId(it) },
                        onFailure = { IntakeBus.offerError(it.message ?: "Failed to import") }
                    )
                }
            }
        }
    }
}
