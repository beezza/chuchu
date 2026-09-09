package com.jossephus.chuchu.ui.terminal

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.text.Editable
import android.text.Selection
import android.util.Log
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.TextAttribute
import android.widget.EditText
import androidx.annotation.RequiresApi

class TerminalInputView(context: Context) : EditText(context) {

    companion object {
        private const val LOG_TAG = "TerminalInput"
        private const val DEBUG_INPUT_LOGS = false
        private const val SUPPRESSION_CLEANUP_WINDOW_MS = 120L
    }

    var onTerminalText: ((String) -> Unit)? = null
    var onTerminalKey: ((Int, Int, Int, Int, Int) -> Unit)? = null

    /**
     * When true, suppress IME text input. Used to prevent double-sends
     * when accessory-bar virtual keys like Tab are tapped: we send the
     * key directly via [onTerminalKey], then ignore the IME's follow-up
     * cleanup edits within [SUPPRESSION_CLEANUP_WINDOW_MS].
     */
    @Volatile
    var suppressInput = false

    @Volatile
    private var suppressionEpoch = 0L

    @Volatile
    private var suppressionSnapshot = ""

    @Volatile
    private var suppressionDeadlineUptimeMs = 0L

    /** Active input connection, used for IME mirror resets. */
    private var activeInputConnection: TerminalInputConnection? = null

    /** Cached InputMethodManager for IME restarts. */
    private var inputMethodManager: InputMethodManager? = null

    /**
     * Shift-produced symbols are sent as layout-resolved text on key-down.
     * Remember those keys so their key-up is consumed instead of becoming an
     * unmatched Ghostty release event.
     */
    private val layoutResolvedTextKeys = mutableSetOf<Int>()

    private fun logInput(message: String) {
        if (!DEBUG_INPUT_LOGS) return
        Log.d(LOG_TAG, message)
    }

    private fun describeText(text: String): String = buildString {
        text.forEach { char ->
            when (char) {
                '\u001b' -> append("<ESC>")
                '\t' -> append("<TAB>")
                '\r' -> append("<CR>")
                '\n' -> append("<LF>")
                '\u007f' -> append("<BS>")
                else -> append(char)
            }
        }
    }

    /**
     * Keys that should drop the IME mirror buffer when delivered as
     * hardware/Ghostty key events. The terminal handles cursor motion
     * and line editing itself, so the mirror would only get out of sync.
     */
    private fun shouldInvalidateImeMirrorForKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_DEL ||
            keyCode == KeyEvent.KEYCODE_FORWARD_DEL ||
            keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
            keyCode == KeyEvent.KEYCODE_TAB ||
            keyCode == KeyEvent.KEYCODE_DPAD_UP ||
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
            keyCode == KeyEvent.KEYCODE_MOVE_HOME ||
            keyCode == KeyEvent.KEYCODE_MOVE_END ||
            keyCode == KeyEvent.KEYCODE_PAGE_UP ||
            keyCode == KeyEvent.KEYCODE_PAGE_DOWN ||
            keyCode == KeyEvent.KEYCODE_INSERT ||
            keyCode == KeyEvent.KEYCODE_ESCAPE

    private fun emitTerminalText(source: String, text: String) {
        logInput("emit source=$source text=${describeText(text)}")
        onTerminalText?.invoke(text)
    }

    private fun emitBackspaceText(source: String) {
        logInput("emit source=$source text=<BS>")
        onTerminalText?.invoke("\u007f")
    }

    /**
     * Android has already applied the active hardware-keyboard layout to
     * [KeyEvent.getUnicodeChar]. Passing that shifted symbol through Ghostty as
     * both a shifted physical key and an unshifted codepoint makes it apply the
     * modifier a second time. Send the resolved symbol as text instead.
     *
     * Ctrl/Alt/Meta combinations stay on the raw-key path for terminal
     * shortcuts and keyboard protocols. Letters and digits also stay there;
     * this workaround is deliberately limited to Shift-produced symbols.
     */
    private fun layoutResolvedShiftSymbol(event: KeyEvent): String? {
        if (!event.isShiftPressed || event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) {
            return null
        }

        val codepoint = event.unicodeChar
        if (
            codepoint <= 0 ||
            codepoint and KeyCharacterMap.COMBINING_ACCENT != 0 ||
            !Character.isValidCodePoint(codepoint) ||
            Character.isISOControl(codepoint) ||
            Character.isWhitespace(codepoint) ||
            Character.isLetterOrDigit(codepoint)
        ) {
            return null
        }

        return String(Character.toChars(codepoint))
    }

    private fun emitLayoutResolvedShiftSymbol(event: KeyEvent, source: String): Boolean {
        val text = layoutResolvedShiftSymbol(event) ?: return false
        clearSuppression("$source keyCode=${event.keyCode}")
        layoutResolvedTextKeys += event.keyCode
        emitTerminalText(source, text)
        return true
    }

    fun armInputSuppression(reason: String) {
        suppressInput = true
        suppressionSnapshot = editableText.toString()
        suppressionDeadlineUptimeMs = SystemClock.uptimeMillis() + SUPPRESSION_CLEANUP_WINDOW_MS
        val epoch = suppressionEpoch + 1
        suppressionEpoch = epoch
        logInput(
            "armSuppression reason=$reason epoch=$epoch snapshot=${describeText(suppressionSnapshot)}",
        )
        activeInputConnection?.armSuppression()
    }

    private fun clearSuppression(reason: String) {
        if (!suppressInput && suppressionSnapshot.isEmpty()) return
        if (suppressInput) {
            logInput("clearSuppression reason=$reason")
        }
        suppressInput = false
        suppressionSnapshot = ""
        suppressionDeadlineUptimeMs = 0L
    }

    private fun isSuppressionCleanupWindowActive(): Boolean =
        suppressInput && SystemClock.uptimeMillis() <= suppressionDeadlineUptimeMs

    init {
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        setTextColor(android.graphics.Color.TRANSPARENT)
        isCursorVisible = false
        isFocusableInTouchMode = true
        isFocusable = true
        setSingleLine(false)
        imeOptions =
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_ACTION_NONE
        inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        logInput(
            "onKeyDown keyCode=$keyCode unicode=${event.unicodeChar} meta=${event.metaState} flags=${event.flags}",
        )
        if (emitLayoutResolvedShiftSymbol(event, "onKeyDown.shiftSymbol")) return true

        val ghosttyAction = GhosttyKeyAction.fromAndroid(event.action, event.repeatCount)
        val mapped = KeyMapper.map(keyCode, event.unicodeChar, event.metaState)
        if (mapped != null && ghosttyAction != null) {
            clearSuppression("onKeyDown keyCode=$keyCode")
            if (ghosttyAction == GhosttyKeyAction.Press && shouldInvalidateImeMirrorForKey(keyCode)) {
                activeInputConnection?.invalidateImeMirror()
            }
            onTerminalKey?.invoke(mapped.key, mapped.codepoint, mapped.mods, ghosttyAction, mapped.charCode)
            return true
        }
        val unicodeChar = event.unicodeChar
        if (unicodeChar != 0) {
            clearSuppression("onKeyDown.unicode keyCode=$keyCode")
            emitTerminalText("onKeyDown.unicode", unicodeChar.toChar().toString())
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        logInput("onKeyUp keyCode=$keyCode flags=${event.flags}")
        if (layoutResolvedTextKeys.remove(keyCode)) return true

        val ghosttyAction = GhosttyKeyAction.fromAndroid(event.action, event.repeatCount)
        val mapped = KeyMapper.map(keyCode, event.unicodeChar, event.metaState)
        if (mapped != null && ghosttyAction != null) {
            onTerminalKey?.invoke(mapped.key, mapped.codepoint, mapped.mods, ghosttyAction, mapped.charCode)
            return true
        }
        if (event.unicodeChar != 0) return true
        return super.onKeyUp(keyCode, event)
    }

    fun showKeyboard(imm: InputMethodManager?) {
        if (imm == null) return
        inputMethodManager = imm
        if (!hasFocus()) {
            requestFocus()
            requestFocusFromTouch()
        }
        post {
            logInput("showKeyboard.restartInput")
            imm.restartInput(this)
            imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // TextView initializes its editor/IME state here. A standalone
        // BaseInputConnection skips selection, composing-region and cursor
        // updates needed by IMEs handling physical-keyboard conversion.
        val editorConnection = checkNotNull(super.onCreateInputConnection(outAttrs))
        inputMethodManager = context.getSystemService(InputMethodManager::class.java)
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_ACTION_NONE
        // NO_SUGGESTIONS is an IME-wide request to suppress candidates, not
        // just English autocorrect. Leave Japanese conversion available.
        outAttrs.inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        outAttrs.initialSelStart = selectionStart
        outAttrs.initialSelEnd = selectionEnd

        val conn = TerminalInputConnection(this, editorConnection)
        activeInputConnection = conn
        logInput("onCreateInputConnection conn=${conn.connectionId}")
        return conn
    }

    private class TerminalInputConnection(
        private val view: TerminalInputView,
        editorConnection: InputConnection,
    ) : InputConnectionWrapper(editorConnection, false) {

        val connectionId: Int = System.identityHashCode(this)

        private val maxImeBufferChars = 1024

        private var batchEditDepth = 0
        private var outerBatchBeforeText: String? = null
        private var outerBatchHadDirectEmission = false
        private var directMutationDepth = 0

        private fun getEditable(): Editable = view.editableText

        private fun logConn(message: String) {
            view.logInput("conn=$connectionId $message")
        }

        fun armSuppression() {
            logConn("armSuppression -> clearImeBuffer")
            clearImeBuffer()
        }

        fun invalidateImeMirror() {
            logConn("invalidateImeMirror -> clearImeBuffer")
            clearImeBuffer()
        }

        private fun clearImeBuffer() {
            val editable = getEditable()
            BaseInputConnection.removeComposingSpans(editable)
            if (editable.isNotEmpty()) editable.clear()
            Selection.setSelection(editable, 0)
            logConn("clearImeBuffer")
        }

        private fun emitTerminalTextWithNewlineMapping(source: String, text: String) {
            val parts = text.split('\n')
            parts.forEachIndexed { index, part ->
                if (part.isNotEmpty()) {
                    view.emitTerminalText("$source.part", part)
                }
                if (index < parts.lastIndex) {
                    view.emitTerminalText("$source.newline", "\r")
                }
            }
        }

        private fun emitDiff(source: String, before: String, after: String) {
            var commonLen = 0
            while (commonLen < before.length && commonLen < after.length) {
                val codepoint = before.codePointAt(commonLen)
                if (codepoint != after.codePointAt(commonLen)) break
                commonLen += Character.charCount(codepoint)
            }
            val deletes = before.codePointCount(commonLen, before.length)
            val inserted = after.substring(commonLen)
            logConn(
                "emitDiff source=$source before=${view.describeText(before)} after=${view.describeText(after)} del=$deletes ins=${view.describeText(inserted)}",
            )
            repeat(deletes) {
                view.emitBackspaceText("$source.diffDelete")
            }
            if (inserted.isNotEmpty()) {
                emitTerminalTextWithNewlineMapping(source, inserted)
            }
        }

        private fun consumeSuppressionIfCleanup(source: String, before: String, after: String): Boolean {
            if (!view.isSuppressionCleanupWindowActive()) return false

            val snapshot = view.suppressionSnapshot
            val isCleanupEvent =
                before == after ||
                    after.isEmpty() ||
                    after == snapshot ||
                    (snapshot.startsWith(after) && after.length <= snapshot.length)

            if (!isCleanupEvent) return false

            logConn(
                "consumeSuppressionIfCleanup source=$source before=${view.describeText(before)} after=${view.describeText(after)} snapshot=${view.describeText(snapshot)}",
            )
            clearImeBuffer()
            view.clearSuppression("$source cleanup")
            return true
        }

        private fun reconcileAndEmitMutation(source: String, before: String, after: String) {
            if (consumeSuppressionIfCleanup(source, before, after)) {
                return
            }

            if (view.suppressInput) {
                view.clearSuppression("$source real input")
            }

            emitDiff(source, before, after)

            if (after.contains('\n') || after.contains('\r') || after.length > maxImeBufferChars) {
                clearImeBuffer()
            }
        }

        private fun mutateEditableAndEmit(source: String, composing: Boolean, op: () -> Boolean): Boolean {
            val before = getEditable().toString()
            directMutationDepth += 1
            val ok = try {
                op()
            } finally {
                directMutationDepth -= 1
            }
            val after = getEditable().toString()

            logConn(
                "mutate source=$source composing=$composing ok=$ok before=${view.describeText(before)} after=${view.describeText(after)} suppress=${view.suppressInput}",
            )

            // Even an identical commit can clear the mirror below. Its batch
            // must not interpret that local reset as terminal backspaces.
            outerBatchHadDirectEmission = true

            reconcileAndEmitMutation(source, before, after)

            // Drop committed text from the mirror immediately; a long-lived
            // buffer let one backspace diff into many deletes. Keep only an
            // active composing region, until it's committed or finished.
            if (!composing && getEditable().isNotEmpty()) {
                clearMirrorSilently()
            }
            return ok
        }

        // Clear the mirror and composing spans without emitting bytes, keeping
        // it empty between commits so a later delete can't over-emit backspaces.
        private fun clearMirrorSilently() {
            val editable = getEditable()
            BaseInputConnection.removeComposingSpans(editable)
            if (editable.isNotEmpty()) editable.clear()
            Selection.setSelection(editable, 0)
            logConn("clearMirrorSilently")
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            logConn(
                "commitText text=${view.describeText((text ?: "").toString())} cursor=$newCursorPosition",
            )
            return mutateEditableAndEmit("commitText", composing = false) {
                super.commitText(text ?: "", newCursorPosition)
            }
        }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            logConn(
                "setComposingText text=${view.describeText((text ?: "").toString())} cursor=$newCursorPosition",
            )
            // Empty composing text ends composition; treat as a commit so the
            // mirror is dropped instead of holding stale state.
            val stillComposing = !text.isNullOrEmpty()
            return mutateEditableAndEmit("setComposingText", composing = stillComposing) {
                super.setComposingText(text ?: "", newCursorPosition)
            }
        }

        // Wrapper overloads otherwise go straight to the platform connection,
        // bypassing terminal emission on Android 13+.
        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        override fun commitText(text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?): Boolean =
            mutateEditableAndEmit("commitText.attributes", composing = false) {
                super.commitText(text, newCursorPosition, textAttribute)
            }

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        override fun setComposingText(text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?): Boolean =
            mutateEditableAndEmit("setComposingText.attributes", composing = text.isNotEmpty()) {
                super.setComposingText(text, newCursorPosition, textAttribute)
            }

        @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        override fun replaceText(start: Int, end: Int, text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?): Boolean =
            mutateEditableAndEmit("replaceText", composing = false) {
                super.replaceText(start, end, text, newCursorPosition, textAttribute)
            }

        override fun finishComposingText(): Boolean {
            logConn("finishComposingText")
            val ok = super.finishComposingText()
            outerBatchHadDirectEmission = true
            // Composing region is committed; drop the mirror copy so a later
            // backspace can't mass-delete it.
            if (getEditable().isNotEmpty()) clearMirrorSilently()
            return ok
        }

        override fun setComposingRegion(start: Int, end: Int): Boolean {
            logConn("setComposingRegion start=$start end=$end")
            return super.setComposingRegion(start, end)
        }

        override fun setSelection(start: Int, end: Int): Boolean {
            logConn("setSelection start=$start end=$end")
            return super.setSelection(start, end)
        }

        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText {
            // Register monitored extraction with TextView so subsequent edits
            // are reported to the IME even with the software keyboard hidden.
            super.getExtractedText(request, flags)?.let { return it }
            val editable = getEditable()
            val extracted = ExtractedText().apply {
                text = editable.toString()
                startOffset = 0
                partialStartOffset = -1
                partialEndOffset = -1
                selectionStart = Selection.getSelectionStart(editable).coerceAtLeast(0)
                selectionEnd = Selection.getSelectionEnd(editable).coerceAtLeast(0)
            }
            logConn(
                "getExtractedText flags=$flags text=${view.describeText(extracted.text.toString())} sel=${extracted.selectionStart}..${extracted.selectionEnd}",
            )
            return extracted
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean =
            deleteSurroundingTextAndEmit(beforeLength, afterLength) {
                super.deleteSurroundingText(beforeLength, afterLength)
            }

        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean =
            deleteSurroundingTextAndEmit(beforeLength, afterLength) {
                super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
            }

        private fun deleteSurroundingTextAndEmit(beforeLength: Int, afterLength: Int, op: () -> Boolean): Boolean {
            if (beforeLength < 0 || afterLength < 0) return false
            val before = getEditable().toString()
            val ok = op()
            val after = getEditable().toString()
            if (!ok) return false
            // A surrounding delete is emitted here, including inside an IME
            // batch. Do not emit it a second time when that batch closes.
            outerBatchHadDirectEmission = true
            logConn(
                "deleteSurroundingText beforeLen=$beforeLength afterLen=$afterLength ok=$ok before=${view.describeText(before)} after=${view.describeText(after)} suppress=${view.suppressInput}",
            )

            // Special case: in the suppression window, a delete that did
            // nothing (editable already empty) is the IME's cleanup. Swallow it.
            if (view.isSuppressionCleanupWindowActive() && before == after) {
                logConn("deleteSurroundingText cleanup swallowed")
                clearImeBuffer()
                view.clearSuppression("deleteSurroundingText cleanup")
                return ok
            }

            if (view.suppressInput) {
                view.clearSuppression("deleteSurroundingText real input")
            }

            if (before != after) {
                emitDiff("deleteSurroundingText", before, after)
            } else {
                // Editable was already empty but the IME still wants
                // characters deleted on the terminal side.
                repeat(beforeLength) {
                    view.emitBackspaceText("deleteSurroundingText.before")
                }
                repeat(afterLength) {
                    view.emitTerminalText("deleteSurroundingText.after", "\u001b[3~")
                }
            }
            return ok
        }

        override fun beginBatchEdit(): Boolean {
            if (batchEditDepth == 0) {
                outerBatchBeforeText = getEditable().toString()
                outerBatchHadDirectEmission = false
            }
            batchEditDepth += 1
            logConn("beginBatchEdit depth=$batchEditDepth")
            return super.beginBatchEdit()
        }

        override fun endBatchEdit(): Boolean {
            val ok = super.endBatchEdit()
            val editableAfter = getEditable().toString()
            logConn("endBatchEdit ok=$ok depth=$batchEditDepth editable=${view.describeText(editableAfter)}")

            if (batchEditDepth > 0) {
                batchEditDepth -= 1
            }

            if (batchEditDepth == 0) {
                val batchBefore = outerBatchBeforeText
                outerBatchBeforeText = null
                val hadDirectEmission = outerBatchHadDirectEmission
                outerBatchHadDirectEmission = false

                if (directMutationDepth > 0 && batchBefore != editableAfter) {
                    // This outer batch is closing inside a direct mutation callback
                    // (commitText/setComposingText/delete). That callback will emit
                    // the diff once it regains control after super.* returns.
                    outerBatchHadDirectEmission = true
                }

                if (batchBefore != null && batchBefore != editableAfter && !hadDirectEmission && directMutationDepth == 0) {
                    logConn(
                        "endBatchEdit reconcile before=${view.describeText(batchBefore)} after=${view.describeText(editableAfter)}",
                    )
                    reconcileAndEmitMutation("endBatchEdit", batchBefore, editableAfter)
                }
            }

            return ok
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            logConn(
                "sendKeyEvent action=${event.action} keyCode=${event.keyCode} unicode=${event.unicodeChar} meta=${event.metaState} flags=${event.flags}",
            )
            if (event.action == KeyEvent.ACTION_DOWN && view.emitLayoutResolvedShiftSymbol(event, "sendKeyEvent.shiftSymbol")) {
                return true
            }
            if (event.action == KeyEvent.ACTION_UP && view.layoutResolvedTextKeys.remove(event.keyCode)) {
                return true
            }

            val ghosttyAction = GhosttyKeyAction.fromAndroid(event.action, event.repeatCount)
            if (ghosttyAction != null) {
                val mapped = KeyMapper.map(event.keyCode, event.unicodeChar, event.metaState)
                if (mapped != null) {
                    if (ghosttyAction == GhosttyKeyAction.Press && view.shouldInvalidateImeMirrorForKey(event.keyCode)) {
                        invalidateImeMirror()
                    }
                    view.onTerminalKey?.invoke(mapped.key, mapped.codepoint, mapped.mods, ghosttyAction, mapped.charCode)
                    return true
                }
            }
            return super.sendKeyEvent(event)
        }
    }
}
