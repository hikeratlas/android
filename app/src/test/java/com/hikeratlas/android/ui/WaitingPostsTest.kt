package com.hikeratlas.android.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hikeratlas.android.atproto.AccountState
import com.hikeratlas.android.atproto.Posts
import com.hikeratlas.android.atproto.Profiles
import com.hikeratlas.android.outbox.Outbox
import com.hikeratlas.android.routes.KeyedSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The posts waiting on the phone: on your profile above your posts, and on the You tab signed out. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi", application = android.app.Application::class)
class WaitingPostsTest {
    @get:Rule val compose = createComposeRule()

    private val did = "did:plc:abcdefghijklmnopqrstuvwx"

    private fun entry(id: String, title: String, state: Outbox.State = Outbox.State.WAITING, error: String? = null, text: String = "Lovely.") =
        Outbox.Entry(
            id, did, "alice.test", 1, "2026-07-02T19:00:00.000Z", title,
            Posts.Post(text = text, photos = listOf(Posts.Photo("a"), Posts.Photo("b"))), hasGpx = true, state = state, error = error,
        )

    private fun actions(load: suspend () -> Profiles.Profile) = ProfileActions(
        load = { load() }, findHikes = { KeyedSearch(emptyMap(), emptySet()) }, canFind = true, gpx = { _, _ -> "" },
        delete = null, openHike = {}, showTrack = {}, shareGpx = { _, _ -> }, openPhotos = {},
    )

    private fun waitFor(text: String) = compose.waitUntil(5_000) {
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun onYourProfileAboveYourPostsWithRetryAndDiscard() {
        val retried = mutableListOf<String>()
        val discarded = mutableListOf<String>()
        var entries by mutableStateOf(
            listOf(
                entry("1", "Mount Si"),
                entry("2", "Rattlesnake Ledge", Outbox.State.FAILED, "createRecord failed (400): Invalid record"),
                entry("3", "Tiger Mountain", Outbox.State.NEEDS_SIGN_IN, text = ""),
            ),
        )
        var loads = 0
        var refresh by mutableIntStateOf(0)
        compose.setContent {
            HikerAtlasTheme {
                ProfileScreen(
                    ProfileTarget(did, "alice.test"),
                    actions {
                        loads++
                        Profiles.Profile(did, "alice.test", "https://pds.example", emptyList(), emptyList(), emptyList())
                    },
                    onBack = {},
                    waiting = WaitingActions(entries, active = "1", retry = { retried += it }, discard = { discarded += it; entries = entries.filter { e -> e.id != it } }),
                    refreshKey = refresh,
                )
            }
        }
        waitFor("Nothing posted yet.")
        compose.onNodeWithText("Waiting to post").assertExists()
        assertEquals(2, compose.onAllNodesWithText("Review, track and 2 photos").fetchSemanticsNodes().size)
        compose.onNodeWithText("Track and 2 photos").assertExists()
        compose.onNodeWithText("Uploading…").assertExists()
        compose.onNodeWithText("Failed: createRecord failed (400): Invalid record").assertExists()
        compose.onNodeWithText("Needs you to sign in again: your session ended").assertExists()
        // The one going up can't be retried meanwhile.
        compose.onAllNodesWithText("Retry")[0].assertIsNotEnabled()
        compose.onAllNodesWithText("Retry")[1].performClick()
        assertEquals(listOf("2"), retried)

        // Discard asks first.
        compose.onAllNodesWithText("Discard")[1].performClick()
        compose.onNodeWithText("Discard this post?").assertExists()
        compose.onNodeWithText("Keep").performClick()
        assertTrue(discarded.isEmpty())
        compose.onAllNodesWithText("Discard")[1].performClick()
        compose.onAllNodesWithText("Discard").onLastNode().performClick()
        assertEquals(listOf("2"), discarded)
        compose.onNodeWithText("Rattlesnake Ledge").assertDoesNotExist()

        // One went up: the posts are read again.
        val before = loads
        refresh++
        compose.waitForIdle()
        compose.waitUntil(5_000) { loads > before }
    }

    @Test
    fun withoutSignalTheyShowThoughTheProfileWont() {
        compose.setContent {
            HikerAtlasTheme {
                ProfileScreen(
                    ProfileTarget(did, "alice.test"), actions { throw java.io.IOException("Unable to resolve host") }, onBack = {},
                    waiting = WaitingActions(listOf(entry("1", "Mount Si")), null, {}, {}),
                )
            }
        }
        waitFor("Couldn't load the profile")
        compose.onNodeWithText("Mount Si").assertExists()
        compose.onNodeWithText("Waiting for signal").assertExists()
    }

    @Test
    fun onTheYouTabWhenSignedOut() {
        var state by mutableStateOf<AccountState>(AccountState.Out())
        var opened = 0
        compose.setContent {
            HikerAtlasTheme {
                YouScreen(
                    account = AccountActions(state, signIn = { "" }, openPage = {}, signOut = {}, openProfile = { opened++ }),
                    waiting = WaitingActions(listOf(entry("1", "Mount Si", Outbox.State.NEEDS_SIGN_IN)), null, {}, {}),
                )
            }
        }
        compose.onNodeWithText("Mount Si").assertExists()
        compose.onNodeWithText("Needs you to sign in again: your session ended").assertExists()
        // Signed in: they're on your profile, linked to from here.
        state = AccountState.In(did, "alice.test", null)
        compose.onNodeWithText("1 post waiting to go up").performClick()
        assertEquals(1, opened)
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.onLastNode() = this[fetchSemanticsNodes().size - 1]
