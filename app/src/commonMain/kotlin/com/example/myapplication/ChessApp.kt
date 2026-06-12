package com.example.myapplication

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun ChessApp(
    viewModel: GameViewModel,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            val windowSize = remember(maxWidth) {
                calculateWindowWidthSizeClass(maxWidth)
            }
            GameScreen(
                windowSize = windowSize,
                viewModel = viewModel
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
