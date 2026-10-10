package app.reader.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.FastRewind
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
 *
 * Batch 7 (PB-05..PB-08): an expand chevron reveals a speed slider, a pitch
 * slider, and a volume slider. Changes apply IMMEDIATELY to the running
 * playback via [onRateChange] / [onPitchChange] / [onVolumeChange] — the
 * current sentence is NOT restarted. Android's `setSpeechRate` /
 * `setPitch` apply to the next queued utterance; this is the correct
 * interpretation of "do not restart the sentence".
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
    pdfLoading: PdfStreamingParser.State.Loading? = null,
    /** Live speech rate, 0.5..3.0. Driven by [onRateChange]. */
    rate: Float = 1.0f,
    /** Live pitch, 0.5..2.0. Driven by [onPitchChange]. */
    pitch: Float = 1.0f,
    /** Live speech volume, 0..1. Driven by [onVolumeChange]. */
    volume: Float = 1.0f,
    /** Min/max for the rate slider (from Advanced settings). */
    rateMin: Float = 0.5f,
    rateMax: Float = 3.0f,
    /** Step applied on the +/- rate buttons. */
    rateStep: Float = 0.25f,
    /** Apply the new rate to the running engine immediately. */
    onRateChange: (Float) -> Unit = {},
    onPitchChange: (Float) -> Unit = {},
    onVolumeChange: (Float) -> Unit = {}
) {
    var expanded by remember { mutableStateOf(false) }

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
            // --- Status row: title + counter + expand chevron ---
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
                    is PlaybackState.MissingVoice -> "Voice missing"
                    is PlaybackState.Error -> "Error"
                    PlaybackState.Idle -> ""
                }
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1
                )
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
                // Expand chevron — toggles the speed/pitch/volume panel
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        imageVector = if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.ExpandMore,
                        contentDescription = if (expanded) "Collapse" else "Expand"
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

            // --- Expanded panel: speed + pitch + volume sliders (PB-05..PB-07) ---
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // --- Speed row + buttons ---
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = {
                            onRateChange((rate - rateStep).coerceIn(rateMin, rateMax))
                        }) {
                            Icon(Icons.Outlined.FastRewind, contentDescription = "Slow down")
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Speed: ${String.format(Locale.US, "%.2fx", rate)}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Slider(
                                value = rate.coerceIn(rateMin, rateMax),
                                onValueChange = onRateChange,
                                valueRange = rateMin..rateMax
                            )
                        }
                        IconButton(onClick = {
                            onRateChange((rate + rateStep).coerceIn(rateMin, rateMax))
                        }) {
                            Icon(Icons.Outlined.FastForward, contentDescription = "Speed up")
                        }
                    }
                    // --- Pitch row ---
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Pitch", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 8.dp))
                        Slider(
                            value = pitch.coerceIn(0.5f, 2.0f),
                            onValueChange = onPitchChange,
                            valueRange = 0.5f..2.0f,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            String.format(Locale.US, "%.2f", pitch),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // --- Volume row ---
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Vol", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 8.dp))
                        Slider(
                            value = volume.coerceIn(0f, 1f),
                            onValueChange = onVolumeChange,
                            valueRange = 0f..1f,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "${(volume * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    HorizontalDivider()
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
