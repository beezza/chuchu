package com.jossephus.chuchu.service.multiplexer

/** Commands run on an independent exec channel; never on the terminal's input stream. */
object TmuxCommands {
    const val STALE = "CHUCHU_STALE_SESSION"
    const val PREVIEW_MAX_CHARS = 32_768
    private const val IDENTITY_FORMAT = "#{pid}:#{start_time}:#{session_created}:#{session_id}"
    private val sessionId = Regex("\\$[0-9]+")

    fun quote(value: String): String {
        require('\u0000' !in value) { "NUL is not supported" }
        return "'" + value.replace("'", "'\\''") + "'"
    }

    // Hex keeps arbitrary names and paths (including pipes, tabs and newlines) out of the framing.
    // Only fixed numeric fields are read by the shell; remote text is never evaluated as code.
    fun list(): String =
        """
        if ! command -v tmux >/dev/null 2>&1; then
          printf 'tmux executable not found\n' >&2; exit 127
        fi
        listing=${'$'}(LC_ALL=C tmux list-sessions -F '#{session_id}|#{session_attached}|#{session_windows}|#{session_created}|#{pid}|#{start_time}|#{pane_id}' 2>&1)
        status=${'$'}?
        if [ "${'$'}status" -ne 0 ]; then
          case "${'$'}listing" in
            *'no server running on '*|*'no sessions'*|*'error connecting to '*' (No such file or directory)') exit 0 ;;
            *) printf '%s\n' "${'$'}listing" >&2; exit "${'$'}status" ;;
          esac
        fi
        printf '%s\n' "${'$'}listing" | while IFS='|' read -r id clients windows created pid started pane; do
          [ -n "${'$'}id" ] || continue
          name=${'$'}(tmux display-message -p -t "${'$'}id" '#{session_name}' | od -An -v -tx1 | tr -d ' \n')
          path=${'$'}(tmux display-message -p -t "${'$'}id" '#{pane_current_path}' | od -An -v -tx1 | tr -d ' \n')
          [ -n "${'$'}name" ] || continue
          printf '%s|%s|%s|%s|%s|%s|%s|%s|%s\n' "${'$'}id" "${'$'}clients" "${'$'}windows" "${'$'}created" "${'$'}pid" "${'$'}started" "${'$'}pane" "${'$'}name" "${'$'}path"
        done
        """
            .trimIndent()

    fun parse(output: String): List<RemoteMultiplexerSession> =
        output
            .lineSequence()
            .filter { it.isNotBlank() }
            .map { line ->
                val fields = line.trimEnd('\r').split('|')
                require(fields.size == 9 && sessionId.matches(fields[0])) {
                    "Invalid tmux session response"
                }
                val clients = fields[1].toInt().also { require(it >= 0) }
                val created = fields[3].toLong()
                RemoteMultiplexerSession(
                    name = decodeHex(fields[7]).also { require(it.isNotEmpty()) },
                    attached = clients > 0,
                    identity =
                        TmuxSessionIdentity(
                            fields[0],
                            fields[4].toLong(),
                            fields[5].toLong(),
                            created,
                        ),
                    clientCount = clients,
                    windowCount = fields[2].toInt().also { require(it > 0) },
                    createdAt = created,
                    workingDirectory = decodeHex(fields[8]).takeIf { it.isNotEmpty() },
                    activePaneId = fields[6].takeIf { Regex("%[0-9]+").matches(it) },
                )
            }
            .toList()

    fun create(name: String, directory: String): String {
        validateName(name)
        require('\u0000' !in directory) { "Invalid working directory" }
        // No -A: a concurrent name collision must fail rather than attach to someone else's
        // session.
        val create = "tmux new-session -d -P -F '$IDENTITY_FORMAT' -s ${quote(name)}"
        if (directory.isEmpty()) return create
        // Some tmux versions silently fall back to HOME when -c is invalid. Validate first.
        return """
            directory=${quote(directory)}
            case "${'$'}directory" in
              '~') directory=${'$'}HOME ;;
              '~/'*) directory="${'$'}HOME/${'$'}{directory#\~/}" ;;
            esac
            if [ ! -d "${'$'}directory" ]; then
              printf 'Working directory does not exist: %s\n' "${'$'}directory" >&2; exit 1
            fi
            format_directory=${'$'}(printf '%sx' "${'$'}directory" | sed 's/#/##/g')
            format_directory=${'$'}{format_directory%x}
            $create -c "${'$'}format_directory"
        """
            .trimIndent()
    }

    fun rename(identity: TmuxSessionIdentity, name: String): String {
        validateName(name)
        return guarded(identity, "rename-session -t ${quote(identity.id)} -- ${quote(name)}")
    }

    fun terminate(identity: TmuxSessionIdentity): String =
        guarded(identity, "kill-session -t ${quote(identity.id)}")

    fun verify(identity: TmuxSessionIdentity): String =
        guarded(identity, "display-message -p -t ${quote(identity.id)} ${quote(IDENTITY_FORMAT)}")

    fun attach(identity: TmuxSessionIdentity): String =
        "exec " + guarded(identity, "attach-session -t ${quote(identity.id)}")

    fun preview(identity: TmuxSessionIdentity): String {
        // Resolves the session's CURRENT active window and pane inside the same server queue.
        // -S is relative to the visible screen, so tail also bounds tall panes to 80 lines.
        val capture = guarded(identity, "capture-pane -p -t ${quote(identity.id + ":")} -S -80")
        return "captured=\$($capture 2>&1); status=\$?; " +
            "if [ \"\$status\" -ne 0 ]; then printf '%s' \"\$captured\" >&2; exit \"\$status\"; fi; " +
            "printf '%s' \"\$captured\" | tail -n 80 | head -c 32768"
    }

    fun validateName(name: String) {
        require(name.isNotBlank()) { "Session name is required" }
        require(name.length <= 128 && name.none { it.isISOControl() || it == ':' || it == '.' }) {
            "Use up to 128 characters without control characters, ':' or '.'"
        }
    }

    private fun guarded(identity: TmuxSessionIdentity, action: String): String {
        require(
            sessionId.matches(identity.id) &&
                identity.serverPid > 0 &&
                identity.serverStartedAt > 0 &&
                identity.createdAt > 0
        ) {
            "Invalid tmux session identity"
        }
        // if-shell -F evaluates and queues the action in the server, avoiding a shell-side
        // has-session/kill-session race if a server restarts and reuses $0.
        val condition = "#{==:$IDENTITY_FORMAT,${identity.token}}"
        return "tmux if-shell -F -t ${quote(identity.id)} ${quote(condition)} " +
            "${quote(action)} ${quote("display-message -p $STALE") }"
    }

    private fun decodeHex(hex: String): String {
        require(hex.length % 2 == 0) { "Invalid tmux text encoding" }
        return ByteArray(hex.length / 2) { index ->
                hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
            .toString(Charsets.UTF_8)
            .removeSuffix("\n")
    }

    /** capture-pane omits styling by default; discard residual terminal controls defensively. */
    fun sanitizePreview(value: String): String =
        value
            .take(PREVIEW_MAX_CHARS)
            .replace(Regex("\u001b\\][^\u0007\u001b]*(?:\u0007|\u001b\\\\|$)"), "")
            .replace(Regex("\u001b\\[[0-?]*[ -/]*[@-~]"), "")
            .replace(Regex("\u001b[ -/]*[@-~]"), "")
            .filter { it == '\n' || it == '\t' || !it.isISOControl() }
}
