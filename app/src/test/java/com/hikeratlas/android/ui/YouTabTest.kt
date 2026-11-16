package com.hikeratlas.android.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.hikeratlas.android.atproto.AccountState
import com.hikeratlas.android.atproto.Posts
import com.hikeratlas.android.atproto.Profiles
import com.hikeratlas.android.outbox.Outbox
import com.hikeratlas.android.routes.KeyedSearch
import com.hikeratlas.android.units.Units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #84: signed in, the You tab is your profile (Overview and Settings), with your picture as the tab's icon. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi", application = android.app.Application::class)
class YouTabTest {
    @get:Rule val compose = createComposeRule()

    private val did = "did:plc:abcdefghijklmnopqrstuvwx"
    private val signedIn = AccountState.In(did, "alice.test", null)

    private fun account(state: AccountState, signOut: suspend () -> Unit = {}) =
        AccountActions(state, signIn = { "" }, openPage = {}, signOut = signOut)

    private fun profileActions(load: () -> Profiles.Profile = { profile(emptyList()) }) = ProfileActions(
        load = { load() }, findHikes = { KeyedSearch(emptyMap(), emptySet()) }, canFind = true, gpx = { _, _ -> "" },
        delete = {}, openHike = {}, showTrack = {}, shareGpx = { _, _ -> }, openPhotos = {},
    )

    private fun profile(tracks: List<Profiles.Track>) = Profiles.Profile(did, "alice.test", "https://pds.example", emptyList(), tracks, emptyList())

    private fun storage() = StorageActions({ 0 }, { 0L }, { 0L }, {}, {})

    private fun count(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().size

    private fun waitFor(text: String) = compose.waitUntil(5_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun signedInItIsYourProfile() {
        val waiting = Outbox.Entry(
            "1", did, "alice.test", 1, "2026-07-02T19:00:00.000Z", "Mount Si",
            Posts.Post(text = "Lovely."), hasGpx = true, state = Outbox.State.WAITING,
        )
        compose.setContent {
            HikerAtlasTheme {
                YouTab(
                    account(signedIn), profileActions(), UnitsActions(Units.METRIC, signedIn = true, save = {}),
                    storage = storage(), waiting = WaitingActions(listOf(waiting), null, {}, {}),
                )
            }
        }
        // The header: picture (the handle's letter, without one) and handle; no back arrow, as it's a tab.
        compose.onNodeWithText("@alice.test").assertIsDisplayed()
        compose.onNodeWithText("A").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").assertDoesNotExist()
        compose.onNodeWithText("Overview").assertIsSelected()
        waitFor("Nothing posted yet.")
        compose.onNodeWithText("Waiting to post").assertExists()
        compose.onNodeWithText("Mount Si").assertExists()
        // What the You tab had before is in Settings, not here; no separate way to the profile.
        assertEquals(0, count("Your profile"))
        assertEquals(0, count("UNITS"))
        assertEquals(0, count("Sign out"))
    }

    @Test
    @Config(qualifiers = "w390dp-h3000dp-xxhdpi")
    fun settingsGathersUnitsLicenceStorageAccountAndAbout() {
        var signedOut = 0
        compose.setContent {
            HikerAtlasTheme {
                YouTab(
                    account(signedIn) { signedOut++ }, profileActions(), UnitsActions(Units.METRIC, signedIn = true, save = {}),
                    storage = storage(), license = LicenseActions(Posts.DEFAULT_LICENSE) {},
                )
            }
        }
        compose.onNodeWithText("Settings").performClick()
        for (h in listOf("Posting", "On this phone", "Account", "About")) compose.onNodeWithText(h).assertExists()
        for (c in listOf("UNITS", "LICENCE", "STORAGE")) compose.onNodeWithText(c).assertExists()
        compose.onNodeWithText("Terms and privacy").assertExists()
        compose.onNodeWithText("Version").assertExists()
        // The headings say it: the Account and About cards have no titles of their own.
        assertEquals(0, count("ACCOUNT"))
        assertEquals(0, count("ABOUT"))
        compose.onNodeWithText("Sign out").performScrollTo().performClick()
        compose.waitUntil(5_000) { signedOut == 1 }
    }

    @Test
    fun seePostLandsOnOverviewAtThePost() {
        val tracks = (0 until 30).map { i ->
            Profiles.Track(
                "at://$did/${Posts.TRACK}/t$i", "c", "2026-07-01T10:00:00.000Z", Profiles.Blob("bafk-$i", "application/gzip"),
                title = "Track $i",
            )
        }
        var seePost by mutableStateOf<PostRef?>(null)
        compose.setContent {
            HikerAtlasTheme {
                YouTab(
                    account(signedIn), profileActions { profile(tracks) }, UnitsActions(Units.METRIC, signedIn = true, save = {}),
                    seePost = seePost,
                )
            }
        }
        waitFor("Track 0")
        // Left on Settings: "See post" still lands on Overview, at the post.
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("UNITS").assertExists()
        seePost = seePostTarget(did, "alice.test", com.hikeratlas.android.track.TrackStore.Posted(tracks[25].uri, "c", null, 1))!!.highlight
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("highlighted-post").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Overview").assertIsSelected()
        compose.onNodeWithTag("highlighted-post").assertIsDisplayed().assert(hasAnyDescendant(hasText("Track 25")))
    }

    @Test
    fun signedOutItIsAsBefore() {
        compose.setContent {
            HikerAtlasTheme {
                YouTab(account(AccountState.Out()), profileActions(), UnitsActions(Units.METRIC, signedIn = false, save = {}), storage = storage())
            }
        }
        compose.onNodeWithText("You").assertIsDisplayed()
        compose.onNodeWithText("Sign in").assertIsDisplayed()
        compose.onNodeWithText("UNITS").assertExists()
        compose.onNodeWithText("STORAGE").assertExists()
        compose.onNodeWithText("ABOUT").assertExists()
        assertEquals(0, count("Overview"))
        assertEquals(0, count("Settings"))
    }

    @Test
    fun theTabsIconIsYourPictureSignedIn() {
        var state by mutableStateOf<AccountState>(signedIn)
        compose.setContent { HikerAtlasTheme { YouTabIcon(state, selected = false) } }
        compose.onNodeWithContentDescription("You, @alice.test").assertExists()
        compose.onNodeWithTag("you-avatar").assertExists()
        compose.onNodeWithText("A", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("you-person").assertDoesNotExist()
        state = AccountState.Out()
        compose.onNodeWithTag("you-person").assertExists()
        compose.onNodeWithTag("you-avatar").assertDoesNotExist()
        assertTrue(compose.onAllNodesWithText("A").fetchSemanticsNodes().isEmpty())
    }
}
