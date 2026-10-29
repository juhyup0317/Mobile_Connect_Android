package com.example.mobile_connect_android

import com.example.mobile_connect_android.data.InputFilter
import com.example.mobile_connect_android.domain.LiveText
import com.example.mobile_connect_android.domain.UndoHistory
import com.example.mobile_connect_android.ui.Highlighter
import com.example.mobile_connect_android.ui.SyntaxKind
import com.example.mobile_connect_android.ui.SyntaxLanguage
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HighlighterTest {
    /** The color at each index, or null. */
    private fun colorAt(text: String, language: SyntaxLanguage, index: Int): Int? {
        val runs = Highlighter.runs(text, language)
        var i = 0
        while (i < runs.size) {
            if (index >= runs[i] && index < runs[i + 1]) return runs[i + 2]
            i += 3
        }
        return null
    }

    @Test fun keywordsInsideStringsStayStringColored() {
        val code = "val s = \"if return\""
        assertEquals(SyntaxKind.KEYWORD.color, colorAt(code, SyntaxLanguage.cLike, 0))
        assertEquals(SyntaxKind.STRING.color, colorAt(code, SyntaxLanguage.cLike, code.indexOf("if")))
    }

    @Test fun slashesInsideStringsAreNotComments() {
        val code = "const url = \"http://example.com\" // note"
        assertEquals(SyntaxKind.STRING.color, colorAt(code, SyntaxLanguage.javascript, code.indexOf("example")))
        assertEquals(SyntaxKind.COMMENT.color, colorAt(code, SyntaxLanguage.javascript, code.indexOf("note")))
    }

    @Test fun blockCommentsSpanLines() {
        val code = "/* one\n two */ int x = 1;"
        assertEquals(SyntaxKind.COMMENT.color, colorAt(code, SyntaxLanguage.cLike, code.indexOf("two")))
        assertEquals(SyntaxKind.NUMBER.color, colorAt(code, SyntaxLanguage.cLike, code.indexOf("1")))
    }

    @Test fun jsonKeysAreSpecialValuesAreStrings() {
        val code = "{\"name\": \"value\"}"
        assertEquals(SyntaxKind.SPECIAL.color, colorAt(code, SyntaxLanguage.json, code.indexOf("name")))
        assertEquals(SyntaxKind.STRING.color, colorAt(code, SyntaxLanguage.json, code.indexOf("value")))
    }

    @Test fun pythonTripleQuotesAndDecorators() {
        val code = "@app.route\ndef f():\n    \"\"\"doc 'x' \"\"\"\n"
        assertEquals(SyntaxKind.SPECIAL.color, colorAt(code, SyntaxLanguage.python, 1))
        assertEquals(SyntaxKind.STRING.color, colorAt(code, SyntaxLanguage.python, code.indexOf("doc")))
    }

    @Test fun shellVariablesInsideStrings() {
        val code = "echo \"\$HOME/x\""
        assertEquals(SyntaxKind.SPECIAL.color, colorAt(code, SyntaxLanguage.shell, code.indexOf("HOME")))
    }

    @Test fun runsNeverOverlap() {
        val runs = Highlighter.runs("fun main() { println(\"hi\") } // done 42", SyntaxLanguage.cLike)
        var i = 3
        while (i < runs.size) {
            assert(runs[i] >= runs[i - 2]) { "run at $i overlaps the previous one" }
            i += 3
        }
    }

    @Test fun detectsLanguageByName() {
        assertEquals(SyntaxLanguage.shell, SyntaxLanguage.detect("/x/Dockerfile"))
        assertEquals(SyntaxLanguage.cLike, SyntaxLanguage.detect("/x/Main.kt"))
        assertEquals(SyntaxLanguage.plain, SyntaxLanguage.detect("/x/notes"))
    }
}

class LiveTextTest {
    /** Plays recognizer updates into a fake screen. */
    private fun play(vararg updates: String): String {
        val screen = StringBuilder()
        val live = LiveText()
        for (u in updates) {
            live.apply(u, erase = { n -> repeat(n) { screen.deleteCharAt(screen.length - 1) } }, type = { screen.append(it) })
        }
        return screen.toString()
    }

    @Test fun cumulativePartialsAreNotRepeated() = assertEquals("hello world", play("hello", "hello wor", "hello world"))

    @Test fun revisedWordsAreRetyped() = assertEquals("I scream", play("ice", "ice cream", "I scream"))

    @Test fun koreanSyllablesRevise() = assertEquals("안녕하세요", play("안녕", "안녕하", "안녕하세요"))
}

class UndoHistoryTest {
    private fun applyUndo(text: StringBuilder, history: UndoHistory) {
        val e = history.popUndo()!!
        text.replace(e.start, e.start + e.inserted.length, e.removed)
    }

    @Test fun aBurstOfTypingIsOneStep() {
        val h = UndoHistory()
        val text = StringBuilder("ab")
        for ((i, c) in "xyz".withIndex()) {
            text.insert(2 + i, c)
            h.record(2 + i, "", c.toString(), now = 100L + i)
        }
        applyUndo(text, h)
        assertEquals("ab", text.toString())
        assertEquals(false, h.canUndo)
        assertEquals(true, h.canRedo)
    }

    @Test fun keyboardRevisingAComposingWordIsFolded() {
        val h = UndoHistory()
        val text = StringBuilder()
        text.append("hel"); h.record(0, "", "hel", 0)
        text.replace(0, 3, "hello"); h.record(0, "hel", "hello", 10)
        applyUndo(text, h)
        assertEquals("", text.toString())
    }

    @Test fun aPauseStartsANewStep() {
        val h = UndoHistory()
        val text = StringBuilder()
        text.append("a"); h.record(0, "", "a", 0)
        text.append("b"); h.record(1, "", "b", 5_000)
        applyUndo(text, h)
        assertEquals("a", text.toString())
    }
}

/** The terminal now runs a real emulator; these are exactly the cases the old text renderer got wrong. */
class TerminalEmulatorTest {
    private class Output : TerminalOutput() {
        val sent = StringBuilder()
        override fun write(data: ByteArray, offset: Int, count: Int) { sent.append(String(data, offset, count)) }
        override fun titleChanged(oldTitle: String?, newTitle: String?) {}
        override fun onCopyTextToClipboard(text: String?) {}
        override fun onPasteTextFromClipboard() {}
        override fun onBell() {}
        override fun onColorsChanged() {}
    }

    private object Client : TerminalSessionClient {
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

    private val output = Output()
    private val emu = TerminalEmulator(output, 20, 5, 10, 20, 100, Client)

    private fun feed(s: String) = s.toByteArray().let { emu.append(it, it.size) }
    private fun line(row: Int) = emu.getSelectedText(0, row, 19, row).trimEnd()

    @Test fun carriageReturnOverwrites() {
        // Progress bars and spinners.
        feed("50%\r100%")
        assertEquals("100%", line(0))
    }

    @Test fun clearScreenAndCursorMoves() {
        feed("old text\r\nmore\u001b[2J\u001b[H")
        feed("\u001b[3;5Hhere")
        assertEquals("", line(0))
        assertEquals("    here", line(2))
    }

    @Test fun backspaceAndEraseLine() {
        feed("abcdef\b\b\u001b[K")
        assertEquals("abcd", line(0))
    }

    @Test fun fullScreenProgramsUseTheAlternateScreen() {
        feed("shell prompt")
        feed("\u001b[?1049h\u001b[Hvim")
        assertEquals("vim", line(0))
        feed("\u001b[?1049l")
        assertEquals("shell prompt", line(0))
    }

    @Test fun automaticRepliesNeverReachTheShell() {
        // Device attributes and cursor position queries: the emulator answers, the filter drops the answers.
        feed("\u001b[c\u001b[6n")
        assert(output.sent.isNotEmpty())
        assertEquals("", InputFilter.userInput(output.sent.toString()))
    }

    @Test fun bracketedPasteIsKept() {
        feed("\u001b[?2004h")
        emu.paste("a\nb")
        assertEquals("\u001b[200~a\rb\u001b[201~", InputFilter.userInput(output.sent.toString()))
    }

    @Test fun keysAreValidJsonOnTheWire() {
        // The old socket hand-escaped JSON and sent raw ESC bytes, which the server rejected.
        val payload = kotlinx.serialization.json.buildJsonObject {
            put("t", kotlinx.serialization.json.JsonPrimitive("i"))
            put("d", kotlinx.serialization.json.JsonPrimitive("\u001b[A\u0003"))
        }.toString()
        assertNull(Regex("[\\u0000-\\u001f]").find(payload))
    }
}
