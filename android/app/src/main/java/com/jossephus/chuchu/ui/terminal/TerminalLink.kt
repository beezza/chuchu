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
    val renderedRows = (0 until rows).map { renderTerminalLinkRow(it) }
    val tappedRow = cellIndex / cols

    for (startRow in 0..tappedRow) {
        val row = renderedRows[startRow]
        for (match in TERMINAL_LINK_PATTERN.findAll(row.text)) {
            val firstRange = row.cellRangeForText(match.range.first, match.range.last + 1) ?: continue
            val candidate = StringBuilder(match.value)
            var lastCell = startRow * cols + firstRange.last
            var lastRow = startRow
            var canWrap = row.text.substring(match.range.last + 1).all { it == ' ' }

            while (canWrap && lastRow + 1 < rows) {
                val nextRow = renderedRows[lastRow + 1]
                val continuationLength = nextRow.text.indexOfFirst { it == ' ' || it == '\t' }
                    .let { if (it < 0) nextRow.text.length else it }
                if (continuationLength <= 0) break

                val continuationRange = nextRow.cellRangeForText(0, continuationLength) ?: break
                candidate.append(nextRow.text, 0, continuationLength)
                lastCell = (lastRow + 1) * cols + continuationRange.last
                lastRow++
                canWrap = nextRow.text.substring(continuationLength).all { it == ' ' }
            }

            val url = normalizeTerminalLink(candidate.toString()) ?: continue
            val link = TerminalLink(
                url = url,
                cellRange = (startRow * cols + firstRange.first)..lastCell,
            )
            if (cellIndex in link.cellRange) return link
        }
    }
    return null
}

private data class RenderedTerminalLinkRow(
    val row: Int,
    val text: String,
    val cellStarts: IntArray,
    val cellEnds: IntArray,
) {
    fun cellRangeForText(start: Int, endExclusive: Int): IntRange? {
        var first = -1
        var last = -1
        for (offset in cellStarts.indices) {
            if (cellStarts[offset] < endExclusive && cellEnds[offset] > start) {
                if (first < 0) first = offset
                last = offset
            }
        }
        return if (first >= 0) first..last else null
    }
}

private fun TerminalSnapshot.renderTerminalLinkRow(row: Int): RenderedTerminalLinkRow {
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

    return RenderedTerminalLinkRow(row, text.toString(), cellStarts, cellEnds)
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
