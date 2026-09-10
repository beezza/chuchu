package com.jossephus.chuchu.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HardwareKeyInputTest {
    @Test
    fun shiftedUppercaseKeepsUnshiftedCodepointAndResolvedText() {
        val input =
            prepareHardwareKeyInput(
                codepoint = 'a'.code,
                charCode = 'A'.code,
                mods = 1,
                action = GhosttyKeyAction.Press,
            )

        assertEquals('a'.code, input.unshiftedCodepoint)
        assertEquals("A", input.utf8)
    }

    @Test
    fun shiftedSymbolKeepsUnshiftedCodepointAndLayoutResolvedText() {
        val input =
            prepareHardwareKeyInput(
                codepoint = '2'.code,
                charCode = '@'.code,
                mods = 1,
                action = GhosttyKeyAction.Press,
            )

        assertEquals('2'.code, input.unshiftedCodepoint)
        assertEquals("@", input.utf8)
    }

    @Test
    fun controlCombinationDoesNotSendRawText() {
        val input =
            prepareHardwareKeyInput(
                codepoint = 'c'.code,
                charCode = 'c'.code,
                mods = 1 shl 1,
                action = GhosttyKeyAction.Press,
            )

        assertEquals('c'.code, input.unshiftedCodepoint)
        assertNull(input.utf8)
    }

    @Test
    fun keyReleaseDoesNotSendRawText() {
        val input =
            prepareHardwareKeyInput(
                codepoint = 'a'.code,
                charCode = 'A'.code,
                mods = 1,
                action = GhosttyKeyAction.Release,
            )

        assertEquals('a'.code, input.unshiftedCodepoint)
        assertNull(input.utf8)
    }
}
