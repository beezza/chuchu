package com.jossephus.chuchu.ui.terminal

internal data class HardwareKeyInput(
    val unshiftedCodepoint: Int,
    val utf8: String?,
)

/**
 * Keeps Ghostty's physical/unshifted key data separate from the character that
 * Android resolved through the active hardware-keyboard layout.
 */
internal fun prepareHardwareKeyInput(
    codepoint: Int,
    charCode: Int,
    mods: Int,
    action: Int,
): HardwareKeyInput {
    val hasNonTextModifier = mods and ((1 shl 1) or (1 shl 2) or (1 shl 3)) != 0
    val isRelease = action == GhosttyKeyAction.Release
    val textCodepoint = if (charCode > 0) charCode else codepoint
    val utf8 =
        if (
            textCodepoint > 0 &&
            Character.isValidCodePoint(textCodepoint) &&
            !hasNonTextModifier &&
            !isRelease
        ) {
            String(Character.toChars(textCodepoint))
        } else {
            null
        }
    return HardwareKeyInput(unshiftedCodepoint = codepoint, utf8 = utf8)
}
