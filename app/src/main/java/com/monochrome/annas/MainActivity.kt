package com.monochrome.annas

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import com.monochrome.annas.data.*
import com.monochrome.annas.ui.AppRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WebFetch.activityContext = this
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            var onboarded by remember { mutableStateOf(applicationContext.getOnboarded()) }
            AppRoot(onboarding = !onboarded, onOnboarded = { onboarded = true })
        }
    }
}
