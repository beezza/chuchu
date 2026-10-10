package com.jossephus.chuchu.service.multiplexer

/** Testable management boundary. The repository supplies an isolated SSH executor. */
class TmuxSessionService(private val execute: suspend (String) -> MultiplexerCommandResult) {
    suspend fun list(): List<RemoteMultiplexerSession> =
        TmuxCommands.parse(run(TmuxCommands.list()))

    suspend fun create(
        name: String,
        directory: String,
        localNames: List<String>,
    ): RemoteMultiplexerSession {
        val sessions = list()
        val resolved = name.ifBlank {
            MultiplexerSessionAllocator.nextChuchuSessionName(sessions, localNames)
        }
        check(sessions.none { it.name == resolved }) {
            "A session named \"$resolved\" already exists"
        }
        val token = run(TmuxCommands.create(resolved, directory)).trim()
        return list().firstOrNull { it.identity?.token == token }
            ?: error("Session was created but is no longer available. Refresh the list.")
    }

    suspend fun verify(identity: TmuxSessionIdentity) {
        check(run(TmuxCommands.verify(identity)).trim() == identity.token) {
            "Session changed or no longer exists. Refresh the list."
        }
    }

    suspend fun rename(identity: TmuxSessionIdentity, name: String) {
        TmuxCommands.validateName(name)
        check(list().none { it.name == name && it.identity != identity }) {
            "A session named \"$name\" already exists"
        }
        run(TmuxCommands.rename(identity, name))
    }

    suspend fun terminate(identity: TmuxSessionIdentity) {
        run(TmuxCommands.terminate(identity))
    }

    suspend fun preview(identity: TmuxSessionIdentity): String =
        TmuxCommands.sanitizePreview(run(TmuxCommands.preview(identity)))

    private suspend fun run(command: String): String {
        val result = execute(command)
        check(result.isSuccess) {
            result.output.take(1_024).ifBlank { "tmux command failed (exit ${result.exitCode})" }
        }
        check(result.stdout.trim() != TmuxCommands.STALE) {
            "Session changed or no longer exists. Refresh the list."
        }
        return result.stdout
    }
}
