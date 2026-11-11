package com.hikeratlas.android.track

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.hikeratlas.android.ui.HikerAtlasTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi", application = android.app.Application::class)
class TrackScreensTest {
    @get:Rule val compose = createComposeRule()

    private val stats = TrackStore.Stats(7_340.0, 3_849_000, 3_130_000, 612.0, 598.0)
    private val track = TrackStore.Track(7, 1_700_000_000_000, 1_700_004_000_000, "Raging River State Forest · 14 Nov", 0, null, stats)
    private val detail = TrackLibrary.Detail(track, listOf(listOf(listOf(-121.9, 47.5), listOf(-121.9, 47.51))), stats, null)

    @Test
    fun theSummarySavesTheNameAsEdited() {
        var saved: String? = null
        compose.setContent { HikerAtlasTheme { TrackSummarySheet(detail, onSave = { saved = it }, onResume = {}, onDiscard = {}, onScrub = {}) } }
        compose.onNodeWithText("7.3 km · 1:04:09 · ↑ 612 m").assertIsDisplayed()
        compose.onNodeWithText("Moving 52:10 · ↓ 598 m").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextReplacement("Upward Mobility")
        compose.onNodeWithText("Save").performClick()
        assertEquals("Upward Mobility", saved)
    }

    @Test
    fun discardingAsksFirst() {
        var discarded = false
        var resumed = false
        compose.setContent { HikerAtlasTheme { TrackSummarySheet(detail, onSave = {}, onResume = { resumed = true }, onDiscard = { discarded = true }, onScrub = {}) } }
        compose.onNodeWithText("Discard").performClick()
        assertEquals(false, discarded)
        compose.onNodeWithText("Discard this track?").assertIsDisplayed()
        // The alert's button: after the sheet's (the sheet is a dialog window too).
        compose.onAllNodes(androidx.compose.ui.test.hasText("Discard") and androidx.compose.ui.test.hasClickAction()).onLast().performClick()
        assertEquals(true, discarded)
        assertEquals(false, resumed)
    }

    @Test
    fun theSavedListOpensATrack() {
        var opened: Long? = null
        compose.setContent { HikerAtlasTheme { SavedScreen(listOf(track)) { opened = it } } }
        compose.onNodeWithText("Raging River State Forest · 14 Nov").performClick()
        assertEquals(7L, opened)
    }
}
