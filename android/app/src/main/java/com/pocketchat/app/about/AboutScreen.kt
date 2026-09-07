package com.pocketchat.app.about

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pocketchat.app.ui.TermBackground
import com.pocketchat.app.ui.TermDim
import com.pocketchat.app.ui.TermForeground
import com.pocketchat.app.ui.TerminalMenuItem
import com.pocketchat.app.ui.TerminalText

private const val SOURCE_URL = "https://github.com/O-xix/Project-Pocketchat"

/**
 * FR-039: version, AGPLv3 license text, and a link to the public source —
 * standard practice for distributing a GPL-family-licensed binary, at very
 * low build cost. The full license text ships as an asset (`LICENSE.txt`,
 * copied from the repo root's own `LICENSE` file) rather than a short
 * notice-and-link, since the ticket asks for the license text itself.
 */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var licenseText by remember { mutableStateOf("") }
    val versionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
    }

    LaunchedEffect(Unit) {
        licenseText = try {
            context.assets.open("LICENSE.txt").bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            "(license text unavailable in this build — see $SOURCE_URL/blob/main/LICENSE)"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TermBackground)
            .padding(12.dp)
    ) {
        TerminalText("pocketchat> about", TermForeground)
        Spacer(Modifier.height(8.dp))
        TerminalText("PocketChat $versionName", TermForeground)
        TerminalText("AGPLv3 — source available at:", TermDim)
        // A fully user-initiated action (an explicit tap on the visible URL)
        // opening the device's own browser — doesn't touch NFR-001's
        // zero-telemetry principle, same reasoning as FR-021's export.
        TerminalMenuItem(SOURCE_URL, onClick = {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL)))
        })
        Spacer(Modifier.height(12.dp))
        TerminalText("license (GNU Affero General Public License v3):", TermDim)

        LazyColumn(modifier = Modifier.weight(1f)) {
            item { TerminalText(licenseText, TermDim) }
        }

        Spacer(Modifier.height(8.dp))
        TerminalMenuItem("[back]", onClick = onBack)
    }
}
