package com.example.mobile_connect_android.domain

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.mobile_connect_android.data.ApiClient
import com.example.mobile_connect_android.data.InputFilter
import com.example.mobile_connect_android.data.KeyId
import com.example.mobile_connect_android.data.TermSocket
import com.example.mobile_connect_android.data.applyMods
import com.example.mobile_connect_android.data.keySequence
import com.example.mobile_connect_android.ui.KeyBarState
import com.example.mobile_connect_android.ui.TermView
import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.terminal.TextStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Whether the terminal is showing the live shell. */
enum class LinkState { CONNECTING, LIVE, RECONNECTING }

/**
 * Owns one terminal's socket, emulator and key-bar state. The emulator (Termux's, a real xterm) lives here rather than
 * in the view, so the screen can be rebuilt without losing what is on it. Everything runs on the main thread.
 */
class TermController(
    private val api: ApiClient,
    private val context: Context,
    id: String
) : TerminalOutput() {
    val keyState = KeyBarState()

    var link by mutableStateOf(LinkState.CONNECTING)
        private set
    var exited by mutableStateOf(false)
        private set
    var exitCode by mutableStateOf<Int?>(null)
        private set
    /** Bumped after every copy, so the screen can confirm it. */
    var copies by mutableIntStateOf(0)
        private set

    /** The shell's current folder, from its window title. */
    var onPath: (String) -> Unit = {}
    var onToggleKeyboard: () -> Unit = {}

    var emulator: TerminalEmulator? = null
        private set
    var view: TermView? = null

    private val socket = TermSocket(api, id)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var closed = false
    /** Off while the app is in the background: the socket is closed and the server replays when we come back. */
    private var active = true
    /** True once the server has sent the screen: from then on typed text can be sent. */
    private var ready = false
    private var everLive = false
    /** Keys typed while the connection was down, sent when it is back (dropped if it takes too long). */
    private val pending = StringBuilder()
    private var pendingSince = 0L
    /** Milliseconds to wait before the next reconnect attempt; doubles while the server is unreachable. */
    private var retryDelay = 2_000L
    private var size: Size? = null

    private data class Size(val cols: Int, val rows: Int, val cellWidth: Int, val cellHeight: Int)

    init {
        installPalette()
        socket.onEvent = ::handle
    }

    // MARK: Lifecycle

    /** Tells the server whether this terminal is on screen and the app is in use. */
    fun setWatching(on: Boolean) = socket.setWatching(on)

    fun setActive(on: Boolean) {
        active = on
        if (on) connectIfNeeded() else {
            socket.close()
            ready = false
        }
    }

    /** Closing the screen releases the controller; the shell keeps running on the server. */
    fun close() {
        closed = true
        scope.cancel()
        socket.close()
        view = null
    }

    /** Connects once the view has measured itself, so the server learns the real size before it replays the screen. */
    private fun connectIfNeeded() {
        val size = size ?: return
        if (closed || exited || !active || socket.isConnected) return
        socket.connect()
        socket.resize(size.cols, size.rows)
    }

    /** The view's size in character cells. */
    fun resize(cols: Int, rows: Int, cellWidth: Int, cellHeight: Int) {
        val new = Size(cols, rows, cellWidth, cellHeight)
        if (new == size) return
        size = new
        emulator?.resize(cols, rows, cellWidth, cellHeight)
        if (socket.isConnected) socket.resize(cols, rows) else connectIfNeeded()
        view?.onScreenUpdated()
    }

    // MARK: Output

    private fun freshEmulator(): TerminalEmulator {
        val s = size ?: Size(80, 24, 12, 24)
        return TerminalEmulator(this, s.cols, s.rows, s.cellWidth, s.cellHeight, TRANSCRIPT_ROWS, SessionClient).also { emulator = it }
    }

    private fun feed(text: String) {
        val emu = emulator ?: freshEmulator()
        val bytes = text.toByteArray(Charsets.UTF_8)
        emu.append(bytes, bytes.size)
        view?.onScreenUpdated()
    }

    private fun handle(event: TermSocket.Event) {
        if (closed) return
        when (event) {
            is TermSocket.Event.Output -> feed(event.text)
            is TermSocket.Event.Replay -> {
                retryDelay = 2_000L  // connected again
                // The replay is the whole screen: start from a clean emulator so nothing is drawn twice.
                freshEmulator()
                view?.resetScroll()
                feed(event.text)
                ready = true
                everLive = true
                link = LinkState.LIVE
                // Keys typed while offline go out now, unless that was so long ago they would surprise the person.
                if (pending.isNotEmpty() && SystemClock.elapsedRealtime() - pendingSince < 20_000) socket.sendInput(pending.toString())
                pending.clear()
            }
            is TermSocket.Event.Exited -> {
                exited = true
                exitCode = event.code
                socket.close()
            }
            is TermSocket.Event.Failed -> feed("\r\n[${event.message}]\r\n")
            TermSocket.Event.Disconnected -> {
                // Retry with growing pauses (2 s, 4 s ... up to 30 s) unless the shell has ended or the screen is gone.
                ready = false
                if (exited || !active) return
                link = if (everLive) LinkState.RECONNECTING else LinkState.CONNECTING
                val wait = retryDelay
                retryDelay = minOf(retryDelay * 2, 30_000L)
                scope.launch {
                    // If the session has expired this signs the person out (the app then shows the sign-in screen).
                    api.ping()
                    delay(wait)
                    connectIfNeeded()
                }
            }
        }
    }

    // MARK: Input

    /** Sends input to the shell, or holds it briefly while the connection is down. */
    private fun transmit(text: String) {
        if (text.isEmpty() || exited) return
        // Typing brings the live screen back, as in any terminal; output alone leaves a scrolled-back view where it is.
        view?.scrollToBottom()
        if (ready) {
            socket.sendInput(text)
        } else if (pending.length + text.length <= 4096) {
            if (pending.isEmpty()) pendingSince = SystemClock.elapsedRealtime()
            pending.append(text)
        }
    }

    /**
     * Text typed on the keyboard. A single character picks up the key bar's Control / Option / Shift.
     * Returns whether a modifier was used, so the view can reset the keyboard's composing state.
     */
    fun type(text: String): Boolean {
        view?.endSelection()
        var out = text
        val modded = keyState.mods.any
        if (modded) {
            if (text.codePointCount(0, text.length) == 1) out = applyMods(text, keyState.mods)
            keyState.consume()
        }
        transmit(out)
        return modded
    }

    /** An escape sequence already built for a hardware key (its modifiers included). */
    fun sendKeyCode(code: String) {
        view?.endSelection()
        keyState.consume()
        transmit(code)
    }

    /** A tap on a key-bar key. */
    fun press(key: KeyId) {
        view?.haptic()
        key.modifier?.let { keyState.tap(it); return }
        view?.endSelection()
        when (key) {
            KeyId.KBD -> onToggleKeyboard()
            KeyId.PASTE -> paste()
            else -> keySequence(key, keyState.mods, emulator?.isCursorKeysApplicationMode == true)?.let {
                view?.resetInput()
                transmit(it)
                keyState.consume()
            }
        }
    }

    /** Live dictation: backspaces over the last `erasing` characters (words the recognizer revised), then types `text`. */
    fun typeSpoken(erasing: Int, text: String) {
        if (!ready || exited) return
        view?.endSelection()
        view?.scrollToBottom()
        val clean = text.filter { !it.isISOControl() }
        socket.sendInput("\u007f".repeat(erasing) + clean)
    }

    /** Enter, once the dictation is over (the "After speaking: Press Enter" setting). */
    fun pressReturn() {
        if (ready && !exited) socket.sendInput("\r")
    }

    /** Wrapped in bracketed-paste markers when the program asked for them, so a multi-line paste doesn't run line by line. */
    fun paste() {
        val clip = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        if (clip.isNullOrEmpty()) return
        view?.endSelection()
        val emu = emulator
        if (emu != null) emu.paste(clip) else transmit(clip)
    }

    // MARK: Selection and clipboard. The view keeps the selection; these make it reachable from the top bar.

    fun selectAll() = view?.selectAll()

    /** Copies the selected text; does nothing when nothing is selected (so the clipboard is never wiped by accident). */
    fun copySelection() {
        val text = view?.selectedText() ?: return
        copy(text)
        view?.endSelection()
    }

    /** Copies everything: the screen and its scrollback. */
    fun copyAll() {
        val text = emulator?.screen?.transcriptText?.trimEnd() ?: return
        copy(text)
    }

    fun copy(text: String) {
        if (text.isEmpty()) return
        clipboard.setPrimaryClip(ClipData.newPlainText("Terminal", text))
        copies++
    }

    private val clipboard get() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    // MARK: TerminalOutput (what the emulator itself wants to say)

    override fun write(data: ByteArray, offset: Int, count: Int) {
        // The emulator's automatic answers to programs' queries must never reach the shell (it would print them as
        // text); pastes and mouse reports pass.
        transmit(InputFilter.userInput(String(data, offset, count, Charsets.UTF_8)))
    }

    override fun titleChanged(oldTitle: String?, newTitle: String?) {
        // Default shell prompts title the window "user@host: ~/folder".
        val title = newTitle ?: return
        val split = title.indexOf(": ")
        if (split <= 0) return
        val who = title.substring(0, split)
        if (!who.contains("@") || who.contains(" ")) return
        val path = title.substring(split + 2).trim()
        if (path.startsWith("/") || path.startsWith("~")) onPath(path)
    }

    override fun onCopyTextToClipboard(text: String?) {
        if (!text.isNullOrEmpty()) clipboard.setPrimaryClip(ClipData.newPlainText("Terminal", text))
    }

    override fun onPasteTextFromClipboard() = paste()
    override fun onBell() { view?.haptic() }
    override fun onColorsChanged() { view?.invalidate() }

    /** The emulator only logs and asks for a cursor style through this; there is no local process. */
    private object SessionClient : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {}
        override fun onTitleChanged(changedSession: TerminalSession) {}
        override fun onSessionFinished(finishedSession: TerminalSession) {}
        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {}
        override fun onPasteTextFromClipboard(session: TerminalSession?) {}
        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) {}
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun getTerminalCursorStyle(): Int = TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK
        override fun logError(tag: String?, message: String?) {}
        override fun logWarn(tag: String?, message: String?) {}
        override fun logInfo(tag: String?, message: String?) {}
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
        override fun logStackTrace(tag: String?, e: Exception?) {}
    }

    companion object {
        private const val TRANSCRIPT_ROWS = 5000

        // Normal 0-7, then bright 8-15: the same readable-on-black palette as the iOS app.
        private val palette = intArrayOf(
            0x2e3436, 0xff5f56, 0x5af78e, 0xf3f99d, 0x57c7ff, 0xff6ac1, 0x9aedfe, 0xe6e6e6,
            0x7a7a7a, 0xff6e67, 0x5af78e, 0xf4f99d, 0x8fd8ff, 0xff92d0, 0xa4ffff, 0xffffff,
        )

        /** Emulators copy their colors from this shared scheme, including after a program resets them. */
        private fun installPalette() {
            val colors = TerminalColors.COLOR_SCHEME.mDefaultColors
            palette.forEachIndexed { i, rgb -> colors[i] = 0xff000000.toInt() or rgb }
            colors[TextStyle.COLOR_INDEX_FOREGROUND] = 0xffffffff.toInt()
            colors[TextStyle.COLOR_INDEX_BACKGROUND] = 0xff000000.toInt()
            colors[TextStyle.COLOR_INDEX_CURSOR] = 0xffffffff.toInt()
        }
    }
}
