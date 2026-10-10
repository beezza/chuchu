package com.jossephus.chuchu.service.multiplexer

/** SSH exec is parsed by the account's shell, which may be fish rather than a POSIX shell. */
object MultiplexerShell {
    fun command(script: String): String = "exec /bin/sh -c ${quoteForLoginShell(script)}"

    fun execCommand(script: String): String =
        command("( $script\n) 2>&1; printf '\\nCHUCHU_EXIT:%s\\n' \"\$?\"")

    fun parseResult(output: String): MultiplexerCommandResult {
        val marker =
            Regex("(?:^|\\n)CHUCHU_EXIT:(\\d+)\\s*$").find(output)
                ?: return MultiplexerCommandResult(125, output, "Missing command exit marker")
        val exitCode = marker.groupValues[1].toIntOrNull() ?: 125
        val cleanOutput = output.substring(0, marker.range.first)
        // The native SSH reader merges stdout and stderr.
        return if (exitCode == 0) MultiplexerCommandResult(exitCode, cleanOutput, "")
        else MultiplexerCommandResult(exitCode, "", cleanOutput)
    }

    private fun quoteForLoginShell(value: String): String {
        require('\u0000' !in value) { "NUL is not supported in remote commands" }
        return buildString {
            append('\'')
            for (char in value) {
                when (char) {
                    '\'' -> append("'\\''")
                    // fish interprets \\ and \' even inside single quotes. Emit backslashes
                    // outside quotes so fish, bash, zsh and sh preserve the same script bytes.
                    '\\' -> append("'\\\\'")
                    else -> append(char)
                }
            }
            append('\'')
        }
    }
}
