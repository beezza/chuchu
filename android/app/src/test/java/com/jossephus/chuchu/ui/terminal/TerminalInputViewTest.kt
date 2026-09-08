package com.jossephus.chuchu.ui.terminal

import android.app.Activity
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the real Android editable/input connection; no native SSH library required. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TerminalInputViewTest {
    private val activity = Robolectric.buildActivity(Activity::class.java)
    private lateinit var view: TerminalInputView
    private lateinit var connection: InputConnection
    private lateinit var editorInfo: EditorInfo
    private val output = StringBuilder()
    private val keys = mutableListOf<List<Int>>()

    @Before
    fun setUp() {
        activity.setup()
        view = TerminalInputView(activity.get())
        activity.get().setContentView(view)
        view.requestFocus()
        view.onTerminalText = { output.append(it) }
        view.onTerminalKey = { key, codepoint, mods, action, charCode ->
            keys += listOf(key, codepoint, mods, action, charCode)
        }
        editorInfo = EditorInfo()
        connection = view.onCreateInputConnection(editorInfo)
    }

    @After
    fun tearDown() {
        activity.pause().stop().destroy()
    }

    @Test
    fun japaneseCandidatesAreNotDisabledInEitherInputType() {
        for (type in listOf(view.inputType, editorInfo.inputType)) {
            assertEquals(InputType.TYPE_CLASS_TEXT, type and InputType.TYPE_MASK_CLASS)
            assertEquals(0, type and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        }
        assertEquals(0, editorInfo.initialSelStart)
        assertEquals(0, editorInfo.initialSelEnd)
    }

    @Test
    fun physicalTextKeyIsNotConsumedBeforeTheIme() {
        assertFalse(view.dispatchKeyEventPreIme(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_N)))
        assertTrue(output.isEmpty())
        assertTrue(keys.isEmpty())
    }

    @Test
    fun editorBatchLifecycleIsAvailableToTheIme() {
        // Standalone BaseInputConnection returns false and never starts a
        // TextView editor batch, so its selection notifications are missing.
        assertTrue(connection.beginBatchEdit())
        connection.setComposingText("にほん", 1)
        connection.endBatchEdit()
        assertEquals(3, view.selectionStart)
        assertEquals(3, view.selectionEnd)
    }

    @Test
    fun japaneseConversionReplacesReadingAndCommitsOnlyOnce() {
        connection.setComposingText("にほん", 1)
        assertEquals("にほん", connection.getTextBeforeCursor(20, 0).toString())
        assertEquals(0, BaseInputConnection.getComposingSpanStart(view.editableText))
        assertEquals(3, BaseInputConnection.getComposingSpanEnd(view.editableText))
        connection.setComposingText("日本", 1)
        connection.commitText("日本", 1)
        assertEquals("にほん\u007f\u007f\u007f日本", output.toString())
        assertEquals("", view.editableText.toString())
    }

    @Test
    fun identicalCommitInsideNestedBatchesDoesNotDeleteTheCommittedWord() {
        connection.setComposingText("日本", 1)
        output.clear()
        connection.beginBatchEdit()
        connection.beginBatchEdit()
        connection.commitText("日本", 1)
        connection.endBatchEdit()
        connection.endBatchEdit()
        assertEquals("", output.toString())
        assertEquals("", view.editableText.toString())
    }

    @Test
    fun finishCompositionInsideBatchDoesNotDeleteTheWord() {
        connection.setComposingText("日本", 1)
        output.clear()
        connection.beginBatchEdit()
        connection.finishComposingText()
        connection.endBatchEdit()
        assertEquals("", output.toString())
    }

    @Test
    fun nextBackspaceAfterCommitDeletesOneCharacter() {
        connection.commitText("日本語", 1)
        output.clear()
        connection.deleteSurroundingText(1, 0)
        assertEquals("\u007f", output.toString())
    }

    @Test
    fun batchedSurroundingDeleteIsEmittedOnce() {
        connection.setComposingText("日本語", 1)
        // Remove the composing span without clearing the editable, modelling
        // an IME that edits a previously composed surrounding-text range.
        BaseInputConnection.removeComposingSpans(view.editableText)
        output.clear()
        connection.beginBatchEdit()
        connection.deleteSurroundingText(1, 0)
        connection.endBatchEdit()
        assertEquals("\u007f", output.toString())
    }

    @Test
    fun unicodeCandidateReplacementDoesNotSplitASurrogatePair() {
        connection.setComposingText("\uD842\uDFB7", 1) // 𠮷
        output.clear()
        connection.setComposingText("\uD842\uDFB8", 1)
        assertEquals("\u007f\uD842\uDFB8", output.toString())
    }

    @Test
    fun codePointBackspaceReachesTheTerminal() {
        connection.commitText("日本", 1)
        output.clear()
        assertTrue(connection.deleteSurroundingTextInCodePoints(1, 0))
        assertEquals("\u007f", output.toString())
    }

    @Test
    fun android13TextAttributeOverloadsStillEmitTerminalText() {
        connection.setComposingText("にほん", 1, null)
        connection.commitText("日本", 1, null)
        assertEquals("にほん\u007f\u007f\u007f日本", output.toString())
    }

    @Test
    fun android14ReplaceTextStillEmitsTerminalText() {
        connection.setComposingText("にほん", 1)
        output.clear()
        connection.replaceText(0, 3, "日本", 1, null)
        assertEquals("\u007f\u007f\u007f日本", output.toString())
    }

    @Test
    fun accessoryCleanupDoesNotDuplicateTextAndNewJapaneseInputSurvives() {
        connection.setComposingText("abc", 1)
        view.armInputSuppression("test tab")
        output.clear()
        connection.commitText("abc", 1)
        assertEquals("", output.toString())
        connection.commitText("日本", 1)
        assertEquals("日本", output.toString())
    }

    @Test
    fun rawHardwareShiftAndCtrlRetainTerminalModifiers() {
        view.onKeyDown(KeyEvent.KEYCODE_A, KeyEvent(0, 0, KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_A, 0, KeyEvent.META_SHIFT_ON))
        view.onKeyDown(KeyEvent.KEYCODE_C, KeyEvent(0, 0, KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_C, 0, KeyEvent.META_CTRL_ON))
        assertEquals(1, keys[0][2])
        assertEquals('A'.code, keys[0][4])
        assertEquals(2, keys[1][2])
        assertTrue(output.isEmpty())
    }

    @Test
    fun shiftedHardwareSymbolUsesTheAndroidLayoutResolvedCharacter() {
        val down = KeyEvent(0, 0, KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_2, 0, KeyEvent.META_SHIFT_ON)
        val up = KeyEvent(0, 0, KeyEvent.ACTION_UP,
            KeyEvent.KEYCODE_2, 0, KeyEvent.META_SHIFT_ON)
        val expected = String(Character.toChars(down.unicodeChar))

        assertTrue(view.onKeyDown(down.keyCode, down))
        assertTrue(view.onKeyUp(up.keyCode, up))

        assertEquals(expected, output.toString())
        assertTrue(keys.isEmpty())
    }

    @Test
    fun inputConnectionAlsoEmitsShiftedSymbolAsResolvedText() {
        val down = KeyEvent(0, 0, KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_1, 0, KeyEvent.META_SHIFT_ON)
        val up = KeyEvent(0, 0, KeyEvent.ACTION_UP,
            KeyEvent.KEYCODE_1, 0, KeyEvent.META_SHIFT_ON)
        val expected = String(Character.toChars(down.unicodeChar))

        assertTrue(connection.sendKeyEvent(down))
        assertTrue(connection.sendKeyEvent(up))

        assertEquals(expected, output.toString())
        assertTrue(keys.isEmpty())
    }

    @Test
    fun ctrlShiftSymbolStaysOnTheTerminalKeyPath() {
        val event = KeyEvent(0, 0, KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_2, 0, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)

        assertTrue(view.onKeyDown(event.keyCode, event))

        assertTrue(output.isEmpty())
        assertEquals(1, keys.size)
        assertEquals(3, keys.single()[2])
    }
}
