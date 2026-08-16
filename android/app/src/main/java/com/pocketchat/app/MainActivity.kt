package com.pocketchat.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

// Phase 0 placeholder screen — recovery-menu-styled hello world.
// Replaced by the real terminal chat UI in Phase 2.
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RecoveryStyleHelloWorld()
        }
    }
}

@Composable
fun RecoveryStyleHelloWorld() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(24.dp),
        contentAlignment = Alignment.TopStart
    ) {
        Text(
            text = "pocketchat> hello world_",
            color = Color(0xFF33FF66),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}
