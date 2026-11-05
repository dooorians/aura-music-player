package com.ikko.aura

import android.os.Bundle
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import com.ikko.aura.ui.theme.AuraTheme
import com.ikko.aura.viewmodel.SettingsViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Both bars fully transparent with light icons, whatever the system theme: the app is dark
        // throughout. Contrast enforcement is off too, or a 3-button navigation bar gets a
        // translucent scrim painted over the bottom of every page.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        window.isNavigationBarContrastEnforced = false
        setContent {
            AuraApp()
        }
    }
}

@Composable
private fun AuraApp() {
    // Still created here, as before: building it brings the playback connection up (SleepTimer -> PlayerController).
    // Moving that is a startup change of its own, not part of this one.
    hiltViewModel<SettingsViewModel>()
    AuraTheme {
        MainLayout()
    }
}
