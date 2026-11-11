package com.hikeratlas.android.map

import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A route opened with no sheet showing is fitted once its new sheet is placed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi", application = android.app.Application::class)
class RouteFitEffectTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun aNewSheetsRouteIsFittedOnceTheSheetIsPlaced() {
        val container = 2400
        var pending by mutableStateOf(true)
        val fits = mutableListOf<Float>()
        compose.setContent {
            // As MapScreen: a fresh sheet, the fit asked before the sheet is
            // composed, and the peek known only once its header is measured.
            val sheet = remember { AnchoredDraggableState(Stop.Peek) }
            var headerPx by remember { mutableIntStateOf(0) }
            RouteFitEffect("park/day/0", pending, sheet, ready = headerPx > 0, containerPx = container) { top ->
                fits += top
                pending = false
            }
            Box(Modifier.fillMaxSize()) {
                MapSheet(
                    state = sheet, containerPx = container, peekPx = headerPx, onHeaderHeight = { headerPx = it },
                    halfAndFull = false, header = { Text("A hike", Modifier.height(100.dp)) }, body = {},
                )
            }
        }
        compose.waitUntil(5_000) { fits.isNotEmpty() }
        compose.waitForIdle()
        assertEquals(1, fits.size)
        // Fitted above the sheet's peek, not under a sheet with no position.
        assertTrue("sheet top ${fits[0]}", fits[0] > 0 && fits[0] < container)
    }
}
