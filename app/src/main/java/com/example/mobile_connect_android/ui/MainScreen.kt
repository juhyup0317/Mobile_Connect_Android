package com.example.mobile_connect_android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.sp
import com.example.mobile_connect_android.R
import com.example.mobile_connect_android.domain.AppModel
import com.example.mobile_connect_android.domain.AppTab
import com.example.mobile_connect_android.domain.EditorDocument
import com.example.mobile_connect_android.data.TermSession
import kotlinx.coroutines.launch

@Composable
fun MainScreen(model: AppModel) {
    val activeTab by model.tab.collectAsState()
    val notes by model.watch.notes.collectAsState()
    val unreadCount = remember(notes) { notes.count { !it.read } }

    var selectedFilePath by remember { mutableStateOf<String?>(null) }
    // Documents outlive their screen, so switching tabs keeps unsaved edits, the cursor's text and the loaded file.
    val documents = remember { mutableMapOf<String, EditorDocument>() }
    val folders = remember { FolderNavigation(model.rootPath) }
    // The session picked in the list (a new one may not have reached the watcher yet).
    var pickedSession by remember { mutableStateOf<TermSession?>(null) }
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current

    // From another tab, Back returns to Files; from Files it leaves the app. Screens inside a tab handle Back first.
    BackHandler(enabled = activeTab != AppTab.FILES) {
        keyboardController?.hide()
        model.setTab(AppTab.FILES)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                NavigationBarItem(
                    selected = activeTab == AppTab.FILES,
                    onClick = {
                        keyboardController?.hide()
                        model.setTab(AppTab.FILES)
                    },
                    icon = { Icon(painterResource(R.drawable.ic_nav_files), contentDescription = "Files") },
                    label = { Text("Files") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        unselectedIconColor = Color.Gray,
                        unselectedTextColor = Color.Gray,
                        indicatorColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )

                NavigationBarItem(
                    selected = activeTab == AppTab.TERMINALS,
                    onClick = {
                        keyboardController?.hide()
                        model.setTab(AppTab.TERMINALS)
                    },
                    icon = { Icon(painterResource(R.drawable.ic_nav_terminals), contentDescription = "Terminals") },
                    label = { Text("Terminals") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        unselectedIconColor = Color.Gray,
                        unselectedTextColor = Color.Gray,
                        indicatorColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )

                NavigationBarItem(
                    selected = activeTab == AppTab.NOTIFICATIONS,
                    onClick = {
                        keyboardController?.hide()
                        model.setTab(AppTab.NOTIFICATIONS)
                    },
                    icon = {
                        if (unreadCount > 0) {
                            BadgedBox(badge = { Badge { Text(unreadCount.toString()) } }) {
                                Icon(painterResource(R.drawable.ic_nav_notifications), contentDescription = "Notifications")
                            }
                        } else {
                            Icon(painterResource(R.drawable.ic_nav_notifications), contentDescription = "Notifications")
                        }
                    },
                    label = { Text("Notifications") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        unselectedIconColor = Color.Gray,
                        unselectedTextColor = Color.Gray,
                        indicatorColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )

                NavigationBarItem(
                    selected = activeTab == AppTab.SETTINGS,
                    onClick = {
                        keyboardController?.hide()
                        model.setTab(AppTab.SETTINGS)
                    },
                    icon = { Icon(painterResource(R.drawable.ic_nav_settings), contentDescription = "Settings") },
                    label = { Text("Settings") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        unselectedIconColor = Color.Gray,
                        unselectedTextColor = Color.Gray,
                        indicatorColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
            }
        }
    ) { innerPadding ->
        // We pass this innerPadding (which includes the bottom NavigationBar height) down to the nested screens
        // so their scrollable content can add it to their contentPadding, allowing them to scroll behind the glass nav bar.
        Box(modifier = Modifier.fillMaxSize()) {
            when (activeTab) {
                AppTab.FILES -> {
                    if (selectedFilePath != null) {
                        val path = selectedFilePath!!
                        EditorScreen(
                            document = documents.getOrPut(path) { EditorDocument(path) },
                            model = model,
                            onBack = {
                                documents.remove(path)
                                selectedFilePath = null
                            },
                            bottomPadding = innerPadding.calculateBottomPadding()
                        )
                    } else {
                        FilesScreen(
                            model = model,
                            navigation = folders,
                            onOpenFile = { path -> selectedFilePath = path },
                            bottomPadding = innerPadding.calculateBottomPadding()
                        )
                    }
                }
                AppTab.TERMINALS -> {
                    val activeSessions by model.watch.sessions.collectAsState()
                    val currentTermId by model.openTerminalID.collectAsState()
                    val currentSession = currentTermId?.let { id ->
                        activeSessions.firstOrNull { it.id == id }
                            ?: pickedSession?.takeIf { it.id == id }
                            ?: TermSession(id = id)
                    }

                    if (currentSession != null) {
                        TerminalScreen(
                            session = currentSession,
                            model = model,
                            onBack = { model.setOpenTerminalID(null) },
                            onClose = {
                                model.setOpenTerminalID(null)
                                scope.launch { model.api.closeSession(currentSession.id) }
                            },
                            bottomPadding = innerPadding.calculateBottomPadding()
                        )
                    } else {
                        TerminalsListScreen(
                            model = model,
                            onSelectSession = { session ->
                                pickedSession = session
                                model.setOpenTerminalID(session.id)
                            },
                            bottomPadding = innerPadding.calculateBottomPadding()
                        )
                    }
                }
                AppTab.NOTIFICATIONS -> NotificationsScreen(
                    model = model,
                    bottomPadding = innerPadding.calculateBottomPadding()
                )
                AppTab.SETTINGS -> {
                    var showKeyBarSettings by remember { mutableStateOf(false) }
                    if (showKeyBarSettings) {
                        KeyBarSettingsScreen(
                            model = model,
                            onBack = { showKeyBarSettings = false },
                            bottomPadding = innerPadding.calculateBottomPadding()
                        )
                    } else {
                        SettingsScreen(
                            model = model,
                            onCustomizeKeyboard = { showKeyBarSettings = true },
                            bottomPadding = innerPadding.calculateBottomPadding()
                        )
                    }
                }
            }
        }
    }
}
