package com.example.myapplication

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.getDisplayCutoutBounds
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Gap between a landscape rail and the edge it hugs (the window edge, or a cutout it clears). */
internal val SIDE_RAIL_EDGE_MARGIN = 6.dp

/**
 * The insets the landscape frame pads by: system bars at the top and bottom, but at the sides only
 * what isn't a display cutout. A cutout covers a small part of one edge (the Pixel 7a's camera is a
 * hole at mid-height), so padding a whole side for it would push every button off that edge.
 * [RailColumn] steps only the buttons that would actually meet it.
 *
 * On iOS the cutout is reported as a full-height strip on the notch side, so there every button on
 * that side still clears it, as before.
 */
@Composable
internal fun sideRailInsets(): WindowInsets =
    WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical)
        .union(WindowInsets.safeDrawing.exclude(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal))

/**
 * A landscape button rail: children stacked from the top, [spacing] apart, each at its own width
 * and pushed against the outer edge (the end edge when [alignEnd]). A child whose span would meet a
 * display cutout is stepped inward just far enough to clear it; the others keep hugging the edge.
 *
 * The cutout bounds are only known at placement, as rulers in this layout's own coordinates, which
 * is why this is a [Layout] and not a Column with per-button padding.
 */
@Composable
internal fun RailColumn(
    alignEnd: Boolean,
    modifier: Modifier = Modifier,
    spacing: Dp = 8.dp,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val gap = spacing.roundToPx()
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val width = (placeables.maxOfOrNull { it.width } ?: 0)
            .coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = (placeables.sumOf { it.height } + gap * (placeables.size - 1).coerceAtLeast(0))
            .coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            val cutouts = getDisplayCutoutBounds().mapNotNull { rulers ->
                Rect(
                    left = rulers.left.current(Float.NaN),
                    top = rulers.top.current(Float.NaN),
                    right = rulers.right.current(Float.NaN),
                    bottom = rulers.bottom.current(Float.NaN),
                ).takeUnless { it.left.isNaN() || it.top.isNaN() || it.right.isNaN() || it.bottom.isNaN() }
            }
            val margin = SIDE_RAIL_EDGE_MARGIN.toPx()
            var y = 0
            placeables.forEach { placeable ->
                val x = if (alignEnd) width - placeable.width else 0
                val clearX = railChildClearOfCutouts(
                    left = x.toFloat(),
                    width = placeable.width.toFloat(),
                    top = y.toFloat(),
                    bottom = (y + placeable.height).toFloat(),
                    alignEnd = alignEnd,
                    cutouts = cutouts,
                    margin = margin,
                )
                placeable.place(clearX.roundToInt(), y)
                y += placeable.height + gap
            }
        }
    }
}

/**
 * Where a rail child whose box is [left]..[left]+[width] by [top]..[bottom] goes so that it keeps
 * [margin] clear of every cutout in [cutouts] (all in the rail's coordinates). Unchanged when no
 * cutout shares its rows; otherwise moved inward (rightwards for the start rail, leftwards when
 * [alignEnd]) to [margin] past the cutout.
 */
internal fun railChildClearOfCutouts(
    left: Float,
    width: Float,
    top: Float,
    bottom: Float,
    alignEnd: Boolean,
    cutouts: List<Rect>,
    margin: Float,
): Float {
    var x = left
    cutouts.filterNot { it.isEmpty }.forEach { cutout ->
        val sharesRows = top < cutout.bottom + margin && bottom > cutout.top - margin
        val sharesColumns = x < cutout.right + margin && x + width > cutout.left - margin
        if (sharesRows && sharesColumns) {
            x = if (alignEnd) minOf(x, cutout.left - margin - width) else maxOf(x, cutout.right + margin)
        }
    }
    return x
}
