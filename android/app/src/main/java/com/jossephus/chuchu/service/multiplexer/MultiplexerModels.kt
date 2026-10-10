package com.jossephus.chuchu.service.multiplexer

import com.jossephus.chuchu.model.MultiplexerType
import com.jossephus.chuchu.model.Transport

data class RemoteMultiplexerSession(
    val name: String,
    val attached: Boolean,
    val identity: TmuxSessionIdentity? = null,
    val clientCount: Int = if (attached) 1 else 0,
    val windowCount: Int? = null,
    val createdAt: Long? = null,
    val workingDirectory: String? = null,
    val activePaneId: String? = null,
)

/** IDs are unique only within one tmux server lifetime. */
data class TmuxSessionIdentity(
    val id: String,
    val serverPid: Long,
    val serverStartedAt: Long,
    val createdAt: Long,
) {
    val token: String get() = "$serverPid:$serverStartedAt:$createdAt:$id"
}

fun filterMultiplexerSessions(
    sessions: List<RemoteMultiplexerSession>,
    query: String,
): List<RemoteMultiplexerSession> = sessions.filter { it.name.contains(query, ignoreCase = true) }

sealed interface MultiplexerAvailability {
    data object Available : MultiplexerAvailability
    data class Missing(val multiplexer: MultiplexerType) : MultiplexerAvailability
    data class UnsupportedMultiplexer(val multiplexer: MultiplexerType) : MultiplexerAvailability
    data class UnsupportedTransport(val transport: Transport) : MultiplexerAvailability
    data class Error(val message: String, val output: String = "") : MultiplexerAvailability

    companion object {
        fun fromResult(type: MultiplexerType, result: MultiplexerCommandResult): MultiplexerAvailability {
            if (result.isSuccess) return Available
            // command -v returns 1 with no output when the executable is absent.
            // Shell syntax errors, unsupported exec and missing envelopes are not absence.
            if (result.exitCode == 1 && result.output.isBlank()) return Missing(type)
            val detail = result.output.take(1_024).ifBlank {
                "remote command exited with status ${result.exitCode}"
            }
            return Error(message = "Could not check ${type.label}: $detail")
        }
    }
}

data class MultiplexerCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val output: String
        get() = listOf(stdout, stderr).filter { it.isNotBlank() }.joinToString("\n")

    val isSuccess: Boolean
        get() = exitCode == 0
}
