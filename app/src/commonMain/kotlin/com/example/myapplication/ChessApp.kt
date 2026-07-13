package com.example.myapplication

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

import com.example.myapplication.board3d.Board3DSupport
import com.example.myapplication.persistence.GameHistoryRepository
import com.example.myapplication.share.PgnSharer

@Composable
fun ChessApp(
    viewModel: GameViewModel,
    modifier: Modifier = Modifier,
    board3D: Board3DSupport? = null,
    gameHistory: GameHistoryRepository? = null,
    pgnSharer: PgnSharer? = null,
    switchTopPadding: Dp = 8.dp,
    onOpenHistory: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenRules: () -> Unit = {},
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize()
        ) {
            val windowSize = remember(maxWidth) {
                calculateWindowWidthSizeClass(maxWidth)
            }
            GameScreen(
                windowSize = windowSize,
                viewModel = viewModel,
                board3D = board3D,
                gameHistory = gameHistory,
                pgnSharer = pgnSharer,
                switchTopPadding = switchTopPadding,
                onOpenHistory = onOpenHistory,
                onOpenSettings = onOpenSettings,
                onOpenRules = onOpenRules,
            )
        }
    }
}

enum class WindowWidthSizeClass {
    Compact,
    Medium,
    Expanded
}

fun calculateWindowWidthSizeClass(width: Dp): WindowWidthSizeClass {
    return when {
        width >= 840.dp -> WindowWidthSizeClass.Expanded
        width >= 600.dp -> WindowWidthSizeClass.Medium
        else -> WindowWidthSizeClass.Compact
    }
}
