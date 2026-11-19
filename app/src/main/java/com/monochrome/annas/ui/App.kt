package com.monochrome.annas.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.monochrome.annas.data.Downloads
import com.monochrome.annas.data.*
import com.monochrome.annas.data.SearchResult
import com.monochrome.annas.ui.theme.AnnasTheme
import kotlinx.coroutines.flow.collectLatest

/** The splash is a cold-start thing only; warm starts go straight to the library. */
private object SplashState { var shown = false }

sealed class Screen {
    data object Search : Screen()
    data class Book(val url: String, val preview: SearchResult?) : Screen()
    data object Library : Screen()
    data object Ebooks : Screen()
    data object AudioLibrary : Screen()
    data object Settings : Screen()
    data class Opds(val url: String) : Screen()
    data class Reader(val item: LibraryItem) : Screen()
    data class Audio(val item: LibraryItem, val uri: String? = null, val bookId: String? = null) : Screen()
}

@Composable
fun AppRoot(onboarding: Boolean, onOnboarded: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val mode by com.monochrome.annas.data.ThemeController.mode.collectAsState()
    // Onboarding also reacts live: the theme chooser updates the controller
    // on tap, so the preview switches instantly (same as Settings).
    val dark = when (mode) {
        "light" -> false
        "dark" -> true
        else -> androidx.compose.foundation.isSystemInDarkTheme()
    }
    AnnasTheme(dark = dark) {
        if (onboarding) {
            OnboardingScreen(onFinish = onOnboarded)
        } else {
            var showSplash by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(!SplashState.shown) }
            androidx.compose.runtime.LaunchedEffect(Unit) {
                if (showSplash) {
                    kotlinx.coroutines.delay(900)
                    showSplash = false
                }
                SplashState.shown = true
            }
            if (showSplash) {
                Splash()
            } else {
                MainApp()
            }
        }
    }
}

@Composable
private fun Splash() {
    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            LogoMark(size = 96.dp)
            Spacer(Modifier.height(20.dp))
            Text("Quiet Archive", style = MaterialTheme.typography.displayMedium, color = androidx.compose.ui.graphics.Color.White)
        }
    }
}

@Composable
fun MainApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val vm: AppViewModel = viewModel()
    // Real back stack: every push remembers where it came from, so BACK never
    // skips screens. At the root it sends the app to the background instead of
    // finishing it, which is what the user expects from a reading app.
    val stack = remember { androidx.compose.runtime.mutableStateListOf<Screen>(Screen.Library) }
    val screen = stack.last()
    val downloads by Downloads.tasks.collectAsState()
    val savedEvents = Downloads.saved

    fun back() {
        if (stack.size > 1) {
            stack.removeAt(stack.lastIndex)
        } else {
            val activity = context as? android.app.Activity
            if (activity != null) activity.moveTaskToBack(true) else stack.clear()
        }
    }

    fun navigate(next: Screen) {
        if (stack.lastOrNull() != next) stack.add(next)
    }

    fun openItem(item: LibraryItem) {
        when {
            Reader.isAudio(item) -> navigate(Screen.Audio(item))
            Reader.isReadable(item) -> navigate(Screen.Reader(item))
            else -> com.monochrome.annas.util.openItem(context, item)
        }
    }

    fun openAudiobook(item: LibraryItem) {
        val link = vm.audioLinks[item.id] ?: return
        navigate(Screen.Audio(item, link, item.id))
    }

    BackHandler { back() }

    LaunchedEffect(Unit) {
        vm.refreshLibrary()
        Downloads.ensurePoller(context)
        savedEvents.collectLatest { vm.refreshLibrary() }
    }

    val immersive = screen is Screen.Reader || screen is Screen.Audio

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (!immersive) {
                BottomBar(current = screen, onSelect = { target ->
                    stack.clear()
                    stack.add(target)
                })
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            AnimatedContent(targetState = screen, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "screen") { s ->
                when (s) {
                    is Screen.Search -> SearchScreen(
                        vm = vm,
                        onOpenBook = { url, preview -> navigate(Screen.Book(url, preview)) },
                    )
                    is Screen.Book -> BookScreen(
                        url = s.url,
                        preview = s.preview,
                        vm = vm,
                        onBack = { back() },
                        onOpenDownloaded = { item -> openItem(item) },
                        onListen = { item -> openAudiobook(item) },
                    )
                    is Screen.Library -> LibraryScreen(
                        vm = vm,
                        onOpen = { item -> openItem(item) },
                        onOpenBook = { url -> navigate(Screen.Book(url, null)) },
                        onListen = { item -> openAudiobook(item) },
                    )
                    is Screen.Ebooks -> LibraryScreen(
                        vm = vm,
                        kind = LibraryKind.EBOOKS,
                        onOpen = { item -> openItem(item) },
                        onOpenBook = { url -> navigate(Screen.Book(url, null)) },
                        onListen = { item -> openAudiobook(item) },
                    )
                    is Screen.AudioLibrary -> AudioLibraryScreen(
                        vm = vm,
                        onListen = { item -> openAudiobook(item) },
                        onOpenAudio = { item -> openItem(item) },
                        onOpenBook = { url -> navigate(Screen.Book(url, null)) },
                        onAudiobookify = { item -> vm.audiobookify(listOf(item)) },
                    )
                    is Screen.Settings -> SettingsScreen(vm = vm, onOpenOpds = { url -> navigate(Screen.Opds(url)) })
                    is Screen.Opds -> OpdsScreen(
                        feedUrl = s.url,
                        onBack = { back() },
                        onOpen = { url ->
                            try {
                                context.startActivity(
                                    android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            } catch (_: Exception) {}
                        },
                    )
                    is Screen.Reader -> ReaderScreen(
                        item = s.item,
                        vm = vm,
                        onListen = { openAudiobook(s.item) },
                        onBack = { back() },
                    )
                    is Screen.Audio -> AudioScreen(
                        item = s.item,
                        sourceUri = s.uri,
                        bookId = s.bookId ?: s.item.id,
                        onBack = { back() },
                    )
                }
            }
            if (!immersive) {
                val active = downloads.firstOrNull { it.state in setOf("queued", "downloading", "waiting", "saving") }
                val failed = downloads
                    .filter { it.state == "error" && !it.error.isNullOrBlank() }
                    .maxByOrNull { it.createdAt }
                var dismissedError by remember { mutableStateOf<String?>(null) }
                when {
                    active != null -> DownloadBanner(
                        active,
                        onCancel = { Downloads.cancel(context, active.id) },
                        modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter),
                    )
                    failed != null && dismissedError != failed.id ->
                        DownloadErrorBanner(
                            failed,
                            onRetry = { Downloads.retry(context, failed.id) },
                            onDismiss = { dismissedError = failed.id },
                            modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter),
                        )
                }
            }
        }
    }
    vm.audiobookStatus?.let { status ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {},
            title = { Text("Making audiobook", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column {
                    if (vm.audiobookProgress.isNotBlank()) MonoLabel(vm.audiobookProgress)
                    Spacer(Modifier.height(8.dp))
                    Text(status, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    ProgressLine()
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Uses this phone's text-to-speech engine. You can stop at any time.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { vm.cancelAudiobookify() }) {
                    Text("STOP", style = MaterialTheme.typography.labelLarge)
                }
            },
            dismissButton = {},
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }
}

@Composable
fun BottomBar(current: Screen, onSelect: (Screen) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BottomItem(MonoIcons.Search, "SEARCH", current is Screen.Search) { onSelect(Screen.Search) }
        BottomItem(MonoIcons.Library, "LIBRARY", current is Screen.Library) { onSelect(Screen.Library) }
        BottomItem(MonoIcons.Book, "EBOOKS", current is Screen.Ebooks) { onSelect(Screen.Ebooks) }
        BottomItem(MonoIcons.Headphones, "AUDIO", current is Screen.AudioLibrary) { onSelect(Screen.AudioLibrary) }
        BottomItem(MonoIcons.Settings, "SETTINGS", current is Screen.Settings) { onSelect(Screen.Settings) }
    }
}

@Composable
fun BottomItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onClick() }.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Icon(icon, contentDescription = label, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(2.dp))
        MonoLabel(label, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun DownloadBanner(task: com.monochrome.annas.data.DownloadProgress, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(task.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            ProgressLine()
            Spacer(Modifier.height(2.dp))
            MonoLabel(downloadStateLabel(task))
        }
        Spacer(Modifier.width(8.dp))
        GhostButton("CANCEL", onCancel)
    }
}

private fun downloadStateLabel(task: com.monochrome.annas.data.DownloadProgress): String = when (task.state) {
    "queued" -> "STARTING…"
    "waiting" -> task.error ?: "WAITING FOR THE SITE…"
    "saving" -> "SAVING TO YOUR PHONE…"
    else -> {
        val percent = if (task.total > 0) task.received * 100 / task.total else 0
        "${percent}%  ${formatSpeed(task.speed)}".trim()
    }
}

@Composable
fun DownloadErrorBanner(
    task: com.monochrome.annas.data.DownloadProgress,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            MonoLabel("DOWNLOAD FAILED", color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(2.dp))
            Text(task.error ?: "Download failed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(6.dp))
        GhostButton("RETRY", onRetry)
        Spacer(Modifier.width(6.dp))
        GhostButton("DISMISS", onDismiss)
    }
}
