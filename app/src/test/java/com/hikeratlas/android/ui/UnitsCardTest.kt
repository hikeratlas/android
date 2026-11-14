package com.hikeratlas.android.ui

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hikeratlas.android.units.Units
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi", application = android.app.Application::class)
class UnitsCardTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun aFailedSaveSaysSoLoudlyAndKeepsTheUnits() {
        compose.setContent {
            UnitsCard(UnitsActions(Units.METRIC, signedIn = true, save = { throw IOException("HTTP 502") }))
        }
        compose.onNodeWithText("Miles (mi)").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("Couldn't save your units", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Couldn't save your units to your Hiker Atlas profile, so they haven't changed. (HTTP 502)").assertExists()
        // Back to what's in effect.
        compose.onNodeWithText("Kilometres (km)").assertIsSelected()
    }
}
