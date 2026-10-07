package com.jossephus.chuchu.ui.terminal

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HardwareKeyEventTest {
    @Test
    fun `shifted punctuation keeps its base key and consumes shift for text`() {
        val keys =
            listOf(
                Triple(KeyEvent.KEYCODE_SEMICOLON, ';', ':'),
                Triple(KeyEvent.KEYCODE_SLASH, '/', '?'),
                Triple(KeyEvent.KEYCODE_LEFT_BRACKET, '[', '{'),
                Triple(KeyEvent.KEYCODE_RIGHT_BRACKET, ']', '}'),
                Triple(KeyEvent.KEYCODE_BACKSLASH, '\\', '|'),
                Triple(KeyEvent.KEYCODE_APOSTROPHE, '\'', '"'),
                Triple(KeyEvent.KEYCODE_GRAVE, '`', '~'),
                Triple(KeyEvent.KEYCODE_MINUS, '-', '_'),
                Triple(KeyEvent.KEYCODE_EQUALS, '=', '+'),
                Triple(KeyEvent.KEYCODE_COMMA, ',', '<'),
                Triple(KeyEvent.KEYCODE_PERIOD, '.', '>'),
            ) +
                "!@#$%^&*()"
                    .mapIndexed { index, shifted ->
                        val digit = (index + 1) % 10
                        Triple(KeyEvent.KEYCODE_0 + digit, '0' + digit, shifted)
                    }

        for ((keyCode, base, shifted) in keys) {
            val mapped = KeyMapper.map(keyCode, shifted.code, KeyEvent.META_SHIFT_ON, base.code)!!
            val event =
                HardwareKeyEvent.from(
                    mapped.key,
                    mapped.codepoint,
                    mapped.mods,
                    GhosttyKeyAction.Press,
                    mapped.charCode,
                )
            assertEquals("base of $shifted", base.code, event.codepoint)
            assertEquals(shifted.toString(), event.utf8)
            assertEquals(1, event.mods)
            assertEquals(1, event.consumedMods)
        }
    }

    @Test
    fun `Japanese layout characters take precedence over the US table`() {
        val mapped =
            KeyMapper.map(KeyEvent.KEYCODE_APOSTROPHE, '*'.code, KeyEvent.META_SHIFT_ON, ':'.code)!!
        assertEquals(':'.code, mapped.codepoint)
        assertEquals('*'.code, mapped.charCode)
        val event =
            HardwareKeyEvent.from(
                mapped.key,
                mapped.codepoint,
                mapped.mods,
                GhosttyKeyAction.Press,
                mapped.charCode,
            )
        assertEquals("*", event.utf8)
        assertEquals(1, event.consumedMods)
    }

    @Test
    fun `unmapped writing key uses layout base instead of shifted character`() {
        val mapped =
            KeyMapper.map(KeyEvent.KEYCODE_UNKNOWN, '_'.code, KeyEvent.META_SHIFT_ON, '\\'.code)!!
        assertEquals('\\'.code, mapped.codepoint)
        assertEquals('_'.code, mapped.charCode)
    }

    @Test
    fun `uppercase text does not replace the unshifted letter`() {
        val event =
            HardwareKeyEvent.from(GhosttyKey.keyA, 'a'.code, 1, GhosttyKeyAction.Press, 'A'.code)
        assertEquals('a'.code, event.codepoint)
        assertEquals("A", event.utf8)
        assertEquals(1, event.consumedMods)
    }

    @Test
    fun `shift space remains a distinct shortcut`() {
        val event =
            HardwareKeyEvent.from(GhosttyKey.space, ' '.code, 1, GhosttyKeyAction.Press, ' '.code)
        assertEquals(" ", event.utf8)
        assertEquals(1, event.mods)
        assertEquals(0, event.consumedMods)
    }

    @Test
    fun `control alt and super shortcuts retain all modifiers`() {
        for (mods in listOf(2, 3, 4, 5, 8, 9)) {
            val event =
                HardwareKeyEvent.from(
                    GhosttyKey.bracketLeft,
                    '['.code,
                    mods,
                    GhosttyKeyAction.Press,
                    '{'.code,
                )
            assertEquals('['.code, event.codepoint)
            assertEquals(mods, event.mods)
            assertEquals(0, event.consumedMods)
            assertNull(event.utf8)
        }
    }

    @Test
    fun `release retains the same key identity without emitting text`() {
        val event =
            HardwareKeyEvent.from(
                GhosttyKey.semicolon,
                ';'.code,
                1,
                GhosttyKeyAction.Release,
                ':'.code,
            )
        assertEquals(';'.code, event.codepoint)
        assertNull(event.utf8)
        assertEquals(0, event.consumedMods)
    }

    @Test
    fun `repeat produces the same shifted text as press`() {
        val event =
            HardwareKeyEvent.from(GhosttyKey.slash, '/'.code, 1, GhosttyKeyAction.Repeat, '?'.code)
        assertEquals("?", event.utf8)
        assertEquals('/'.code, event.codepoint)
        assertEquals(1, event.consumedMods)
    }

    @Test
    fun `functional keys do not acquire layout text`() {
        for ((key, character) in
            listOf(KeyEvent.KEYCODE_ENTER to '\r', KeyEvent.KEYCODE_TAB to '\t')) {
            val mapped = KeyMapper.map(key, character.code, 0, character.code)!!
            assertEquals(0, mapped.codepoint)
            val event =
                HardwareKeyEvent.from(
                    mapped.key,
                    mapped.codepoint,
                    mapped.mods,
                    GhosttyKeyAction.Press,
                    mapped.charCode,
                )
            assertNull(event.utf8)
        }
    }

    @Test
    fun `plain punctuation still produces text with no consumed modifiers`() {
        val event =
            HardwareKeyEvent.from(
                GhosttyKey.semicolon,
                ';'.code,
                0,
                GhosttyKeyAction.Press,
                ';'.code,
            )
        assertEquals(";", event.utf8)
        assertEquals(0, event.consumedMods)
    }

    @Test
    fun `supplementary Unicode characters are not truncated to UTF16`() {
        val event =
            HardwareKeyEvent.from(
                GhosttyKey.unidentified,
                0x1f600,
                0,
                GhosttyKeyAction.Press,
                0x1f600,
            )
        assertEquals("😀", event.utf8)
    }
}
