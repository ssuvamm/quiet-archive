package com.monochrome.annas.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.monochrome.annas.data.*
import com.monochrome.annas.ui.AppViewModel
import com.monochrome.annas.util.openItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LibraryScreen(vm: AppViewModel, onOpen: (LibraryItem) -> Unit, onOpenBook: (String) -> Unit) {
    val context = LocalContext.current
    var tab by remember { mutableStateOf(0) } // 0 downloaded, 1 saved
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(0) } // 0 all 1 unread 2 read
    var sort by remember { mutableStateOf(0) } // 0 newest 1 oldest 2 title 3 author 4 size
    var sortOpen by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<LibraryItem?>(null) }
    var toUnsave by remember { mutableStateOf<SavedBook?>(null) }

    val savedIds = context.getSavedBooks()
    androidx.compose.runtime.LaunchedEffect(tab) { if (tab == 1) vm.refreshSaved() }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(18.dp))
        MonoLabel("YOUR LIBRARY")
        Text("Library", style = MaterialTheme.typography.displayMedium)
        val items = vm.library.value
        Text("${items.size} books · ${formatBytes(items.sumOf { it.size })}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            TabLabel("DOWNLOADED", tab == 0, Modifier.weight(1f)) { tab = 0 }
            TabLabel("SAVED", tab == 1, Modifier.weight(1f)) { tab = 1 }
        }
        Hairline()
        Spacer(Modifier.height(10.dp))
        SearchField(value = query, onValue = { query = it }, onSearch = {}, placeholder = "Filter by title or author…")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip("ALL", filter == 0) { filter = 0 }
            FilterChip("UNREAD", filter == 1) { filter = 1 }
            FilterChip("READ", filter == 2) { filter = 2 }
            Spacer(Modifier.weight(1f))
            GhostButton("SORT", { sortOpen = true })
            DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                listOf("Newest", "Oldest", "Title", "Author", "Size").forEachIndexed { i, s ->
                    DropdownMenuItem(text = { Text(s.uppercase(), style = MaterialTheme.typography.labelLarge) }, onClick = { sort = i; sortOpen = false })
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        val filtered: List<LibraryItem> = if (tab == 0) {
            items.filter { it ->
                val q = query.lowercase()
                (q.isBlank() || it.title.lowercase().contains(q) || it.authors.joinToString(" ").lowercase().contains(q)) &&
                (filter == 0 || (filter == 1 && !it.read) || (filter == 2 && it.read))
            }.let { list ->
                when (sort) {
                    1 -> list.sortedBy { it.savedAt }
                    2 -> list.sortedBy { it.title.lowercase() }
                    3 -> list.sortedBy { it.authors.firstOrNull()?.lowercase() ?: "" }
                    4 -> list.sortedByDescending { it.size }
                    else -> list.sortedByDescending { it.savedAt }
                }
            }
        } else {
            items.filter { it.id in savedIds }
        }
        if (tab == 0 && filtered.isEmpty()) {
            EmptyState("Nothing downloaded yet", "Find a book and tap Download or Save.")
        } else if (tab == 1) {
            val dlSaved = filtered
            val savedOnly = vm.savedBooks.filter { s ->
                s.id !in dlSaved.map { it.id } &&
                    (query.isBlank() || s.title.lowercase().contains(query.lowercase()))
            }
            if (dlSaved.isEmpty() && savedOnly.isEmpty()) {
                EmptyState("Nothing saved yet", "Tap SAVE on a book page to keep it here.")
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(dlSaved, key = { it.id }) { item ->
                        LibraryRow(item, onOpen = { onOpen(item) }, onToggleRead = { vm.toggleRead(item) }, onDelete = { toDelete = item })
                        Hairline()
                    }
                    items(savedOnly, key = { "s" + it.id }) { s ->
                        SavedBookRow(s, onOpen = { onOpenBook(s.url) }, onRemove = { toUnsave = s })
                        Hairline()
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(filtered, key = { it.id }) { item ->
                    LibraryRow(item, onOpen = { onOpen(item) }, onToggleRead = { vm.toggleRead(item) }, onDelete = { toDelete = item })
                    Hairline()
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
    toDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete book?", style = MaterialTheme.typography.titleMedium) },
            text = { Text("This removes the file from your library and storage.", style = MaterialTheme.typography.bodySmall) },
            confirmButton = { TextButton(onClick = { vm.deleteItem(item) { toDelete = null } }) { Text("DELETE", style = MaterialTheme.typography.labelLarge) } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("CANCEL", style = MaterialTheme.typography.labelLarge) } },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }
    toUnsave?.let { s ->
        AlertDialog(
            onDismissRequest = { toUnsave = null },
            title = { Text("Remove from saved?", style = MaterialTheme.typography.titleMedium) },
            text = { Text("This removes the bookmark; the file stays in your library if downloaded.", style = MaterialTheme.typography.bodySmall) },
            confirmButton = { TextButton(onClick = { vm.saveBookmark(s.id); vm.pruneSaved(); toUnsave = null }) { Text("REMOVE", style = MaterialTheme.typography.labelLarge) } },
            dismissButton = { TextButton(onClick = { toUnsave = null }) { Text("CANCEL", style = MaterialTheme.typography.labelLarge) } },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }
}

@Composable
private fun TabLabel(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.clickable { onClick() }) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        if (selected) Box(Modifier.fillMaxWidth().height(2.dp).background(MaterialTheme.colorScheme.primary)) else Spacer(Modifier.height(2.dp))
    }
}

@Composable
fun FilterChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(Modifier.border(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, androidx.compose.foundation.shape.RoundedCornerShape(2.dp)).clickable { onClick() }.padding(horizontal = 10.dp, vertical = 6.dp)) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LibraryRow(item: LibraryItem, onOpen: () -> Unit, onToggleRead: () -> Unit, onDelete: () -> Unit) {    var menuOpen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable { onOpen() }.padding(vertical = 12.dp), verticalAlignment = Alignment.Top) {
        CoverImage(item.cover, 48.dp, 72.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (item.authors.isNotEmpty()) Text(item.authors.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tag(item.format.uppercase())
                MonoLabel(formatBytes(item.size))
                MonoLabel(SimpleDateFormat("dd MMM yy", Locale.getDefault()).format(Date(item.savedAt)))
            }
            if (item.read) {
                Spacer(Modifier.height(4.dp))
                MonoLabel("READ", color = MaterialTheme.colorScheme.primary)
            }
        }
        IconButton(onClick = { menuOpen = true }) { Icon(MonoIcons.More, contentDescription = "More", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(text = { Text(if (item.read) "MARK UNREAD" else "MARK READ", style = MaterialTheme.typography.labelLarge) }, onClick = { onToggleRead(); menuOpen = false })
            DropdownMenuItem(text = { Text("DELETE", style = MaterialTheme.typography.labelLarge) }, onClick = { onDelete(); menuOpen = false })
        }
    }
}

@Composable
private fun SavedBookRow(s: com.monochrome.annas.data.SavedBook, onOpen: () -> Unit, onRemove: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable { onOpen() }.padding(vertical = 12.dp), verticalAlignment = Alignment.Top) {
        CoverImage(s.cover, 48.dp, 72.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(s.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (s.authors.isNotEmpty()) Text(s.authors.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (s.meta.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                MonoLabel(s.meta)
            }
        }
        IconButton(onClick = { menuOpen = true }) { Icon(MonoIcons.More, contentDescription = "More", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(text = { Text("REMOVE SAVED", style = MaterialTheme.typography.labelLarge) }, onClick = { onRemove(); menuOpen = false })
        }
    }
}
