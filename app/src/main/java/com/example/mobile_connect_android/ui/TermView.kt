package com.example.mobile_connect_android.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.ActionMode
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.OverScroller
import com.example.mobile_connect_android.data.Mods
import com.example.mobile_connect_android.data.applyMods
import com.example.mobile_connect_android.domain.TermController
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalEmulator
import com.termux.view.TerminalRenderer
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Draws a `TermController`'s emulator with Termux's renderer and turns touches and keys into terminal input.
 *
 * - Tap: shows the keyboard (and clicks, when the program tracks the mouse). A tap during a selection ends it.
 * - Drag: scrolls back through the output, or sends wheel / arrow keys in full-screen programs.
 * - Touch and hold: selects the word, dragging extends it; the handles adjust it; a floating menu copies it.
 * - Pinch: text size.
 */
@SuppressLint("ViewConstructor")
class TermView(context: Context, private val controller: TermController) : View(context) {
    var onFontSizeChange: (Int) -> Unit = {}

    private var fontSizeSp = 0
    private var renderer = TerminalRenderer(1, Typeface.MONOSPACE)
    private val cellWidth get() = renderer.fontWidth
    private val cellHeight get() = renderer.fontLineSpacing

    /** First row on screen: 0 is the live screen, negative rows are scrollback. */
    private var topRow = 0
    private var scrollRemainder = 0f
    private val scroller = OverScroller(context)
    private var lastFlingY = 0

    // Selection, in emulator rows (negative = scrollback) and columns, both ends inclusive.
    private var selecting = false
    private var anchorCol = 0
    private var anchorRow = 0
    private var endCol = 0
    private var endRow = 0
    /** Which end the finger is moving: 0 none, 1 the end, 2 the anchor. */
    private var dragging = 0
    private var actionMode: ActionMode? = null

    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2196F3.toInt() }
    private val handleRadius = dp(9f)
    private val background = 0xFF000000.toInt()

    private val imm get() = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    private var input: TermInputConnection? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        isHapticFeedbackEnabled = true
        setPadding(dp(6f).toInt(), dp(4f).toInt(), dp(2f).toInt(), dp(6f).toInt())
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    fun setFontSize(sp: Int) {
        if (sp == fontSizeSp) return
        fontSizeSp = sp
        val px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp.toFloat(), resources.displayMetrics).toInt()
        renderer = TerminalRenderer(max(1, px), Typeface.MONOSPACE)
        updateSize()
        invalidate()
    }

    // MARK: Size

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // While the keyboard slides in or out the height changes every frame; only the final size is sent, so
        // full-screen programs don't redraw dozens of times.
        removeCallbacks(sizeUpdate)
        if (oldw == 0 || oldh == 0) updateSize() else postDelayed(sizeUpdate, 120)
    }

    private val sizeUpdate = Runnable { updateSize() }

    private fun updateSize() {
        if (width == 0 || height == 0 || fontSizeSp == 0) return
        val cols = max(4, ((width - paddingLeft - paddingRight) / cellWidth).toInt())
        val rows = max(1, (height - paddingTop - paddingBottom) / cellHeight)
        controller.resize(cols, rows, cellWidth.toInt(), cellHeight)
    }

    // MARK: Drawing

    /** New output arrived: follow it, unless the person has scrolled back or is selecting. */
    fun onScreenUpdated() {
        val emu = controller.emulator ?: return invalidate()
        val history = emu.screen.activeTranscriptRows
        val shift = emu.scrollCounter
        if (selecting || topRow != 0) {
            // Keep the same text in view as the output scrolls past underneath.
            topRow -= shift
            anchorRow -= shift
            endRow -= shift
            if (topRow < -history || (selecting && min(anchorRow, endRow) < -history)) {
                endSelection()
                topRow = 0
            }
        }
        topRow = topRow.coerceIn(-history, 0)
        emu.clearScrollCounter()
        invalidate()
    }

    fun scrollToBottom() {
        if (topRow == 0) return
        scroller.forceFinished(true)
        topRow = 0
        invalidate()
    }

    fun resetScroll() {
        topRow = 0
        endSelection()
    }

    override fun onDraw(canvas: Canvas) {
        // Compose hands an unclipped canvas; without this, drawColor (here and in the renderer) paints over the top bar.
        canvas.clipRect(0, 0, width, height)
        canvas.drawColor(background)
        val emu = controller.emulator ?: return
        canvas.save()
        canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat())
        if (selecting) {
            val (c1, r1, c2, r2) = ordered()
            renderer.render(emu, canvas, topRow, r1, r2, c1, c2)
        } else {
            renderer.render(emu, canvas, topRow, -1, -1, -1, -1)
        }
        canvas.restore()
        if (selecting) {
            val (c1, r1, c2, r2) = ordered()
            canvas.drawCircle(cellX(c1), cellY(r1 + 1), handleRadius, handlePaint)
            canvas.drawCircle(cellX(c2 + 1), cellY(r2 + 1), handleRadius, handlePaint)
        }
    }

    private fun cellX(col: Int) = paddingLeft + col * cellWidth
    private fun cellY(row: Int) = paddingTop + (row - topRow) * cellHeight.toFloat()

    // MARK: Selection

    private data class Span(val c1: Int, val r1: Int, val c2: Int, val r2: Int)

    private fun ordered(): Span =
        if (anchorRow < endRow || (anchorRow == endRow && anchorCol <= endCol)) Span(anchorCol, anchorRow, endCol, endRow)
        else Span(endCol, endRow, anchorCol, anchorRow)

    private fun cellAt(x: Float, y: Float): Pair<Int, Int> {
        val emu = controller.emulator
        val cols = emu?.mColumns ?: 80
        val rows = emu?.mRows ?: 24
        val history = emu?.screen?.activeTranscriptRows ?: 0
        val col = ((x - paddingLeft) / cellWidth).toInt().coerceIn(0, cols - 1)
        val row = (((y - paddingTop) / cellHeight).toInt() + topRow).coerceIn(-history, rows - 1)
        return col to row
    }

    private fun charAt(emu: TerminalEmulator, col: Int, row: Int): String = emu.getSelectedText(col, row, col, row)

    /** Selects the word under the finger (or just the cell, on a space). */
    private fun selectWord(x: Float, y: Float) {
        val emu = controller.emulator ?: return
        val (col, row) = cellAt(x, y)
        var start = col
        var end = col
        if (charAt(emu, col, row).isNotBlank()) {
            while (start > 0 && charAt(emu, start - 1, row).isNotBlank()) start--
            while (end < emu.mColumns - 1 && charAt(emu, end + 1, row).isNotBlank()) end++
        }
        anchorCol = start; anchorRow = row
        endCol = end; endRow = row
        selecting = true
        invalidate()
    }

    fun selectAll() {
        val emu = controller.emulator ?: return
        anchorCol = 0; anchorRow = -emu.screen.activeTranscriptRows
        endCol = emu.mColumns - 1; endRow = emu.mRows - 1
        selecting = true
        invalidate()
        showMenu()
    }

    fun selectedText(): String? {
        if (!selecting) return null
        val emu = controller.emulator ?: return null
        val (c1, r1, c2, r2) = ordered()
        return emu.getSelectedText(c1, r1, c2, r2).takeIf { it.isNotEmpty() }
    }

    fun endSelection() {
        if (!selecting && actionMode == null) return
        selecting = false
        dragging = 0
        actionMode?.finish()
        actionMode = null
        invalidate()
    }

    private fun showMenu() {
        actionMode?.invalidateContentRect() ?: run {
            actionMode = startActionMode(menuCallback, ActionMode.TYPE_FLOATING)
        }
    }

    private val menuCallback = object : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.add(Menu.NONE, 1, 1, android.R.string.copy)
            menu.add(Menu.NONE, 2, 2, android.R.string.paste)
            menu.add(Menu.NONE, 3, 3, android.R.string.selectAll)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            when (item.itemId) {
                1 -> controller.copySelection()
                2 -> { endSelection(); controller.paste() }
                3 -> selectAll()
            }
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            if (actionMode === mode) actionMode = null
        }

        override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
            val (c1, r1, c2, r2) = ordered()
            val top = cellY(r1).toInt().coerceIn(0, height)
            val bottom = cellY(r2 + 1).toInt().coerceIn(0, height)
            outRect.set(cellX(min(c1, c2)).toInt(), top, cellX(max(c1, c2) + 1).toInt(), max(bottom, top + 1))
        }
    }

    // MARK: Touch

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (selecting) {
                endSelection()
                return true
            }
            showKeyboard()
            val emu = controller.emulator
            if (emu != null && emu.isMouseTrackingActive && topRow == 0) {
                val (col, row) = cellAt(e.x, e.y)
                emu.sendMouseEvent(TerminalEmulator.MOUSE_LEFT_BUTTON, col + 1, row + 1, true)
                emu.sendMouseEvent(TerminalEmulator.MOUSE_LEFT_BUTTON, col + 1, row + 1, false)
            }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (scale.isInProgress) return
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            actionMode?.finish()
            selectWord(e.x, e.y)
            dragging = 1
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (scale.isInProgress || dragging != 0) return true
            actionMode?.hide(0)
            scrollRemainder += distanceY
            val rows = (scrollRemainder / cellHeight).toInt()
            scrollRemainder -= rows * cellHeight
            if (rows != 0) hideKeyboard()
            scrollBy(rows)
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (scale.isInProgress || dragging != 0) return true
            lastFlingY = 0
            scroller.fling(0, 0, 0, -velocityY.toInt(), 0, 0, Int.MIN_VALUE / 2, Int.MAX_VALUE / 2)
            postOnAnimation(flingStep)
            return true
        }
    })

    private val flingStep = object : Runnable {
        override fun run() {
            if (!scroller.computeScrollOffset()) return
            val delta = scroller.currY - lastFlingY
            val rows = delta / cellHeight
            if (rows != 0) {
                lastFlingY += rows * cellHeight
                if (!scrollBy(rows)) return scroller.forceFinished(true)
            }
            postOnAnimation(this)
        }
    }

    /** Positive rows scroll toward newer output. Returns false once there is nothing more to scroll. */
    private fun scrollBy(rows: Int): Boolean {
        val emu = controller.emulator ?: return false
        if (rows == 0) return true
        val up = rows < 0
        when {
            emu.isMouseTrackingActive -> repeat(abs(rows)) {
                emu.sendMouseEvent(if (up) TerminalEmulator.MOUSE_WHEELUP_BUTTON else TerminalEmulator.MOUSE_WHEELDOWN_BUTTON, 1, 1, true)
            }
            // Full-screen programs without mouse support (less, man) scroll with the arrow keys.
            emu.isAlternateBufferActive -> {
                val code = KeyHandler.getCode(if (up) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN, 0, emu.isCursorKeysApplicationMode, emu.isKeypadApplicationMode)
                repeat(min(abs(rows), 10)) { controller.sendKeyCode(code) }
            }
            else -> {
                val next = (topRow + rows).coerceIn(-emu.screen.activeTranscriptRows, 0)
                if (next == topRow) return false
                topRow = next
                invalidate()
            }
        }
        return true
    }

    private var pinch = 1f
    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            pinch = 1f
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            pinch *= detector.scaleFactor
            val step = when {
                pinch > 1.15f -> 1
                pinch < 0.87f -> -1
                else -> return true
            }
            pinch = 1f
            val next = (fontSizeSp + step).coerceIn(9, 24)
            if (next != fontSizeSp) {
                setFontSize(next)
                onFontSizeChange(next)
            }
            return true
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE) && event.actionMasked == MotionEvent.ACTION_SCROLL) return true
        scale.onTouchEvent(event)

        // A touch that starts on a selection handle moves that end of the selection.
        if (event.actionMasked == MotionEvent.ACTION_DOWN && selecting) {
            val (c1, r1, c2, r2) = ordered()
            val slop = handleRadius * 2.5f
            val nearStart = hypot(event.x - cellX(c1), event.y - cellY(r1 + 1)) < slop
            val nearEnd = hypot(event.x - cellX(c2 + 1), event.y - cellY(r2 + 1)) < slop
            if (nearStart || nearEnd) {
                // Make the end being dragged the moving one.
                if (nearStart) { anchorCol = c2; anchorRow = r2; endCol = c1; endRow = r1 }
                else { anchorCol = c1; anchorRow = r1; endCol = c2; endRow = r2 }
                dragging = 1
                actionMode?.hide(Long.MAX_VALUE)
                return true
            }
        }

        if (dragging != 0) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val (col, row) = cellAt(event.x, event.y - handleRadius)
                    if (col != endCol || row != endRow) {
                        endCol = col; endRow = row
                        invalidate()
                    }
                    // Near the top or bottom edge, keep scrolling so a selection can reach past the screen.
                    if (event.y < cellHeight) scrollBy(-1) else if (event.y > height - cellHeight) scrollBy(1)
                }
                MotionEvent.ACTION_UP -> {
                    dragging = 0
                    if (actionMode != null) actionMode?.apply { hide(0); invalidateContentRect() } else showMenu()
                }
                MotionEvent.ACTION_CANCEL -> dragging = 0
            }
            gestures.onTouchEvent(event)
            return true
        }
        gestures.onTouchEvent(event)
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE) && event.actionMasked == MotionEvent.ACTION_SCROLL) {
            scrollBy(if (event.getAxisValue(MotionEvent.AXIS_VSCROLL) > 0) -3 else 3)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    fun haptic() {
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    // MARK: Keyboard

    fun showKeyboard() {
        if (!hasFocus()) requestFocus()
        imm.showSoftInput(this, 0)
    }

    fun hideKeyboard() {
        imm.hideSoftInputFromWindow(windowToken, 0)
    }

    /** Ends any half-typed (composing) word, e.g. after a key-bar key moved the cursor away from it. */
    fun resetInput() {
        if (input?.reset() == true) imm.restartInput(this)
    }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // Plain text, not a password field: keyboards still offer every language (Korean, Japanese...), and with
        // suggestions off they type straight through.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return TermInputConnection().also { input = it }
    }

    /**
     * Mirrors the keyboard into the terminal as it types. Words the keyboard is still composing are sent right away,
     * and when it revises them (autocorrect, Hangul syllables forming), only the changed tail is erased and retyped.
     */
    private inner class TermInputConnection : BaseInputConnection(this, true) {
        /** What has been sent for the word being composed. */
        private var sent = ""

        fun reset(): Boolean {
            val had = sent.isNotEmpty() || editable?.isNotEmpty() == true
            sent = ""
            editable?.clear()
            return had
        }

        private fun mirror(text: String) {
            var common = 0
            while (common < sent.length && common < text.length && sent[common] == text[common]) common++
            // Don't split a surrogate pair.
            if (common > 0 && Character.isHighSurrogate(text.getOrElse(common - 1) { ' ' })) common--
            val erase = sent.codePointCount(common, sent.length)
            if (erase > 0) controller.type("\u007f".repeat(erase))
            if (text.length > common) send(text.substring(common))
            sent = text
        }

        private fun send(text: String) {
            // The keyboard's Enter is the terminal's Return.
            val out = text.replace("\r\n", "\r").replace('\n', '\r')
            if (controller.type(out)) {
                // Control / Option turned the letter into something else: the word on the keyboard no longer matches.
                post { resetInput() }
            }
        }

        override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
            super.setComposingText(text, newCursorPosition)
            mirror(text.toString())
            return true
        }

        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            super.commitText(text, newCursorPosition)
            mirror(text.toString())
            sent = ""
            editable?.clear()
            return true
        }

        override fun finishComposingText(): Boolean {
            super.finishComposingText()
            sent = ""
            editable?.clear()
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            if (sent.isEmpty()) {
                // Nothing of ours in the field: the keyboard's backspace is the terminal's.
                repeat(beforeLength) { controller.type("\u007f") }
            } else {
                val keep = max(0, sent.length - beforeLength)
                mirror(sent.substring(0, keep))
            }
            return super.deleteSurroundingText(beforeLength, afterLength)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.isSystem) return super.onKeyDown(keyCode, event)
        val emu = controller.emulator ?: return super.onKeyDown(keyCode, event)

        val bar = controller.keyState.mods
        val ctrl = event.isCtrlPressed || bar.ctrl
        val alt = event.isAltPressed || bar.alt
        val shift = event.isShiftPressed || bar.shift
        var mods = 0
        if (ctrl) mods = mods or KeyHandler.KEYMOD_CTRL
        if (alt) mods = mods or KeyHandler.KEYMOD_ALT
        if (shift) mods = mods or KeyHandler.KEYMOD_SHIFT
        if (event.isNumLockOn) mods = mods or KeyHandler.KEYMOD_NUM_LOCK

        // Arrows, Enter, Tab, Backspace, function keys...
        if (!event.isFunctionPressed) {
            KeyHandler.getCode(keyCode, mods, emu.isCursorKeysApplicationMode, emu.isKeypadApplicationMode)?.let {
                controller.sendKeyCode(it)
                return true
            }
        }

        // Printable characters, with Control and Option applied the terminal way.
        val meta = event.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK).inv()
        val cp = event.getUnicodeChar(meta)
        if (cp == 0 || (cp and KeyCharacterMap.COMBINING_ACCENT) != 0) return super.onKeyDown(keyCode, event)
        val text = String(Character.toChars(cp))
        if (event.isCtrlPressed || event.isAltPressed) {
            controller.sendKeyCode(applyMods(text, Mods(ctrl, alt, false)))
        } else {
            controller.type(text)
        }
        return true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        controller.view = this
        onScreenUpdated()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(sizeUpdate)
        removeCallbacks(flingStep)
        actionMode?.finish()
        if (controller.view === this) controller.view = null
    }
}
