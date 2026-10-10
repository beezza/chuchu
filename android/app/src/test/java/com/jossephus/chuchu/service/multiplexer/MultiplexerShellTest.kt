package com.jossephus.chuchu.service.multiplexer

import com.jossephus.chuchu.model.MultiplexerType
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class MultiplexerShellTest {
    private fun execute(shell: String, command: String): MultiplexerCommandResult {
        val process =
            ProcessBuilder(shell, "-c", MultiplexerShell.execCommand(command))
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(10, TimeUnit.SECONDS)) { "Shell test timed out" }
        return MultiplexerShell.parseResult(output)
    }

    private fun requireShell(shell: String) {
        val probe = ProcessBuilder("sh", "-c", "command -v $shell").start()
        assumeTrue("$shell is required for this shell integration test", probe.waitFor() == 0)
    }

    private fun roundTrip(shell: String) {
        requireShell(shell)
        val directory = Files.createTempDirectory("chuchu-shell-test-").toFile()
        try {
            val marker = File(directory, "must-not-exist")
            val text =
                "quotes ' \" \\\\ \\' \$HOME \$(touch ${marker.path}) `touch ${marker.path}` | 日本語\nnext"
            val result = execute(shell, "printf '%s' ${TmuxCommands.quote(text)}")
            assertTrue(result.output, result.isSuccess)
            assertEquals(text, result.stdout)
            assertFalse(marker.exists())
            val failure = execute(shell, "printf 'command failed' >&2; exit 7")
            assertEquals(7, failure.exitCode)
            assertEquals("command failed", failure.stderr)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun posixShellPreservesScriptAndExitStatus() = roundTrip("sh")

    @Test fun bashPreservesScriptAndExitStatus() = roundTrip("bash")

    @Test fun zshPreservesScriptAndExitStatus() = roundTrip("zsh")

    @Test fun fishPreservesScriptAndExitStatus() = roundTrip("fish")

    @Test
    fun failedShellParsingIsAnErrorRatherThanMissingTmux() {
        val result =
            MultiplexerShell.parseResult(
                "fish: command substitutions not allowed in command position"
            )
        assertEquals(125, result.exitCode)
        val availability = MultiplexerAvailability.fromResult(MultiplexerType.Tmux, result)
        assertTrue(availability is MultiplexerAvailability.Error)
        assertTrue(
            (availability as MultiplexerAvailability.Error)
                .message
                .contains("Missing command exit marker")
        )
    }

    @Test
    fun onlyAnEmptyNotFoundProbeMeansMissing() {
        assertEquals(
            MultiplexerAvailability.Missing(MultiplexerType.Tmux),
            MultiplexerAvailability.fromResult(
                MultiplexerType.Tmux,
                MultiplexerCommandResult(1, "", ""),
            ),
        )
        assertEquals(
            MultiplexerAvailability.Available,
            MultiplexerAvailability.fromResult(
                MultiplexerType.Tmux,
                MultiplexerCommandResult(0, "", ""),
            ),
        )
        for (result in
            listOf(
                MultiplexerCommandResult(1, "", "Remote server did not open an exec channel"),
                MultiplexerCommandResult(124, "", "Command timed out"),
                MultiplexerCommandResult(2, "", "syntax error"),
            )) {
            assertTrue(
                MultiplexerAvailability.fromResult(MultiplexerType.Tmux, result)
                    is MultiplexerAvailability.Error
            )
        }
    }

    @Test
    fun fishCanProbeInstalledAndAbsentExecutables() {
        requireShell("fish")
        assertTrue(execute("fish", "command -v sh >/dev/null 2>&1").isSuccess)
        val absent =
            execute("fish", "command -v chuchu_nonexistent_tmux_test_binary >/dev/null 2>&1")
        assertEquals(
            MultiplexerAvailability.Missing(MultiplexerType.Tmux),
            MultiplexerAvailability.fromResult(MultiplexerType.Tmux, absent),
        )
    }
}
