package app.reader.ui.reader

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
import app.reader.tts.PlaybackState

/**
 * The bottom playback bar — primary transport controls, a progress
 * indicator, and a small status line. Mirrors the layout @Voice uses so a
 * user familiar with the original feels at home.
 */
@Composable
fun PlaybackBar(
    state: PlaybackState,
    onPlayPause: () -> Unit,
    onPrevSentence: () -> Unit,
    onNextSentence: () -> Unit,
    onPrevParagraph: () -> Unit,
    onNextParagraph: () -> Unit,
    onStop: () -> Unit
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
                val (done, total) = when (state) {
                    is PlaybackState.Playing -> state.sentencesDone to state.sentencesTotal
                    is PlaybackState.Paused -> state.done to state.total
                    else -> 0 to 0
                }
                if (total > 0) {
                    Text(
                        "$done/$total",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            if (state is PlaybackState.Playing || state is PlaybackState.Paused) {
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
                val progress = if (total > 0) done.toFloat() / total else 0f
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(4.dp)
                )
                Spacer(Modifier.height(8.dp))
            }
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
