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
        assertTrue(CommandCompletionShellHooks.install("-bash")!!.contains("PROMPT_COMMAND"))
        assertTrue(CommandCompletionShellHooks.install("zsh")!!.contains("precmd_functions"))
        assertTrue(CommandCompletionShellHooks.install("fish")!!.contains("fish_postexec"))
        assertNull(CommandCompletionShellHooks.install("sh"))
    }
}
