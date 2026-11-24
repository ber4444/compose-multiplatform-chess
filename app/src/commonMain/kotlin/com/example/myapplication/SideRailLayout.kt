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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.getDisplayCutoutBounds
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Gap between a landscape rail and the edge it hugs (the window edge, or a cutout it clears). */
internal val SIDE_RAIL_EDGE_MARGIN = 6.dp

/** Space between a rail's outer column and the inner column it wraps into beside a cutout. */
internal val SIDE_RAIL_COLUMN_GAP = 8.dp

/**
 * The insets the landscape frame pads by: system bars at the top and bottom, but at the sides only
 * what isn't a display cutout. A cutout covers a small part of one edge (the Pixel 7a's camera is a
 * hole at mid-height), so padding a whole side for it would push every button off that edge.
 * [RailColumn] instead wraps the buttons that would meet it into a second column.
 *
 * On iOS the cutout is reported as a full-height strip on the notch side, so there the first button
 * already meets it and the whole rail steps past it, as before.
 */
@Composable
internal fun sideRailInsets(): WindowInsets =
    WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical)
        .union(WindowInsets.safeDrawing.exclude(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal))

/**
 * A landscape button rail: children stacked from the top, [spacing] apart, each at its own width
 * and pushed against the outer edge (the end edge when [alignEnd]). See [railPositions] for what
 * happens when a display cutout sits on that edge.
 *
 * The rail fills the width it is given so a wrapped second column has room; the cutout bounds are
 * only known at placement, as rulers in this layout's own coordinates, which is why this is a
 * [Layout] rather than a Column.
 */
@Composable
internal fun RailColumn(
    alignEnd: Boolean,
    modifier: Modifier = Modifier,
    spacing: Dp = 8.dp,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val widest = placeables.maxOfOrNull { it.width } ?: 0
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else widest
        val gap = spacing.roundToPx()
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
            val positions = railPositions(
                sizes = placeables.map { Size(it.width.toFloat(), it.height.toFloat()) },
                railWidth = width.toFloat(),
                rowGap = gap.toFloat(),
                columnGap = SIDE_RAIL_COLUMN_GAP.toPx(),
                alignEnd = alignEnd,
                cutouts = cutouts,
                margin = SIDE_RAIL_EDGE_MARGIN.toPx(),
            )
            placeables.forEachIndexed { i, placeable ->
                placeable.place(positions[i].x.roundToInt(), positions[i].y.roundToInt())
            }
        }
    }
}

/**
 * Where each rail child (of [sizes], in order) goes, in a rail [railWidth] wide whose outer edge is
 * the start edge, or the end edge when [alignEnd]. Everything is in the rail's own coordinates.
 *
 * Normally one column from the top, [rowGap] apart, each child against the outer edge. When a
 * child's box would come within [margin] of one of [cutouts], that child and every one after it
 * wrap into an inner column, [columnGap] in from the outer column's widest child, which also starts
 * at the top: the outer column keeps the edge and the rows above the cutout, and the buttons keep
 * their order reading down each column in turn. Should the inner column itself reach the cutout's
 * rows, it steps past the cutout too.
 */
internal fun railPositions(
    sizes: List<Size>,
    railWidth: Float,
    rowGap: Float,
    columnGap: Float,
    alignEnd: Boolean,
    cutouts: List<Rect>,
    margin: Float,
): List<Offset> {
    val holes = cutouts.filterNot { it.isEmpty }
    fun meets(x: Float, y: Float, size: Size, hole: Rect) =
        y < hole.bottom + margin && y + size.height > hole.top - margin &&
            x < hole.right + margin && x + size.width > hole.left - margin
    fun meets(x: Float, y: Float, size: Size) = holes.any { meets(x, y, size, it) }
    fun stack(from: List<Size>): List<Float> {
        var y = 0f
        return from.map { size -> y.also { y += size.height + rowGap } }
    }
    fun outerX(size: Size) = if (alignEnd) railWidth - size.width else 0f

    val tops = stack(sizes)
    val wrapAt = sizes.indices.firstOrNull { meets(outerX(sizes[it]), tops[it], sizes[it]) }
        ?: return sizes.mapIndexed { i, size -> Offset(outerX(size), tops[i]) }

    val outer = sizes.subList(0, wrapAt)
    val inner = sizes.subList(wrapAt, sizes.size)
    val innerTops = stack(inner)
    val outerWidth = outer.maxOfOrNull { it.width } ?: 0f
    val inset = if (outer.isEmpty()) 0f else outerWidth + columnGap
    // The inner column's edge nearest the outer one: its left edge for the start rail, its right
    // edge for the end rail. Moved past any cutout one of its own children would still meet.
    var edge = if (alignEnd) railWidth - inset else inset
    fun innerX(size: Size) = if (alignEnd) edge - size.width else edge
    repeat(holes.size) {
        inner.forEachIndexed { j, size ->
            holes.forEach { hole ->
                if (meets(innerX(size), innerTops[j], size, hole)) {
                    edge = if (alignEnd) minOf(edge, hole.left - margin) else maxOf(edge, hole.right + margin)
                }
            }
        }
    }
    return outer.mapIndexed { i, size -> Offset(outerX(size), tops[i]) } +
        inner.mapIndexed { j, size -> Offset(innerX(size), innerTops[j]) }
}
