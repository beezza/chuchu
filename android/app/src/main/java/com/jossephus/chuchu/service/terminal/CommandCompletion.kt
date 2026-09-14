package com.jossephus.chuchu.service.terminal

import java.util.ArrayDeque
import java.util.Locale

private const val SHELL_DOLLAR = "\$"
private const val TMUX_PASSTHROUGH_SETUP =
    "tmux set-option -p allow-passthrough on >/dev/null 2>&1"
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
    /** A coding agent finished a turn but kept its interactive process alive. */
    data class AgentDone(val exitCode: Int) : ChuchuControlEvent
}

internal object ChuchuControlEventParser {
    private const val SHELL_PREFIX = "chuchu-shell;"
    private const val COMMAND_DONE_PREFIX = "chuchu-command-done;"
    private const val AGENT_DONE_PREFIX = "chuchu-agent-done;"

    // Codex's built-in OSC 9 notification uses human-readable text, while Pi
    // extensions commonly use one of the ready-for-input messages below. Keep
    // these exact markers narrow so arbitrary OSC 9 messages do not become
    // Android command notifications.
    private const val CODEX_AGENT_EVENT = "agent-turn-complete"
    private const val CODEX_AGENT_EVENT_TEXT = "agent turn complete"
    private const val PI_AGENT_EVENT = "pi: ready for input"
    private const val PI_AGENT_EVENT_SHORT = "ready for input"
    private const val PI_AGENT_EVENT_COMPLETE = "pi turn complete"

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
            line.startsWith(AGENT_DONE_PREFIX) -> {
                val payload = line.removePrefix(AGENT_DONE_PREFIX).trim()
                val status =
                    payload.toIntOrNull()
                        ?: payload
                            .split(';')
                            .firstNotNullOfOrNull { field ->
                                field.removePrefix("status=").toIntOrNull()
                            }
                status?.let { ChuchuControlEvent.AgentDone(it) }
            }
            isAgentCompletionMessage(line) -> ChuchuControlEvent.AgentDone(exitCode = 0)
            else -> null
        }
    }

    private fun isAgentCompletionMessage(line: String): Boolean {
        val normalized = line.lowercase(Locale.ROOT)
        return normalized == CODEX_AGENT_EVENT ||
            normalized.startsWith("$CODEX_AGENT_EVENT;") ||
            normalized == "agentturncomplete" ||
            normalized == CODEX_AGENT_EVENT_TEXT ||
            normalized.startsWith("$CODEX_AGENT_EVENT_TEXT:") ||
            normalized.startsWith("codex: $CODEX_AGENT_EVENT_TEXT") ||
            normalized == PI_AGENT_EVENT ||
            normalized == PI_AGENT_EVENT_SHORT ||
            normalized == PI_AGENT_EVENT_COMPLETE ||
            normalized.startsWith("$PI_AGENT_EVENT_COMPLETE:")
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

    /**
     * Completes the current interactive-agent turn and drops stale prompt
     * markers. Agent TUIs keep the shell process alive, so their completion
     * event is not FIFO-equivalent to a shell prompt: the queue may contain
     * the initial `codex`/`pi` launch and one or more approval Enter keys.
     */
    fun agentCompleted(exitCode: Int): CompletedCommand? {
        if (commandStarts.isEmpty()) return null
        val startedAt = commandStarts.removeFirst()
        commandStarts.clear()
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
        // deliberately does not implement). The path is normalized by the receiver. tmux
        // consumes ordinary OSC sequences, so emit both the normal form and its DCS passthrough
        // form. The normal form is recognized outside tmux; the passthrough form is recognized
        // inside tmux after the pane option is enabled below.
        "${TMUX_PASSTHROUGH_SETUP}; " +
            "printf '\\033]9;chuchu-shell;%s\\007' \"${'$'}SHELL\"; " +
            "printf '\\033Ptmux;\\033\\033]9;chuchu-shell;%s\\007\\033\\\\' \"${'$'}SHELL\""

    /** Bash/zsh fallback for hosts that do not export SHELL to an interactive session. */
    val fallbackDetectionCommand: String =
        "${TMUX_PASSTHROUGH_SETUP}; " +
            "printf '\\033]9;chuchu-shell;%s\\007' \"${'$'}0\"; " +
            "printf '\\033Ptmux;\\033\\033]9;chuchu-shell;%s\\007\\033\\\\' \"${'$'}0\""

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
        "${TMUX_PASSTHROUGH_SETUP}; " +
            "__chuchu_done(){ local __chuchu_status=${'$'}?; " +
            "if [ -n \"${'$'}{TMUX-}\" ]; then " +
            "${TMUX_PASSTHROUGH_SETUP}; " +
            "printf '\\033Ptmux;\\033\\033]9;chuchu-command-done;%s\\007\\033\\\\' \"${'$'}__chuchu_status\"; " +
            "else printf '\\033]9;chuchu-command-done;%s\\007' \"${'$'}__chuchu_status\"; fi; }; " +
            "case \";${'$'}{PROMPT_COMMAND-};\" in *\";__chuchu_done;\"*) ;; *) " +
            "PROMPT_COMMAND=\"__chuchu_done${'$'}{PROMPT_COMMAND:+;${'$'}PROMPT_COMMAND}\";; esac"

    private val zshHook: String =
        "${TMUX_PASSTHROUGH_SETUP}; " +
            "__chuchu_done() { local __chuchu_status=${'$'}?; " +
            "if [ -n \"${'$'}{TMUX-}\" ]; then " +
            "${TMUX_PASSTHROUGH_SETUP}; " +
            "printf '\\033Ptmux;\\033\\033]9;chuchu-command-done;%s\\007\\033\\\\' \"${'$'}__chuchu_status\"; " +
            "else printf '\\033]9;chuchu-command-done;%s\\007' \"${'$'}__chuchu_status\"; fi; }; " +
            "precmd_functions=(${SHELL_DOLLAR}{precmd_functions:#__chuchu_done}); " +
            "precmd_functions+=(__chuchu_done)"

    private val fishHook: String =
        "${TMUX_PASSTHROUGH_SETUP}; " +
            "functions -q __chuchu_done; and functions --erase __chuchu_done; " +
            "function __chuchu_done --on-event fish_postexec; " +
            "set -l __chuchu_status ${'$'}status; " +
            "if set -q TMUX; " +
            "${TMUX_PASSTHROUGH_SETUP}; " +
            "printf '\\033Ptmux;\\033\\033]9;chuchu-command-done;%s\\007\\033\\\\' \"${'$'}__chuchu_status\"; " +
            "else; printf '\\033]9;chuchu-command-done;%s\\007' \"${'$'}__chuchu_status\"; " +
            "end; end"
}
