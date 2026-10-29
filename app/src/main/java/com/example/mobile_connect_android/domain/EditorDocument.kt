package com.example.mobile_connect_android.domain

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.mobile_connect_android.data.ApiClient
import com.example.mobile_connect_android.data.ApiError
import com.example.mobile_connect_android.data.FileInfo
import com.example.mobile_connect_android.data.FileKind
import com.example.mobile_connect_android.data.Path
import java.io.File

/**
 * One open file. The workspace owns it independently of its screen, so switching tabs keeps unsaved edits, the undo
 * history and the cursor.
 */
class EditorDocument(val path: String) {
    var info by mutableStateOf<FileInfo?>(null)
        private set
    /** The text as last saved to (or read from) the server. */
    var saved = ""
        private set
    var dirty by mutableStateOf(false)
        private set
    var baseMtime = 0.0
        private set
    /** Downloaded copy, for previews and sharing. */
    var localFile by mutableStateOf<File?>(null)
        private set
    var downloading by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set
    var loadError by mutableStateOf<String?>(null)
    var conflict by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)
    var saving by mutableStateOf(false)
        private set
    /** Bumped whenever the text is replaced from outside the editor (load, reload), so the editor shows it. */
    var revision by mutableIntStateOf(0)
        private set

    val undo = UndoHistory()
    /** Where the cursor and scroll were, for when the editor is shown again. */
    var selection = 0 to 0
    var scrollY = 0
    var scrollX = 0

    /** The editor, while it is on screen, reads the live text; otherwise this copy is the text. */
    private var text = ""
    var source: (() -> String)? = null

    fun currentText(): String = source?.invoke() ?: text

    /** The editor is going away: keep its text. */
    fun detach(finalText: String) {
        text = finalText
        source = null
        refreshDirty()
    }

    /** Called after every edit (cheaply); `refreshDirty` does the full comparison once typing pauses. */
    fun edited() {
        if (info?.kind == FileKind.TEXT) dirty = true
    }

    fun refreshDirty() {
        dirty = info?.kind == FileKind.TEXT && currentText() != saved
    }

    suspend fun load(api: ApiClient, context: Context, ignoreDraft: Boolean = false) {
        if (loading) return
        loading = true
        loadError = null
        try {
            val loaded = api.read(path)
            val disk = loaded.text ?: ""
            saved = disk
            text = disk
            baseMtime = loaded.mtime
            // Unsaved edits from an earlier session win, but keep the original base version so conflicts are still detected.
            val draft = if (ignoreDraft) null else Drafts.load(context, path)
            if (draft != null && draft.text != disk) {
                text = draft.text
                baseMtime = draft.baseMtime
            } else {
                Drafts.remove(context, path)
            }
            source = null
            selection = 0 to 0
            scrollX = 0
            scrollY = 0
            undo.clear()
            info = loaded
            refreshDirty()
            revision++
        } catch (e: Exception) {
            loadError = e.message ?: "Couldn't open the file."
        } finally {
            loading = false
        }
    }

    suspend fun download(api: ApiClient, context: Context) {
        if (downloading || localFile != null) return
        downloading = true
        try {
            // A folder per path keeps the real file name, which apps opening it rely on.
            val dir = File(context.cacheDir, "preview/" + Integer.toHexString(path.hashCode()))
            dir.mkdirs()
            val file = File(dir, Path.name(path))
            api.download(path, file)
            localFile = file
        } catch (e: Exception) {
            errorMessage = e.message ?: "Download failed."
        } finally {
            downloading = false
        }
    }

    /** Writes the text to the server. Anything typed while the request is in flight stays unsaved. Returns whether it worked. */
    suspend fun save(api: ApiClient, context: Context, force: Boolean = false): Boolean {
        if (saving) return false
        saving = true
        val sent = currentText()
        try {
            baseMtime = api.write(path, sent, baseMtime, force)
            saved = sent
            refreshDirty()
            keepDraft(context)
            return true
        } catch (e: ApiError) {
            if (e.status == 409) conflict = true else errorMessage = e.message
        } catch (e: Exception) {
            errorMessage = e.message ?: "Save failed."
        } finally {
            saving = false
        }
        return false
    }

    /** Throws the edits away. */
    fun discard(context: Context) {
        text = saved
        source = null
        dirty = false
        Drafts.remove(context, path)
        revision++
    }

    /** Keeps unsaved edits on the device, and drops the copy once there is nothing left to keep. */
    fun keepDraft(context: Context) {
        if (info?.kind != FileKind.TEXT) return
        refreshDirty()
        if (dirty) Drafts.save(context, path, currentText(), baseMtime) else Drafts.remove(context, path)
    }
}

/**
 * Undo and redo for the editor. Typing in one place (including the keyboard revising a word it is composing) is one
 * step, so undo takes back a burst of typing rather than a letter at a time.
 */
class UndoHistory {
    class Edit(var start: Int, var removed: String, var inserted: String, var at: Long)

    private val undos = ArrayDeque<Edit>()
    private val redos = ArrayDeque<Edit>()

    val canUndo get() = undos.isNotEmpty()
    val canRedo get() = redos.isNotEmpty()

    fun clear() {
        undos.clear()
        redos.clear()
    }

    fun record(start: Int, removed: String, inserted: String, now: Long) {
        redos.clear()
        val last = undos.lastOrNull()
        if (last != null && now - last.at < 1500 && !inserted.contains('\n')) {
            val lastEnd = last.start + last.inserted.length
            // Inside (or right after) what the last step typed: fold it in.
            if (start >= last.start && start + removed.length <= lastEnd) {
                val from = start - last.start
                last.inserted = last.inserted.substring(0, from) + inserted + last.inserted.substring(from + removed.length)
                last.at = now
                return
            }
            // Backspacing further back than the last step typed.
            if (inserted.isEmpty() && last.inserted.isEmpty() && start + removed.length == last.start) {
                last.start = start
                last.removed = removed + last.removed
                last.at = now
                return
            }
        }
        undos.addLast(Edit(start, removed, inserted, now))
        if (undos.size > 300) undos.removeFirst()
    }

    /** The step to take back, moved onto the redo stack. Apply it by replacing `inserted` with `removed` at `start`. */
    fun popUndo(): Edit? = undos.removeLastOrNull()?.also { it.at = 0; redos.addLast(it) }

    /** The step to do again. Apply it by replacing `removed` with `inserted` at `start`. */
    fun popRedo(): Edit? = redos.removeLastOrNull()?.also { it.at = 0; undos.addLast(it) }
}
