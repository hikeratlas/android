package com.hikeratlas.android.map

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** The heights the sheet rests at. */
enum class Stop { Peek, Half, Full }

/** Where each stop puts the sheet's top, as a share of the map's height. */
private const val HALF = 0.45f
private const val FULL = 0.2f

/**
 * A bottom sheet over the map with three heights: [Stop.Peek] shows just the
 * [header], [Stop.Half] some of the [body], [Stop.Full] all of it, scrolling.
 *
 * With [halfAndFull] false the sheet rests only at the peek, and shows no
 * grab handle.
 *
 * The header never scrolls. The body scrolls only at [Stop.Full]; below that,
 * dragging anywhere moves the sheet. At [Stop.Full], pulling the body down
 * from its top moves the sheet down.
 */
@Composable
fun MapSheet(
    state: AnchoredDraggableState<Stop>,
    containerPx: Int,
    peekPx: Int,
    onHeaderHeight: (Int) -> Unit,
    halfAndFull: Boolean,
    header: @Composable () -> Unit,
    body: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val fullTop = containerPx * FULL
    val peekTop = (containerPx - peekPx).toFloat()

    LaunchedEffect(containerPx, peekPx, halfAndFull) {
        if (containerPx <= 0 || peekPx <= 0) return@LaunchedEffect
        val anchors = DraggableAnchors {
            Stop.Peek at peekTop
            if (halfAndFull) {
                // Half only where it's higher than the peek (a tall header).
                if (containerPx * HALF < peekTop) Stop.Half at containerPx * HALF
                Stop.Full at minOf(fullTop, peekTop)
            }
        }
        val target = state.targetValue.takeIf { anchors.hasPositionFor(it) } ?: Stop.Peek
        state.updateAnchors(anchors, target)
    }

    val scroll = rememberScrollState()
    val atFull = state.settledValue == Stop.Full
    val nested = remember(state) {
        object : NestedScrollConnection {
            // Scrolling up while the sheet is below Full: raise the sheet first.
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val y = available.y
                return if (y < 0 && source == NestedScrollSource.UserInput && state.offset > fullTop + 1) {
                    Offset(0f, state.dispatchRawDelta(y))
                } else {
                    Offset.Zero
                }
            }

            // Pulling down past the body's top: lower the sheet.
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                val y = available.y
                return if (y > 0 && source == NestedScrollSource.UserInput) Offset(0f, state.dispatchRawDelta(y)) else Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                // If the sheet was moved by scrolling, let it settle.
                if (state.offset > fullTop + 1 && state.offset < peekTop - 1) {
                    state.settle(androidx.compose.animation.core.spring())
                    return available
                }
                return Velocity.Zero
            }
        }
    }

    val sheetHeightDp = with(density) { (containerPx - fullTop.coerceAtMost(peekTop)).toDp() }
    Surface(
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .offset { IntOffset(0, (if (state.offset.isNaN()) containerPx.toFloat() else state.offset).roundToInt()) }
            .fillMaxWidth()
            .height(sheetHeightDp)
            .anchoredDraggable(state, Orientation.Vertical)
            .nestedScroll(nested),
    ) {
        Column {
            Column(Modifier.onSizeChanged { onHeaderHeight(it.height) }) {
                Box(
                    Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    // The grab handle, only where there's somewhere to drag to:
                    // otherwise just its space, so the header sits the same.
                    val handle = Modifier.size(width = 36.dp, height = 4.dp)
                    Box(if (halfAndFull) handle.background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp)) else handle)
                }
                header()
            }
            Column(
                Modifier.weight(1f, fill = false)
                    .verticalScroll(scroll, enabled = atFull)
                    .navigationBarsPadding(),
            ) {
                body()
            }
        }
    }
}
