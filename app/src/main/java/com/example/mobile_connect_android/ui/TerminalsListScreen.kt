package com.example.mobile_connect_android.ui

import com.example.mobile_connect_android.R

import androidx.compose.ui.res.painterResource

import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.PaddingValues
import com.example.mobile_connect_android.data.Activity
import com.example.mobile_connect_android.data.Preset
import com.example.mobile_connect_android.data.TermSession
import com.example.mobile_connect_android.domain.AppModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalsListScreen(
    model: AppModel,
    onSelectSession: (TermSession) -> Unit,
    bottomPadding: Dp = 0.dp
) {
    val scope = rememberCoroutineScope()
    val sessions by model.watch.sessions.collectAsState()
    val termPaths by model.termPaths.collectAsState()

    var showNewSheet by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    fun loadSessions() {
        scope.launch {
            loading = true
            try {
                val list = model.api.sessions()
                val ids = list.map { it.id }.toSet()
                model.pruneTermPaths(ids)
            } catch (e: Exception) {
                errorMsg = e.message
            } finally {
                loading = false
            }
        }
    }

    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        loadSessions()
        keyboardController?.hide()
    }

    val listState = rememberLazyListState()
    val hideOnScroll = rememberHideOnScroll { listState.canScrollForward || listState.canScrollBackward }
    LaunchedEffect(sessions.size) { hideOnScroll.show() }

    Scaffold(
        modifier = Modifier.nestedScroll(hideOnScroll.connection),
        topBar = {
            HideOnScrollHeader(hideOnScroll) {
                CenterAlignedTopAppBar(
                    title = { Text("Terminals", fontSize = 18.sp) },
                    actions = {
                        IconButton(onClick = { showNewSheet = true }) {
                            Icon(painterResource(R.drawable.ic_add), contentDescription = "New Terminal")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                    windowInsets = WindowInsets(0)
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(top = innerPadding.calculateTopPadding())
        ) {
            if (loading && sessions.isEmpty()) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (sessions.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("💻", fontSize = 48.sp)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("No Active Terminals", color = MaterialTheme.colorScheme.onBackground, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = { showNewSheet = true }) {
                            Text("Create Terminal")
                        }
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = bottomPadding)) {
                    items(sessions) { session ->
                        var showRowMenu by remember { mutableStateOf(false) }
                        val path = termPaths[session.id] ?: session.cwd

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectSession(session) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Text(
                                    text = when (session.preset) {
                                        Preset.SHELL -> "💻"
                                        Preset.CLAUDE, Preset.CODEX -> "✨"
                                        Preset.COPILOT -> "⚙️"
                                    },
                                    fontSize = 20.sp,
                                    modifier = Modifier.padding(end = 12.dp)
                                )
                                Column {
                                    Text(session.displayName(path), color = MaterialTheme.colorScheme.onBackground, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                    Text(path, color = Color.Gray, fontSize = 12.sp)
                                    Text(
                                        text = when {
                                            session.exited -> "Exited"
                                            session.activity == Activity.RUNNING -> "Running..."
                                            session.activity == Activity.DONE -> "Finished"
                                            session.activity == Activity.ASK -> "Needs answer"
                                            else -> "Idle"
                                        },
                                        color = when {
                                            session.exited -> Color.Gray
                                            session.activity == Activity.RUNNING -> MaterialTheme.colorScheme.primary
                                            session.activity == Activity.DONE -> Color(0xFF5AF78E)
                                            session.activity == Activity.ASK -> Color(0xFFFFC107)
                                            else -> Color.Gray
                                        },
                                        fontSize = 11.sp
                                    )
                                }
                            }

                            Box {
                                IconButton(onClick = { showRowMenu = true }) {
                                    Text("⋮", color = Color.Gray, fontSize = 18.sp)
                                }
                                DropdownMenu(expanded = showRowMenu, onDismissRequest = { showRowMenu = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Close Terminal") },
                                        onClick = {
                                            showRowMenu = false
                                            scope.launch {
                                                try {
                                                    model.api.closeSession(session.id)
                                                    loadSessions()
                                                } catch (_: Exception) {}
                                            }
                                        }
                                    )
                                }
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f))
                    }
                }
            }
        }
    }

    if (showNewSheet) {
        var selectedPreset by remember { mutableStateOf(Preset.SHELL) }
        var creating by remember { mutableStateOf(false) }

        val folder = model.ui.collectAsState().value.cwd ?: model.home
        AppDialog(
            title = "New terminal",
            text = "Opens in $folder",
            onDismiss = { showNewSheet = false },
            confirmText = "Create",
            busy = creating,
            onConfirm = {
                creating = true
                scope.launch {
                    try {
                        val newSession = model.api.newSession(folder, selectedPreset)
                        showNewSheet = false
                        onSelectSession(newSession)
                    } catch (e: Exception) {
                        errorMsg = e.message
                    } finally {
                        creating = false
                    }
                }
            }
        ) {
            DialogRadioList(Preset.entries, selectedPreset, { it.title }) { selectedPreset = it }
        }
    }
}
