package com.transcribbio.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.transcribbio.desktop.ui.App
import com.transcribbio.desktop.ui.AppState
import com.transcribbio.desktop.ui.ThemeMode
import com.transcribbio.desktop.ui.theme.TranscribbioTheme
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths

fun main() = application {
    val appState = remember { AppState() }
    val themeMode by appState.themeMode.collectAsState()
    val windowState = rememberWindowState(width = 1120.dp, height = 780.dp)

    Window(
        onCloseRequest = { appState.shutdown(); exitApplication() },
        title = "Transcribbio",
        state = windowState,
    ) {
        val frame = window
        val dark = when (themeMode) {
            ThemeMode.SYSTEM -> isSystemInDarkTheme()
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }
        TranscribbioTheme(darkTheme = dark) {
            App(
                state = appState,
                pickAudioFile = { pickOpenFile(frame) },
                pickSaveFile = { name -> pickSaveFile(frame, name) },
                onOpenUrl = { url -> openUrl(url) },
            )
        }
    }
}

private fun pickOpenFile(parent: Frame): Path? {
    val dialog = FileDialog(parent, "Select audio file", FileDialog.LOAD).apply {
        isMultipleMode = false
        isVisible = true
    }
    val file = dialog.file ?: return null
    val dir = dialog.directory ?: return null
    return Paths.get(dir, file)
}

private fun pickSaveFile(parent: Frame, defaultName: String): Path? {
    val dialog = FileDialog(parent, "Save file", FileDialog.SAVE).apply {
        file = defaultName
        isVisible = true
    }
    val file = dialog.file ?: return null
    val dir = dialog.directory ?: return null
    return Paths.get(dir, file)
}

private fun openUrl(url: String) {
    runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI(url))
        }
    }
}
