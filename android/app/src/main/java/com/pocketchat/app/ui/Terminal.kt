package com.pocketchat.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

// Shared palette/primitives for the recovery-menu-styled terminal look used
// across every screen (see PLAN.md's UI/Visual design section).
// FR-044: background stays pure black regardless of the selected palette
// (preserves FR-002's visual identity) — only the accent colors below vary,
// driven by whatever TerminalPalette is provided via LocalTerminalPalette at
// the app root. Kept as top-level vals (not a parameter every call site must
// thread through) via a @Composable getter, so none of the ~10 files already
// referencing these by name needed to change.
val TermBackground = Color.Black
val TermForeground: Color
    @Composable get() = LocalTerminalPalette.current.foreground
val TermDim: Color
    @Composable get() = LocalTerminalPalette.current.dim
val TermError: Color
    @Composable get() = LocalTerminalPalette.current.error
val TermUser: Color
    @Composable get() = LocalTerminalPalette.current.user

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

/**
 * A single-line terminal-styled text input — "label> [cursor]" — used for
 * search boxes and free-text fields (FR-040's live-scrollback search is the
 * first caller; new fields introduced after this one should reuse it rather
 * than reimplementing the same Row+BasicTextField shape inline).
 */
@Composable
fun TerminalTextField(prefix: String, value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, color: Color = TermUser) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TerminalText(prefix, TermDim)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            textStyle = TextStyle(color = color, fontFamily = FontFamily.Monospace, fontSize = MaterialTheme.typography.bodyLarge.fontSize),
            cursorBrush = SolidColor(color),
        )
    }
}
