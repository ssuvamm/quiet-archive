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
import com.monochrome.annas.data.getAudioPosition
import com.monochrome.annas.data.getAudioSpeed
import com.monochrome.annas.data.setAudioPosition
import com.monochrome.annas.data.setAudioSpeed
import kotlinx.coroutines.delay

private val SPEEDS = listOf(0.8f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

/**
 * Simple audiobook / audio file player: resume where you stopped, skip
 * 15 seconds, change speed, and fall asleep to it with a sleep timer.
 */
@Composable
fun AudioScreen(item: LibraryItem, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var prepared by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var duration by remember { mutableStateOf(0) }
    var position by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var speed by remember(item.id) { mutableStateOf(context.getAudioSpeed()) }
    var speedOpen by remember { mutableStateOf(false) }
    var sleepOpen by remember { mutableStateOf(false) }
    var sleepAt by remember { mutableStateOf(0L) }
    var sleepLeft by remember { mutableStateOf("") }

    val player = remember { MediaPlayer() }

    fun applySpeed(value: Float) {
        speed = value
        context.setAudioSpeed(value)
        if (prepared) {
            runCatching {
                player.playbackParams = player.playbackParams.setSpeed(value)
            }
        }
    }

    LaunchedEffect(item.id) {
        try {
            player.reset()
            val uri = item.uri?.takeIf { it.isNotBlank() }?.let(Uri::parse)
            if (uri != null) player.setDataSource(context, uri) else player.setDataSource(item.localPath)
            player.setOnPreparedListener { mp ->
                duration = mp.duration.coerceAtLeast(0)
                val resume = context.getAudioPosition(item.id).toInt()
                    .coerceIn(0, duration.coerceAtLeast(0))
                if (resume > 0) runCatching { mp.seekTo(resume) }
                position = resume
                prepared = true
                runCatching { mp.playbackParams = mp.playbackParams.setSpeed(speed) }
            }
            player.setOnCompletionListener { playing = false }
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

    // Poll the playhead while playing.
    LaunchedEffect(prepared, playing) {
        while (prepared) {
            if (playing) {
                runCatching { position = player.currentPosition }
                if (sleepAt > 0 && System.currentTimeMillis() >= sleepAt) {
                    runCatching { player.pause() }
                    playing = false
                    sleepAt = 0
                    sleepLeft = ""
                }
            }
            delay(400)
        }
    }

    // Keep the resume point fresh without hammering SharedPreferences.
    LaunchedEffect(item.id) {
        while (true) {
            delay(5000)
            if (position > 0) context.setAudioPosition(item.id, position.toLong())
        }
    }

    LaunchedEffect(sleepAt) {
        while (sleepAt > 0) {
            val left = sleepAt - System.currentTimeMillis()
            sleepLeft = if (left > 0) "SLEEP ${formatTime(left)}" else ""
            delay(1000)
        }
    }

    DisposableEffect(player) {
        onDispose {
            runCatching { context.setAudioPosition(item.id, player.currentPosition.toLong()) }
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
            MonoLabel("AUDIO")
            Spacer(Modifier.weight(1f))
            IconButton(onClick = {}, enabled = false) { Spacer(Modifier.size(20.dp)) }
        }

        if (error != null) {
            Column(Modifier.padding(24.dp)) { ErrorBanner(error!!, onRetry = null) }
        } else {
            Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(18.dp))
            CoverImage(item.cover, 180.dp, 180.dp)
            Spacer(Modifier.height(20.dp))
            Text(item.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (item.authors.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    item.authors.joinToString(", "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(6.dp))
            MonoLabel(if (prepared) formatTime(duration) else "OPENING…")

            Spacer(Modifier.height(22.dp))
            Slider(
                value = if (duration > 0) position.toFloat() / duration else 0f,
                onValueChange = { value ->
                    val target = (value * duration).toInt()
                    position = target
                    if (prepared) runCatching { player.seekTo(target) }
                },
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

            Spacer(Modifier.height(22.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                GhostButton("−15s", {
                    if (prepared) {
                        val target = (position - 15_000).coerceAtLeast(0)
                        runCatching { player.seekTo(target) }
                        position = target
                    }
                }, enabled = prepared)
                PrimaryButton(
                    if (playing) "PAUSE" else "PLAY",
                    {
                        if (!prepared) return@PrimaryButton
                        if (playing) {
                            runCatching { player.pause() }
                            playing = false
                            context.setAudioPosition(item.id, position.toLong())
                        } else {
                            runCatching { player.start() }
                            playing = true
                        }
                    },
                    enabled = prepared,
                    icon = null,
                )
                GhostButton("+15s", {
                    if (prepared) {
                        val target = (position + 15_000).coerceAtMost(duration.coerceAtLeast(0))
                        runCatching { player.seekTo(target) }
                        position = target
                    }
                }, enabled = prepared)
            }

            Spacer(Modifier.height(26.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                GhostButton("SPEED ${speed}x", { speedOpen = true })
                GhostButton(sleepLeft.ifBlank { "SLEEP" }, { sleepOpen = true })
            }
            if (sleepAt > 0) {
                Spacer(Modifier.height(8.dp))
                GhostButton("CANCEL SLEEP", { sleepAt = 0; sleepLeft = "" })
            }
            Spacer(Modifier.height(18.dp))
            Text(
                "Position is remembered automatically. Speed applies to this and every other audiobook.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp),
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp)
                                .clickable {
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
                    listOf(15, 30, 45, 60, 90).forEach { minutes ->
                        Text(
                            "$minutes minutes",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp)
                                .clickable {
                                    sleepAt = System.currentTimeMillis() + minutes * 60_000L
                                    sleepOpen = false
                                },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { sleepOpen = false }) { Text("CLOSE") } },
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
