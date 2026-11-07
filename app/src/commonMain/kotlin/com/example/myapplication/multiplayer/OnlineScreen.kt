package com.example.myapplication.multiplayer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.myapplication.Board
import com.example.myapplication.FenConverter
import com.example.myapplication.INVALID_POSITION
import com.example.myapplication.Pawn
import com.example.myapplication.PgnSerializer
import com.example.myapplication.PgnTags
import com.example.myapplication.PieceAnimationState
import com.example.myapplication.PromotionDialog
import com.example.myapplication.Set as Side
import com.example.myapplication.SubScreenScaffold
import com.example.myapplication.board3d.Board3D
import com.example.myapplication.board3d.Board3DSessionState
import com.example.myapplication.board3d.Board3DSupport
import com.example.myapplication.board3d.BoardSquare
import com.example.myapplication.board3d.OrbitCameraController
import com.example.myapplication.calculateWindowWidthSizeClass
import com.example.myapplication.getLegalMovesForPiece
import com.example.myapplication.persistence.GameHistoryRepository
import com.example.myapplication.persistence.SavedGame
import com.example.myapplication.share.PgnSharer
import kotlin.time.Instant
import kotlinx.coroutines.delay

@Composable
internal fun OnlineScreen(
    state: OnlineMatchState,
    controller: OnlineMatchController,
    board3D: Board3DSupport?,
    use3D: Boolean,
    history: GameHistoryRepository?,
    sharer: PgnSharer?,
    onBack: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val game = state.game
    SubScreenScaffold(
        title = "Play Online",
        onBack = onBack,
        showBackButton = !state.assistanceBlocked && !com.example.myapplication.isAndroidPlatform,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.profile == null) {
                Text(if (state.reference == null) "Play a casual 15+10 game on Lichess." else "Sign in to reconnect to your match.")
                Button(onClick = controller::signIn, enabled = !state.busy, modifier = Modifier.testTag("lichess_sign_in")) {
                    Text(if (state.busy) "Waiting for Lichess…" else "Sign in with Lichess")
                }
            } else if (game == null) {
                OnlineLobby(state, controller)
            } else {
                OnlineMatch(game, controller, board3D, use3D, history, sharer)
            }
            val linkId = game?.id ?: state.reference?.gameId
            if (linkId != null) {
                TextButton(onClick = { uriHandler.openUri("$LICHESS_BASE_URL/$linkId") }) { Text("Open on Lichess") }
            }
            if (!state.assistanceBlocked) {
                TextButton(onClick = onBack) { Text("Return to solo") }
            }
        }
    }
}

@Composable
private fun OnlineLobby(state: OnlineMatchState, controller: OnlineMatchController) {
    var username by remember { mutableStateOf("") }
    Text("Signed in as ${state.profile?.username}")
    Text("Casual standard chess · 15 minutes + 10 seconds per move")
    when {
        state.outgoing -> {
            Text("Waiting for the challenge to be accepted…")
            Button(onClick = controller::cancelChallenge) { Text("Cancel challenge") }
        }
        state.connectingGame -> {
            Text("Connecting to your match…")
            TextButton(onClick = controller::reconnect) { Text("Retry connection") }
        }
        else -> {
            OutlinedTextField(
                value = username,
                onValueChange = { username = it.take(30) },
                label = { Text("Opponent’s Lichess username") },
                singleLine = true,
            )
            Button(onClick = { controller.challenge(username) }, enabled = username.isNotBlank() && !state.busy) {
                Text("Send challenge")
            }
            state.incoming?.let { challenge ->
                Text("Challenge from ${challenge.challenger?.displayName ?: "a player"}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { controller.answerChallenge(challenge.id, true) }) { Text("Accept") }
                    TextButton(onClick = { controller.answerChallenge(challenge.id, false) }) { Text("Decline") }
                }
            }
            state.ongoing.forEach { match ->
                TextButton(onClick = { controller.resume(match.gameId) }) {
                    Text("Resume vs ${match.opponent.displayName}")
                }
            }
            TextButton(onClick = controller::disconnect) { Text("Disconnect Lichess") }
        }
    }
}

@Composable
private fun OnlineMatch(
    game: OnlineGame,
    controller: OnlineMatchController,
    board3D: Board3DSupport?,
    use3D: Boolean,
    history: GameHistoryRepository?,
    sharer: PgnSharer?,
) {
    val moveCount = game.board.moveHistory.size
    var selected by remember(game.id, moveCount, game.canMove) { mutableStateOf(INVALID_POSITION) }
    var promotionTo by remember(game.id, moveCount, game.canMove) { mutableStateOf<Pair<Int, Int>?>(null) }
    var failed3D by remember(game.id) { mutableStateOf(false) }
    val camera = remember(game.playerSide) {
        Board3DSessionState(initialYawDegrees = if (game.playerSide == Side.BLACK) OrbitCameraController.BLACK_YAW_DEG else 0f)
    }
    val white = game.playerSide == Side.WHITE
    val own = if (white) game.board.positionsWhite else game.board.positionsBlack
    val ownPieces = if (white) game.board.piecesWhite else game.board.piecesBlack
    val enemies = if (white) game.board.positionsBlack else game.board.positionsWhite
    val enemyPieces = if (white) game.board.piecesBlack else game.board.piecesWhite
    val tap: (Pair<Int, Int>) -> Unit = tap@{ square ->
        if (!game.canMove || promotionTo != null) return@tap
        if (square in own) {
            selected = if (square == selected) INVALID_POSITION else square
            return@tap
        }
        val index = own.indexOf(selected)
        if (index >= 0 && square in getLegalMovesForPiece(
                index, enemies, enemyPieces, own, ownPieces, game.board.castlingRights, game.board.enPassantTarget,
            )
        ) {
            if (ownPieces[index] is Pawn && square.first in listOf(0, 7)) promotionTo = square
            else controller.submitMove(selected, square)
        } else selected = INVALID_POSITION
    }

    OnlineClocks(game)
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val boardSize = minOf(maxWidth, 520.dp)
        if (use3D && board3D != null && !failed3D) {
            Board3D(
                support = board3D,
                fen = FenConverter.gameStateToFen(game.board),
                modifier = Modifier.size(boardSize),
                onUnavailable = { failed3D = true },
                cameraSession = camera,
                selectedSquare = selected.takeIf { it != INVALID_POSITION }?.let { BoardSquare(it.first, it.second) },
                onSquareTapped = { tap(it.row to it.col) },
            )
        } else {
            Board(
                gameState = game.board.copy(selectedSquare = selected),
                animState = PieceAnimationState(),
                windowSize = calculateWindowWidthSizeClass(maxWidth),
                playerSide = game.playerSide,
                updateSelected = {},
                playerMove = { _, _ -> },
                animationEnd = {},
                boardMaxSize = boardSize,
                onSquareTapped = tap,
            )
        }
    }

    promotionTo?.let { to ->
        PromotionDialog(game.playerSide, onSelect = {
            promotionTo = null
            controller.submitMove(selected, to, it)
        }, onDismiss = { promotionTo = null })
    }

    if (game.state.isTerminal) {
        OnlineResult(game, history, sharer)
    } else if (!game.connected) {
        Text("Reconnecting to Lichess…")
        TextButton(onClick = controller::reconnect) { Text("Retry now") }
    } else {
        OnlineGameActions(game, controller)
    }
}

@Composable
private fun OnlineClocks(game: OnlineGame) {
    var white by remember(game) { mutableStateOf(game.clockMillis(Side.WHITE)) }
    var black by remember(game) { mutableStateOf(game.clockMillis(Side.BLACK)) }
    LaunchedEffect(game) {
        while (!game.state.isTerminal) {
            delay(250)
            white = game.clockMillis(Side.WHITE)
            black = game.clockMillis(Side.BLACK)
        }
    }
    Text("${game.white.displayName}  ${clockText(white)}")
    Text("${game.black.displayName}  ${clockText(black)}")
}

private fun clockText(millis: Long): String {
    val seconds = (millis + 999) / 1000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

@Composable
private fun OnlineGameActions(game: OnlineGame, controller: OnlineMatchController) {
    var resign by remember { mutableStateOf(false) }
    val opposingDraw = if (game.playerSide == Side.WHITE) game.state.blackDraw else game.state.whiteDraw
    Text(if (game.movePending) "Waiting for Lichess…" else if (game.canMove) "Your move" else "Opponent’s move")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (opposingDraw) {
            TextButton(onClick = { controller.offerDraw(true) }) { Text("Accept draw") }
            TextButton(onClick = { controller.offerDraw(false) }) { Text("Decline") }
        } else {
            TextButton(onClick = { controller.offerDraw(true) }) { Text("Offer draw") }
        }
        if (game.board.moveHistory.size < 2) {
            TextButton(onClick = controller::abort) { Text("Abort") }
        } else {
            TextButton(onClick = { resign = true }) { Text("Resign") }
        }
    }
    if (resign) AlertDialog(
        onDismissRequest = { resign = false },
        title = { Text("Resign this match?") },
        confirmButton = { TextButton(onClick = { resign = false; controller.resign() }) { Text("Resign") } },
        dismissButton = { TextButton(onClick = { resign = false }) { Text("Keep playing") } },
    )
}

@Composable
private fun OnlineResult(game: OnlineGame, history: GameHistoryRepository?, sharer: PgnSharer?) {
    val saved = remember(game) { game.savedGame() }
    if (saved == null) {
        Text(if (game.state.isAborted) "Match aborted" else "Match ended")
        return
    }
    Text("Result: ${saved.result}")
    var stored by remember(saved.id) { mutableStateOf(false) }
    var saveFailed by remember(saved.id) { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (history != null) {
            Button(enabled = !stored, onClick = {
                stored = runCatching { history.add(saved) }.isSuccess
                saveFailed = !stored
            }) { Text(if (stored) "Saved offline" else "Save offline copy") }
        }
        if (sharer != null) {
            TextButton(onClick = { sharer.share(saved.pgn, "${saved.id}.pgn") }) { Text("Share PGN") }
        }
    }
    if (saveFailed) Text("Could not save the offline copy.", color = MaterialTheme.colorScheme.error)
}

internal fun OnlineGame.savedGame(): SavedGame? {
    if (!state.hasResult) return null
    val result = when (state.winnerSide) {
        Side.WHITE -> "1-0"
        Side.BLACK -> "0-1"
        null -> if (state.status in setOf("draw", "stalemate", "timeout", "outoftime")) "1/2-1/2" else "*"
    }
    if (result == "*") return null
    val tags = PgnTags(
        site = "$LICHESS_BASE_URL/$id",
        date = Instant.fromEpochMilliseconds(createdAt).toString().substringBefore('T').replace('-', '.'),
        white = white.displayName,
        black = black.displayName,
        result = result,
        timeControl = "${initialClockMillis / 1000}+${clockIncrementMillis / 1000}",
    )
    return SavedGame(
        "lichess-$id", createdAt, result, tags.white, tags.black, board.moveHistory.size,
        PgnSerializer.toPgn(tags, board.moveHistory), lichessGameId = id,
    )
}
