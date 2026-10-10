package com.jossephus.chuchu.service.multiplexer

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TmuxSessionServiceTest {
    private val identity = TmuxSessionIdentity("\$0", 100, 200, 201)

    private fun hex(text: String) =
        (text + "\n").toByteArray().joinToString("") { "%02x".format(it) }

    private fun row(name: String = "coding", path: String = "/tmp/a|b\t日本語\nnext") =
        "\$0|2|3|201|100|200|%1|${hex(name)}|${hex(path)}\n"

    private fun ok(text: String = "") = MultiplexerCommandResult(0, text, "")

    @Test
    fun parsesMetadataAndArbitraryUtf8Framing() {
        val session = TmuxCommands.parse(row("work's | space")).single()
        assertEquals("work's | space", session.name)
        assertEquals(identity, session.identity)
        assertEquals(2, session.clientCount)
        assertTrue(session.attached)
        assertEquals(3, session.windowCount)
        assertEquals(201L, session.createdAt)
        assertEquals("/tmp/a|b\t日本語\nnext", session.workingDirectory)
        assertEquals("%1", session.activePaneId)
    }

    @Test
    fun emptyListAndMissingPathAreNormal() {
        assertTrue(TmuxCommands.parse("").isEmpty())
        assertNull(TmuxCommands.parse(row(path = "")).single().workingDirectory)
    }

    @Test
    fun rejectsMalformedResponseRatherThanTargetingWrongSession() {
        for (output in listOf("bad", row().replace("\$0", "evil"), row().replace("|2|", "|-1|"))) {
            assertThrows(IllegalArgumentException::class.java) { TmuxCommands.parse(output) }
        }
    }

    @Test
    fun quotesUserTextAndDisallowsTmuxTargetSeparators() {
        assertEquals("'a'\\''; \$(touch /tmp/no)'", TmuxCommands.quote("a'; \$(touch /tmp/no)"))
        assertTrue(
            TmuxCommands.create("a'; \$(echo no)", "/tmp/a #() b").contains("sed 's/#/##/g'")
        )
        for (name in listOf("a:b", "a.b", "a\n", "a\u0000", " ")) {
            assertThrows(IllegalArgumentException::class.java) { TmuxCommands.create(name, "") }
        }
    }

    @Test
    fun createsWithAutomaticNameAndReturnsExactNewIdentity() = runBlocking {
        val calls = mutableListOf<String>()
        val replies = ArrayDeque(listOf(ok(""), ok(identity.token), ok(row("chuchu-2"))))
        val service = TmuxSessionService {
            calls += it
            replies.removeFirst()
        }
        val result = service.create("", "/tmp/my project", listOf("chuchu-1"))
        assertEquals("chuchu-2", result.name)
        assertTrue(calls[1].contains("-s 'chuchu-2'"))
        assertTrue(calls[1].contains("directory='/tmp/my project'"))
        assertFalse(calls[1].contains(" -A"))
    }

    @Test
    fun duplicateNameNeverCreatesOrRenames() = runBlocking {
        var count = 0
        val service = TmuxSessionService {
            count++
            ok(row())
        }
        try {
            service.create("coding", "", emptyList())
            fail()
        } catch (_: IllegalStateException) {}
        try {
            service.rename(identity.copy(id = "\$1"), "coding")
            fail()
        } catch (_: IllegalStateException) {}
        assertEquals(2, count)
    }

    @Test
    fun renameAndTerminateUseIdentityGuards() = runBlocking {
        val calls = mutableListOf<String>()
        val service = TmuxSessionService {
            calls += it
            ok(if (it == TmuxCommands.list()) row() else "")
        }
        service.rename(identity, "new ' name")
        service.terminate(identity)
        assertEquals(TmuxCommands.rename(identity, "new ' name"), calls[1])
        assertEquals(TmuxCommands.terminate(identity), calls[2])
        assertTrue(calls[2].contains(identity.token))
    }

    @Test
    fun staleIdentityAndNonexistentSessionsFailClearly() = runBlocking {
        val stale = TmuxSessionService { ok(TmuxCommands.STALE) }
        try {
            stale.terminate(identity)
            fail()
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Refresh"))
        }
        val missing = TmuxSessionService {
            MultiplexerCommandResult(1, "", "can't find session: \$0")
        }
        try {
            missing.verify(identity)
            fail()
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("can't find"))
        }
    }

    @Test
    fun sshDisconnectMissingTmuxTimeoutAndPreviewFailurePropagate() = runBlocking {
        for (message in
            listOf(
                "SSH disconnected",
                "tmux executable not found",
                "Command timed out",
                "can't find pane",
            )) {
            val service = TmuxSessionService { MultiplexerCommandResult(1, "", message) }
            try {
                service.preview(identity)
                fail()
            } catch (e: IllegalStateException) {
                assertEquals(message, e.message)
            }
        }
    }

    @Test
    fun previewIsBoundedAndStripsAnsiControls() {
        val text = "\u001b[31mhello\u001b[0m\u001b]52;c;secret\u0007\r\u0000\nworld"
        assertEquals("hello\nworld", TmuxCommands.sanitizePreview(text))
        assertEquals(
            TmuxCommands.PREVIEW_MAX_CHARS,
            TmuxCommands.sanitizePreview("x".repeat(40_000)).length,
        )
        assertTrue(TmuxCommands.preview(identity).contains("tail -n 80 | head -c 32768"))
        assertFalse(TmuxCommands.preview(identity).contains("send-keys"))
    }

    @Test
    fun localSearchKeepsSourceAndIgnoresCase() {
        val sessions =
            listOf(RemoteMultiplexerSession("Coding", false), RemoteMultiplexerSession("日本語", true))
        assertEquals(listOf(sessions[0]), filterMultiplexerSessions(sessions, "COD"))
        assertTrue(filterMultiplexerSessions(sessions, "absent").isEmpty())
        assertEquals(2, sessions.size)
    }
}
