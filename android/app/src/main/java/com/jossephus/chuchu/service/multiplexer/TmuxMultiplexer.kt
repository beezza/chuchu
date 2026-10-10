package com.jossephus.chuchu.service.multiplexer

import com.jossephus.chuchu.model.MultiplexerType

object TmuxMultiplexer : Multiplexer {
    private val chuchuSessionRegex = Regex("^chuchu-[1-9][0-9]*$")

    override val type: MultiplexerType = MultiplexerType.Tmux

    override fun availabilityCommand(): String = "command -v tmux >/dev/null 2>&1"

    override fun listSessionsCommand(): String = TmuxCommands.list()

    override fun parseSessions(output: String): List<RemoteMultiplexerSession> = TmuxCommands.parse(output)

    override fun launchCommand(
        sessionName: String,
        createIfMissing: Boolean,
        trustedRemoteName: Boolean,
    ): String = if (createIfMissing) {
        interactiveAttachOrSwitchCommand(sessionName, trustedRemoteName)
    } else {
        interactiveAttachExistingCommand(sessionName, trustedRemoteName)
    }

    override fun defaultSessionName(
        remoteSessions: Collection<RemoteMultiplexerSession>,
        localSessionNames: Collection<String>,
    ): String = MultiplexerSessionAllocator.nextChuchuSessionName(
        remoteSessions = remoteSessions,
        localSessionNames = localSessionNames,
    )

    fun isGeneratedSessionName(name: String): Boolean = chuchuSessionRegex.matches(name)

    fun requireGeneratedSessionName(name: String): String {
        require(isGeneratedSessionName(name)) { "Invalid Chuchu tmux session name" }
        return name
    }

    private fun interactiveAttachOrSwitchCommand(
        sessionName: String,
        trustedRemoteName: Boolean = false,
    ): String {
        if (!trustedRemoteName) requireGeneratedSessionName(sessionName)
        val target = shellQuote(sessionName)
        val exactTarget = shellQuote("=$sessionName")
        return "if [ -n \"\$TMUX\" ]; then tmux switch-client -t $exactTarget; else exec tmux new-session -A -s $target; fi"
    }

    private fun interactiveAttachExistingCommand(
        sessionName: String,
        trustedRemoteName: Boolean = false,
    ): String {
        if (!trustedRemoteName) requireGeneratedSessionName(sessionName)
        val target = shellQuote(sessionName)
        val exactTarget = shellQuote("=$sessionName")
        return "if [ -n \"\$TMUX\" ]; then tmux switch-client -t $exactTarget; " +
            "elif tmux has-session -t $exactTarget 2>/dev/null; then exec tmux attach-session -t $exactTarget; " +
            "else printf 'tmux session %s is no longer available\\n' $target; exec \"\${SHELL:-/bin/sh}\" -l; fi"
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
