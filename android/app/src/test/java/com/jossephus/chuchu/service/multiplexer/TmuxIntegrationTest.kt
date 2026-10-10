package com.jossephus.chuchu.service.multiplexer

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Every command, including cleanup, is pinned to a private socket and empty config. */
class TmuxIntegrationTest {
    private lateinit var directory: File
    private lateinit var binary: String
    private lateinit var wrapperDir: File
    private lateinit var service: TmuxSessionService

    @Before
    fun setUp() {
        val probe = ProcessBuilder("sh", "-c", "command -v tmux").start()
        binary = probe.inputStream.bufferedReader().readText().trim()
        assumeTrue("tmux is required for isolated integration tests", probe.waitFor() == 0)
        directory = Files.createTempDirectory("chuchu-tmux-test-").toFile()
        wrapperDir = File(directory, "bin").apply { mkdir() }
        File(wrapperDir, "tmux").apply {
            writeText(
                "#!/bin/sh\nexec ${TmuxCommands.quote(binary)} -S ${TmuxCommands.quote(File(directory, "socket").path)} -f /dev/null \"\$@\"\n"
            )
            setExecutable(true)
        }
        service = TmuxSessionService { execute(it) }
    }

    @After
    fun tearDown() {
        if (!::directory.isInitialized) return
        execute("tmux kill-server")
        directory.deleteRecursively()
    }

    private fun execute(command: String, loginShell: String = "sh"): MultiplexerCommandResult {
        val builder =
            ProcessBuilder(loginShell, "-c", MultiplexerShell.execCommand(command))
                .redirectErrorStream(true)
        builder.environment()["PATH"] = wrapperDir.path + ":" + System.getenv("PATH")
        builder.environment().remove("TMUX")
        builder.environment().remove("TMUX_PANE")
        val process = builder.start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            "Isolated tmux test timed out"
        }
        return MultiplexerShell.parseResult(output)
    }

    @Test
    fun realListCreateRenamePreviewAndTerminate() = runBlocking {
        assertTrue(service.list().isEmpty())
        val path = File(directory, "project | 日本語\tline\nnext").apply { mkdir() }
        val session = service.create("work's | space", path.path, emptyList())
        assertEquals("work's | space", session.name)
        assertEquals(path.path, session.workingDirectory)
        assertEquals(0, session.clientCount)
        assertEquals(1, session.windowCount)
        val identity = requireNotNull(session.identity)
        service.verify(identity)
        service.rename(identity, "renamed ' ; \$(touch nope)")
        assertEquals("renamed ' ; \$(touch nope)", service.list().single().name)
        assertFalse(File(directory, "nope").exists())
        val result =
            execute(
                "tmux new-window -t ${TmuxCommands.quote(identity.id)} -n preview 'printf ACTIVE_PANE_MARKER; sleep 30'"
            )
        assertTrue(result.output, result.isSuccess)
        execute("tmux select-window -t ${TmuxCommands.quote(identity.id + ":1")}")
        var preview = ""
        repeat(20) {
            if (!preview.contains("ACTIVE_PANE_MARKER")) {
                Thread.sleep(25)
                preview = service.preview(identity)
            }
        }
        assertTrue(preview.contains("ACTIVE_PANE_MARKER"))
        assertTrue(preview.lines().size <= 80)
        assertTrue(preview.length <= TmuxCommands.PREVIEW_MAX_CHARS)
        service.terminate(identity)
        assertTrue(service.list().isEmpty())
    }

    @Test
    fun duplicatesInvalidDirectoriesAndMissingSessionsFail() = runBlocking {
        val session = service.create("coding", "", emptyList())
        try {
            service.create("coding", "", emptyList())
            fail()
        } catch (_: IllegalStateException) {}
        try {
            service.create("bad-path", File(directory, "missing").path, emptyList())
            fail()
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("directory") || e.message!!.contains("chdir"))
        }
        val identity = requireNotNull(session.identity)
        service.terminate(identity)
        try {
            service.preview(identity)
            fail()
        } catch (_: IllegalStateException) {}
        assertTrue(service.list().isEmpty())
    }

    @Test
    fun serverRestartReusesIdButRejectsOldIdentity() = runBlocking {
        val old = requireNotNull(service.create("old", "", emptyList()).identity)
        assertTrue(execute("tmux kill-server").isSuccess)
        val replacement = requireNotNull(service.create("replacement", "", emptyList()).identity)
        assertEquals(old.id, replacement.id)
        assertNotEquals(old, replacement)
        try {
            service.terminate(old)
            fail()
        } catch (_: IllegalStateException) {}
        assertEquals("replacement", service.list().single().name)
        service.verify(replacement)
    }

    @Test
    fun guardedAttachmentUsesExistingSessionAndTerminationDoesNotRecreateIt() = runBlocking {
        attachAndTerminate("sh")
    }

    @Test
    fun fishLoginShellSupportsManagementAndPtyAttachment() = runBlocking {
        val probe = ProcessBuilder("sh", "-c", "command -v fish").start()
        assumeTrue("fish is required for the SSH shell regression test", probe.waitFor() == 0)
        service = TmuxSessionService { execute(it, "fish") }
        assertTrue(execute(TmuxMultiplexer.availabilityCommand(), "fish").isSuccess)
        val name = "fish's | session"
        val path = File(directory, "fish's \\\\ project").apply { mkdir() }
        val session = service.create(name, path.path, emptyList())
        assertEquals(name, session.name)
        assertEquals(path.path, session.workingDirectory)
        val identity = requireNotNull(session.identity)
        service.rename(identity, "renamed ' ; literal session")
        service.verify(identity)
        assertEquals("renamed ' ; literal session", service.list().single().name)
        service.preview(identity)
        service.terminate(identity)
        attachAndTerminate("fish")
    }

    private suspend fun attachAndTerminate(loginShell: String) {
        val probe = ProcessBuilder("sh", "-c", "command -v script").start()
        assumeTrue(probe.waitFor() == 0)
        val session = service.create("attach-test", "", emptyList())
        val identity = requireNotNull(session.identity)
        val command = MultiplexerShell.command(TmuxCommands.attach(identity))
        val builder =
            ProcessBuilder(
                    "script",
                    "-q",
                    "-e",
                    "-c",
                    "$loginShell -c ${TmuxCommands.quote(command)}",
                    "/dev/null",
                )
                .redirectErrorStream(true)
        builder.environment()["PATH"] = wrapperDir.path + ":" + System.getenv("PATH")
        builder.environment()["SHELL"] = "/bin/sh"
        builder.environment()["TERM"] = "xterm-256color"
        builder.environment().remove("TMUX")
        val client = builder.start()
        try {
            var attached = false
            repeat(40) {
                if (!attached) {
                    Thread.sleep(25)
                    attached = service.list().single().clientCount == 1
                }
            }
            assertTrue("Guarded exec must attach a real PTY client", attached)
            service.terminate(identity)
            assertTrue(client.waitFor(5, TimeUnit.SECONDS))
            assertTrue(service.list().isEmpty())
            assertFalse(execute(TmuxCommands.attach(identity), loginShell).isSuccess)
            assertTrue(service.list().isEmpty())
        } finally {
            client.destroyForcibly()
        }
    }

    @Test
    fun shellMetacharactersAndFormatLikeDirectoryStayLiteral() = runBlocking {
        val marker = File(directory, "injected")
        val name = "a'; touch ${marker.path}; echo '"
        val path = File(directory, "#{session_name} #() ' ").apply { mkdir() }
        val session = service.create(name, path.path, emptyList())
        assertEquals(name, session.name)
        assertEquals(path.path, session.workingDirectory)
        service.rename(requireNotNull(session.identity), "b'; touch ${marker.path}; echo '")
        assertFalse(marker.exists())
    }
}
