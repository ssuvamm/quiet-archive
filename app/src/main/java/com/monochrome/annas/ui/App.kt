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

sealed class Screen {
    data object Search : Screen()
    data class Book(val url: String, val preview: SearchResult?) : Screen()
    data object Library : Screen()
    data object Settings : Screen()
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
            var showSplash by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) }
            androidx.compose.runtime.LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(1200)
                showSplash = false
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
    var screen by remember { mutableStateOf<Screen>(Screen.Search) }
    BackHandler(enabled = screen is Screen.Book) { screen = Screen.Search }
    val downloads by Downloads.tasks.collectAsState()
    val savedEvents = Downloads.saved

    LaunchedEffect(Unit) {
        vm.refreshLibrary()
        Downloads.ensurePoller(context)
        savedEvents.collectLatest { vm.refreshLibrary() }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            BottomBar(current = screen, onSelect = { screen = it })
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            AnimatedContent(targetState = screen, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "screen") { s ->
                when (s) {
                    is Screen.Search -> SearchScreen(
                        vm = vm,
                        onOpenBook = { url, preview -> screen = Screen.Book(url, preview) },
                    )
                    is Screen.Book -> BookScreen(
                        url = s.url,
                        preview = s.preview,
                        vm = vm,
                        onBack = { screen = Screen.Search },
                        onOpenDownloaded = { item ->
                            com.monochrome.annas.util.openItem(context, item)
                        },
                    )
                    is Screen.Library -> LibraryScreen(
                        vm = vm,
                        onOpen = { item -> com.monochrome.annas.util.openItem(context, item) },
                        onOpenBook = { url -> screen = Screen.Book(url, null) },
                    )
                    is Screen.Settings -> SettingsScreen(vm = vm)
                }
            }
            val active = downloads.firstOrNull { it.state in setOf("queued", "downloading", "waiting", "saving") }
            val failed = downloads.filter { it.state == "error" && !it.error.isNullOrBlank() }.maxByOrNull { it.createdAt }
            var dismissedError by remember { mutableStateOf<String?>(null) }
            when {
                active != null -> DownloadBanner(active, modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter))
                failed != null && dismissedError != failed.id ->
                    DownloadErrorBanner(failed, onDismiss = { dismissedError = failed.id }, modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter))
            }
        }
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
fun DownloadBanner(task: com.monochrome.annas.data.DownloadProgress, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(task.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            ProgressLine()
            Spacer(Modifier.height(2.dp))
            if (task.state == "waiting") {
                MonoLabel(task.error ?: "Waiting…")
            } else {
                MonoLabel("${(if (task.total > 0) task.received * 100 / task.total else 0)}%  ${formatSpeed(task.speed)}")
            }
        }
    }
}

@Composable
fun DownloadErrorBanner(task: com.monochrome.annas.data.DownloadProgress, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            MonoLabel("DOWNLOAD FAILED", color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(2.dp))
            Text(task.error ?: "Download failed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        GhostButton("DISMISS", onDismiss, modifier = Modifier)
    }
}
