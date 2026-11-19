package com.monochrome.annas.ui

import android.media.MediaPlayer
import android.net.Uri
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.monochrome.annas.data.LibraryItem
import com.monochrome.annas.data.addAudioBookmark
import com.monochrome.annas.data.getAudioBookmarks
import com.monochrome.annas.data.getAudioPosition
import com.monochrome.annas.data.getAudioSpeed
import com.monochrome.annas.data.removeAudioBookmark
import com.monochrome.annas.data.setAudioPosition
import com.monochrome.annas.data.setAudioSpeed
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val SPEEDS = listOf(0.75f, 0.8f, 1.0f, 1.1f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)

/**
 * Audiobook player: remembers where you stopped, skips by 10/15/30 seconds,
 * keeps speed and sleep timer settings, and lets you bookmark passages.
 */
@Composable
fun AudioScreen(
    item: LibraryItem,
    sourceUri: String? = null,
    bookId: String = item.id,
    onBack: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var prepared by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var duration by remember { mutableStateOf(0) }
    var position by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var speed by remember(bookId) { mutableStateOf(context.getAudioSpeed()) }
    var speedOpen by remember { mutableStateOf(false) }
    var sleepOpen by remember { mutableStateOf(false) }
    var marksOpen by remember { mutableStateOf(false) }
    var bookmarks by remember(bookId) { mutableStateOf(context.getAudioBookmarks(bookId)) }
    var sleepAt by remember { mutableStateOf(0L) }
    var sleepLeft by remember { mutableStateOf("") }

    val player = remember { MediaPlayer() }

    fun seekTo(ms: Int) {
        val target = ms.coerceIn(0, duration.coerceAtLeast(0))
        if (prepared) runCatching { player.seekTo(target) }
        position = target
    }

    fun applySpeed(value: Float) {
        speed = value
        context.setAudioSpeed(value)
        if (prepared) runCatching { player.playbackParams = player.playbackParams.setSpeed(value) }
    }

    fun togglePlay() {
        if (!prepared) return
        if (playing) {
            runCatching { player.pause() }
            playing = false
            context.setAudioPosition(bookId, position.toLong())
        } else {
            runCatching { player.start() }
            playing = true
        }
    }

    LaunchedEffect(bookId, sourceUri) {
        try {
            player.reset()
            val uri = (sourceUri ?: item.uri)?.takeIf { it.isNotBlank() }?.let(Uri::parse)
            if (uri != null) player.setDataSource(context, uri)
            else player.setDataSource(item.localPath)
            player.setOnPreparedListener { mp ->
                duration = mp.duration.coerceAtLeast(0)
                val resume = context.getAudioPosition(bookId).toInt().coerceIn(0, duration.coerceAtLeast(0))
                seekTo(resume)
                prepared = true
                runCatching { mp.playbackParams = mp.playbackParams.setSpeed(speed) }
            }
            player.setOnCompletionListener {
                playing = false
                if (sleepAt == Long.MAX_VALUE) { sleepAt = 0; sleepLeft = "" }
                context.setAudioPosition(bookId, duration.toLong())
            }
            player.setOnErrorListener { _, _, _ ->
                error = "This audio file could not be played."
                playing = false
                true
            }
            player.prepareAsync()
        } catch (e: Exception) {
            error = e.message ?: "This audio file could not be played."
        }
    }

    LaunchedEffect(prepared, playing) {
        while (prepared) {
            if (playing) {
                runCatching { position = player.currentPosition }
                if (sleepAt in 1 until Long.MAX_VALUE && System.currentTimeMillis() >= sleepAt) {
                    runCatching { player.pause() }
                    playing = false
                    sleepAt = 0
                    sleepLeft = ""
                }
            }
            delay(300)
        }
    }

    LaunchedEffect(bookId) {
        while (true) {
            delay(5000)
            if (position > 0) context.setAudioPosition(bookId, position.toLong())
        }
    }

    LaunchedEffect(sleepAt) {
        while (sleepAt > 0 && sleepAt != Long.MAX_VALUE) {
            val left = sleepAt - System.currentTimeMillis()
            sleepLeft = if (left > 0) "SLEEP ${formatTime(left)}" else ""
            delay(1000)
        }
    }

    DisposableEffect(player) {
        onDispose {
            runCatching { context.setAudioPosition(bookId, player.currentPosition.toLong()) }
            runCatching { player.release() }
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
            IconButton(onClick = {}, enabled = false) { Spacer(Modifier.size(20.dp)) }
        }

        if (error != null) {
            Column(Modifier.padding(24.dp)) { ErrorBanner(error!!, onRetry = null) }
        } else {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(12.dp))
                CoverImage(item.cover, 140.dp, 140.dp)
                Spacer(Modifier.height(14.dp))
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
                Spacer(Modifier.height(16.dp))

                Slider(
                    value = if (duration > 0) position.toFloat() / duration else 0f,
                    onValueChange = { value -> seekTo((value * duration).roundToInt()) },
                    enabled = prepared && duration > 0,
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

                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    GhostButton("-30", { seekTo(position - 30_000) }, enabled = prepared, modifier = Modifier.padding(horizontal = 4.dp))
                    GhostButton("-15", { seekTo(position - 15_000) }, enabled = prepared, modifier = Modifier.padding(horizontal = 4.dp))
                    PrimaryButton(if (playing) "PAUSE" else "PLAY", { togglePlay() }, enabled = prepared, icon = null)
                    GhostButton("+15", { seekTo(position + 15_000) }, enabled = prepared, modifier = Modifier.padding(horizontal = 4.dp))
                    GhostButton("+30", { seekTo(position + 30_000) }, enabled = prepared, modifier = Modifier.padding(horizontal = 4.dp))
                }

                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton("SPEED ${speed}x", { speedOpen = true })
                    GhostButton(sleepLeft.ifBlank { if (sleepAt == Long.MAX_VALUE) "SLEEP TO END" else "SLEEP" }, { sleepOpen = true })
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton("BOOKMARK HERE", {
                        if (prepared) {
                            context.addAudioBookmark(bookId, position.toLong(), formatTime(position))
                            bookmarks = context.getAudioBookmarks(bookId)
                        }
                    }, enabled = prepared)
                    GhostButton("MARKS (${bookmarks.size})", { marksOpen = true })
                    if (sleepAt > 0) GhostButton("CANCEL SLEEP", { sleepAt = 0; sleepLeft = "" })
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Position, speed and bookmarks are remembered for this audiobook.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
                                applySpeed(value)
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
                                sleepAt = System.currentTimeMillis() + minutes * 60_000L
                                sleepOpen = false
                            },
                        )
                    }
                    Text(
                        "Pause at the end of the book",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).clickable {
                            sleepAt = Long.MAX_VALUE
                            sleepLeft = "SLEEP TO END"
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
                        Text("No bookmarks yet. Tap BOOKMARK HERE while playing.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        bookmarks.toSortedMap().forEach { (pos, label) ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "$label  ·  ${formatTime(pos.toInt())}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f).clickable {
                                        seekTo(pos.toInt())
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

private fun formatTime(ms: Long): String = formatTime(ms.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
