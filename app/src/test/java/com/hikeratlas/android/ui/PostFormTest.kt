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
import com.hikeratlas.android.track.NearbyHike
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

    private fun actions(post: suspend (PostDraft, (String) -> Unit) -> PostOutcome = { _, _ -> PostOutcome.Posted }, license: String = "CC-BY-4.0") = PostActions(
        account, listOf(track), license = { license }, trackStats = { if (it == 7L) stats else null },
        hikesNear = { emptyList() }, post = post, openProfile = { _ -> }, today = { LocalDate.of(2026, 7, 4) },
    )

    private fun waitFor(text: String) = compose.waitUntil(5_000) {
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun aReviewWithTagsIsPostedForTheHike() {
        var got: PostDraft? = null
        compose.setContent { PostForm(hike, null, actions(post = { d, _ -> got = d; PostOutcome.Posted }), onClose = {}) }
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

    /** No signal: kept on the phone, and the form says so (#60). */
    @Test
    fun withoutSignalItSaysItWillPostWhenOnline() {
        var opened = false
        compose.setContent {
            PostForm(
                hike, null,
                PostActions(
                    account, listOf(track), license = { "CC0-1.0" }, trackStats = { stats }, hikesNear = { emptyList() },
                    post = { _, _ -> PostOutcome.Queued(WILL_POST) }, openProfile = { opened = true }, today = { LocalDate.of(2026, 7, 4) },
                ),
                onClose = {},
            )
        }
        compose.onNode(hasSetTextAction()).performTextInput("Muddy.")
        compose.onNodeWithText("Post").performClick()
        waitFor("Saved on your phone")
        compose.onNodeWithText(WILL_POST).assertExists()
        compose.onNodeWithText("See your profile").performClick()
        assertEquals(true, opened)
    }

    @Test
    fun aTrackWaitingToPostCantBeChosenAgain() {
        val tracks = listOf(t(1, "Waiting one"), t(2, "Free one"))
        var chosen: Long? = -1
        compose.setContent { TrackChoices(tracks, emptySet(), null, waiting = setOf(1), onChoose = { chosen = it }) }
        compose.onNodeWithText("Waiting to post").assertExists()
        compose.onNode(hasText("Waiting one", substring = true)).performClick()
        assertEquals(-1L, chosen)
        compose.onNode(hasText("Free one", substring = true)).performClick()
        assertEquals(2L, chosen)
    }

    @Test
    fun aTrackFromThePhoneGoesWithItsNumbersAndDay() {
        var got: PostDraft? = null
        compose.setContent { PostForm(null, 7, actions(post = { d, _ -> got = d; PostOutcome.Posted }), onClose = {}, trackName = "Morning walk") }
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
    fun theHikesNearATrackClosestFirstThenNone() {
        var got: PostHike? = PostHike("unset", "")
        val near = listOf(
            NearbyHike("keyA", KeyedHike("p1", "Raging River State Forest", "p1/day/0", "Upper Lake"), 3.0),
            NearbyHike("keyB", KeyedHike("p1", "Raging River State Forest", "p1/day/1", "Lower Lake"), 420.0),
        )
        compose.setContent { HikeChooser(MatchState.Ready(near), onNext = { got = it }, onCancel = {}) }
        compose.onNodeWithText("Raging River State Forest · on your track").assertExists()
        compose.onNodeWithText("Raging River State Forest · 400 m from your track").assertExists()
        // The track is on the closest: it's chosen to start with.
        compose.onNodeWithText("Next").performClick()
        assertEquals(PostHike("keyA", "Upper Lake"), got)
        compose.onNodeWithText("None of these").performClick()
        compose.onNodeWithText("Next").performClick()
        assertEquals(null, got)
    }

    @Test
    fun noHikeIsGuessedWhenTheTrackIsOnNone() {
        var got: PostHike? = PostHike("unset", "")
        val near = listOf(NearbyHike("keyB", KeyedHike("p1", "Raging River State Forest", "p1/day/1", "Lower Lake"), 420.0))
        compose.setContent { HikeChooser(MatchState.Ready(near), onNext = { got = it }, onCancel = {}) }
        compose.onNodeWithText("Next").performClick()
        assertEquals(null, got)
    }

    private fun t(id: Long, name: String, posted: Boolean = false) = TrackStore.Track(
        id, 1_783_000_000_000 - id * 86_400_000, 1_783_000_000_000, name, 0, null,
        posted = if (posted) TrackStore.Posted("at://did:plc:x/${Posts.TRACK}/3tid$id", "bafy", null, 1) else null,
    )

    @Test
    fun tracksNearTheHikeComeFirstAndPostedOnesCantBeChosen() {
        // Newest first, as the library lists them.
        val tracks = listOf(t(1, "Newest"), t(2, "Near, posted", posted = true), t(3, "Near"), t(4, "Oldest"))
        val (near, other) = trackGroups(tracks, setOf(2, 3))
        assertEquals(listOf(2L, 3L), near.map { it.id })
        assertEquals(listOf(1L, 4L), other.map { it.id })
        var chosen: Long? = -1
        compose.setContent { TrackChoices(tracks, setOf(2, 3), null, onChoose = { chosen = it }) }
        compose.onNodeWithText("Near this hike").assertExists()
        compose.onNodeWithText("Other tracks").assertExists()
        compose.onNodeWithText("Already posted").assertExists()
        compose.onNode(hasText("Near, posted", substring = true)).performClick()
        assertEquals(-1L, chosen)
        compose.onNode(hasText("Near ·", substring = true)).performClick()
        assertEquals(3L, chosen)
        compose.onNodeWithText("No track").performClick()
        assertEquals(null, chosen)
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

    private val gpx = javaClass.getResource("/photos/locate.gpx")!!.readText()

    /** A stand-in for the system's pickers: a button giving these photos. */
    private fun picks(vararg names: String): @androidx.compose.runtime.Composable (Int, Boolean, (List<PhotoInput>) -> Unit) -> Unit =
        { room, enabled, onPicked ->
            androidx.compose.material3.Button(onClick = { onPicked(names.take(room).map { PhotoInput(android.net.Uri.parse("test:$it")) }) }, enabled = enabled) {
                androidx.compose.material3.Text("Pick test photos")
            }
        }

    private fun photoActions(discarded: MutableList<String>) = PhotoActions(
        prepare = { input ->
            when (val n = input.uri.schemeSpecificPart) {
                "gps" -> Posts.Photo(n, takenAt = "2026-07-02T15:02:30.000Z", lat = 47.0, lon = -121.0)
                // In the track's first stretch; long before it.
                "timed" -> Posts.Photo(n, takenAt = "2026-07-02T15:02:30.000Z")
                "outside" -> Posts.Photo(n, takenAt = "2026-06-01T12:00:00.000Z")
                "bare" -> Posts.Photo(n)
                else -> throw IOException("Not an image Android can read")
            }
        },
        discard = { discarded += it.file },
    )

    @Test
    fun eachPhotoSaysWhereItsLocationComesFrom() {
        var got: PostDraft? = null
        val discarded = mutableListOf<String>()
        compose.setContent {
            PostForm(
                null, 7,
                PostActions(
                    account, listOf(track), license = { "CC0-1.0" }, trackStats = { stats }, hikesNear = { emptyList() },
                    post = { d, _ -> got = d; PostOutcome.Posted }, openProfile = { _ -> }, today = { LocalDate.of(2026, 7, 4) },
                    photos = photoActions(discarded), trackGpx = { gpx },
                ),
                onClose = {}, trackName = "Morning walk", photoButtons = picks("gps", "timed", "outside", "bare", "broken"),
            )
        }
        waitFor("2.2 km")
        compose.onNodeWithText("Pick test photos").performScrollTo().performClick()
        waitFor("Can't read this photo")
        compose.onNodeWithText("Location from the photo").assertExists()
        compose.onNodeWithText("Placed on your track by its time").assertExists()
        compose.onNodeWithText("No location (not taken during your track)").assertExists()
        compose.onNodeWithText("No location").assertExists()
        // Track alone: they go on it.
        compose.onNodeWithText("They go on your track.").assertExists()
        // One that can't be read holds the post, until it's removed.
        compose.onNodeWithText("Post").assertIsNotEnabled()
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Remove photo 5")).performScrollTo().performClick()
        compose.onNodeWithText("Can't read this photo").assertDoesNotExist()
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Remove photo 4")).performScrollTo().performClick()
        assertEquals(listOf("bare"), discarded)
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Description of photo 1")).performScrollTo().performTextInput("  The lake  ")
        compose.onNodeWithText("Post").assertIsEnabled().performClick()
        waitFor("Posted")
        assertEquals(
            listOf(
                Posts.Photo("gps", alt = "The lake", takenAt = "2026-07-02T15:02:30.000Z", lat = 47.0, lon = -121.0),
                Posts.Photo("timed", takenAt = "2026-07-02T15:02:30.000Z"),
                Posts.Photo("outside", takenAt = "2026-06-01T12:00:00.000Z"),
            ),
            got!!.photos,
        )
    }

    @Test
    fun withoutATrackAPhotoWithATimeAsksForOne() {
        compose.setContent {
            PostForm(
                hike, null,
                PostActions(
                    account, listOf(track), license = { "CC0-1.0" }, trackStats = { stats }, hikesNear = { emptyList() },
                    post = { _, _ -> PostOutcome.Posted }, openProfile = { _ -> }, photos = photoActions(mutableListOf()), trackGpx = { gpx },
                ),
                onClose = {}, photoButtons = picks("timed"),
            )
        }
        compose.onNodeWithText("Pick test photos").performScrollTo().performClick()
        waitFor("No location (add your track to place it)")
        compose.onNodeWithText("Photos go with a review or a track: write a few words, or choose your track.").assertExists()
        compose.onNodeWithText("Post").assertIsNotEnabled()
        // The review's field comes first; the photo's description after.
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("Lovely.")
        compose.onNodeWithText("They go on your review.").assertExists()
    }

    @Test
    fun whereTextAsTheWebsites() {
        assertEquals("Reading…", whereText(PhotoItem(1), gpx))
        assertEquals("Can't read this photo", whereText(PhotoItem(1, failed = "x"), gpx))
        assertEquals("No location", whereText(PhotoItem(1, Posts.Photo("f")), gpx))
        assertEquals("Placed on your track by its time", whereText(PhotoItem(1, Posts.Photo("f", takenAt = "2026-07-02T16:05:00.000Z")), gpx))
        // In the track's 40-minute stop: not placed.
        assertEquals("No location (not taken during your track)", whereText(PhotoItem(1, Posts.Photo("f", takenAt = "2026-07-02T15:40:00.000Z")), gpx))
    }
}
