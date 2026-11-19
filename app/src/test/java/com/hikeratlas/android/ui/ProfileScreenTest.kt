package com.hikeratlas.android.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hikeratlas.android.atproto.Posts
import com.hikeratlas.android.atproto.Profiles
import com.hikeratlas.android.routes.KeyedHike
import com.hikeratlas.android.routes.KeyedSearch
import com.hikeratlas.android.track.TrackStore
import com.hikeratlas.android.units.DistanceUnit
import com.hikeratlas.android.units.ElevationUnit
import com.hikeratlas.android.units.Units
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/** Your profile: reviews, every track and faves as the website lists them, Delete, Try again, and "See post". */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi", application = android.app.Application::class)
class ProfileScreenTest {
    @get:Rule val compose = createComposeRule()

    @After
    fun tearDown() {
        Units.current = Units.METRIC
    }

    private val did = "did:plc:abcdefghijklmnopqrstuvwx"
    private val key = "1E57E06gbDEBBBICDGF.F55FRcODEp_"
    private val gone = "1E57E06gbDEBBBICDGF.AAAAAAAAAAA"
    private val gpx = "<gpx><trk><trkseg><trkpt lat=\"47.5\" lon=\"-121.7\"/><trkpt lat=\"47.51\" lon=\"-121.69\"/></trkseg></trk></gpx>"

    private fun track(rkey: String, routeKey: String? = key) = Profiles.Track(
        "at://$did/${Posts.TRACK}/$rkey", "c", "2026-07-01T10:00:00.000Z", Profiles.Blob("bafk-$rkey", "application/gzip"),
        startedAt = "2026-07-01T10:00:00.000Z", duration = 7500.0, distance = 7300.0, gain = 612.0, routeKey = routeKey, license = "CC-BY-4.0",
    )

    private fun review(rkey: String, text: String, t: Profiles.Track? = null, routeKey: String? = key) = Profiles.Review(
        "at://$did/${Posts.REVIEW}/$rkey", "c", text, "2026-07-02T10:00:00.000Z", hikedAt = "2026-07-01T12:00:00.000Z",
        routeKey = routeKey, trackUri = t?.uri, tags = listOf("views", "mud"), license = "CC-BY-4.0",
    )

    private fun actions(
        load: suspend () -> Profiles.Profile,
        delete: (suspend (Profiles.Deletion) -> Unit)? = null,
        shown: MutableList<PostedTrack> = mutableListOf(),
        edit: (suspend (Profiles.Review, Posts.ReviewEdit) -> Posts.Edited)? = null,
        openPhotos: (PhotoSet) -> Unit = {},
    ) = ProfileActions(
        load = { load() },
        findHikes = { KeyedSearch(mapOf(key to KeyedHike("p", "Mount Si NRCA", "p/day/0", "Mount Si")), emptySet()) },
        canFind = true,
        gpx = { _, _ -> gpx },
        delete = delete,
        openHike = {},
        showTrack = { shown += it },
        shareGpx = { _, _ -> },
        openPhotos = openPhotos,
        edit = edit,
    )

    private fun profile(reviews: List<Profiles.Review>, tracks: List<Profiles.Track>) =
        Profiles.Profile(did, "alice.test", "https://pds.example", reviews, tracks, emptyList())

    private fun show(p: Profiles.Profile, delete: (suspend (Profiles.Deletion) -> Unit)? = null, shown: MutableList<PostedTrack> = mutableListOf()) {
        compose.setContent {
            HikerAtlasTheme { ProfileScreen(ProfileTarget(did, "alice.test"), actions({ p }, delete, shown), onBack = {}) }
        }
    }

    private fun count(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().size

    @Test
    @Config(qualifiers = "w390dp-h3000dp-xxhdpi")
    fun yourReviewsHaveEditTracksDont() {
        val t = track("t1")
        val p = profile(listOf(review("r1", "With its track.", t), review("r2", "Without.")), listOf(t))
        compose.setContent {
            HikerAtlasTheme {
                ProfileScreen(
                    ProfileTarget(did, "alice.test"),
                    actions({ p }, delete = {}, edit = { r, e -> Posts.Edited(Posts.StrongRef(r.uri, "new"), Posts.editedReview(r.value, e)) }), onBack = {},
                )
            }
        }
        compose.waitUntil(5_000) { count("Mount Si") > 0 }
        assertEquals(2, count("Edit"))
        assertEquals(3, count("Delete"))
    }

    @Test
    @Config(qualifiers = "w390dp-h3000dp-xxhdpi")
    fun aTappedPhotoOpensInTheViewerAtThatPhoto() {
        val photos = listOf(
            Profiles.Photo(Profiles.Blob("bafk-p1", "image/jpeg"), "Haystack"),
            Profiles.Photo(Profiles.Blob("bafk-p2", "image/jpeg"), "Summit", lat = 47.49, lon = -121.72),
        )
        val p = profile(listOf(review("r1", "With photos.").copy(photos = photos)), emptyList())
        var viewing by androidx.compose.runtime.mutableStateOf<PhotoSet?>(null)
        compose.setContent {
            HikerAtlasTheme {
                // As the app hosts it: the viewer over the profile.
                androidx.compose.foundation.layout.Box {
                    ProfileScreen(ProfileTarget(did, "alice.test"), actions({ p }, openPhotos = { viewing = it }), onBack = {})
                    viewing?.let { PhotoViewer(it, PhotoViewerActions({ _, _ -> null }, {}), onClose = { viewing = null }) }
                }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Summit")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Summit").performClick()
        compose.onNodeWithTag("photo-viewer").assertIsDisplayed()
        compose.onNodeWithText("2 / 2").assertIsDisplayed()
        compose.onNodeWithText("CC BY 4.0 · @alice.test").assertIsDisplayed()
        compose.onNodeWithText("Show on map").assertIsDisplayed()
        assertEquals(PhotoSet("https://pds.example", did, "alice.test", "CC-BY-4.0", photos, 1), viewing)
        compose.onNodeWithContentDescription("Close").performClick()
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithTag("photo-viewer").fetchSemanticsNodes().size)
    }

    @Test
    fun someoneElsesReviewsHaveNoEdit() {
        show(profile(listOf(review("r1", "Without.")), emptyList()))
        compose.waitUntil(5_000) { count("Mount Si") > 0 }
        assertEquals(0, count("Edit"))
    }

    @Test
    @Config(qualifiers = "w390dp-h3000dp-xxhdpi")
    fun reviewsAndEveryTrackListedApart() {
        val alone = track("alone", null).copy(title = "Evening loop")
        val t = track("t1")
        val withTrack = review("r1", "With its track.", t)
        val without = review("r2", "Without a track.")
        val missing = review("r3", "Its track is gone.").copy(trackUri = "at://$did/${Posts.TRACK}/gone")
        show(profile(listOf(withTrack, without, missing), listOf(alone, t)))
        compose.waitUntil(5_000) { count("Mount Si") > 0 }
        compose.onNodeWithText("Reviews").assertIsDisplayed()
        compose.onNodeWithText("3 reviews").assertIsDisplayed()
        compose.onNodeWithText("Tracks").assertIsDisplayed()
        compose.onNodeWithText("2 tracks").assertIsDisplayed()
        compose.onNodeWithText("Evening loop").assertIsDisplayed()
        // The two tracks, and the one review's track drawn inline: not the others'.
        assertEquals(3, count("GPX track"))
    }

    @Test
    @Config(qualifiers = "w390dp-h3000dp-xxhdpi")
    fun aReviewDrawsItsTrackInYourUnitsAndDeletingItDeletesBoth() {
        Units.current = Units(DistanceUnit.MI, ElevationUnit.FT)
        val t = track("t1")
        val r = review("r1", "Steep but worth it.", t)
        val deleted = mutableListOf<Profiles.Deletion>()
        val shown = mutableListOf<PostedTrack>()
        show(profile(listOf(r), listOf(t)), { deleted += it }, shown)
        compose.waitUntil(5_000) { count("Mount Si") > 0 }
        compose.onNodeWithText("@alice.test").assertIsDisplayed()
        compose.onNodeWithText("1 review").assertIsDisplayed()
        compose.onNodeWithText("1 track").assertIsDisplayed()
        compose.onNodeWithText("Steep but worth it.").assertIsDisplayed()
        compose.onNodeWithText("Views").assertIsDisplayed()
        compose.onNodeWithText("Mud").assertIsDisplayed()
        // In the review and in Tracks.
        assertEquals(2, count("4.5 mi · ↑ 2,008 ft · 2 h 5 min"))
        assertEquals(2, count("Shared under CC BY 4.0"))

        // The review's own Show on map: its track.
        compose.onAllNodesWithText("Show on map")[0].performClick()
        compose.waitUntil(5_000) { shown.isNotEmpty() }
        assertEquals("Track", shown.single().title)
        assertEquals(listOf(listOf(listOf(-121.7, 47.5), listOf(-121.69, 47.51))), shown.single().segments)

        // The review's Delete comes first.
        compose.onAllNodesWithText("Delete")[0].performClick()
        compose.onNodeWithText("Delete this review?").assertIsDisplayed()
        compose.onNodeWithText("Deleting this review also deletes its track.").assertIsDisplayed()
        compose.onNodeWithText("Keep").performClick()
        assertTrue(deleted.isEmpty())
        compose.onAllNodesWithText("Delete")[0].performClick()
        compose.onAllNodesWithText("Delete").onLast().performClick()
        compose.waitUntil(5_000) { count("Steep but worth it.") == 0 }
        assertEquals(listOf(Profiles.Deletion(listOf(r), listOf(t))), deleted)
        assertEquals(0, count("GPX track"))
        compose.onNodeWithText("Nothing posted yet. Open a hike and choose Post to add a review or your track.").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w390dp-h3000dp-xxhdpi")
    fun deletingATrackAlsoDeletesItsReview() {
        val t = track("t1")
        val r = review("r1", "Steep but worth it.", t)
        val other = review("r2", "Another day.")
        val deleted = mutableListOf<Profiles.Deletion>()
        show(profile(listOf(r, other), listOf(t)), { deleted += it })
        compose.waitUntil(5_000) { count("Mount Si") > 0 }
        // Reviews' two Deletes, then the track's.
        compose.onAllNodesWithText("Delete")[2].performClick()
        compose.onNodeWithText("Delete this track?").assertIsDisplayed()
        compose.onNodeWithText("Deleting this track also deletes its review.").assertIsDisplayed()
        compose.onAllNodesWithText("Delete").onLast().performClick()
        compose.waitUntil(5_000) { count("Steep but worth it.") == 0 }
        assertEquals(listOf(Profiles.Deletion(listOf(r), listOf(t))), deleted)
        compose.onNodeWithText("Another day.").assertIsDisplayed()
        compose.onNodeWithText("1 review").assertIsDisplayed()
        compose.onNodeWithText("No tracks yet.").assertIsDisplayed()
    }

    @Test
    fun aReviewAloneAsksAsBefore() {
        show(profile(listOf(review("r1", "Steep but worth it.")), emptyList()), {})
        compose.waitUntil(5_000) { count("Delete") > 0 }
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Delete this review?").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("also deletes", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun confirmationWording() {
        val t = track("t")
        val r = review("r", "x", t)
        assertEquals("Deleting this review also deletes its track.", Confirming(r.uri, true, Profiles.Deletion(listOf(r), listOf(t))).also)
        assertEquals("Deleting this track also deletes its review.", Confirming(t.uri, false, Profiles.Deletion(listOf(r), listOf(t))).also)
        assertNull(Confirming(r.uri, true, Profiles.Deletion(listOf(r), emptyList())).also)
        assertNull(Confirming(t.uri, false, Profiles.Deletion(emptyList(), listOf(t))).also)
        assertEquals("Delete this review?", Confirming(r.uri, true, Profiles.Deletion(listOf(r), listOf(t))).title)
        assertEquals("Delete this track?", Confirming(t.uri, false, Profiles.Deletion(listOf(r), listOf(t))).title)
    }

    @Test
    fun aDeleteThatFailsSaysSoAndKeepsThePost() {
        show(profile(emptyList(), listOf(track("t1"))), { throw IOException("HTTP 502") })
        compose.waitUntil(5_000) { count("Delete") > 0 }
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Delete this track?").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("also deletes", substring = true).fetchSemanticsNodes().size)
        compose.onAllNodesWithText("Delete").onLast().performClick()
        compose.waitUntil(5_000) { count("Deleting didn't work: HTTP 502") > 0 }
        compose.onNodeWithText("GPX track").assertIsDisplayed()
    }

    @Test
    fun aPdsThatsDownOffersTryAgain() {
        var calls = 0
        compose.setContent {
            HikerAtlasTheme {
                ProfileScreen(ProfileTarget(did, "alice.test"), actions({
                    calls++
                    if (calls == 1) throw IOException("HTTP 502 from com.atproto.repo.listRecords")
                    profile(emptyList(), emptyList())
                }), onBack = {})
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Try again").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Couldn't load the profile: HTTP 502 from com.atproto.repo.listRecords").assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Nothing posted yet.").fetchSemanticsNodes().isNotEmpty() }
        // Not yours (no delete): no hint about posting.
        assertEquals(2, calls)
    }

    @Test
    fun seePostLandsOnItsTrackInTracks() {
        val tracks = (0 until 30).map { i -> track("t$i", routeKey = null).copy(title = "Track $i") }
        val reviews = tracks.mapIndexed { i, t -> review("r$i", "Review $i", t) }
        val posted = TrackStore.Posted(tracks[25].uri, "c", reviews[25].uri, 1)
        val target = seePostTarget(did, "alice.test", posted)!!
        compose.setContent {
            HikerAtlasTheme { ProfileScreen(target, actions({ profile(reviews, tracks) }), onBack = {}) }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("highlighted-post").fetchSemanticsNodes().isNotEmpty() }
        // The track's card, below every review, not the review's.
        compose.onNodeWithTag("highlighted-post").assertIsDisplayed().assert(hasAnyDescendant(hasText("Track 25")))
        assertEquals(0, count("Review 25"))
        // No Delete where it isn't yours to delete.
        assertTrue(compose.onAllNodesWithText("Delete").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun seePostOpensYourProfileAtYourPostOnly() {
        val mine = TrackStore.Posted("at://$did/${Posts.TRACK}/t", "c", "at://$did/${Posts.REVIEW}/r", 1)
        assertEquals(ProfileTarget(did, "alice.test", PostRef(mine.trackUri, mine.reviewUri)), seePostTarget(did, "alice.test", mine))
        // Posted while signed in as someone else: your profile, at the top.
        val theirs = TrackStore.Posted("at://did:plc:zzzzzzzzzzzzzzzzzzzzzzzz/${Posts.TRACK}/t", "c", null, 1)
        assertEquals(ProfileTarget(did, null, null), seePostTarget(did, null, theirs))
        assertNull(seePostTarget(null, null, mine))
    }

    @Test
    fun aPostsHikeIsGoneOnlyAfterAnExactSearch() {
        val search = KeyedSearch(emptyMap(), emptySet())
        assertEquals("Finding the hike…", postTitle(gone, null, HikesFound(null, false, true)))
        assertEquals("A hike no longer on Hiker Atlas", postTitle(gone, null, HikesFound(search, true, true)))
        assertEquals("A hike that couldn't be looked up", postTitle(gone, null, HikesFound(KeyedSearch(emptyMap(), setOf(gone)), true, true)))
        assertEquals("A hike that couldn't be looked up", postTitle(gone, null, HikesFound(null, true, false)))
        assertEquals("A hike that couldn't be looked up", postTitle(gone, null, HikesFound(search, true, false)))
        // Without a hike: the track's own title, else as the website.
        assertEquals("Evening loop", postTitle(null, "Evening loop", HikesFound(search, true, true)))
        assertEquals("A hike", postTitle(null, null, HikesFound(search, true, true)))
    }

    @Test
    fun statsAreInYourUnits() {
        val t = track("t")
        assertEquals("7.3 km · ↑ 612 m · 2 h 5 min", trackFacts(t))
        Units.current = Units(DistanceUnit.MI, ElevationUnit.FT)
        assertEquals("4.5 mi · ↑ 2,008 ft · 2 h 5 min", trackFacts(t))
        assertEquals("4.5 mi", trackFacts(t.copy(gain = null, duration = null)))
    }

    /** A track's steps, as the website shows them: only where its record has a count. */
    @Test
    fun aCountedTracksStepsAreShown() {
        val t = track("t")
        assertEquals("7.3 km · ↑ 612 m · 2 h 5 min · 3,120 steps", trackFacts(t.copy(steps = 3120)))
        fun parsed(steps: String) = com.hikeratlas.android.atproto.Profiles.parseTrack(
            kotlinx.serialization.json.Json.parseToJsonElement(
                """{"uri":"at://did:plc:x/com.hikeratlas.temp.track/t","cid":"c","value":{"createdAt":"2026-07-02T00:00:00.000Z",
                "gpx":{"${'$'}type":"blob","ref":{"${'$'}link":"bafy"},"mimeType":"application/gzip","size":10}$steps}}""",
            ) as kotlinx.serialization.json.JsonObject,
        )!!.steps
        assertEquals(3120L, parsed(",\"steps\":3120"))
        assertEquals(0L, parsed(",\"steps\":0"))
        assertNull(parsed(""))
        assertNull(parsed(",\"steps\":-5"))
        assertNull(parsed(",\"steps\":12.5"))
        assertNull(parsed(",\"steps\":\"3120\""))
    }

    @Test
    fun theProfilesSettingsTab() {
        compose.setContent {
            HikerAtlasTheme {
                ProfileScreen(
                    ProfileTarget(did, "alice.test"), actions({ profile(emptyList(), emptyList()) }), onBack = {},
                    settings = { UnitsCard(UnitsActions(Units.METRIC, signedIn = true, save = {})) },
                )
            }
        }
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("UNITS").assertIsDisplayed()
        compose.onNodeWithText("Overview").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Nothing posted yet.").fetchSemanticsNodes().isNotEmpty() }
    }

    // A review of a track not tied to a curated hike is titled by its track,
    // as the website shows it (#100); a review of a track tied to one, by that hike.
    @Test
    @Config(qualifiers = "w390dp-h3000dp-xxhdpi")
    fun aReviewIsTitledByItsTrack() {
        val walk = track("walk", null).copy(title = "Breithaupt Park walk")
        val hiked = track("hiked")
        show(profile(listOf(review("r1", "Chilly.", walk, routeKey = null), review("r2", "Steep.", hiked, routeKey = null)), listOf(walk, hiked)))
        compose.waitUntil(5_000) { count("Mount Si") > 0 }
        // The review and the track itself.
        assertEquals(2, count("Breithaupt Park walk"))
        assertEquals(0, count("A hike"))
    }
}
