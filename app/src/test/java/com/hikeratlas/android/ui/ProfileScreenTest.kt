package com.hikeratlas.android.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
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

/** Your profile: posts and faves as the website lists them, Delete, Try again, and "See post". */
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
        delete: (suspend (Profiles.Post) -> Unit)? = null,
        shown: MutableList<PostedTrack> = mutableListOf(),
    ) = ProfileActions(
        load = { load() },
        findHikes = { KeyedSearch(mapOf(key to KeyedHike("p", "Mount Si NRCA", "p/day/0", "Mount Si")), emptySet()) },
        canFind = true,
        gpx = { _, _ -> gpx },
        delete = delete,
        openHike = {},
        showTrack = { shown += it },
        shareGpx = { _, _ -> },
        openPhoto = {},
    )

    private fun profile(posts: List<Profiles.Post>) = Profiles.Profile(did, "alice.test", "https://pds.example", posts, emptyList())

    @Test
    fun aReviewWithItsTrackInYourUnitsAndDeleted() {
        Units.current = Units(DistanceUnit.MI, ElevationUnit.FT)
        val t = track("t1")
        val post = Profiles.Post("2026-07-01T12:00:00.000Z", review("r1", "Steep but worth it.", t), t)
        val deleted = mutableListOf<Profiles.Post>()
        val shown = mutableListOf<PostedTrack>()
        compose.setContent {
            HikerAtlasTheme {
                ProfileScreen(ProfileTarget(did, "alice.test"), actions({ profile(listOf(post)) }, { deleted += it }, shown), onBack = {})
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Mount Si").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("@alice.test").assertIsDisplayed()
        compose.onNodeWithText("1 post").assertIsDisplayed()
        compose.onNodeWithText("Steep but worth it.").assertIsDisplayed()
        compose.onNodeWithText("Views").assertIsDisplayed()
        compose.onNodeWithText("Mud").assertIsDisplayed()
        compose.onNodeWithText("4.5 mi · ↑ 2,008 ft · 2 h 5 min").assertIsDisplayed()
        compose.onNodeWithText("Shared under CC BY 4.0").assertIsDisplayed()

        compose.onNodeWithText("Show on map").performClick()
        compose.waitUntil(5_000) { shown.isNotEmpty() }
        assertEquals("Track", shown.single().title)
        assertEquals(listOf(listOf(listOf(-121.7, 47.5), listOf(-121.69, 47.51))), shown.single().segments)

        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Delete this review and its track?").assertIsDisplayed()
        compose.onNodeWithText("Keep").performClick()
        assertTrue(deleted.isEmpty())
        compose.onNodeWithText("Delete").performClick()
        compose.onAllNodesWithText("Delete").onLast().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Steep but worth it.").fetchSemanticsNodes().isEmpty() }
        assertEquals(listOf(post), deleted)
        compose.onNodeWithText("Nothing posted yet. Open a hike and choose Post to add a review or your track.").assertIsDisplayed()
    }

    @Test
    fun aDeleteThatFailsSaysSoAndKeepsThePost() {
        val post = Profiles.Post("2026-07-01T10:00:00.000Z", null, track("t1"))
        compose.setContent {
            HikerAtlasTheme {
                ProfileScreen(ProfileTarget(did, "alice.test"), actions({ profile(listOf(post)) }, { throw IOException("HTTP 502") }), onBack = {})
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Delete").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Delete this track?").assertIsDisplayed()
        compose.onAllNodesWithText("Delete").onLast().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Deleting didn't work: HTTP 502").fetchSemanticsNodes().isNotEmpty() }
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
                    profile(emptyList())
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
    fun seePostScrollsToAndHighlightsThatPost() {
        val posts = (0 until 30).map { i ->
            val t = track("t$i")
            Profiles.Post("2026-07-%02dT10:00:00.000Z".format(30 - i), review("r$i", "Review $i", t), t)
        }
        val posted = TrackStore.Posted(posts[25].track!!.uri, "c", posts[25].review!!.uri, 1)
        val target = seePostTarget(did, "alice.test", posted)!!
        compose.setContent {
            HikerAtlasTheme { ProfileScreen(target, actions({ profile(posts) }), onBack = {}) }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("highlighted-post").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("highlighted-post").assertIsDisplayed()
        compose.onNodeWithText("Review 25").assertIsDisplayed()
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
        val p = Profiles.Post("2026", review("r", "x", routeKey = gone), null)
        val search = KeyedSearch(emptyMap(), emptySet())
        assertEquals("Finding the hike…", postTitle(p, HikesFound(null, false, true)))
        assertEquals("A hike no longer on Hiker Atlas", postTitle(p, HikesFound(search, true, true)))
        assertEquals("A hike that couldn't be looked up", postTitle(p, HikesFound(KeyedSearch(emptyMap(), setOf(gone)), true, true)))
        assertEquals("A hike that couldn't be looked up", postTitle(p, HikesFound(null, true, false)))
        assertEquals("A hike that couldn't be looked up", postTitle(p, HikesFound(search, true, false)))
        // Without a hike: the track's own title, else as the website.
        assertEquals("Evening loop", postTitle(Profiles.Post("2026", null, track("t", null).copy(title = "Evening loop")), HikesFound(search, true, true)))
        assertEquals("A hike", postTitle(Profiles.Post("2026", review("r", "x", routeKey = null), null), HikesFound(search, true, true)))
    }

    @Test
    fun statsAreInYourUnits() {
        val t = track("t")
        assertEquals("7.3 km · ↑ 612 m · 2 h 5 min", trackFacts(t))
        Units.current = Units(DistanceUnit.MI, ElevationUnit.FT)
        assertEquals("4.5 mi · ↑ 2,008 ft · 2 h 5 min", trackFacts(t))
        assertEquals("4.5 mi", trackFacts(t.copy(gain = null, duration = null)))
    }

    @Test
    fun signedInTheAccountCardOpensYourProfileWhereTheSettingsAre() {
        var opened = 0
        compose.setContent {
            HikerAtlasTheme {
                YouScreen(
                    account = AccountActions(
                        com.hikeratlas.android.atproto.AccountState.In(did, "alice.test", null),
                        signIn = { "" }, openPage = {}, signOut = {}, openProfile = { opened++ },
                    ),
                    units = UnitsActions(Units.METRIC, signedIn = true, save = {}),
                )
            }
        }
        // Units are a profile setting signed in: in the profile's Settings, not here.
        assertTrue(compose.onAllNodesWithText("UNITS").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("Your profile").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun theProfilesSettingsTab() {
        compose.setContent {
            HikerAtlasTheme {
                ProfileScreen(
                    ProfileTarget(did, "alice.test"), actions({ profile(emptyList()) }), onBack = {},
                    settings = { UnitsCard(UnitsActions(Units.METRIC, signedIn = true, save = {})) },
                )
            }
        }
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("UNITS").assertIsDisplayed()
        compose.onNodeWithText("Overview").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Nothing posted yet.").fetchSemanticsNodes().isNotEmpty() }
    }
}
