package com.example.mobile_connect_android.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.Layout
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView
import com.example.mobile_connect_android.domain.EditorDocument
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * A plain-text code editor on EditText: monospaced, optional line wrapping (otherwise it scrolls sideways), syntax
 * colors that never block typing, and undo / redo. The text belongs to an `EditorDocument`.
 */
@SuppressLint("ViewConstructor")
class CodeEditorView(context: Context, private val document: EditorDocument) : FrameLayout(context) {
    private val vertical = ScrollView(context).apply { isFillViewport = true; isVerticalScrollBarEnabled = true }
    private val horizontal = HorizontalScrollView(context).apply { isFillViewport = true; isHorizontalScrollBarEnabled = false }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var highlightJob: Job? = null
    private var dirtyJob: Job? = null
    /** Bumped on every edit, so a coloring worked out for older text is thrown away. */
    private var generation = 0
    private var applyingUndo = false
    private var shownRevision = -1
    private var wrap: Boolean? = null
    private var fontSize = 0
    var language: SyntaxLanguage = SyntaxLanguage.plain

    val editor = EditText(context).apply {
        typeface = Typeface.MONOSPACE
        setTextColor(0xFFE6E6E6.toInt())
        highlightColor = 0x662196F3
        background = null
        gravity = Gravity.TOP or Gravity.START
        // No suggestions or autocorrect: code is not prose. Not a password field, so every keyboard language works.
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        isVerticalScrollBarEnabled = false
        breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
        hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
        val pad = dp(12f)
        setPadding(pad, pad, pad, pad * 4)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            textCursorDrawable = GradientDrawable().apply { setColor(0xFFFFFFFF.toInt()); setSize(dp(2f), 0) }
        }
    }

    init {
        setBackgroundColor(0xFF121214.toInt())
        editor.addTextChangedListener(object : TextWatcher {
            private var removed = ""

            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
                removed = if (applyingUndo) "" else s.subSequence(start, start + count).toString()
            }

            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                if (!applyingUndo) document.undo.record(start, removed, s.subSequence(start, start + count).toString(), SystemClock.uptimeMillis())
            }

            override fun afterTextChanged(s: Editable) {
                document.edited()
                generation++
                scheduleHighlight(150)
                dirtyJob?.cancel()
                dirtyJob = scope.launch {
                    delay(300)
                    document.refreshDirty()
                }
            }
        })
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    /** Applies the settings and, when the document's text was replaced (loaded or reloaded), shows it. */
    fun update(fontSizeSp: Int, wrap: Boolean) {
        if (fontSizeSp != fontSize) {
            fontSize = fontSizeSp
            editor.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp.toFloat())
            editor.setLineSpacing(dp(3f).toFloat(), 1f)
            if (this.wrap == false) fitWidth()
        }
        if (wrap != this.wrap) {
            this.wrap = wrap
            arrange(wrap)
        }
        if (document.revision != shownRevision) {
            shownRevision = document.revision
            show(document.currentText())
        }
    }

    /** With wrapping the editor is as wide as the screen; without, as wide as its longest line, inside a sideways scroller. */
    private fun arrange(wrap: Boolean) {
        (editor.parent as? ViewGroup)?.removeView(editor)
        (horizontal.parent as? ViewGroup)?.removeView(horizontal)
        vertical.removeAllViews()
        horizontal.removeAllViews()
        if (vertical.parent == null) addView(vertical, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        editor.setHorizontallyScrolling(!wrap)
        if (wrap) {
            vertical.addView(editor, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        } else {
            horizontal.addView(editor, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            vertical.addView(horizontal, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            fitWidth()
        }
    }

    /**
     * Sizes the unwrapped editor from its longest line. The font is monospaced, so counting characters is enough,
     * and much cheaper than letting the text view measure every line on every keystroke.
     */
    private fun fitWidth() {
        if (wrap != false) return
        val text = editor.text
        var longest = 0
        var line = 0
        for (i in 0 until text.length) {
            val c = text[i]
            when {
                c == '\n' -> { longest = max(longest, line); line = 0 }
                c == '\t' -> line += 8
                c.code >= 0x1100 -> line += 2  // East Asian characters are about two columns wide
                else -> line++
            }
        }
        longest = max(longest, line)
        val charWidth = editor.paint.measureText("M")
        val wanted = ((longest + 4) * charWidth).toInt() + editor.paddingLeft + editor.paddingRight
        val min = max(width, resources.displayMetrics.widthPixels)
        val target = max(wanted, min)
        val params = editor.layoutParams ?: return
        if (params.width != target) {
            params.width = target
            editor.layoutParams = params
        }
    }

    private fun show(text: String) {
        highlightJob?.cancel()
        generation++
        val runs = Highlighter.runs(text, language)
        val colored = SpannableStringBuilder(text)
        // Colors go on before the text reaches the screen, so a large file is laid out once.
        var i = 0
        while (i < runs.size) {
            colored.setSpan(SyntaxSpan(runs[i + 2]), runs[i], runs[i + 1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            i += 3
        }
        applyingUndo = true
        editor.setText(colored, TextView.BufferType.EDITABLE)
        applyingUndo = false
        document.source = { editor.text.toString() }
        document.refreshDirty()
        fitWidth()
        val (start, end) = document.selection
        val length = editor.text.length
        editor.setSelection(start.coerceIn(0, length), end.coerceIn(0, length))
        val (x, y) = document.scrollX to document.scrollY
        post {
            vertical.scrollTo(0, y)
            horizontal.scrollTo(x, 0)
        }
    }

    // MARK: Coloring

    /** Our colors, told apart from anything else in the text. */
    private class SyntaxSpan(color: Int) : ForegroundColorSpan(color)

    /** Waits for a pause in typing, works out the colors on a background thread, and paints them only if nothing changed meanwhile. */
    private fun scheduleHighlight(delayMs: Long) {
        highlightJob?.cancel()
        val mine = generation
        highlightJob = scope.launch {
            delay(delayMs)
            val text = editor.text.toString()
            val language = language
            val runs = withContext(Dispatchers.Default) { Highlighter.runs(text, language) }
            val editable = editor.text
            // Don't repaint under a word the keyboard is still composing; the next pause will.
            if (mine != generation || BaseInputConnection.getComposingSpanStart(editable) != -1) return@launch
            paint(editable, runs)
            if (wrap == false) fitWidth()
        }
    }

    /**
     * Changes only the colors that differ from what is on screen. Spans move with the text as it is edited, so after
     * typing a few characters almost all of them are still right.
     */
    private fun paint(editable: Spannable, runs: IntArray) {
        val existing = HashMap<Long, SyntaxSpan>()
        for (span in editable.getSpans(0, editable.length, SyntaxSpan::class.java)) {
            val start = editable.getSpanStart(span)
            val end = editable.getSpanEnd(span)
            if (start >= end) {
                editable.removeSpan(span)
                continue
            }
            val key = (start.toLong() shl 32) or end.toLong()
            if (existing.put(key, span) != null) editable.removeSpan(span)
        }
        var i = 0
        while (i < runs.size) {
            val (start, end, color) = Triple(runs[i], runs[i + 1], runs[i + 2])
            i += 3
            if (end > editable.length) continue
            val key = (start.toLong() shl 32) or end.toLong()
            val span = existing[key]
            if (span != null && span.foregroundColor == color) {
                existing.remove(key)
            } else {
                editable.setSpan(SyntaxSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        for (span in existing.values) editable.removeSpan(span)
    }

    // MARK: Key bar actions

    fun indent() = replaceSelection("  ")

    /** Removes up to two leading spaces (or one tab) from the current line. */
    fun outdent() {
        val text = editor.text
        var lineStart = editor.selectionStart
        while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--
        var remove = 0
        while (remove < 2 && lineStart + remove < text.length && text[lineStart + remove] == ' ') remove++
        if (remove == 0 && lineStart < text.length && text[lineStart] == '\t') remove = 1
        if (remove > 0) text.delete(lineStart, lineStart + remove)
    }

    fun moveLeft() = editor.setSelection(max(0, editor.selectionEnd - 1))
    fun moveRight() = editor.setSelection(minOf(editor.text.length, editor.selectionEnd + 1))
    fun moveUp() = moveLine(-1)
    fun moveDown() = moveLine(1)

    private fun moveLine(delta: Int) {
        val layout = editor.layout ?: return
        val offset = editor.selectionEnd
        val line = layout.getLineForOffset(offset) + delta
        if (line < 0 || line >= layout.lineCount) return
        editor.setSelection(layout.getOffsetForHorizontal(line, layout.getPrimaryHorizontal(offset)))
    }

    fun undo() = document.undo.popUndo()?.let { apply(it.start, it.inserted.length, it.removed) }
    fun redo() = document.undo.popRedo()?.let { apply(it.start, it.removed.length, it.inserted) }

    private fun apply(start: Int, length: Int, replacement: String) {
        val text = editor.text
        if (start + length > text.length) return document.undo.clear()
        applyingUndo = true
        text.replace(start, start + length, replacement)
        applyingUndo = false
        editor.setSelection(start + replacement.length)
    }

    fun hideKeyboard() {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(windowToken, 0)
    }

    /** Live dictation: takes back the last `erasing` characters (words the recognizer revised), then types `text`. */
    fun typeSpoken(erasing: Int, text: String) {
        val editable = editor.text
        val end = editor.selectionEnd
        var start = editor.selectionStart
        var left = erasing
        while (left > 0 && start > 0) {
            start = Character.offsetByCodePoints(editable, start, -1)
            left--
        }
        editable.replace(start, end, text)
        editor.setSelection(start + text.length)
    }

    private fun replaceSelection(text: String) {
        val start = minOf(editor.selectionStart, editor.selectionEnd)
        val end = max(editor.selectionStart, editor.selectionEnd)
        editor.text.replace(start, end, text)
        editor.setSelection(start + text.length)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (shownRevision == document.revision) document.source = { editor.text.toString() }
    }

    /** Keeps where the person was, and the text, for when the editor is shown again. */
    override fun onDetachedFromWindow() {
        document.selection = editor.selectionStart to editor.selectionEnd
        document.scrollY = vertical.scrollY
        document.scrollX = horizontal.scrollX
        if (document.source != null) document.detach(editor.text.toString())
        highlightJob?.cancel()
        dirtyJob?.cancel()
        super.onDetachedFromWindow()
    }
}
