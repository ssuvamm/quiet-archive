package com.monochrome.annas.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.monochrome.annas.data.LibraryItem
import com.monochrome.annas.data.Reader

/** The audio tab: linked audiobooks, audio files, and books worth converting. */
@Composable
fun AudioLibraryScreen(
    vm: AppViewModel,
    onListen: (LibraryItem) -> Unit,
    onOpenAudio: (LibraryItem) -> Unit,
    onOpenBook: (String) -> Unit,
    onAudiobookify: (LibraryItem) -> Unit,
) {
    val libraryItems by vm.library.collectAsState()
    val links = vm.audioLinks
    val linked = links.mapNotNull { (id) -> libraryItems.firstOrNull { it.id == id } }
    val audioFiles = libraryItems.filter { Reader.isAudio(it) }
    val candidates = libraryItems.filter { !Reader.isAudio(it) && it.id !in links }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(18.dp))
        MonoLabel("YOUR LIBRARY")
        Text("Audiobooks", style = MaterialTheme.typography.displayMedium)
        Text(
            "${linked.size} generated · ${audioFiles.size} audio files",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        LazyColumn(Modifier.fillMaxSize()) {
            item { MonoLabel("LINKED AUDIOBOOKS") }
            if (linked.isEmpty()) {
                item {
                    EmptyState(
                        "No audiobooks yet",
                        "Open an ebook and tap AUDIOBOOKIFY, or use the books below.",
                    )
                }
            } else {
                linked.forEach { item ->
                    item(key = "linked-" + item.id) {
                        AudioRow(item, action = "LISTEN") { onListen(item) }
                        Hairline()
                    }
                }
            }

            item { Spacer(Modifier.height(14.dp)); MonoLabel("AUDIO FILES") }
            if (audioFiles.isEmpty()) {
                item { MonoLabel("No audio files downloaded", modifier = Modifier.padding(vertical = 8.dp)) }
            } else {
                audioFiles.forEach { entry ->
                    item(key = "audio-" + entry.id) {
                        AudioRow(entry, action = "OPEN") { onOpenAudio(entry) }
                        Hairline()
                    }
                }
            }

            if (candidates.isNotEmpty()) {
                item { Spacer(Modifier.height(14.dp)); MonoLabel("TURN A BOOK INTO AUDIO") }
                candidates.forEach { entry ->
                    item(key = "make-" + entry.id) {
                        AudioRow(entry, action = "AUDIOBOOKIFY") { onAudiobookify(entry) }
                        Hairline()
                    }
                }
            }

            if (vm.savedBooks.isNotEmpty()) {
                item { Spacer(Modifier.height(14.dp)); MonoLabel("SAVED · DOWNLOAD FIRST") }
                vm.savedBooks.forEach { saved ->
                    item(key = "saved-" + saved.id) {
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpenBook(saved.url) }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CoverImage(saved.cover, 40.dp, 60.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(saved.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (saved.meta.isNotBlank()) MonoLabel(saved.meta)
                            }
                            MonoLabel("OPEN")
                        }
                        Hairline()
                    }
                }
            }
            item { Spacer(Modifier.height(28.dp)) }
        }
    }
}

@Composable
private fun AudioRow(item: LibraryItem, action: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverImage(item.cover, 40.dp, 60.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (item.authors.isNotEmpty()) {
                Text(
                    item.authors.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            MonoLabel(item.format.uppercase())
        }
        Spacer(Modifier.width(8.dp))
        MonoLabel(action, color = MaterialTheme.colorScheme.primary)
    }
}
