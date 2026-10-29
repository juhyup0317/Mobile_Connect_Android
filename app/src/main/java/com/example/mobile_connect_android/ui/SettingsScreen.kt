package com.example.mobile_connect_android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.example.mobile_connect_android.data.Prefs
import com.example.mobile_connect_android.domain.AppModel
import com.example.mobile_connect_android.domain.VoiceInputManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(model: AppModel, onCustomizeKeyboard: () -> Unit = {}, bottomPadding: Dp = 0.dp) {
    val prefs by model.prefs.collectAsState()
    val uiState by model.ui.collectAsState()
    val baseUrl by model.baseUrl.collectAsState()
    val voiceEnter by model.voiceEnter.collectAsState()

    var showSignOutEverywhereDialog by remember { mutableStateOf(false) }
    var editServerUrl by remember(baseUrl) { mutableStateOf(baseUrl) }
    var editingUrl by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Settings", fontSize = 18.sp) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(top = innerPadding.calculateTopPadding())
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 16.dp + bottomPadding),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Server Connection Section
            SettingsSection("Server Connection") {
                if (editingUrl) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        OutlinedTextField(
                            value = editServerUrl,
                            onValueChange = { editServerUrl = it },
                            label = { Text("Server URL") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                            TextButton(onClick = { editingUrl = false }) { Text("Cancel") }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(onClick = {
                                editingUrl = false
                                model.signIn(editServerUrl, "", "") { _ -> }
                            }) { Text("Save") }
                        }
                    }
                } else {
                    SettingsRow(
                        title = "Server URL",
                        subtitle = baseUrl,
                        onClick = { editingUrl = true }
                    )
                }
            }

            // Keyboard Section
            SettingsSection("Keyboard & KeyBar") {
                SettingsRow(
                    title = "Customize Keyboard",
                    subtitle = "${prefs.keys.size} keys configured",
                    onClick = onCustomizeKeyboard
                )
                SettingsEnumPicker("KeyBar Display", prefs.keybar, Prefs.KeybarMode.entries, { it.title }) { selected ->
                    model.updatePrefs { it.keybar = selected }
                }
                SettingsToggle("Editor KeyBar", prefs.editorKeys) { checked ->
                    model.updatePrefs { it.editorKeys = checked }
                }
            }

            // Voice Input Section
            SettingsSection("Voice Input") {
                SettingsEnumPicker(
                    "Speech Engine",
                    model.voiceInputManager.engine,
                    VoiceInputManager.Engine.entries,
                    { if (it == VoiceInputManager.Engine.LIVE) "Live Speech (Device)" else "Server Whisper" }
                ) { selected ->
                    model.voiceInputManager.engine = selected
                }

                SettingsToggle("Terminal: Press Enter after speaking", voiceEnter) { checked ->
                    model.setVoiceEnter(checked)
                }
            }

            // Appearance Section
            SettingsSection("Appearance") {
                SettingsEnumPicker("Theme", prefs.theme, Prefs.Theme.entries, { it.title }) { selected ->
                    model.updatePrefs { it.theme = selected }
                }
                SettingsStepper("Terminal Font Size", prefs.termFontSize, 9..24) { size ->
                    model.updatePrefs { it.termFontSize = size }
                }
                SettingsStepper("Editor Font Size", prefs.editorFontSize, 10..24) { size ->
                    model.updatePrefs { it.editorFontSize = size }
                }
                SettingsToggle("Wrap Long Lines in Editor", prefs.wrap) { checked ->
                    model.updatePrefs { it.wrap = checked }
                }
            }

            // File Settings Section
            SettingsSection("Files") {
                SettingsRow("Home Folder", uiState.homeDir ?: "/")
                if (uiState.cwd != null && uiState.cwd != uiState.homeDir) {
                    TextButton(onClick = {
                        model.updateUiState { it.homeDir = it.cwd }
                    }) {
                        Text("Use Current Folder as Home")
                    }
                }
            }

            // About Section
            SettingsSection("About") {
                SettingsRow("Version", "1.0 (Android)")
                SettingsRow("Powered by", Brand.company)
            }

            // Account / Sign Out Section
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { model.signOut(everywhere = false) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Sign Out")
                }

                Button(
                    onClick = { showSignOutEverywhereDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5F56)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Sign Out Everywhere")
                }
            }
        }
    }

    if (showSignOutEverywhereDialog) {
        AppDialog(
            title = "Sign out everywhere?",
            text = "You'll be signed out on every device, including this one.",
            onDismiss = { showSignOutEverywhereDialog = false },
            confirmText = "Sign out",
            destructive = true,
            onConfirm = {
                showSignOutEverywhereDialog = false
                model.signOut(everywhere = true)
            }
        )
    }
}

@Composable
fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp))
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)) {
            Column(modifier = Modifier.fillMaxWidth()) {
                content()
            }
        }
    }
}

@Composable
fun SettingsRow(title: String, subtitle: String? = null, onClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(title, color = MaterialTheme.colorScheme.onBackground, fontSize = 15.sp)
            if (subtitle != null) {
                Text(subtitle, color = Color.Gray, fontSize = 13.sp)
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f))
}

@Composable
fun SettingsToggle(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = MaterialTheme.colorScheme.onBackground, fontSize = 15.sp)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f))
}

@Composable
fun <T> SettingsEnumPicker(title: String, selected: T, options: List<T>, label: (T) -> String, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = true }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = MaterialTheme.colorScheme.onBackground, fontSize = 15.sp)
        Text(label(selected), color = MaterialTheme.colorScheme.primary, fontSize = 15.sp)
    }

    if (expanded) {
        // Picking a choice applies it at once, like Android's own settings lists.
        AppDialog(
            title = title,
            onDismiss = { expanded = false },
            dismissText = null,
            confirmText = "Cancel",
            onConfirm = { expanded = false }
        ) {
            DialogRadioList(options, selected, label) { option ->
                onSelect(option)
                expanded = false
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f))
}

@Composable
fun SettingsStepper(title: String, value: Int, range: IntRange, onValueChange: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("$title: $value", color = MaterialTheme.colorScheme.onBackground, fontSize = 15.sp)
        Row {
            IconButton(onClick = { if (value > range.first) onValueChange(value - 1) }) {
                Text("-", color = MaterialTheme.colorScheme.onBackground, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            IconButton(onClick = { if (value < range.last) onValueChange(value + 1) }) {
                Text("+", color = MaterialTheme.colorScheme.onBackground, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f))
}
