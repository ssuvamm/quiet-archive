package com.monochrome.annas.ui

import android.content.Intent
import android.content.Context
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.monochrome.annas.data.Book
import com.monochrome.annas.data.LinkRow
import com.monochrome.annas.data.SearchResult
import com.monochrome.annas.ui.AppViewModel
import com.monochrome.annas.util.openItem

@Composable
fun BookScreen(url: String, preview: SearchResult?, vm: AppViewModel, onBack: () -> Unit, onOpenDownloaded: (com.monochrome.annas.data.LibraryItem) -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(url) { vm.openBook(url, null) }
    val book = vm.book
    val inLibrary = vm.library.value.firstOrNull { it.id == book?.id }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(MonoIcons.Back, contentDescription = "Back", tint = MaterialTheme.colorScheme.onSurface) }
            Spacer(Modifier.weight(1f))
            MonoLabel("BOOK")
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { openInBrowser(context, url) }) { Icon(MonoIcons.Open, contentDescription = "Open on site", tint = MaterialTheme.colorScheme.onSurface) }
        }
        if (vm.bookLoading) {
            Column(Modifier.fillMaxSize().padding(24.dp)) { ProgressLine(); Spacer(Modifier.height(12.dp)); Text("Loading…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else if (vm.bookError != null) {
            Column(Modifier.fillMaxSize().padding(24.dp)) { ErrorBanner(vm.bookError!!, onRetry = { vm.openBook(url, null) }) }
        } else if (book != null) {
            BookContent(book, vm, context, inLibrary, onOpenDownloaded)
        }
    }
}

@Composable
private fun BookContent(book: Book, vm: AppViewModel, context: Context, inLibrary: com.monochrome.annas.data.LibraryItem?, onOpenDownloaded: (com.monochrome.annas.data.LibraryItem) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Top) {
            CoverImage(book.cover, 112.dp, 168.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(book.title, style = MaterialTheme.typography.headlineMedium)
                if (book.authors.isNotEmpty()) Text(book.authors.joinToString(", "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                RatingRow(book.rating, book.quality)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (book.metadata.firstOrNull { it.label.equals("File", true) }?.value != null) Tag(book.metadata.firstOrNull { it.label.equals("File", true) }!!.value)
                    if (book.metadata.firstOrNull { it.label.equals("Language", true) }?.value != null) Tag(book.metadata.firstOrNull { it.label.equals("Language", true) }!!.value)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Slow servers work for guests; fast ones need a member account.
            PrimaryButton(if (inLibrary != null) "OPEN" else "DOWNLOAD", {
                if (inLibrary != null) onOpenDownloaded(inLibrary) else vm.startDownload(
                    book,
                    book.downloads.firstOrNull { it.kind == "slow" } ?: book.downloads.firstOrNull()
                )
            }, modifier = Modifier.weight(1f), icon = if (inLibrary != null) MonoIcons.Open else MonoIcons.Download)
            GhostButton(if (vm.isBookmarked(book.id)) "SAVED" else "SAVE", { vm.saveBookmark(book.id) }, modifier = Modifier)
        }
        if (book.description != null) {
            SectionHeader("DESCRIPTION")
            Text(book.description, style = MaterialTheme.typography.bodyMedium, maxLines = if (expanded) Int.MAX_VALUE else 6, overflow = TextOverflow.Ellipsis)
            if (book.description.length > 260) Text(if (expanded) "Show less" else "Show more", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.clickable { expanded = !expanded }.padding(top = 4.dp))
        }
        if (book.metadata.isNotEmpty()) {
            SectionHeader("DETAILS")
            book.metadata.forEach { m ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MonoLabel(m.label, modifier = Modifier.width(96.dp))
                    Text(m.value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
                Hairline()
            }
        }
        if (book.downloads.isNotEmpty()) {
            SectionHeader("FILES")
            book.downloads.forEach { link -> FileRow(link, book, vm) }
        } else {
            SectionHeader("DOWNLOAD")
            Column(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, androidx.compose.foundation.shape.RoundedCornerShape(2.dp)).padding(14.dp)) {
                Text("No direct download links on this page.", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text("The site lists this title through partner services. Open the page to request or read more.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                GhostButton("Open on site", { openInBrowser(context, book.sourceUrl) })
            }
        }
        if (book.requestCode != null) {
            SectionHeader("REQUEST CODE")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(book.requestCode, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.weight(1f))
                GhostButton("Copy", { copyToClipboard(context, book.requestCode) })
            }
        }
        if (book.actions.isNotEmpty()) {
            SectionHeader("FROM THE SITE")
            book.actions.forEach { a ->
                Row(Modifier.fillMaxWidth().clickable { openInBrowser(context, a.url) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(a.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Icon(MonoIcons.Open, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                }
                Hairline()
            }
        }
        if (book.comments != null) {
            SectionHeader("COMMENTS")
            Text(book.comments, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun FileRow(link: LinkRow, book: Book, vm: AppViewModel) {
    val context = LocalContext.current
    val external = link.kind == "external"
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { if (external) openInBrowser(context, link.url) else vm.startDownload(book, link) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(link.label, style = MaterialTheme.typography.bodyMedium)
            MonoLabel(link.kind)
        }
        Icon(
            if (external) MonoIcons.Open else MonoIcons.Download,
            contentDescription = if (external) "Open on site" else "Download",
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(18.dp),
        )
    }
    Hairline()
}

private fun openInBrowser(context: Context, url: String) {
    try {
        context.startActivity(Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
    } catch (_: Exception) {}
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.setPrimaryClip(android.content.ClipData.newPlainText("code", text))
    android.widget.Toast.makeText(context, "Copied", android.widget.Toast.LENGTH_SHORT).show()
}
