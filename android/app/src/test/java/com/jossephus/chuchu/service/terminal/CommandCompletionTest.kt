package com.jossephus.chuchu.service.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandCompletionTest {
    @Test
    fun completionBelowThresholdIsIgnored() {
        var now = 0L
        val tracker = CommandCompletionTracker(nowMs = { now })
        tracker.recordInput(byteArrayOf('\r'.code.toByte()))

        now = 4_999L
        assertNull(tracker.commandCompleted(exitCode = 0))
    }

    @Test
    fun completionAtThresholdKeepsExitCodeAndDuration() {
        var now = 10L
        val tracker = CommandCompletionTracker(nowMs = { now })
        tracker.recordInput(byteArrayOf('\n'.code.toByte()))

        now += CommandCompletionTracker.DEFAULT_MINIMUM_DURATION_MS
        assertEquals(
            CompletedCommand(exitCode = 7, durationMs = 5_000L),
            tracker.commandCompleted(exitCode = 7),
        )
    }

    @Test
    fun logicalEnterStartsTimingWhenEncodingHasNoNewline() {
        var now = 0L
        val tracker = CommandCompletionTracker(nowMs = { now })
        tracker.recordInput(byteArrayOf(0x1b, '['.code.toByte(), 'u'.code.toByte()), logicalEnter = true)
        now = CommandCompletionTracker.DEFAULT_MINIMUM_DURATION_MS
        assertEquals(CompletedCommand(0, 5_000L), tracker.commandCompleted(0))
    }

    @Test
    fun crlfInputCountsAsOneCommand() {
        val tracker = CommandCompletionTracker(minimumDurationMs = 0L, nowMs = { 1_000L })
        tracker.recordInput("echo ok\r".toByteArray())
        tracker.recordInput("\n".toByteArray())
        assertEquals(CompletedCommand(exitCode = 0, durationMs = 0L), tracker.commandCompleted(exitCode = 0))
        assertNull(tracker.commandCompleted(exitCode = 0))
    }

    @Test
    fun parserRecognizesShellAndExitEvents() {
        assertEquals(
            ChuchuControlEvent.ShellDetected("-bash"),
            ChuchuControlEventParser.parse("chuchu-shell;-bash"),
        )
        assertEquals(
            ChuchuControlEvent.ShellDetected(""),
            ChuchuControlEventParser.parse("chuchu-shell;"),
        )
        assertEquals(
            ChuchuControlEvent.CommandDone(2),
            ChuchuControlEventParser.parse("chuchu-command-done;status=2"),
        )
        assertNull(ChuchuControlEventParser.parse("ordinary output"))
    }

    @Test
    fun shellHooksAreSelectedWithoutTouchingStartupFiles() {
        val bash = CommandCompletionShellHooks.install("-bash")!!
        val zsh = CommandCompletionShellHooks.install("zsh")!!
        val fish = CommandCompletionShellHooks.install("fish")!!
        assertTrue(bash.contains("PROMPT_COMMAND"))
        assertTrue(zsh.contains("precmd_functions"))
        assertTrue(fish.contains("fish_postexec"))
        listOf(bash, zsh, fish).forEach { hook ->
            assertTrue(hook.contains("allow-passthrough"))
            assertTrue(hook.contains("\\033Ptmux;"))
            assertTrue(hook.contains("${'$'}{TMUX-}") || hook.contains("set -q TMUX"))
        }
        assertTrue(CommandCompletionShellHooks.detectionCommand.contains("allow-passthrough"))
        assertTrue(CommandCompletionShellHooks.detectionCommand.contains("\\033Ptmux;"))
        assertTrue(CommandCompletionShellHooks.fallbackDetectionCommand.contains("\\033Ptmux;"))
        assertNull(CommandCompletionShellHooks.install("sh"))
    }
}
