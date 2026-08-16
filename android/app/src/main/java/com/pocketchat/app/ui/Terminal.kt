package com.pocketchat.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

// Shared palette/primitives for the recovery-menu-styled terminal look used
// across every screen (see PLAN.md's UI/Visual design section).
val TermBackground = Color.Black
val TermForeground = Color(0xFF33FF66)
val TermDim = Color(0xFF1F8A3D)
val TermError = Color(0xFFFF5C5C)
val TermUser = Color(0xFFEDEDED)

@Composable
fun TerminalText(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = color,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodyLarge,
        modifier = modifier,
    )
}

/** A flat-text-menu "button" — e.g. "[download]" — matching the terminal aesthetic. */
@Composable
fun TerminalMenuItem(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    TerminalText(
        text = text,
        color = if (enabled) TermForeground else TermDim,
        modifier = if (enabled) modifier.clickable(onClick = onClick) else modifier,
    )
}
