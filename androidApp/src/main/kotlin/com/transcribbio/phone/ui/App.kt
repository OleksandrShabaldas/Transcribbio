package com.transcribbio.phone.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.transcribbio.phone.ui.screens.HomeScreen
import com.transcribbio.phone.ui.screens.LectureDetailScreen
import com.transcribbio.phone.ui.screens.ResultsScreen
import com.transcribbio.phone.ui.screens.SettingsScreen

private enum class Tab(val label: String, val icon: ImageVector) {
    Home("Record", Icons.Default.Mic),
    Lectures("Lectures", Icons.Default.Description),
    Settings("Settings", Icons.Default.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    var tab by remember { mutableStateOf(Tab.Home) }
    var detailId by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (detailId != null) "Lecture" else tab.label) },
                navigationIcon = {
                    if (detailId != null) {
                        IconButton(onClick = { detailId = null }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (detailId == null) {
                NavigationBar {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(t.icon, t.label) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                detailId != null -> LectureDetailScreen(detailId!!)
                tab == Tab.Home -> HomeScreen()
                tab == Tab.Lectures -> ResultsScreen(onOpen = { detailId = it })
                tab == Tab.Settings -> SettingsScreen()
            }
        }
    }
}
