package com.hikeratlas.android.ui

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hikeratlas.android.atproto.AccountState
import com.hikeratlas.android.atproto.Faves
import com.hikeratlas.android.routes.KeyedHike
import com.hikeratlas.android.routes.KeyedSearch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/** The hike sheet's heart and the Saved tab's faved hikes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi", application = android.app.Application::class)
class FavesUiTest {
    @get:Rule val compose = createComposeRule()

    private val key = "1E57E06gbDEBBBICDGF.F55FRcODEp_"

    private fun account(state: AccountState) = AccountActions(state, signIn = { "https://example.test/" }, openPage = {}, signOut = {})

    @Test
    fun keysArePlacedUnderRobolectricToo() {
        // h3-java picks its library by the JVM's vendor and OS: here, the desktop one.
        assertTrue(com.hikeratlas.android.routes.RouteKeys.available)
        assertTrue(com.hikeratlas.android.routes.RouteKeys.centroid(key) != null)
    }

    @Test
    fun signedOutTheHeartOffersSignIn() {
        val set = mutableListOf<Boolean>()
        compose.setContent {
            HikerAtlasTheme {
                FaveButton(key, "Nugget Falls", FaveActions(account(AccountState.Out()), isFaved = { error("not signed in") }, setFave = { _, on -> set += on }))
            }
        }
        compose.onNodeWithContentDescription("Save to your faves").performClick()
        compose.onNodeWithText("Sign in to save Nugget Falls to your faves.").fetchSemanticsNode()
        compose.onNodeWithText("Continue with Bluesky").fetchSemanticsNode()
        assertTrue(set.isEmpty())
    }

    @Test
    fun favingAndUnfaving() {
        var stored = false
        val set = mutableListOf<Pair<String, Boolean>>()
        compose.setContent {
            HikerAtlasTheme {
                FaveButton(key, "Nugget Falls", FaveActions(
                    account(AccountState.In("did:plc:x", "alice.test", null)),
                    isFaved = { stored },
                    setFave = { k, on -> set += k to on; stored = on },
                ))
            }
        }
        compose.onNodeWithContentDescription("Save to your faves").performClick()
        compose.waitUntil(5_000) { set.size == 1 }
        compose.onNodeWithContentDescription("Remove from your faves").performClick()
        compose.waitUntil(5_000) { set.size == 2 }
        assertEquals(listOf(key to true, key to false), set)
        compose.onNodeWithContentDescription("Save to your faves").fetchSemanticsNode()
    }

    @Test
    fun alreadyFavedShowsSo() {
        compose.setContent {
            HikerAtlasTheme {
                FaveButton(key, "Nugget Falls", FaveActions(account(AccountState.In("did:plc:x", null, null)), isFaved = { true }, setFave = { _, _ -> }))
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Remove from your faves").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun aFaveThatFailsIsUndone() {
        compose.setContent {
            HikerAtlasTheme {
                FaveButton(key, "Nugget Falls", FaveActions(
                    account(AccountState.In("did:plc:x", null, null)),
                    isFaved = { false },
                    setFave = { _, _ -> throw IOException("no network") },
                ))
            }
        }
        compose.onNodeWithContentDescription("Save to your faves").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Save to your faves").fetchSemanticsNode()
    }

    @Test
    fun favedHikesNewestFirstEachOpeningItsHike() {
        val nugget = KeyedHike("200", "Juneau Trails", "200/day/1", "Nugget Falls")
        val opened = mutableListOf<KeyedHike>()
        val state = FavedState.Ready(
            listOf(Faves.Fave(key, "2026-11-14T12:00:00.000Z"), Faves.Fave("1E57E06gbDEBBBICDGF.tn3dBnAuR_Q", "2026-10-01T12:00:00.000Z")),
            found = mapOf(key to nugget),
        )
        compose.setContent {
            HikerAtlasTheme {
                LazyColumn { favedHikesSection(state, onOpen = { opened += it }, onRetry = {}) }
            }
        }
        compose.onNodeWithText("Faved hikes").fetchSemanticsNode()
        compose.onNodeWithText("Juneau Trails · Nov 14, 2026").fetchSemanticsNode()
        compose.onNodeWithText("A hike no longer on Hiker Atlas").performClick()
        compose.onNodeWithText("Oct 1, 2026").fetchSemanticsNode()
        assertTrue(opened.isEmpty())
        compose.onNodeWithText("Nugget Falls").performClick()
        assertEquals(listOf(nugget), opened)
    }

    @Test
    fun aFaveThatCouldNotBeSearchedIsNotSaidToBeGone() {
        val state = FavedState.Ready(
            listOf(Faves.Fave(key, "2026-11-14T12:00:00.000Z"), Faves.Fave("gone", "2026-10-01T12:00:00.000Z")),
            found = emptyMap(), unsure = setOf(key),
        )
        compose.setContent {
            HikerAtlasTheme { LazyColumn { favedHikesSection(state, onOpen = {}, onRetry = {}) } }
        }
        assertEquals(1, compose.onAllNodesWithText("A hike that couldn't be looked up").fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodesWithText("A hike no longer on Hiker Atlas").fetchSemanticsNodes().size)
    }

    @Test
    fun whileLookingAndWhenNone() {
        var state: FavedState by mutableStateOf(FavedState.Ready(listOf(Faves.Fave(key, "")), found = null))
        compose.setContent {
            HikerAtlasTheme { LazyColumn { favedHikesSection(state, onOpen = {}, onRetry = {}) } }
        }
        compose.onNodeWithText("Finding the hike…").fetchSemanticsNode()
        state = FavedState.Ready(emptyList())
        compose.onNodeWithText("No faved hikes yet.").fetchSemanticsNode()
        state = FavedState.Failed("HTTP 502 from com.atproto.repo.listRecords")
        compose.onNodeWithText("Couldn't load your faves: HTTP 502 from com.atproto.repo.listRecords").fetchSemanticsNode()
        compose.onNodeWithText("Try again").fetchSemanticsNode()
        assertEquals(0, compose.onAllNodesWithText("No faved hikes yet.").fetchSemanticsNodes().size)
    }

    @Test
    fun loadingLooksOnlyForHikesNotYetFound() = runBlocking {
        val nugget = KeyedHike("200", "Juneau Trails", "200/day/1", "Nugget Falls")
        val other = KeyedHike("100", "Parc du Lac", "100/base/0", "Camp Rouge")
        val faves = listOf(Faves.Fave(key, "2026-11-14T12:00:00.000Z"), Faves.Fave("b", "2026-11-01T12:00:00.000Z"))
        val asked = mutableListOf<Collection<String>>()
        val states = mutableListOf<FavedState>()
        loadFaved({ faves }, { ks -> asked += ks; KeyedSearch(mapOf("b" to other), emptySet()) }, known = mapOf(key to nugget), canFind = true) { states += it }
        assertEquals(listOf(listOf("b")), asked)
        assertEquals(listOf(FavedState.Ready(faves, null), FavedState.Ready(faves, mapOf(key to nugget, "b" to other))), states)

        // Unsure keys are passed on, and not kept as found.
        states.clear()
        loadFaved({ faves }, { KeyedSearch(emptyMap(), setOf("b")) }, known = mapOf(key to nugget), canFind = true) { states += it }
        assertEquals(FavedState.Ready(faves, mapOf(key to nugget), unsure = setOf("b")), states.last())

        // Everything already found: nothing looked for.
        states.clear()
        loadFaved({ faves }, { error("looked") }, known = mapOf(key to nugget, "b" to other), canFind = true) { states += it }
        assertEquals(listOf<FavedState>(FavedState.Ready(faves, mapOf(key to nugget, "b" to other))), states)

        // No H3 here, or the build's index can't be read: listed, but as not looked up.
        states.clear()
        loadFaved({ faves }, { throw IOException("offline") }, known = emptyMap(), canFind = true) { states += it }
        assertEquals(FavedState.Ready(faves, emptyMap(), canFind = false), states.last())

        states.clear()
        loadFaved({ throw IOException("HTTP 502") }, { KeyedSearch(emptyMap(), emptySet()) }, known = emptyMap(), canFind = true) { states += it }
        assertEquals(listOf<FavedState>(FavedState.Failed("HTTP 502")), states)
    }
}
