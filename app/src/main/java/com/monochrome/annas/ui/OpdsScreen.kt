package com.monochrome.annas.ui

import android.util.Xml
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.monochrome.annas.data.WebFetch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

data class OpdsEntry(val title: String, val author: String, val link: String)

/** Minimal OPDS/Atom feed browser for Calibre and similar catalogs. */
@Composable
fun OpdsScreen(feedUrl: String, onBack: () -> Unit, onOpen: (String) -> Unit) {
    var entries by remember { mutableStateOf<List<OpdsEntry>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(feedUrl) {
        try {
            val xml = withContext(Dispatchers.IO) { WebFetch.fetch(feedUrl, 30_000L) }
            entries = parseOpds(xml)
            if (entries.isEmpty()) error = "No books found in this OPDS feed."
        } catch (e: Exception) {
            error = e.message ?: "Could not open this catalog."
        } finally {
            loading = false
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(MonoIcons.Back, contentDescription = "Back", tint = MaterialTheme.colorScheme.onSurface) }
            Column(Modifier.weight(1f)) {
                MonoLabel("OPDS CATALOG")
                Text(feedUrl, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(12.dp))
        when {
            error != null -> ErrorBanner(error!!, onRetry = null)
            loading -> ProgressLine()
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(entries) { entry ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpen(entry.link) }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(entry.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (entry.author.isNotBlank()) MonoLabel(entry.author)
                        }
                        if (entry.link.isNotBlank()) MonoLabel("OPEN")
                    }
                    Hairline()
                }
            }
        }
    }
}

private fun parseOpds(xml: String): List<OpdsEntry> {
    val parser = Xml.newPullParser()
    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
    parser.setInput(StringReader(xml))
    val out = mutableListOf<OpdsEntry>()
    var inEntry = false
    var inTitle = false
    var inAuthor = false
    var title = ""
    var author = ""
    var link = ""
    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        when (event) {
            XmlPullParser.START_TAG -> when (parser.name.substringAfter(':').lowercase()) {
                "entry" -> { inEntry = true; title = ""; author = ""; link = "" }
                "title" -> if (inEntry) inTitle = true
                "author" -> if (inEntry) inAuthor = true
                "name" -> if (inEntry && inAuthor) inAuthor = true
                "link" -> if (inEntry && link.isBlank()) {
                    val rel = parser.getAttributeValue(null, "rel").orEmpty()
                    val href = parser.getAttributeValue(null, "href").orEmpty()
                    if (rel.contains("acquisition") || href.contains("/download") || href.endsWith(".epub") || href.endsWith(".pdf")) {
                        link = href
                    }
                }
            }
            XmlPullParser.TEXT -> {
                val text = parser.text.orEmpty().trim()
                if (inEntry && text.isNotBlank()) {
                    if (inTitle) title += text
                    else if (inAuthor) author += text
                }
            }
            XmlPullParser.END_TAG -> when (parser.name.substringAfter(':').lowercase()) {
                "title" -> inTitle = false
                "author" -> inAuthor = false
                "entry" -> {
                    inEntry = false
                    if (title.isNotBlank()) out.add(OpdsEntry(title.trim(), author.trim(), link))
                }
            }
        }
        event = parser.next()
    }
    return out
}
