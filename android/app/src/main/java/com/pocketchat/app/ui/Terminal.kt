package com.pocketchat.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

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

/**
 * A two-tap confirmation for a destructive menu action — tapping [label]
 * reveals [confirmLabel]/"[cancel]" instead of firing [onConfirmed]
 * immediately. A lightweight, local pattern (not a dialog), shared by
 * FR-029's chat-clear action and FR-033's memory-viewer delete actions;
 * NFR-018 may later generalize confirmation across the app, at which point
 * these call sites can defer to that instead of this.
 */
@Composable
fun ConfirmableMenuItem(label: String, confirmLabel: String, onConfirmed: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    if (confirming) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TerminalMenuItem(confirmLabel, onClick = onConfirmed)
            TerminalMenuItem("[cancel]", onClick = { confirming = false })
        }
    } else {
        TerminalMenuItem(label, onClick = { confirming = true })
    }
}
