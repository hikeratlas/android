package com.hikeratlas.android.map

import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

/**
 * Fits a route into the map the sheet leaves: when it opens (if [pending]),
 * and whenever the sheet comes to rest at half height. [fit] gets the
 * sheet's top.
 *
 * It waits for the sheet to be placed: a sheet that's just opened has no
 * position until [MapSheet] sets its anchors, which it does after the header
 * is measured, so after this is first asked. (A hike opened with no sheet
 * showing, as from a website link, would otherwise never be fitted.)
 */
@Composable
fun RouteFitEffect(
    routeId: Any?,
    pending: Boolean,
    sheet: AnchoredDraggableState<Stop>,
    /** The map and the sheet's header are ready. */
    ready: Boolean,
    /** The map's height: a change fits the route again (if pending, or at half). */
    containerPx: Int,
    fit: (sheetTop: Float) -> Unit,
) {
    val placed by remember(sheet) { derivedStateOf { !sheet.offset.isNaN() } }
    val doFit by rememberUpdatedState(fit)
    LaunchedEffect(routeId, pending, sheet.settledValue, ready, placed, containerPx) {
        if (routeId == null || !ready || !placed) return@LaunchedEffect
        val settled = sheet.settledValue
        if ((pending && settled != Stop.Full) || settled == Stop.Half) doFit(sheet.offset)
    }
}
