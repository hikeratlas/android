package com.hikeratlas.android.map

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A link is opened to the end, however long that takes, then cleared. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LinkEffectTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun aSlowOpenFinishesBeforeTheLinkIsCleared() {
        var link by mutableStateOf<String?>("/hikes/a")
        var ready by mutableStateOf(false)
        val loaded = CompletableDeferred<Unit>()
        val opened = mutableListOf<String>()
        compose.setContent {
            LinkEffect(link, ready, onHandled = { link = null }) { l ->
                loaded.await() // the park's routes, from the network
                opened += l
            }
        }
        compose.waitForIdle()
        // Not before the map is ready.
        assertEquals("/hikes/a", link)
        ready = true
        compose.waitForIdle()
        // Opening, not yet opened: the link stays until it is.
        assertEquals("/hikes/a", link)
        loaded.complete(Unit)
        compose.waitUntil(5_000) { opened.isNotEmpty() }
        compose.waitForIdle()
        assertEquals(listOf("/hikes/a"), opened)
        assertNull(link)
    }

    @Test
    fun aNewLinkReplacesOneStillOpening() {
        var link by mutableStateOf<String?>("/hikes/a")
        val gates = mapOf("/hikes/a" to CompletableDeferred<Unit>(), "/hikes/b" to CompletableDeferred())
        val opened = mutableListOf<String>()
        compose.setContent {
            LinkEffect(link, ready = true, onHandled = { link = null }) { l ->
                gates.getValue(l).await()
                opened += l
            }
        }
        compose.waitForIdle()
        link = "/hikes/b"
        compose.waitForIdle()
        gates.values.forEach { it.complete(Unit) }
        compose.waitUntil(5_000) { opened.isNotEmpty() }
        compose.waitForIdle()
        assertEquals(listOf("/hikes/b"), opened)
        assertNull(link)
    }
}
