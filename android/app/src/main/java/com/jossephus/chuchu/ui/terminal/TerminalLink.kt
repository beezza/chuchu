package com.jossephus.chuchu.ui.terminal

import com.jossephus.chuchu.service.terminal.TerminalSnapshot
import java.net.URI
import java.util.Locale

/** A link and the terminal cells occupied by its visible text. */
data class TerminalLink(
    val url: String,
    val cellRange: IntRange,
)

private val TERMINAL_LINK_PATTERN =
    Regex("(?i)(?<![A-Za-z0-9_])(?:https?://|www\\.)[^\\s<>\\\"']+")

private const val TRAILING_LINK_PUNCTUATION = ".,;:!?]}"

/**
 * Return the first safe web link in arbitrary terminal text.
 *
 * Terminal output often wraps links in punctuation, so punctuation at the end
 * of a candidate is removed while balanced parentheses are preserved. Only
 * web URLs are accepted before handing the value to an ACTION_VIEW intent.
 */
fun findTerminalLink(text: String): String? {
    for (match in TERMINAL_LINK_PATTERN.findAll(text)) {
        normalizeTerminalLink(match.value)?.let { return it }
    }
    return null
}

/** Find the web link under a rendered terminal cell, if any. */
internal fun TerminalSnapshot.linkAt(cellIndex: Int): TerminalLink? {
    if (cols <= 0 || rows <= 0 || cellIndex !in codepoints.indices) return null
    val row = cellIndex / cols
    if (row !in 0 until rows) return null

    val rowStart = row * cols
    val rowEnd = minOf(rowStart + cols, codepoints.size)
    val cellStarts = IntArray(rowEnd - rowStart)
    val cellEnds = IntArray(rowEnd - rowStart)
    val text = StringBuilder(cols)

    for (index in rowStart until rowEnd) {
        val offset = index - rowStart
        cellStarts[offset] = text.length
        val codepoint = codepoints[index]
        when {
            codepoint == 0 -> text.append(' ')
            codepoint == 32 && !isSpacerContinuation(index) -> text.append(' ')
            codepoint != 32 -> text.append(glyphAt(index))
        }
        cellEnds[offset] = text.length
    }

    val match = TERMINAL_LINK_PATTERN.findAll(text).firstOrNull { normalizeTerminalLink(it.value) != null }
        ?: return null
    val url = normalizeTerminalLink(match.value) ?: return null
    var firstCell = -1
    for (offset in cellStarts.indices) {
        if (cellStarts[offset] < match.range.last + 1 && cellEnds[offset] > match.range.first) {
            firstCell = offset
            break
        }
    }
    if (firstCell < 0) return null

    var lastCell = firstCell
    for (offset in firstCell until cellStarts.size) {
        if (cellStarts[offset] >= match.range.last + 1) break
        if (cellEnds[offset] > match.range.first) lastCell = offset
    }
    val link = TerminalLink(
        url = url,
        cellRange = (rowStart + firstCell)..(rowStart + lastCell),
    )
    return link.takeIf { cellIndex in it.cellRange }
}

private fun normalizeTerminalLink(rawCandidate: String): String? {
    var candidate = rawCandidate
    while (candidate.isNotEmpty()) {
        val last = candidate.last()
        when {
            last in TRAILING_LINK_PUNCTUATION -> candidate = candidate.dropLast(1)
            last == ')' && candidate.count { it == ')' } > candidate.count { it == '(' } ->
                candidate = candidate.dropLast(1)
            else -> break
        }
    }
    if (candidate.isBlank()) return null

    val normalized = if (candidate.startsWith("www.", ignoreCase = true)) {
        "https://$candidate"
    } else {
        candidate
    }
    val uri = runCatching { URI(normalized) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
    if (scheme != "http" && scheme != "https") return null
    if (uri.host.isNullOrBlank()) return null
    return normalized
}
