package com.pocketchat.app.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pocketchat.app.inference.ChatMessage

private val TermBackground = Color.Black
private val TermForeground = Color(0xFF33FF66)
private val TermDim = Color(0xFF1F8A3D)
private val TermError = Color(0xFFFF5C5C)
private val TermUser = Color(0xFFEDEDED)

@Composable
fun ChatScreen(viewModel: ChatViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TermBackground)
            .padding(12.dp)
    ) {
        MessageScrollback(modifier = Modifier.weight(1f), uiState = uiState)
        InputPrompt(
            enabled = uiState.modelStatus is ModelStatus.Ready && !uiState.isGenerating,
            onSubmit = viewModel::sendMessage,
        )
    }
}

@Composable
private fun MessageScrollback(modifier: Modifier, uiState: ChatUiState) {
    val listState = rememberLazyListState()
    val statusLine = statusLineFor(uiState.modelStatus, uiState.error)
    val totalRows = uiState.messages.size + (if (statusLine != null) 1 else 0) + (if (uiState.isGenerating) 1 else 0)

    LaunchedEffect(totalRows, uiState.streamingResponse) {
        if (totalRows > 0) listState.animateScrollToItem(totalRows - 1)
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(uiState.messages) { message -> MessageLine(message) }

        if (statusLine != null) {
            item { TerminalText(statusLine.first, statusLine.second) }
        }
        if (uiState.isGenerating) {
            item { TerminalText("pocketchat> ${uiState.streamingResponse}_", TermForeground) }
        }
    }
}

private fun statusLineFor(status: ModelStatus, error: String?): Pair<String, Color>? = when {
    status is ModelStatus.Loading -> "pocketchat> loading model_" to TermDim
    status is ModelStatus.Failed -> "pocketchat> error: ${status.message}" to TermError
    error != null -> "pocketchat> error: $error" to TermError
    else -> null
}

@Composable
private fun MessageLine(message: ChatMessage) {
    val (prefix, color) = if (message.role == "user") "you> " to TermUser else "pocketchat> " to TermForeground
    TerminalText(prefix + message.content, color)
}

@Composable
private fun TerminalText(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodyLarge,
    )
}

@Composable
private fun InputPrompt(enabled: Boolean, onSubmit: (String) -> Unit) {
    var input by remember { mutableStateOf("") }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "> ",
            color = TermForeground,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyLarge,
        )
        BasicTextField(
            value = input,
            onValueChange = { input = it },
            enabled = enabled,
            modifier = Modifier.weight(1f),
            textStyle = TextStyle(
                color = if (enabled) TermForeground else TermDim,
                fontFamily = FontFamily.Monospace,
                fontSize = MaterialTheme.typography.bodyLarge.fontSize,
            ),
            cursorBrush = SolidColor(TermForeground),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = {
                onSubmit(input)
                input = ""
            }),
        )
    }
}
