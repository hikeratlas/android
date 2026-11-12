package com.hikeratlas.android.ui

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi", application = android.app.Application::class)
class StorageCardTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun showsSizesAndClearsAfterAsking() {
        var areas = 2
        var offline = 52_300_000L
        var cache = 87_000_000L
        var deleted = 0
        var cleared = 0
        val storage = StorageActions(
            areaCount = { areas },
            offlineBytes = { offline },
            mapCacheBytes = { cache },
            deleteAll = { deleted++; areas = 0; offline = 0 },
            clearMapCache = { cleared++; cache = 1_000_000 },
        )
        compose.setContent { HikerAtlasTheme { YouScreen(storage) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("52 MB · 2 areas").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("87 MB · places you've looked at, kept to load faster").fetchSemanticsNode()

        compose.onNodeWithText("Clear").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("1.0 MB · places you've looked at, kept to load faster").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, cleared)

        // Deleting all asks first.
        compose.onNodeWithText("Delete all").performClick()
        assertEquals(0, deleted)
        compose.onAllNodes(hasText("Delete all") and hasClickAction()).onLast().performClick()
        compose.waitUntil(5_000) { deleted == 1 }
    }
}
