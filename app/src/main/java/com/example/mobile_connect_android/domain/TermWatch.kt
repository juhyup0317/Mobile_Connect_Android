package com.example.mobile_connect_android.domain

import com.example.mobile_connect_android.data.ApiClient
import com.example.mobile_connect_android.data.TermNote
import com.example.mobile_connect_android.data.TermSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response

class TermWatch(
    private val api: ApiClient,
    private val notifier: Notifier? = null
) {
    @Serializable
    private data class Snapshot(
        val sessions: List<TermSession> = emptyList(),
        val notes: List<TermNote> = emptyList()
    )

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var job: Job? = null

    private val _sessions = MutableStateFlow<List<TermSession>>(emptyList())
    val sessions: StateFlow<List<TermSession>> = _sessions.asStateFlow()

    private val _notes = MutableStateFlow<List<TermNote>>(emptyList())
    val notes: StateFlow<List<TermNote>> = _notes.asStateFlow()

    val unread: Int get() = _notes.value.count { !it.read }

    var titleProvider: (TermNote) -> String = { "Terminal" }

    private var lastAlerted: Int? = null

    private val json = Json { ignoreUnknownKeys = true }

    fun start() {
        if (job != null) return
        job = scope.launch {
            var retryDelay = 2000L
            while (job?.isActive == true) {
                try {
                    stream()
                } catch (e: Exception) {
                    // Retry with backoff
                }
                delay(retryDelay)
                retryDelay = (retryDelay * 2).coerceAtMost(30000L)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun restart() {
        stop()
        start()
    }

    fun reset() {
        stop()
        _sessions.value = emptyList()
        _notes.value = emptyList()
    }

    private suspend fun stream() = withContext(Dispatchers.IO) {
        val url = api.buildUrl("/term/events")
        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream")
            .header("Origin", api.getOrigin())
            .build()

        val response: Response = api.okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) return@withContext

        val source = response.body?.source() ?: return@withContext
        while (!source.exhausted() && job?.isActive == true) {
            val line = source.readUtf8Line() ?: break
            if (line.startsWith("data:")) {
                val dataStr = line.substring(5).trim()
                try {
                    val snapshot = json.decodeFromString<Snapshot>(dataStr)
                    applySnapshot(snapshot)
                } catch (_: Exception) {}
            }
        }
    }

    private fun applySnapshot(snapshot: Snapshot) {
        val unreadBefore = _notes.value.filter { !it.read }.map { it.id }.toSet()
        _sessions.value = snapshot.sessions
        _notes.value = snapshot.notes

        val readNotes = snapshot.notes.filter { it.read && unreadBefore.contains(it.id) }.map { it.id }
        notifier?.remove(readNotes)

        val freshNotes = fresh(snapshot.notes, lastAlerted, System.currentTimeMillis().toDouble())
        lastAlerted = snapshot.notes.maxOfOrNull { it.id } ?: 0
        freshNotes.forEach { note ->
            notifier?.post(note, titleProvider(note))
        }
    }

    companion object {
        fun fresh(notes: List<TermNote>, last: Int?, now: Double): List<TermNote> {
            val newest = notes.maxOfOrNull { it.id } ?: 0
            val previous = last ?: return emptyList()
            return notes.filter { it.id > previous && !it.read && (now - it.at) < 120000 }
                .sortedBy { it.id }
        }
    }

    fun markRead(term: String? = null) {
        val updated = _notes.value.map {
            if (term == null || it.term == term) it.copy(read = true) else it
        }
        _notes.value = updated
        scope.launch {
            try {
                api.markNotesRead(term)
            } catch (_: Exception) {}
        }
    }

    fun clear() {
        _notes.value = emptyList()
        scope.launch {
            try {
                api.clearNotes()
            } catch (_: Exception) {}
        }
    }
}
