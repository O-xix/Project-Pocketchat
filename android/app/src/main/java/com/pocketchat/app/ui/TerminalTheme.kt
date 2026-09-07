package com.pocketchat.app.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * FR-044: named accent-color schemes, always against FR-002's black
 * background — every palette picks foreground/dim/user/error so they stay
 * clearly distinguishable from each other, not just from black, since a
 * palette that only maximized contrast against the background but left its
 * own colors close together would defeat the point.
 */
enum class TerminalPalette(
    val label: String,
    val foreground: Color,
    val dim: Color,
    val user: Color,
    val error: Color,
) {
    GREEN("green", Color(0xFF33FF66), Color(0xFF1F8A3D), Color(0xFFEDEDED), Color(0xFFFF5C5C)),
    AMBER("amber", Color(0xFFFFB000), Color(0xFF8A6100), Color(0xFFFFFFFF), Color(0xFFFF453A)),
    CYAN("cyan", Color(0xFF00E5FF), Color(0xFF0A6E7A), Color(0xFFFFFFFF), Color(0xFFFF6B6B)),
    HIGH_CONTRAST("high contrast", Color(0xFFFFFFFF), Color(0xFF9E9E9E), Color(0xFFFFD60A), Color(0xFFFF453A)),
    SYNTHWAVE("synthwave", Color(0xFFFF3EC9), Color(0xFF8A1F6D), Color(0xFF00E5FF), Color(0xFFFFD400)),
}

enum class TerminalFontSize(val label: String, val sp: Int) {
    SMALL("small", 14),
    MEDIUM("medium", 16),
    LARGE("large", 20),
    EXTRA_LARGE("extra large", 24),
}

/** Provided once at the root ([com.pocketchat.app.PocketChatApp]); read by [TermForeground] etc. below. */
val LocalTerminalPalette = compositionLocalOf { TerminalPalette.GREEN }
