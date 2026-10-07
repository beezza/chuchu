package com.jossephus.chuchu.ui.terminal

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28, 35])
class TerminalInputConnectionTest {
    private lateinit var view: TerminalInputView
    private lateinit var connection: InputConnection
    private val emitted = mutableListOf<String>()

    @Before
    fun setUp() {
        emitted.clear()
        view = TerminalInputView(RuntimeEnvironment.getApplication())
        view.onTerminalText = { emitted += it }
        connection = view.onCreateInputConnection(EditorInfo())
    }

    private fun terminalText(): String = buildString {
        emitted.forEach { chunk ->
            chunk.forEach { char ->
                if (char == '\u007f') {
                    if (isNotEmpty()) deleteCharAt(lastIndex)
                } else {
                    append(char)
                }
            }
        }
    }

    @Test
    fun finishingCompositionInBatchKeepsJapaneseText() {
        connection.setComposingText("日本語", 1)
        connection.beginBatchEdit()
        connection.finishComposingText()
        connection.endBatchEdit()

        assertEquals("日本語", terminalText())
        assertEquals(listOf("日本語"), emitted)
        assertEquals("", view.editableText.toString())
    }

    @Test
    fun committingUnchangedCandidateInBatchKeepsJapaneseText() {
        connection.setComposingText("日本語", 1)
        connection.beginBatchEdit()
        connection.commitText("日本語", 1)
        connection.finishComposingText()
        connection.endBatchEdit()

        assertEquals("日本語", terminalText())
        assertEquals(listOf("日本語"), emitted)
    }

    @Test
    fun replacingKanaWithCandidateAndFinishingKeepsCandidateOnce() {
        connection.setComposingText("にほんご", 1)
        connection.beginBatchEdit()
        connection.commitText("日本語", 1)
        connection.finishComposingText()
        connection.endBatchEdit()

        assertEquals("日本語", terminalText())
    }

    @Test
    fun nestedBatchFinishingDoesNotDeleteCommittedText() {
        connection.setComposingText("日本語", 1)
        connection.beginBatchEdit()
        connection.beginBatchEdit()
        connection.finishComposingText()
        connection.endBatchEdit()
        assertEquals("日本語", terminalText())
        connection.endBatchEdit()
        assertEquals(listOf("日本語"), emitted)
    }

    @Test
    fun enterMirrorResetInBatchDoesNotSendBackspaces() {
        connection.setComposingText("日本語", 1)
        connection.beginBatchEdit()
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        connection.endBatchEdit()

        assertEquals(listOf("日本語"), emitted)
        assertEquals("", view.editableText.toString())
    }

    @Test
    fun cancellingCompositionStillDeletesItsText() {
        connection.setComposingText("にほんご", 1)
        connection.beginBatchEdit()
        connection.setComposingText("", 1)
        connection.finishComposingText()
        connection.endBatchEdit()

        assertEquals("", terminalText())
        assertEquals(4, emitted.count { it == "\u007f" })
    }

    @Test
    fun nextCommitAndBackspaceDoNotDeletePreviousWord() {
        connection.setComposingText("日本語", 1)
        connection.beginBatchEdit()
        connection.commitText("日本語", 1)
        connection.endBatchEdit()
        connection.commitText("a", 1)
        connection.deleteSurroundingText(1, 0)

        assertEquals("日本語", terminalText())
        assertEquals(1, emitted.count { it == "\u007f" })
    }

    @Test
    fun unhandledEditableChangesStillReachTerminalAtBatchEnd() {
        connection.beginBatchEdit()
        view.editableText.append("日本語")
        connection.endBatchEdit()

        assertEquals("日本語", terminalText())
    }
}
