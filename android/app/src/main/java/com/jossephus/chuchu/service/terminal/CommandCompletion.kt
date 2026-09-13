package com.jossephus.chuchu.service.terminal

import java.util.ArrayDeque
import java.util.Locale

private const val SHELL_DOLLAR = "\$"
internal const val GHOSTTY_ENTER_KEY = 58
internal const val GHOSTTY_RELEASE_ACTION = 0

/** A command that ran long enough to be useful as a completion notification. */
data class CompletedCommand(
    val exitCode: Int,
    val durationMs: Long,
)

/** Events sent from a shell through the terminal's OSC 9 notification channel. */
internal sealed interface ChuchuControlEvent {
    data class ShellDetected(val shell: String) : ChuchuControlEvent
    data class CommandDone(val exitCode: Int) : ChuchuControlEvent
}

internal object ChuchuControlEventParser {
    private const val SHELL_PREFIX = "chuchu-shell;"
    private const val COMMAND_DONE_PREFIX = "chuchu-command-done;"

    fun parse(raw: String): ChuchuControlEvent? {
        val line = raw.trim()
        return when {
            line.startsWith(SHELL_PREFIX) -> {
                val shell = line.removePrefix(SHELL_PREFIX).trim()
                ChuchuControlEvent.ShellDetected(shell)
            }
            line.startsWith(COMMAND_DONE_PREFIX) -> {
                val payload = line.removePrefix(COMMAND_DONE_PREFIX).trim()
                val status =
                    payload.toIntOrNull()
                        ?: payload
                            .split(';')
                            .firstNotNullOfOrNull { field ->
                                field.removePrefix("status=").toIntOrNull()
                            }
                status?.let { ChuchuControlEvent.CommandDone(it) }
            }
            else -> null
        }
    }
}

/**
 * Keeps the time at which Enter was sent and pairs it with the shell's next prompt completion.
 *
 * The queue makes pasted multi-line input behave sensibly, while the CRLF guard avoids treating
 * one Enter from a text input connection as two commands.
 */
internal class CommandCompletionTracker(
    private val minimumDurationMs: Long = DEFAULT_MINIMUM_DURATION_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val commandStarts = ArrayDeque<Long>()
    private var previousWasCarriageReturn = false

    fun recordInput(data: ByteArray, logicalEnter: Boolean = false) {
        var sawLineBreak = false
        for (value in data) {
            when (value.toInt() and 0xff) {
                '\r'.code -> {
                    commandStarts.addLast(nowMs())
                    sawLineBreak = true
                    previousWasCarriageReturn = true
                }
                '\n'.code -> {
                    if (!previousWasCarriageReturn) commandStarts.addLast(nowMs())
                    sawLineBreak = true
                    previousWasCarriageReturn = false
                }
                else -> previousWasCarriageReturn = false
            }
        }
        // Kitty keyboard protocol can encode Enter as a CSI-u sequence without a literal CR/LF.
        // Ghostty's physical Enter key is 58 in the pinned input.Key enum; the caller supplies
        // logicalEnter so command timing still works in that mode.
        if (logicalEnter && !sawLineBreak) commandStarts.addLast(nowMs())
    }

    fun commandCompleted(exitCode: Int): CompletedCommand? {
        if (commandStarts.isEmpty()) return null
        val startedAt = commandStarts.removeFirst()
        val durationMs = (nowMs() - startedAt).coerceAtLeast(0L)
        return CompletedCommand(exitCode, durationMs).takeIf {
            durationMs >= minimumDurationMs
        }
    }

    fun reset() {
        commandStarts.clear()
        previousWasCarriageReturn = false
    }

    companion object {
        const val DEFAULT_MINIMUM_DURATION_MS = 5_000L
    }
}

internal object CommandCompletionShellHooks {
    /** Emits the login shell path without touching shell startup files. */
    val detectionCommand: String =
        // $SHELL is understood by bash, zsh, and fish (unlike bash's $0 syntax, which fish
        // deliberately does not implement). The path is normalized by the receiver.
        "printf '\\033]9;chuchu-shell;%s\\007' \"${'$'}SHELL\""

    /** Bash/zsh fallback for hosts that do not export SHELL to an interactive session. */
    val fallbackDetectionCommand: String =
        "printf '\\033]9;chuchu-shell;%s\\007' \"${'$'}0\""

    fun install(shellName: String): String? {
        val shell =
            shellName
                .trim()
                .substringAfterLast('/')
                .removePrefix("-")
                .lowercase(Locale.ROOT)
        return when (shell) {
            "bash" -> bashHook
            "zsh" -> zshHook
            "fish" -> fishHook
            else -> null
        }
    }

    // Keep the hook in the current shell only. This is deliberately sent as one command so it
    // also works when the PTY is an already-attached tmux or zellij pane.
    private val bashHook: String =
        "__chuchu_done(){ local __chuchu_status=${'$'}?; " +
            "printf '\\033]9;chuchu-command-done;%s\\007' \"${'$'}__chuchu_status\"; }; " +
            "case \";${'$'}{PROMPT_COMMAND-};\" in *\";__chuchu_done;\"*) ;; *) " +
            "PROMPT_COMMAND=\"__chuchu_done${'$'}{PROMPT_COMMAND:+;${'$'}PROMPT_COMMAND}\";; esac"

    private val zshHook: String =
        "__chuchu_done() { local __chuchu_status=${'$'}?; " +
            "printf '\\033]9;chuchu-command-done;%s\\007' \"${'$'}__chuchu_status\"; }; " +
            "precmd_functions=(${SHELL_DOLLAR}{precmd_functions:#__chuchu_done}); " +
            "precmd_functions+=(__chuchu_done)"

    private val fishHook: String =
        "functions -q __chuchu_done; and functions --erase __chuchu_done; " +
            "function __chuchu_done --on-event fish_postexec; " +
            "set -l __chuchu_status ${'$'}status; " +
            "printf '\\033]9;chuchu-command-done;%s\\007' \"${'$'}__chuchu_status\"; end"
}
