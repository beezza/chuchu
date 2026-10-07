package com.jossephus.chuchu.ui.terminal

/** Keep the layout's base key separate from the text produced with modifiers. */
data class HardwareKeyEvent(
    val key: Int,
    val codepoint: Int,
    val mods: Int,
    val action: Int,
    val utf8: String?,
    val consumedMods: Int,
) {
    companion object {
        fun from(
            key: Int,
            codepoint: Int,
            mods: Int,
            action: Int,
            charCode: Int,
        ): HardwareKeyEvent {
            val hasNonTextModifier = mods and 0b1110 != 0
            val textCodepoint = if (charCode > 0) charCode else codepoint
            val utf8 =
                if (
                    textCodepoint >= 0x20 &&
                        textCodepoint != 0x7f &&
                        Character.isValidCodePoint(textCodepoint) &&
                        !hasNonTextModifier &&
                        action != GhosttyKeyAction.Release
                )
                    String(Character.toChars(textCodepoint))
                else null

            // Ghostty uses consumed modifiers to send plain text in Kitty mode.
            // Keep Shift in mods for protocols that request all key events, and
            // only consume it when it actually changed the produced character.
            val consumedMods = if (utf8 != null && textCodepoint != codepoint) mods and 1 else 0
            return HardwareKeyEvent(key, codepoint, mods, action, utf8, consumedMods)
        }
    }
}
