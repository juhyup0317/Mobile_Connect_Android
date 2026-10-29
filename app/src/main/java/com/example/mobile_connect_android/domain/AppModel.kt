package com.example.mobile_connect_android.domain

import android.app.Application
import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.mobile_connect_android.data.ApiClient
import com.example.mobile_connect_android.data.KeyId
import com.example.mobile_connect_android.data.Path
import com.example.mobile_connect_android.data.Prefs
import com.example.mobile_connect_android.data.TermNote
import com.example.mobile_connect_android.data.UiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

enum class AppTab { FILES, TERMINALS, NOTIFICATIONS, SETTINGS }

sealed class FileRoute {
    data class Folder(val path: String) : FileRoute()
    data class FilePath(val path: String) : FileRoute()
}

class AppModel(application: Application) : AndroidViewModel(application) {
    companion object {
        const val DEFAULT_SERVER_URL = "https://nexlitflow.com/mobile-code-connect"
    }

    val context: Context get() = getApplication<Application>().applicationContext
    private val prefsSharedPreferences = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    val api = ApiClient(context = context)
    val notifier = Notifier(context)
    val watch = TermWatch(api, notifier)
    val voiceInputManager = VoiceInputManager(context, api)

    private val _baseUrl = MutableStateFlow(prefsSharedPreferences.getString("baseUrl", null) ?: DEFAULT_SERVER_URL)
    val baseUrl: StateFlow<String> = _baseUrl.asStateFlow()

    private val _signedIn = MutableStateFlow(false)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    private val _checking = MutableStateFlow(true)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    private val _voiceEnter = MutableStateFlow(false)
    val voiceEnter: StateFlow<Boolean> = _voiceEnter.asStateFlow()

    private val _savingVoiceEnter = MutableStateFlow(false)
    val savingVoiceEnter: StateFlow<Boolean> = _savingVoiceEnter.asStateFlow()

    private val _voiceSettingError = MutableStateFlow<String?>(null)
    val voiceSettingError: StateFlow<String?> = _voiceSettingError.asStateFlow()

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _prefs = MutableStateFlow(Prefs())
    val prefs: StateFlow<Prefs> = _prefs.asStateFlow()

    private val _tab = MutableStateFlow(AppTab.FILES)
    val tab: StateFlow<AppTab> = _tab.asStateFlow()

    val stack = mutableStateListOf<FileRoute>()

    private val _root = MutableStateFlow<String?>(null)
    val root: StateFlow<String?> = _root.asStateFlow()

    private val _openTerminalID = MutableStateFlow<String?>(null)
    val openTerminalID: StateFlow<String?> = _openTerminalID.asStateFlow()

    private val _termPaths = MutableStateFlow<Map<String, String>>(emptyMap())
    val termPaths: StateFlow<Map<String, String>> = _termPaths.asStateFlow()

    private var prefsSaveJob: Job? = null

    init {
        api.baseUrl = _baseUrl.value
        api.onSignedOut = { sessionExpired() }
        watch.titleProvider = { noteTitle(it) }

        // Load cached termPaths
        val cachedPathsJson = prefsSharedPreferences.getString("termPaths", null)
        if (cachedPathsJson != null) {
            try {
                _termPaths.value = Json.decodeFromString(cachedPathsJson)
            } catch (_: Exception) {}
        }
    }

    fun setTab(newTab: AppTab) {
        _tab.value = newTab
    }

    fun setOpenTerminalID(id: String?) {
        _openTerminalID.value = id
    }

    fun openTerminal(id: String) {
        _tab.value = AppTab.TERMINALS
        _openTerminalID.value = id
    }

    fun setRoot(path: String?) {
        _root.value = path
    }

    fun noteTitle(note: TermNote): String {
        val session = watch.sessions.value.firstOrNull { it.id == note.term }
        val path = _termPaths.value[note.term] ?: session?.cwd ?: note.cwd
        val folder = if (path == "/" || path == "~") path else Path.name(path)
        return if (note.preset.rawValue == "shell") folder else "${note.preset.title} · $folder"
    }

    val home: String get() = _ui.value.homeDir ?: "/"
    val rootPath: String get() = _root.value ?: home

    fun restoreSession() {
        viewModelScope.launch {
            val savedUrl = prefsSharedPreferences.getString("baseUrl", null) ?: DEFAULT_SERVER_URL
            _baseUrl.value = savedUrl
            api.baseUrl = savedUrl
            val restored = loadRemote()
            _signedIn.value = restored
            if (restored) watch.start()
            delay(1000)
            _checking.value = false
        }
    }

    private fun sessionExpired() {
        if (!_signedIn.value) return
        viewModelScope.launch {
            stack.clear()
            _root.value = null
            _openTerminalID.value = null
            _signedIn.value = false
            watch.reset()
        }
    }

    fun refresh() {
        if (!_signedIn.value) return
        viewModelScope.launch {
            loadRemote()
            watch.restart()
        }
    }

    fun signIn(serverUrl: String, password: String, code: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            try {
                var cleanUrl = serverUrl.trim()
                if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
                    cleanUrl = "http://$cleanUrl"
                }
                api.baseUrl = cleanUrl
                api.login(password, code)

                _baseUrl.value = cleanUrl
                prefsSharedPreferences.edit().putString("baseUrl", cleanUrl).apply()

                stack.clear()
                _root.value = null
                loadRemote()
                _signedIn.value = true
                watch.start()
                onResult(null)
            } catch (e: Exception) {
                onResult(e.message ?: "Sign in failed")
            }
        }
    }

    fun signOut(everywhere: Boolean = false) {
        viewModelScope.launch {
            api.logout(everywhere)
            api.clearCookies()
            _root.value = null
            _openTerminalID.value = null
            _tab.value = AppTab.FILES
            _signedIn.value = false
            watch.reset()
        }
    }

    private suspend fun loadRemote(): Boolean {
        return try {
            val state = api.loadState()
            _ui.value = state
            try {
                val loadedPrefs = api.loadPrefs()
                _prefs.value = loadedPrefs
            } catch (_: Exception) {}

            val savedKeysJson = prefsSharedPreferences.getString("keyBarKeys", null)
            if (savedKeysJson != null) {
                try {
                    val keyIds = Json.decodeFromString<List<String>>(savedKeysJson).mapNotNull { raw ->
                        KeyId.entries.firstOrNull { it.rawValue == raw }
                    }
                    if (keyIds.isNotEmpty()) {
                        _prefs.value = _prefs.value.copy(keys = keyIds)
                    }
                } catch (_: Exception) {}
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun setVoiceEnter(value: Boolean) {
        if (_savingVoiceEnter.value || value == _voiceEnter.value) return
        val previous = _voiceEnter.value
        _voiceEnter.value = value
        _savingVoiceEnter.value = true
        viewModelScope.launch {
            try {
                api.setVoiceEnter(value)
            } catch (e: Exception) {
                _voiceEnter.value = previous
                _voiceSettingError.value = e.message
            } finally {
                _savingVoiceEnter.value = false
            }
        }
    }

    fun setTermPath(id: String, path: String) {
        if (_termPaths.value[id] == path) return
        val updated = _termPaths.value.toMutableMap()
        updated[id] = path
        _termPaths.value = updated
        prefsSharedPreferences.edit().putString("termPaths", Json.encodeToString(updated)).apply()
    }

    fun pruneTermPaths(keeping: Set<String>) {
        val kept = _termPaths.value.filterKeys { keeping.contains(it) }
        if (kept.size == _termPaths.value.size) return
        _termPaths.value = kept
        prefsSharedPreferences.edit().putString("termPaths", Json.encodeToString(kept)).apply()
    }

    fun updateUiState(change: (UiState) -> Unit) {
        val updated = _ui.value.copy()
        change(updated)
        _ui.value = updated
        viewModelScope.launch {
            api.saveState(updated)
        }
    }

    fun updatePrefs(change: (Prefs) -> Unit) {
        val updated = _prefs.value.copy()
        change(updated)
        _prefs.value = updated
        prefsSharedPreferences.edit().putString("keyBarKeys", Json.encodeToString(updated.keys.map { it.rawValue })).apply()

        prefsSaveJob?.cancel()
        prefsSaveJob = viewModelScope.launch {
            delay(500)
            api.savePrefs(updated)
        }
    }

    fun clearVoiceError() {
        _voiceSettingError.value = null
    }
}

object Drafts {
    @Serializable
    data class Draft(val text: String, val baseMtime: Double)

    private fun getFile(context: Context, path: String): File {
        val dir = File(context.filesDir, "Drafts")
        if (!dir.exists()) dir.mkdirs()
        val encoded = android.util.Base64.encodeToString(path.toByteArray(), android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE)
        return File(dir, encoded)
    }

    fun save(context: Context, path: String, text: String, baseMtime: Double) {
        try {
            val file = getFile(context, path)
            val draft = Draft(text, baseMtime)
            file.writeText(Json.encodeToString(draft))
        } catch (_: Exception) {}
    }

    fun load(context: Context, path: String): Draft? {
        return try {
            val file = getFile(context, path)
            if (!file.exists()) return null
            Json.decodeFromString<Draft>(file.readText())
        } catch (_: Exception) {
            null
        }
    }

    fun remove(context: Context, path: String) {
        try {
            val file = getFile(context, path)
            if (file.exists()) file.delete()
        } catch (_: Exception) {}
    }
}
