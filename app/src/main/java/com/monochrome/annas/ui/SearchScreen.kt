package com.monochrome.annas.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.monochrome.annas.data.SearchResult
import com.monochrome.annas.ui.AppViewModel

@Composable
fun SearchScreen(vm: AppViewModel, onOpenBook: (String, SearchResult?) -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(18.dp))
        MonoLabel("ANNA'S ARCHIVE")
        Spacer(Modifier.height(4.dp))
        Text("Find a book", style = MaterialTheme.typography.displayMedium)
        Text("Search the archive by title, author, or ISBN.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        SearchField(value = vm.searchQuery, onValue = { vm.searchQuery = it }, onSearch = { vm.search() }, placeholder = "Title, author, ISBN…")
        Spacer(Modifier.height(10.dp))
        FilterRow(vm)
        Spacer(Modifier.height(10.dp))
        if (vm.searchLoading && vm.searchResults.isEmpty()) {
            ProgressLine()
        }
        vm.searchError?.let { ErrorBanner(it, onRetry = { vm.search() }) }
        if (vm.verificationRequired) {
            ErrorBanner(
                "Site verification required — complete the check on screen.",
                onRetry = null,
                label = "SITE CHECK",
            )
        }
        if (vm.searchResults.isEmpty() && !vm.searchLoading) {
            EmptyState("Search the archive", "Type a title, an author, or an ISBN to begin.")
        } else {
            val displayed: List<SearchResult> = if (vm.hasDownloadsFilter) vm.searchResults.filter { vm.downloadAvailability[it.id] != false } else vm.searchResults
            val listState = rememberLazyListState()
            val shouldLoadMore by remember { derivedStateOf {
                val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                val total = listState.layoutInfo.totalItemsCount
                lastVisible >= total - 3 && total > 0
            } }
            androidx.compose.runtime.LaunchedEffect(shouldLoadMore) {
                if (shouldLoadMore) vm.loadMore()
            }
            MonoLabel("${displayed.size} RESULTS${if (vm.searchTook > 0) "  ·  ${vm.searchTook} MS" else ""}", modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(displayed) { r ->
                    SearchResultRow(r) { onOpenBook(r.url, r) }
                    Hairline()
                }
                if (vm.searchLoading) {
                    item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                }
            }
        }
    }
}

@Composable
private fun FilterRow(vm: AppViewModel) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterMenu("Format", vm.searchExt, listOf("any", "epub", "pdf", "mobi", "azw3", "djvu", "fb2", "txt")) { vm.searchExt = it; vm.search() }
        FilterMenu("Language", vm.searchLang, listOf("any", "English", "Russian", "German", "French", "Spanish", "Italian", "Portuguese", "Hungarian")) { vm.searchLang = it; vm.search() }
        FilterMenu("Sort", vm.searchSort, listOf("relevance", "newest", "oldest", "largest", "smallest")) { vm.searchSort = it; vm.search() }
        FilterChip("WITH DOWNLOADS", vm.hasDownloadsFilter) { vm.hasDownloadsFilter = !vm.hasDownloadsFilter; if (vm.hasDownloadsFilter) vm.checkDownloadAvailability() }
    }
}

@Composable
private fun FilterMenu(label: String, value: String, options: List<String>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        GhostButton(if (value == "any" || value == "relevance") label else value, { open = true }, modifier = Modifier)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { opt ->
                DropdownMenuItem(text = { Text(opt.uppercase(), style = MaterialTheme.typography.labelLarge) }, onClick = { onPick(opt); open = false })
            }
        }
    }
}

@Composable
private fun SearchResultRow(r: SearchResult, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 12.dp), verticalAlignment = Alignment.Top) {
        CoverImage(r.cover, 54.dp, 80.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(r.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (r.authors.isNotEmpty()) {
                Text(r.authors.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(6.dp))
            // One compact metadata line: year, format, language, size.
            val meta = listOfNotNull(r.year, r.format?.uppercase(), r.language?.uppercase(), r.size?.uppercase())
                .joinToString(" · ")
            if (meta.isNotBlank()) MonoLabel(meta)
            if (r.publisher != null) Text(r.publisher, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End) {
            if (r.rating != null) Text(r.rating, style = MaterialTheme.typography.titleSmall)
            if (r.quality != null) Text("/ ${r.quality}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
