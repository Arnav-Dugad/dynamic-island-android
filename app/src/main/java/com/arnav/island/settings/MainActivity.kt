package com.arnav.island.settings

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arnav.island.IslandApp
import com.arnav.island.settings.ui.Dest
import com.arnav.island.settings.ui.IslandRoot
import com.arnav.island.settings.ui.theme.IslandTheme
import com.arnav.island.settings.ui.theme.LocalIslandColors
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private val destination = MutableStateFlow<Dest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        destination.value = intent.destination()
        val graph = (application as IslandApp).graph

        setContent {
            val settings by graph.settings.state.collectAsStateWithLifecycle()
            val initial by destination.collectAsStateWithLifecycle()
            IslandTheme(settings.appTheme, settings.amoledBlack, settings.dynamicColor) {
                // System bar icons follow the app theme (which may differ from the system's).
                val dark = LocalIslandColors.current.isDark
                LaunchedEffect(dark) {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }
                Surface(color = MaterialTheme.colorScheme.background) {
                    IslandRoot(graph, initial)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        destination.value = intent.destination()
    }

    private fun Intent.destination(): Dest? = when (getStringExtra(EXTRA_DESTINATION)) {
        "timers" -> Dest.TIMERS
        "calibration" -> Dest.CALIBRATION
        "developer" -> Dest.DEVELOPER
        "studio" -> Dest.STUDIO
        "whats_new" -> Dest.WHATS_NEW
        "share" -> Dest.SHARE
        else -> null
    }

    companion object {
        const val EXTRA_DESTINATION = "destination"
    }
}
