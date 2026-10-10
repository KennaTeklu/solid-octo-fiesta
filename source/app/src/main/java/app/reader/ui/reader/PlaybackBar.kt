package app.reader.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.FastRewind
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.reader.parser.PdfStreamingParser
import app.reader.tts.PlaybackState
import java.util.Locale

/**
 * The bottom playback bar — primary transport controls, a progress
 * indicator, and a small status line. Mirrors the layout @Voice uses so a
 * user familiar with the original feels at home.
 *
 * Batch 2 (RD-10, X-13): while a PDF is streaming in, the bar reflects
 * parse progress (Page X of Y, %). Once parsing completes, it shows the
 * usual reading-position progress (Sentence done/total).
 */
@Composable
fun PlaybackBar(
    state: PlaybackState,
    onPlayPause: () -> Unit,
    onPrevSentence: () -> Unit,
    onNextSentence: () -> Unit,
    onPrevParagraph: () -> Unit,
    onNextParagraph: () -> Unit,
    onStop: () -> Unit,
    /** Live PDF parse state — when non-null, the bar shows parse progress. */
    pdfLoading: PdfStreamingParser.State.Loading? = null
) {
    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // --- Status row: title + counter ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val title = when (state) {
                    is PlaybackState.Playing -> state.title
                    is PlaybackState.Paused -> state.title
                    is PlaybackState.Ready -> state.title
                    is PlaybackState.Ended -> "Finished"
                    is PlaybackState.Loading -> "Loading…"
                    is PlaybackState.Error -> "Error"
                    PlaybackState.Idle -> ""
                }
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1
                )
                // Counter: while parsing show "Page X of Y", otherwise show
                // sentence progress.
                val counterText = if (pdfLoading != null && pdfLoading.pagesTotal > 0) {
                    "Page ${pdfLoading.pagesDone}/${pdfLoading.pagesTotal}"
                } else {
                    val (done, total) = when (state) {
                        is PlaybackState.Playing -> state.sentencesDone to state.sentencesTotal
                        is PlaybackState.Paused -> state.done to state.total
                        else -> 0 to 0
                    }
                    if (total > 0) "$done/$total" else ""
                }
                if (counterText.isNotEmpty()) {
                    Text(
                        counterText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(4.dp))

            // --- Progress bar: parse progress while parsing, reading progress after ---
            val progress: Float = if (pdfLoading != null && pdfLoading.pagesTotal > 0) {
                pdfLoading.pagesDone.toFloat() / pdfLoading.pagesTotal
            } else {
                val total = when (state) {
                    is PlaybackState.Playing -> state.sentencesTotal
                    is PlaybackState.Paused -> state.total
                    else -> 0
                }
                val done = when (state) {
                    is PlaybackState.Playing -> state.sentencesDone
                    is PlaybackState.Paused -> state.done
                    else -> 0
                }
                if (total > 0) done.toFloat() / total else 0f
            }
            val showBar = pdfLoading != null ||
                state is PlaybackState.Playing ||
                state is PlaybackState.Paused
            AnimatedVisibility(visible = showBar, enter = fadeIn(), exit = fadeOut()) {
                Column {
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(4.dp)
                    )
                    if (pdfLoading != null) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            String.format(Locale.US, "Parsing %d%%", (progress * 100).toInt()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }

            // --- Transport row ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onPrevParagraph) {
                    Icon(Icons.Outlined.FastRewind, contentDescription = "Previous paragraph")
                }
                IconButton(onClick = onPrevSentence) {
                    Icon(Icons.Outlined.SkipPrevious, contentDescription = "Previous sentence")
                }
                IconButton(onClick = onPlayPause) {
                    Icon(
                        imageVector = if (state is PlaybackState.Playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                        contentDescription = if (state is PlaybackState.Playing) "Pause" else "Play",
                        modifier = Modifier.size(40.dp)
                    )
                }
                IconButton(onClick = onNextSentence) {
                    Icon(Icons.Outlined.SkipNext, contentDescription = "Next sentence")
                }
                IconButton(onClick = onNextParagraph) {
                    Icon(Icons.Outlined.FastForward, contentDescription = "Next paragraph")
                }
                IconButton(onClick = onStop) {
                    Icon(Icons.Outlined.Stop, contentDescription = "Stop")
                }
            }
        }
    }
}
