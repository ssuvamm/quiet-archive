package com.monochrome.annas.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.monochrome.annas.AudioPlayerService
import com.monochrome.annas.data.AudioController
import com.monochrome.annas.data.LibraryItem
import com.monochrome.annas.data.addAudioBookmark
import com.monochrome.annas.data.getAudioBookmarks
import com.monochrome.annas.data.getAudioPosition
import com.monochrome.annas.data.removeAudioBookmark
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val SPEEDS = listOf(0.75f, 0.8f, 1.0f, 1.1f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)

/** Control surface for the shared audiobook player (notification works in background). */
@Composable
fun AudioScreen(
    item: LibraryItem,
    sourceUri: String? = null,
    bookId: String = item.id,
    onBack: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val playing by AudioController.playing.collectAsState()
    val position by AudioController.position.collectAsState()
    val duration by AudioController.duration.collectAsState()
    val speed by AudioController.speed.collectAsState()
    val sleepUntil by AudioController.sleepUntil.collectAsState()
    var speedOpen by remember { mutableStateOf(false) }
    var sleepOpen by remember { mutableStateOf(false) }
    var marksOpen by remember { mutableStateOf(false) }
    var bookmarks by remember(bookId) { mutableStateOf(context.getAudioBookmarks(bookId)) }

    LaunchedEffect(bookId, sourceUri) {
        val source = sourceUri ?: item.uri ?: item.localPath
        if (source.isNullOrBlank()) return@LaunchedEffect
        AudioController.start(
            context,
            source,
            bookId,
            item.title,
            context.getAudioPosition(bookId).toInt(),
        )
        AudioPlayerService.start(context)
    }

    // Keep the slider moving while the shared player runs in the background.
    LaunchedEffect(Unit) {
        while (true) {
            AudioController.poll()
            delay(300)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(MonoIcons.Back, contentDescription = "Back", tint = MaterialTheme.colorScheme.onSurface) }
            Spacer(Modifier.weight(1f))
            MonoLabel("AUDIOBOOK")
            Spacer(Modifier.weight(1f))
            IconButton(onClick = {
                AudioController.stop()
                AudioPlayerService.stop(context)
                onBack()
            }) { Icon(MonoIcons.Close, contentDescription = "Close", tint = MaterialTheme.colorScheme.onSurface) }
        }

        Column(
            Modifier.fillMaxSize().padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(10.dp))
            CoverImage(item.cover, 132.dp, 132.dp)
            Spacer(Modifier.height(12.dp))
            Text(item.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (item.authors.isNotEmpty()) {
                Text(
                    item.authors.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(14.dp))

            Slider(
                value = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                onValueChange = { value -> AudioController.seek((value * duration).roundToInt()) },
                enabled = duration > 0,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant,
                ),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MonoLabel(formatTime(position))
                MonoLabel(if (duration > 0) "-${formatTime(duration - position)}" else "")
            }

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GhostButton("-30", { AudioController.skip(-30_000) }, modifier = Modifier.padding(horizontal = 4.dp))
                GhostButton("-15", { AudioController.skip(-15_000) }, modifier = Modifier.padding(horizontal = 4.dp))
                PrimaryButton(if (playing) "PAUSE" else "PLAY", { AudioController.toggle() }, icon = null)
                GhostButton("+15", { AudioController.skip(15_000) }, modifier = Modifier.padding(horizontal = 4.dp))
                GhostButton("+30", { AudioController.skip(30_000) }, modifier = Modifier.padding(horizontal = 4.dp))
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("SPEED ${speed}x", { speedOpen = true })
                GhostButton(
                    when {
                        sleepUntil == Long.MAX_VALUE -> "SLEEP TO END"
                        sleepUntil > 0 -> "SLEEP ${((sleepUntil - System.currentTimeMillis()) / 60000).coerceAtLeast(0)}m"
                        else -> "SLEEP"
                    },
                    { sleepOpen = true },
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("BOOKMARK HERE", {
                    context.addAudioBookmark(bookId, position.toLong(), formatTime(position))
                    bookmarks = context.getAudioBookmarks(bookId)
                })
                GhostButton("MARKS (${bookmarks.size})", { marksOpen = true })
                if (sleepUntil > 0) GhostButton("CANCEL SLEEP", { AudioController.setSleepUntil(0) })
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Playback continues in the background with notification controls. Position, speed and bookmarks are remembered.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (speedOpen) {
        AlertDialog(
            onDismissRequest = { speedOpen = false },
            title = { Text("Playback speed", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column {
                    SPEEDS.forEach { value ->
                        Text(
                            "${value}x",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (value == speed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).clickable {
                                AudioController.setSpeed(value)
                                speedOpen = false
                            },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { speedOpen = false }) { Text("CLOSE") } },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }

    if (sleepOpen) {
        AlertDialog(
            onDismissRequest = { sleepOpen = false },
            title = { Text("Sleep timer", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column {
                    listOf(5, 10, 15, 30, 45, 60, 90).forEach { minutes ->
                        Text(
                            "$minutes minutes",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).clickable {
                                AudioController.setSleepUntil(System.currentTimeMillis() + minutes * 60_000L)
                                sleepOpen = false
                            },
                        )
                    }
                    Text(
                        "Pause at the end of the book",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).clickable {
                            AudioController.setSleepUntil(Long.MAX_VALUE)
                            sleepOpen = false
                        },
                    )
                }
            },
            confirmButton = { TextButton(onClick = { sleepOpen = false }) { Text("CLOSE") } },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }

    if (marksOpen) {
        AlertDialog(
            onDismissRequest = { marksOpen = false },
            title = { Text("Bookmarks", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column {
                    if (bookmarks.isEmpty()) {
                        Text("No bookmarks yet. Tap BOOKMARK HERE while listening.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        bookmarks.toSortedMap().forEach { (pos, label) ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "$label  ·  ${formatTime(pos.toInt())}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f).clickable {
                                        AudioController.seek(pos.toInt())
                                        marksOpen = false
                                    },
                                )
                                TextButton(onClick = {
                                    context.removeAudioBookmark(bookId, pos)
                                    bookmarks = context.getAudioBookmarks(bookId)
                                }) { Text("REMOVE") }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { marksOpen = false }) { Text("CLOSE") } },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }
}

private fun formatTime(ms: Int): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
}
