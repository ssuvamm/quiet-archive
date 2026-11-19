package com.monochrome.annas.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.MotionEvent
import android.graphics.pdf.PdfRenderer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.monochrome.annas.data.AudioExport
import com.monochrome.annas.data.LibraryItem
import com.monochrome.annas.data.ReadingPosition
import com.monochrome.annas.data.Reader
import com.monochrome.annas.data.ReaderDocument
import com.monochrome.annas.data.ReaderPage
import com.monochrome.annas.data.ReaderStyle
import com.monochrome.annas.data.getReaderBookmarks
import com.monochrome.annas.data.getReaderBrightness
import com.monochrome.annas.data.getReaderFontFamily
import com.monochrome.annas.data.getReaderFontSize
import com.monochrome.annas.data.getReaderLineHeight
import com.monochrome.annas.data.getReaderMargin
import com.monochrome.annas.data.getReaderTheme
import com.monochrome.annas.data.getReadingPosition
import com.monochrome.annas.data.getTtsEngine
import com.monochrome.annas.data.setReaderBrightness
import com.monochrome.annas.data.setReaderFontFamily
import com.monochrome.annas.data.setReaderFontSize
import com.monochrome.annas.data.setReaderLineHeight
import com.monochrome.annas.data.setReaderMargin
import com.monochrome.annas.data.setReaderTheme
import com.monochrome.annas.data.setReadingPosition
import com.monochrome.annas.data.toggleReaderBookmark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Colours used by the reader chrome, matching the page theme passed to the WebView. */
private data class ReaderColors(val background: Color, val foreground: Color, val muted: Color, val accent: Color)

private fun readerColors(theme: String): ReaderColors = when (theme) {
    "night" -> ReaderColors(Color(0xFF121212), Color(0xFFD8D4CD), Color(0xFF8E8A82), Color(0xFF9AB8D8))
    "sepia" -> ReaderColors(Color(0xFFF2E8D5), Color(0xFF3B3226), Color(0xFF7C705C), Color(0xFF7A5A2E))
    else -> ReaderColors(Color(0xFFFAF8F4), Color(0xFF1C1A17), Color(0xFF6E6A63), Color(0xFF3F5F8A))
}

@Composable
fun ReaderScreen(item: LibraryItem, vm: AppViewModel, onListen: () -> Unit, onBack: () -> Unit) {
    if (Reader.isPdf(item)) PdfReaderScreen(item, onBack) else EbookReaderScreen(item, vm, onListen, onBack)
}

// ---------------------------------------------------------------- EPUB/TXT

@Composable
private fun EbookReaderScreen(item: LibraryItem, vm: AppViewModel, onListen: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var doc by remember(item.id) { mutableStateOf<ReaderDocument?>(null) }
    var loadError by remember(item.id) { mutableStateOf<String?>(null) }
    var chapter by remember(item.id) { mutableStateOf(context.getReadingPosition(item.id).chapter) }
    var fraction by remember(item.id) { mutableStateOf(context.getReadingPosition(item.id).fraction) }
    var page by remember { mutableStateOf<ReaderPage?>(null) }
    var turning by remember { mutableStateOf(true) }
    var fontSize by remember { mutableStateOf(context.getReaderFontSize()) }
    var theme by remember { mutableStateOf(context.getReaderTheme()) }
    var lineHeight by remember { mutableStateOf(context.getReaderLineHeight()) }
    var fontFamily by remember { mutableStateOf(context.getReaderFontFamily()) }
    var margin by remember { mutableStateOf(context.getReaderMargin()) }
    var brightness by remember { mutableStateOf(context.getReaderBrightness()) }
    var readerMarks by remember(item.id) { mutableStateOf(context.getReaderBookmarks(item.id)) }
    var listenPrompt by remember { mutableStateOf(false) }
    val style = ReaderStyle(fontSize, theme, lineHeight, fontFamily, margin)
    var tocOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var exportStatus by remember { mutableStateOf("") }

    // ---- text to speech (the OpenAudioBookify idea, streamed live) ----
    var ttsReady by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var speaking by remember { mutableStateOf(false) }
    var speakOnLoad by remember { mutableStateOf(false) }
    val tts = remember {
        val init = TextToSpeech.OnInitListener { status -> ttsReady = status == TextToSpeech.SUCCESS }
        val chosen = context.getTtsEngine()
        if (chosen == null) TextToSpeech(context, init) else TextToSpeech(context, init, chosen)
    }
    val latestChapter by rememberUpdatedState(chapter)
    val latestListening by rememberUpdatedState(listening)
    val latestDoc by rememberUpdatedState(doc)

    fun speakChapter(index: Int) {
        val d = latestDoc ?: return
        scope.launch {
            val text = runCatching { d.chapterText(index) }.getOrNull().orEmpty()
            if (text.isBlank()) return@launch
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "chapter-$index")
        }
    }

    DisposableEffect(tts) {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { speaking = true }
            override fun onDone(utteranceId: String?) {
                speaking = false
                if (!latestListening) return
                val total = latestDoc?.chapters?.size ?: 0
                val next = latestChapter + 1
                if (next < total) {
                    speakOnLoad = true
                    chapter = next
                    fraction = 0f
                } else {
                    listening = false
                }
            }

            override fun onError(utteranceId: String?) { speaking = false }
        })
        onDispose {
            runCatching { tts.stop() }
            runCatching { tts.shutdown() }
        }
    }

    LaunchedEffect(item.id) {
        try {
            val opened = Reader.open(context, item)
            doc = opened
            chapter = chapter.coerceIn(0, (opened.chapters.size - 1).coerceAtLeast(0))
        } catch (t: Throwable) {
            loadError = t.message ?: "Could not open this book."
        }
    }

    LaunchedEffect(doc, chapter, style) {
        val d = doc ?: return@LaunchedEffect
        turning = true
        page = runCatching { d.chapterPage(chapter, style) }.getOrNull()
        turning = false
        if (speakOnLoad && listening) {
            speakOnLoad = false
            speakChapter(chapter)
        }
    }

    // Persist the position shortly after scrolling stops.
    LaunchedEffect(chapter, fraction) {
        delay(700)
        context.setReadingPosition(item.id, ReadingPosition(chapter, fraction))
    }

    val colors = readerColors(theme)
    val bridge = remember(item.id) {
        ReaderBridge { f -> fraction = f }
    }
    val chapterCount by rememberUpdatedState(doc?.chapters?.size ?: 0)
    val swipeChapter by rememberUpdatedState { delta: Int ->
        listening = false
        runCatching { tts.stop() }
        val next = (chapter + delta).coerceIn(0, (chapterCount - 1).coerceAtLeast(0))
        if (next != chapter) {
            chapter = next
            fraction = 0f
        }
    }
    val webView = remember {
        SwipeWebView(context, { swipeChapter(1) }, { swipeChapter(-1) }).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            settings.textZoom = 100
            addJavascriptInterface(bridge, "AndroidReader")
        }
    }
    DisposableEffect(webView) {
        onDispose {
            runCatching { webView.stopLoading() }
            runCatching { webView.destroy() }
        }
    }

    LaunchedEffect(page) {
        val p = page ?: return@LaunchedEffect
        webView.loadDataWithBaseURL(p.baseUrl, p.html + readerScript(), "text/html", "utf-8", null)
        webView.postDelayed({
            runCatching {
                webView.evaluateJavascript(
                    "window.scrollTo(0,(document.documentElement.scrollHeight-document.documentElement.clientHeight)*$fraction);",
                    null,
                )
            }
        }, 350)
    }

    val chapters = doc?.chapters.orEmpty()
    val chapterTitle = chapters.getOrNull(chapter)?.title ?: item.title

    Column(Modifier.fillMaxSize().background(colors.background)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = {
                listening = false
                runCatching { tts.stop() }
                onBack()
            }) { Icon(MonoIcons.Back, contentDescription = "Back", tint = colors.foreground) }
            Column(Modifier.weight(1f)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    chapterTitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ReaderAction("Aa", colors) { settingsOpen = true }
            ReaderAction("LISTEN", colors) {
                if (item.id in vm.audioLinks) onListen() else listenPrompt = true
            }
            ReaderAction("TOC", colors) { tocOpen = true }
        }
        Hairline(color = colors.muted.copy(alpha = 0.25f))

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                loadError != null -> Column(Modifier.padding(24.dp)) {
                    ErrorBanner(loadError!!, onRetry = { loadError = null; doc = null })
                }
                doc == null -> Column(Modifier.padding(24.dp)) {
                    ProgressLine()
                    Spacer(Modifier.height(10.dp))
                    Text("Opening book…", color = colors.muted, style = MaterialTheme.typography.bodySmall)
                }
                else -> AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
            }
            if (brightness < 0.99f) {
                Box(
                    Modifier.fillMaxSize().background(
                        Color.Black.copy(alpha = (1f - brightness).coerceIn(0f, 0.75f)),
                    ),
                )
            }
            if (turning && doc != null) ProgressLine(Modifier.align(Alignment.TopCenter))
            if (exporting) {
                Row(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(colors.foreground.copy(alpha = 0.09f)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        exportStatus.ifBlank { "Preparing audiobook…" },
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.foreground,
                    )
                }
            }
        }

        Hairline(color = colors.muted.copy(alpha = 0.25f))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ReaderAction("PREV", colors, enabled = chapter > 0) {
                if (chapter > 0) {
                    listening = false
                    runCatching { tts.stop() }
                    chapter -= 1
                    fraction = 0f
                }
            }
            val lastChapter = (chapters.size - 1).coerceAtLeast(0)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                MonoLabel(
                    "${chapter + 1} / ${chapters.size.coerceAtLeast(1)}${if (chapter in readerMarks) "  ★" else ""}",
                    color = colors.muted,
                )
                Slider(
                    value = chapter.coerceAtMost(lastChapter).toFloat(),
                    onValueChange = { value ->
                        val next = value.roundToInt().coerceIn(0, lastChapter)
                        if (next != chapter) {
                            listening = false
                            runCatching { tts.stop() }
                            chapter = next
                            fraction = 0f
                        }
                    },
                    valueRange = 0f..lastChapter.toFloat().coerceAtLeast(1f),
                    steps = if (lastChapter > 1) lastChapter - 1 else 0,
                    enabled = chapters.size > 1,
                    colors = SliderDefaults.colors(
                        thumbColor = colors.accent,
                        activeTrackColor = colors.accent,
                        inactiveTrackColor = colors.muted.copy(alpha = 0.4f),
                    ),
                )
            }
            ReaderAction("NEXT", colors, enabled = chapter < chapters.lastIndex) {
                if (chapter < chapters.lastIndex) {
                    listening = false
                    runCatching { tts.stop() }
                    chapter += 1
                    fraction = 0f
                }
            }
        }
    }

    if (listenPrompt) {
        AlertDialog(
            onDismissRequest = { listenPrompt = false },
            title = { Text("No audiobook yet", style = MaterialTheme.typography.titleMedium) },
            text = {
                Text(
                    "Create an audiobook from this book first. It will be saved beside the book, then LISTEN opens the audiobook player.",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    listenPrompt = false
                    vm.audiobookify(listOf(item))
                }) { Text("CREATE", style = MaterialTheme.typography.labelLarge) }
            },
            dismissButton = {
                TextButton(onClick = { listenPrompt = false }) { Text("CANCEL", style = MaterialTheme.typography.labelLarge) }
            },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }

    if (tocOpen) {
        AlertDialog(
            onDismissRequest = { tocOpen = false },
            title = { Text("Chapters", style = MaterialTheme.typography.titleMedium) },
            text = {
                LazyColumn(Modifier.height((LocalDensity.current.density * 420).dp)) {
                    itemsIndexed(chapters) { i, c ->
                        Text(
                            c.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (i == chapter) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    chapter = i
                                    fraction = 0f
                                    tocOpen = false
                                }
                                .padding(vertical = 10.dp),
                        )
                        Hairline()
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { tocOpen = false }) { Text("CLOSE", style = MaterialTheme.typography.labelLarge) }
            },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }

    if (settingsOpen) {
        AlertDialog(
            onDismissRequest = { settingsOpen = false },
            title = { Text("Reading", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column {
                    MonoLabel("TEXT SIZE")
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GhostButton("A−", {
                            fontSize = (fontSize - 2f).coerceAtLeast(13f)
                            context.setReaderFontSize(fontSize)
                        })
                        Text("${fontSize.toInt()}", style = MaterialTheme.typography.titleMedium)
                        GhostButton("A+", {
                            fontSize = (fontSize + 2f).coerceAtMost(32f)
                            context.setReaderFontSize(fontSize)
                        })
                    }
                    Spacer(Modifier.height(18.dp))
                    MonoLabel("PAGE THEME")
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("paper" to "PAPER", "sepia" to "SEPIA", "night" to "NIGHT").forEach { (key, label) ->
                            FilterChip(label, theme == key) {
                                theme = key
                                context.setReaderTheme(key)
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    MonoLabel("LINE SPACING")
                    Slider(
                        value = lineHeight,
                        onValueChange = { value ->
                            lineHeight = value.coerceIn(1.2f, 2.2f)
                            context.setReaderLineHeight(lineHeight)
                        },
                        valueRange = 1.2f..2.2f,
                    )
                    MonoLabel("MARGINS")
                    Slider(
                        value = margin.toFloat(),
                        onValueChange = { value ->
                            margin = value.roundToInt().coerceIn(0, 36)
                            context.setReaderMargin(margin)
                        },
                        valueRange = 0f..36f,
                    )
                    MonoLabel("BRIGHTNESS")
                    Slider(
                        value = brightness,
                        onValueChange = { value ->
                            brightness = value.coerceIn(0.25f, 1f)
                            context.setReaderBrightness(brightness)
                        },
                        valueRange = 0.25f..1f,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip("SERIF", fontFamily == "serif") {
                            fontFamily = "serif"
                            context.setReaderFontFamily(fontFamily)
                        }
                        FilterChip("SANS", fontFamily == "sans") {
                            fontFamily = "sans"
                            context.setReaderFontFamily(fontFamily)
                        }
                        GhostButton(if (listening) "STOP READING" else "READ ALOUD", {
                            if (listening) {
                                listening = false
                                runCatching { tts.stop() }
                                speaking = false
                            } else if (!ttsReady) {
                                Toast.makeText(context, "Text-to-speech is not ready on this device.", Toast.LENGTH_SHORT).show()
                            } else {
                                listening = true
                                speakChapter(chapter)
                            }
                        })
                        GhostButton(if (chapter in readerMarks) "★ CHAPTER" else "MARK CHAPTER", {
                            readerMarks = context.toggleReaderBookmark(item.id, chapter)
                        })
                    }
                    Spacer(Modifier.height(18.dp))
                    MonoLabel("AUDIOBOOK")
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Speak this book with the phone's text-to-speech engine and save it to Downloads as a WAV file.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    GhostButton(if (exporting) "EXPORTING…" else "EXPORT AUDIOBOOK", {
                        val d = doc
                        if (d == null || exporting) return@GhostButton
                        settingsOpen = false
                        exporting = true
                        exportStatus = "Preparing audiobook…"
                        scope.launch {
                            try {
                                val name = AudioExport.export(context, d, item.title, item.id) { c, total ->
                                    exportStatus = "Speaking chapter $c / $total…"
                                }
                                Toast.makeText(context, "Saved to Downloads: $name", Toast.LENGTH_LONG).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, e.message ?: "Export failed", Toast.LENGTH_LONG).show()
                            } finally {
                                exporting = false
                                exportStatus = ""
                            }
                        }
                    }, enabled = !exporting)
                }
            },
            confirmButton = {
                TextButton(onClick = { settingsOpen = false }) { Text("DONE", style = MaterialTheme.typography.labelLarge) }
            },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }
}

@Composable
private fun ReaderAction(
    label: String,
    colors: ReaderColors,
    active: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(Modifier.clickable(enabled = enabled) { onClick() }.padding(horizontal = 9.dp, vertical = 6.dp)) {
        val tint = when {
            !enabled -> colors.muted.copy(alpha = 0.4f)
            active -> colors.accent
            else -> colors.muted
        }
        MonoLabel(label, color = tint)
    }
}

/** Receives scroll progress from the page so reading position survives closing the app. */
private class ReaderBridge(private val onFraction: (Float) -> Unit) {
    @JavascriptInterface
    fun onScroll(fraction: Float) {
        if (fraction.isFinite()) onFraction(fraction.coerceIn(0f, 1f))
    }
}

/** WebView that turns chapters on a horizontal swipe while leaving vertical scroll alone. */
private class SwipeWebView(
    context: android.content.Context,
    private val onNext: () -> Unit,
    private val onPrevious: () -> Unit,
) : WebView(context) {
    private var downX = 0f
    private var downY = 0f

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (kotlin.math.abs(dx) > 90 && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5f) {
                    if (dx < 0) onNext() else onPrevious()
                }
            }
        }
        return super.onTouchEvent(event)
    }
}

private fun readerScript(): String = """
<script>
(function(){
  var last = -1;
  function report(){
    var h = document.documentElement;
    var max = h.scrollHeight - h.clientHeight;
    var f = max > 0 ? h.scrollTop / max : 0;
    if (Math.abs(f - last) > 0.004) { last = f; try { AndroidReader.onScroll(f); } catch (e) {} }
  }
  window.addEventListener('scroll', report, {passive:true});
  setTimeout(report, 400);
})();
</script>
""".trimIndent()

// ------------------------------------------------------------------- PDF

@Composable
private fun PdfReaderScreen(item: LibraryItem, onBack: () -> Unit) {
    val context = LocalContext.current
    val saved = remember(item.id) { context.getReadingPosition(item.id) }
    var error by remember(item.id) { mutableStateOf<String?>(null) }
    val descriptor = remember(item.id) {
        runCatching { Reader.openPdf(context, item) }.onFailure { error = it.message }.getOrNull()
    }
    val renderer = remember(descriptor) {
        descriptor?.let { pfd -> runCatching { PdfRenderer(pfd) }.onFailure { error = it.message }.getOrNull() }
    }
    DisposableEffect(renderer, descriptor) {
        onDispose {
            runCatching { renderer?.close() }
            runCatching { descriptor?.close() }
        }
    }
    val pageCount = renderer?.pageCount ?: 0
    val listState = rememberLazyListState()
    val mutex = remember { Mutex() }
    val colors = readerColors("paper")

    LaunchedEffect(pageCount) {
        if (pageCount > 0) listState.scrollToItem(saved.chapter.coerceIn(0, pageCount - 1))
    }
    LaunchedEffect(listState, pageCount) {
        if (pageCount == 0) return@LaunchedEffect
        snapshotFlowOfFirstPage(listState).collectLatest { page ->
            delay(700)
            context.setReadingPosition(item.id, ReadingPosition(page, 0f))
        }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF2A2A2A))) {
        Row(
            Modifier.fillMaxWidth().background(Color(0xFF1E1E1E)).padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(MonoIcons.Back, contentDescription = "Back", tint = Color(0xFFE6E3DE))
            }
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleSmall, color = Color(0xFFE6E3DE), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (pageCount > 0) "PDF · ${listState.firstVisibleItemIndex + 1} / $pageCount" else "PDF",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF9C9890),
                )
            }
        }
        when {
            error != null -> Column(Modifier.padding(24.dp)) {
                ErrorBanner(error!!, onRetry = null)
            }
            renderer == null || pageCount == 0 -> Column(Modifier.padding(24.dp)) {
                ProgressLine()
                Spacer(Modifier.height(10.dp))
                Text("Opening PDF…", color = Color(0xFF9C9890), style = MaterialTheme.typography.bodySmall)
            }
            else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                val widthPx = with(LocalDensity.current) { maxWidth.toPx().toInt() }
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(pageCount) { index ->
                        PdfPage(renderer, index, widthPx, mutex)
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfPage(renderer: PdfRenderer, index: Int, widthPx: Int, mutex: Mutex) {
    val bitmap by produceState<Bitmap?>(null, index, widthPx) {
        value = withContext(Dispatchers.IO) {
            mutex.withLock {
                runCatching {
                    val page = renderer.openPage(index)
                    try {
                        val scale = widthPx.toFloat() / page.width
                        val height = (page.height * scale).toInt().coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(android.graphics.Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bmp
                    } finally {
                        page.close()
                    }
                }.getOrNull()
            }
        }
    }
    val current = bitmap
    if (current != null) {
        Image(
            bitmap = current.asImageBitmap(),
            contentDescription = "Page ${index + 1}",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
        )
    } else {
        Box(
            Modifier.fillMaxWidth().height(420.dp).padding(horizontal = 6.dp)
                .background(Color(0xFF3A3A3A)),
            contentAlignment = Alignment.Center,
        ) {
            MonoLabel("PAGE ${index + 1}", color = Color(0xFF9C9890))
        }
    }
}

private fun snapshotFlowOfFirstPage(state: androidx.compose.foundation.lazy.LazyListState) =
    androidx.compose.runtime.snapshotFlow { state.firstVisibleItemIndex }
