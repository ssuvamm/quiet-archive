package com.monochrome.annas.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.monochrome.annas.data.*
import kotlinx.coroutines.delay

@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    val context = LocalContext.current
    var step by remember { mutableIntStateOf(0) } // 0 splash, 1 theme, 2 folder
    var theme by remember { mutableStateOf(context.getThemeMode()) }
    var folder by remember { mutableStateOf<String?>(null) }

    val onBg = MaterialTheme.colorScheme.onBackground
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    LaunchedEffect(step) {
        if (step == 0) {
            delay(1500)
            step = 1
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (_: Exception) {
                try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            }
            context.setFolderUri(uri.toString())
            folder = uri.toString()
            context.setOnboarded(true)
            onFinish()
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when (step) {
            0 -> Box(Modifier.fillMaxSize().background(Color.Black)) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    LogoMark()
                    Spacer(Modifier.height(24.dp))
                    Text("Quiet Archive", style = MaterialTheme.typography.displayMedium, color = Color.White, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Text("A quiet place for books.", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF9A9A9A), textAlign = TextAlign.Center)
                }
            }
            1 -> Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                LogoMark(size = 64.dp, color = onBg)
                Spacer(Modifier.height(24.dp))
                Text("Choose your theme", style = MaterialTheme.typography.headlineLarge, color = onBg, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text("You can change this later in Settings.", style = MaterialTheme.typography.bodySmall, color = muted, textAlign = TextAlign.Center)
                Spacer(Modifier.height(32.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf("system", "light", "dark").forEach { t ->
                        GhostButton(
                            t.uppercase(),
                            onClick = {
                                theme = t
                                // Live preview: retheme the whole screen immediately,
                                // same mechanism the Settings page uses.
                                ThemeController.set(t)
                            },
                            modifier = Modifier
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                MonoLabel("SELECTED: $theme")
                Spacer(Modifier.height(40.dp))
                GhostButton("CONTINUE", onClick = {
                    context.setThemeMode(theme)
                    ThemeController.set(theme)
                    step = 2
                }, modifier = Modifier.fillMaxWidth())
            }
            else -> Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                LogoMark(size = 64.dp, color = onBg)
                Spacer(Modifier.height(24.dp))
                Text("Where should books live?", style = MaterialTheme.typography.headlineLarge, color = onBg, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text("Pick a folder, or use app storage.", style = MaterialTheme.typography.bodySmall, color = muted, textAlign = TextAlign.Center)
                Spacer(Modifier.height(32.dp))
                GhostButton("CHOOSE STORAGE FOLDER", { launcher.launch(null) }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                GhostButton("USE APP STORAGE", {
                    context.setFolderUri(null)
                    context.setOnboarded(true)
                    onFinish()
                }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                MonoLabel("You can change this later in Settings.")
            }
        }
    }
}

@Composable
fun LogoMark(size: androidx.compose.ui.unit.Dp = 96.dp, color: Color = Color.White) {
    Canvas(Modifier.size(size)) {
        val w = size.toPx()
        val h = size.toPx()
        drawLine(color, Offset(w * 0.15f, h * 0.25f), Offset(w * 0.5f, h * 0.15f), strokeWidth = 2f, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.5f, h * 0.15f), Offset(w * 0.85f, h * 0.25f), strokeWidth = 2f, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.15f, h * 0.25f), Offset(w * 0.15f, h * 0.8f), strokeWidth = 2f, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.85f, h * 0.25f), Offset(w * 0.85f, h * 0.8f), strokeWidth = 2f, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.15f, h * 0.8f), Offset(w * 0.5f, h * 0.72f), strokeWidth = 2f, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.85f, h * 0.8f), Offset(w * 0.5f, h * 0.72f), strokeWidth = 2f, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.5f, h * 0.15f), Offset(w * 0.5f, h * 0.72f), strokeWidth = 2f, cap = StrokeCap.Round)
        for (i in 0..2) {
            val y = h * (0.35f + i * 0.12f)
            drawLine(color, Offset(w * 0.22f, y), Offset(w * 0.45f, y - h * 0.02f), strokeWidth = 1.2f, cap = StrokeCap.Round)
            drawLine(color, Offset(w * 0.55f, y - h * 0.02f), Offset(w * 0.78f, y), strokeWidth = 1.2f, cap = StrokeCap.Round)
        }
    }
}
