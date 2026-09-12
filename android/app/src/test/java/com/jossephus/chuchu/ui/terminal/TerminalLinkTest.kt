package com.jossephus.chuchu.ui.terminal

import com.jossephus.chuchu.service.terminal.TerminalSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TerminalLinkTest {
    @Test
    fun findsWebLinksAndDropsSentencePunctuation() {
        assertEquals(
            "https://example.com/docs?q=1",
            findTerminalLink("see (https://example.com/docs?q=1)."),
        )
    }

    @Test
    fun addsSchemeForWwwLinks() {
        assertEquals("https://www.example.com/path", findTerminalLink("www.example.com/path"))
    }

    @Test
    fun rejectsNonWebSchemesAndMalformedHosts() {
        assertNull(findTerminalLink("javascript://example.com"))
        assertNull(findTerminalLink("https://"))
        assertNull(findTerminalLink("ftp://example.com/file"))
    }

    @Test
    fun skipsAnInvalidCandidateWhenAnotherLinkIsPresent() {
        assertEquals(
            "https://example.com",
            findTerminalLink("https:// https://example.com"),
        )
    }

    @Test
    fun mapsLinkToTheCellThatWasTapped() {
        val text = "open https://example.com/docs now"
        val snapshot = snapshotOf(text)

        val firstUrlCell = text.indexOf("https://example.com/docs")
        val link = snapshot.linkAt(firstUrlCell)

        assertEquals("https://example.com/docs", link?.url)
        assertEquals(firstUrlCell..(firstUrlCell + "https://example.com/docs".lastIndex), link?.cellRange)
        assertEquals("https://example.com/docs", snapshot.linkAt(firstUrlCell + 5)?.url)
        assertNull(snapshot.linkAt(0))
    }

    private fun snapshotOf(text: String): TerminalSnapshot {
        val codepoints = text.codePoints().toArray()
        return TerminalSnapshot(
            cols = codepoints.size,
            rows = 1,
            cursorX = 0,
            cursorY = 0,
            cursorVisible = false,
            defaultBgArgb = 0xFF000000.toInt(),
            defaultFgArgb = 0xFFFFFFFF.toInt(),
            codepoints = codepoints,
            fgArgb = IntArray(codepoints.size) { 0xFFFFFFFF.toInt() },
            bgArgb = IntArray(codepoints.size) { 0xFF000000.toInt() },
            flags = ByteArray(codepoints.size),
        )
    }
}
