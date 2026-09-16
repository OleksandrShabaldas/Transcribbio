package com.transcribbio.phone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.transcribbio.phone.ui.App
import com.transcribbio.phone.ui.theme.TranscribbioTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TranscribbioTheme {
                App()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Opportunistic sync + update check whenever the app comes to the foreground.
        AppGraph.sync.requestSync(this)
        AppGraph.updater.checkOnLaunch()
    }
}
