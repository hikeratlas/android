package com.hikeratlas.android.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.hikeratlas.android.atproto.AccountState
import com.hikeratlas.android.atproto.PostException
import com.hikeratlas.android.atproto.Posts
import com.hikeratlas.android.routes.KeyedHike
import com.hikeratlas.android.track.GpxStats
import com.hikeratlas.android.track.HikeMatch
import com.hikeratlas.android.track.TrackStore
import com.hikeratlas.android.units.Units
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi", application = android.app.Application::class)
class PostFormTest {
    @get:Rule val compose = createComposeRule()

    private val hike = PostHike("keyA", "Mount Si")
    private val account = AccountActions(AccountState.In("did:plc:x", "alice.test", null), { "" }, {}, {})
    private val track = TrackStore.Track(7, 1_783_000_000_000, 1_783_005_000_000, "Morning walk", 0, null)
    private val stats = GpxStats("2026-07-02T13:46:43.000Z", 4928, 2204, 88, 300)

    @Before
    fun setUp() {
        Units.current = Units.METRIC
    }

    private fun actions(post: suspend (PostDraft, (String) -> Unit) -> Unit = { _, _ -> }, license: String = "CC-BY-4.0") = PostActions(
        account, listOf(track), license = { license }, trackStats = { if (it == 7L) stats else null },
        hikesWalked = { emptyList() }, post = post, openProfile = {}, today = { LocalDate.of(2026, 7, 4) },
    )

    private fun waitFor(text: String) = compose.waitUntil(5_000) {
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun aReviewWithTagsIsPostedForTheHike() {
        var got: PostDraft? = null
        compose.setContent { PostForm(hike, null, actions(post = { d, _ -> got = d }), onClose = {}) }
        compose.onNodeWithText("Post").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("Great views, some mud.")
        compose.onNodeWithText("Views").performScrollTo().performClick()
        compose.onNodeWithText("Mud").performScrollTo().performClick()
        compose.onNodeWithText("Post").assertIsEnabled().performClick()
        waitFor("Posted")
        compose.onNodeWithText("Thanks! It's on your profile, and in your own account for any app to show.").assertExists()
        assertEquals(PostDraft(hike, "Mount Si", "Great views, some mud.", listOf("views", "mud"), LocalDate.of(2026, 7, 4), null), got)
    }

    @Test
    fun theLicenceNoteSaysTheProfiles() {
        compose.setContent { PostForm(hike, null, actions(), onClose = {}) }
        waitFor("CC BY 4.0")
        compose.onNodeWithText(
            "Posts are public, and stay in your own account. You keep ownership; this one is shared under CC BY 4.0. To change that, see Licence in the You tab.",
        ).assertExists()
    }

    @Test
    fun aFailureSaysWhatFailedAndKeepsWhatWasTyped() {
        compose.setContent {
            PostForm(hike, null, actions(post = { _, _ -> throw PostException("createRecord failed (400): Invalid record") }), onClose = {})
        }
        compose.onNode(hasSetTextAction()).performTextInput("Muddy.")
        compose.onNodeWithText("Post").performClick()
        waitFor("createRecord failed")
        compose.onNodeWithText("createRecord failed (400): Invalid record").assertExists()
        compose.onNodeWithText("Muddy.").assertExists()
        compose.onNodeWithText("Post").assertIsEnabled()
    }

    @Test
    fun aTrackFromThePhoneGoesWithItsNumbersAndDay() {
        var got: PostDraft? = null
        compose.setContent { PostForm(null, 7, actions(post = { d, _ -> got = d }), onClose = {}, trackName = "Morning walk") }
        // A track alone can be posted; its numbers as the record will have them.
        waitFor("2.2 km")
        compose.onNodeWithText("2.2 km · ↑ 88 m · 1 h 22 min").assertExists()
        compose.onNodeWithText("Post").assertIsEnabled().performClick()
        waitFor("Posted")
        // The day it started (13:46 UTC: 2 July in any zone but the far east of the date line).
        assertEquals(7L, got!!.trackId)
        assertEquals(null, got!!.hike)
        assertEquals("Morning walk", got!!.title)
    }

    @Test
    fun atMostTenTags() {
        compose.setContent { PostForm(hike, null, actions(), onClose = {}) }
        for ((_, name) in Posts.TAGS.take(10)) compose.onNodeWithText(name).performScrollTo().performClick()
        compose.onNodeWithText(Posts.TAGS[10].second).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Up to 10 tags.").assertExists()
    }

    @Test
    fun overTheLimitCantBePosted() {
        compose.setContent { PostForm(hike, null, actions(), onClose = {}) }
        compose.onNode(hasSetTextAction()).performTextInput("a".repeat(Posts.MAX_TEXT + 1))
        compose.onNodeWithText("${Posts.MAX_TEXT + 1} / ${Posts.MAX_TEXT}").assertExists()
        compose.onNodeWithText("Post").assertIsNotEnabled()
    }

    @Test
    fun theHikesATrackWalkedThenNone() {
        var got: PostHike? = PostHike("unset", "")
        val matches = listOf(
            HikeMatch("keyA", KeyedHike("p1", "Raging River State Forest", "p1/day/0", "Upper Lake"), 0.97, 4.1),
            HikeMatch("keyB", KeyedHike("p1", "Raging River State Forest", "p1/day/1", "Lower Lake"), 0.55, 3.0),
        )
        compose.setContent { HikeChooser(MatchState.Ready(matches), onNext = { got = it }, onCancel = {}) }
        compose.onNodeWithText("Raging River State Forest · follows 97% of it").assertExists()
        // The best one is chosen to start with.
        compose.onNodeWithText("Next").performClick()
        assertEquals(PostHike("keyA", "Upper Lake"), got)
        compose.onNodeWithText("None of these").performClick()
        compose.onNodeWithText("Next").performClick()
        assertEquals(null, got)
    }

    @Test
    fun aLicenceThatWontSaveSaysSoLoudly() {
        compose.setContent { LicenseCard(LicenseActions("CC0-1.0") { throw IOException("HTTP 502") }) }
        compose.onNodeWithText("Anything, crediting you (CC BY 4.0)").performClick()
        waitFor("Couldn't save your licence")
        compose.onNodeWithText("Couldn't save your licence to your Hiker Atlas profile, so it hasn't changed. (HTTP 502)").assertExists()
        compose.onNodeWithText("Anything: public domain (CC0)").assertIsSelected()
    }

    @Test
    fun aLicenceSaved() {
        var saved: String? = null
        compose.setContent { LicenseCard(LicenseActions("CC0-1.0") { saved = it }) }
        compose.onNodeWithText("Nothing beyond Hiker Atlas showing them").performClick()
        waitFor("Saved.")
        assertEquals("hikeratlas-only", saved)
        compose.onNodeWithText("Nothing beyond Hiker Atlas showing them").assertIsSelected()
    }
}
