package com.example.myapplication

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import game.app.generated.resources.Res

/** Where every distributed build's corresponding source lives (GPLv3 §6). */
internal const val SOURCE_CODE_URL = "https://github.com/ber4444/compose-multiplatform-chess"

/** One third-party component as the screen lists it. Mirrors COPYING.md / THIRD_PARTY_NOTICES.md. */
internal data class ThirdPartyNotice(val name: String, val usedFor: String, val licence: String, val url: String)

internal val thirdPartyNotices = listOf(
    ThirdPartyNotice(
        "Stockfish 17", "the opponent and the move analysis (Android, desktop, web; on iOS via ChessKitEngine)",
        "GNU GPL v3 or later — full text below", "https://github.com/official-stockfish/Stockfish/tree/sf_17",
    ),
    ThirdPartyNotice(
        "ChessKitEngine", "runs Stockfish on iOS; also bundles Lc0 0.31.1 (GPL v3)",
        "MIT", "https://github.com/chesskit-app/chesskit-engine",
    ),
    ThirdPartyNotice(
        "Chess pieces by Colin M.L. Burnett", "the 2D board's pieces",
        "GNU GPL v2 or later (also BSD and GFDL)", "https://commons.wikimedia.org/wiki/Category:SVG_chess_pieces",
    ),
    ThirdPartyNotice(
        "3D chess set", "the 3D board's model",
        "see the project's third-party notices", "$SOURCE_CODE_URL/blob/main/THIRD_PARTY_NOTICES.md#chess-3d-model",
    ),
    ThirdPartyNotice("Filament", "3D rendering", "Apache-2.0", "https://github.com/google/filament"),
    ThirdPartyNotice("SceneView", "3D rendering on Android", "Apache-2.0", "https://github.com/SceneView/sceneview-android"),
    ThirdPartyNotice("Material Icons", "interface icons", "Apache-2.0", "https://fonts.google.com/icons"),
    ThirdPartyNotice(
        "Lichess chess-openings", "opening names behind the opening explainer",
        "CC0 1.0", "https://github.com/lichess-org/chess-openings",
    ),
    ThirdPartyNotice(
        "Wikibooks: Chess/Rules", "the rules corpus behind Rules Q&A, condensed and adapted",
        "CC BY-SA 4.0", "https://en.wikibooks.org/wiki/Chess/Rules",
    ),
)

/**
 * Settings → Open-source licences. The app is GPL software shipped through stores, so this is
 * where a user who installed a binary gets what the licence promises them: the notice, the
 * licence text itself, and a way to the source. Third-party components are listed alongside.
 */
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    var showFullText by rememberSaveable { mutableStateOf(false) }

    SubScreenScaffold(title = "Open-source licences", onBack = onBack, showBackButton = !isAndroidPlatform) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text("This app is free software", style = MaterialTheme.typography.titleMedium)
            Text(
                "You may redistribute and modify it under the terms of the GNU General Public License, " +
                    "version 3 or (at your option) any later version. It comes with ABSOLUTELY NO WARRANTY.",
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = { uriHandler.openUri(SOURCE_CODE_URL) }, modifier = Modifier.testTag("licenses_source")) {
                Text("Source code: $SOURCE_CODE_URL")
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Third-party software and assets", style = MaterialTheme.typography.titleMedium)
            for (notice in thirdPartyNotices) {
                Column(Modifier.padding(top = 8.dp)) {
                    Text(notice.name, style = MaterialTheme.typography.titleSmall)
                    Text("${notice.usedFor}. ${notice.licence}.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { uriHandler.openUri(notice.url) }) { Text(notice.url, style = MaterialTheme.typography.bodySmall) }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            TextButton(onClick = { showFullText = !showFullText }, modifier = Modifier.testTag("licenses_toggle_gpl")) {
                Text(if (showFullText) "Hide the GNU GPL v3 text" else "Show the full GNU GPL v3 text")
            }
            if (showFullText) {
                // Loaded on demand: 35 KB of text nobody reads should not cost every visit.
                val gplText by produceState("") { value = Res.readBytes("files/licenses/GPL-3.0.txt").decodeToString() }
                Text(gplText, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("licenses_gpl_text"))
            }
        }
    }
}
