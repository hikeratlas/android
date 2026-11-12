package com.hikeratlas.android.offline

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hikeratlas.android.ui.HikerAtlasTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi", application = android.app.Application::class)
class OfflineUiTest {
    @get:Rule val compose = createComposeRule()

    private val park = OfflineAreas.Target(OfflineStore.PARK, "2226817", "Garibaldi Provincial Park")
    private fun area(state: String, done: Int = 0, total: Int = 0, bytes: Long = 0, error: String? = null) =
        OfflineStore.Area(1, OfflineStore.PARK, "2226817", "Garibaldi Provincial Park", 0, state, done, total, bytes, error)

    @Test
    fun theButtonOffersADownloadOrSaysWhereItsAt() {
        var area: OfflineStore.Area? = null
        var asked: OfflineAreas.Target? = null
        val actions = OfflineActions(areaFor = { area }, onDownload = { asked = it })
        compose.setContent { HikerAtlasTheme { OfflineButton(park, actions) } }
        compose.onNodeWithContentDescription("Download for offline use").performClick()
        assertEquals(park, asked)
    }

    @Test
    fun aDownloadedParksButtonSaysSo() {
        val actions = OfflineActions(areaFor = { area(OfflineStore.READY, bytes = 37_000_000) }, onDownload = { throw AssertionError() })
        compose.setContent { HikerAtlasTheme { OfflineButton(park, actions) } }
        compose.onNodeWithContentDescription("Downloaded for offline use").assertIsDisplayed()
    }

    @Test
    fun theDialogSaysTheSizeBeforeItDownloads() {
        val size = CompletableDeferred<Pair<Long, Long>>()
        var confirmed = false
        compose.setContent {
            HikerAtlasTheme { DownloadDialog(park, wifiOnly = true, estimate = { size.await() }, onConfirm = { confirmed = true }, onDismiss = {}) }
        }
        compose.onNodeWithText("Working out its size…").assertIsDisplayed()
        compose.onNodeWithText("Download").assertIsNotEnabled()
        size.complete(37_400_000L to 14_000_000L)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("About 37 MB: the map, terrain and routes.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("The first download also gets a 14 MB map of North America at small scale.").assertIsDisplayed()
        compose.onNodeWithText("It downloads on Wi-Fi, in the background.").assertIsDisplayed()
        compose.onNodeWithText("Download").assertIsEnabled().performClick()
        assertEquals(true, confirmed)
    }

    @Test
    fun statusesReadPlainly() {
        assertEquals("37 MB", areaStatus(area(OfflineStore.READY, bytes = 37_400_000), true))
        assertEquals("Downloading · 45%", areaStatus(area(OfflineStore.DOWNLOADING, 450, 1000), true))
        // Waiting: says why.
        assertEquals("Waiting for Wi-Fi", areaStatus(area(OfflineStore.QUEUED), true, Network.OTHER))
        assertEquals(
            "Waiting for Wi-Fi: this Wi-Fi is set as metered (or turn off Wi-Fi only)",
            areaStatus(area(OfflineStore.QUEUED), true, Network.METERED_WIFI),
        )
        assertEquals("Waiting to start", areaStatus(area(OfflineStore.QUEUED), true, Network.UNMETERED))
        assertEquals("Waiting to start", areaStatus(area(OfflineStore.QUEUED), false, Network.OTHER))
        assertEquals("Trying again soon: HTTP 500", areaStatus(area(OfflineStore.RETRYING, error = "HTTP 500"), true))
        assertEquals("Couldn't download: HTTP 500", areaStatus(area(OfflineStore.FAILED, error = "HTTP 500"), true))
        assertEquals("0.4 MB", megabytes(400_000))
    }

    @Test
    fun theSavedListRetriesAndDeletesAfterAsking() {
        var retried: Long? = null
        var deleted: Long? = null
        val failed = area(OfflineStore.FAILED, error = "HTTP 500")
        compose.setContent {
            HikerAtlasTheme {
                LazyColumn { offlineAreasSection(listOf(failed), true, onWifiOnly = {}, onRetry = { retried = it }, onDelete = { deleted = it }) }
            }
        }
        compose.onNodeWithText("Offline areas").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        assertEquals(1L, retried)
        compose.onNodeWithContentDescription("Delete Garibaldi Provincial Park").performClick()
        assertEquals(null, deleted)
        compose.onAllNodes(hasText("Delete") and hasClickAction()).onLast().performClick()
        assertEquals(1L, deleted)
    }

    @Test
    fun anAreaFromAnOlderMapOffersUpdate() {
        var updated: Long? = null
        compose.setContent {
            HikerAtlasTheme {
                LazyColumn {
                    offlineAreasSection(
                        listOf(area(OfflineStore.READY, bytes = 37_400_000)), true, {}, {}, {},
                        updateAvailable = { true }, onUpdate = { updated = it },
                    )
                }
            }
        }
        compose.onNodeWithText("37 MB · Map updated since").assertIsDisplayed()
        compose.onNodeWithText("Update").performClick()
        assertEquals(1L, updated)
    }

    @Test
    fun tappingAParkOrHikeOpensItButNotTheOverview() {
        val opened = mutableListOf<OfflineAreas.Target>()
        val hike = OfflineStore.Area(2, OfflineStore.HIKE, "2226817/garibaldi-lake", "Garibaldi Lake", 0, OfflineStore.READY, 0, 0, 0, null)
        val overview = OfflineStore.Area(3, OfflineStore.OVERVIEW, "", "North America overview", 0, OfflineStore.READY, 0, 0, 0, null)
        compose.setContent {
            HikerAtlasTheme {
                LazyColumn { offlineAreasSection(listOf(area(OfflineStore.READY), hike, overview), true, {}, {}, {}, onOpen = { opened += it }) }
            }
        }
        compose.onNodeWithText("Garibaldi Provincial Park").performClick()
        compose.onNodeWithText("Garibaldi Lake").performClick()
        compose.onNodeWithText("North America overview").performClick()
        assertEquals(
            listOf(
                OfflineAreas.Target(OfflineStore.PARK, "2226817", "Garibaldi Provincial Park"),
                OfflineAreas.Target(OfflineStore.HIKE, "2226817/garibaldi-lake", "Garibaldi Lake"),
            ),
            opened,
        )
    }

    @Test
    fun knowsWhenItsOfflineAndTheChipSaysSo() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        val shadow = org.robolectric.Shadows.shadowOf(cm)
        val wifi = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        org.robolectric.Shadows.shadowOf(wifi).addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadow.setNetworkCapabilities(cm.activeNetwork, wifi)
        var online: Boolean? = null
        compose.setContent {
            HikerAtlasTheme {
                val on = rememberOnline()
                online = on
                if (!on) OfflineChip()
            }
        }
        compose.waitForIdle()
        assertEquals(true, online)
        assertEquals(0, compose.onAllNodesWithText("Offline · downloaded areas").fetchSemanticsNodes().size)

        // Airplane mode: no network, and the callback says it's lost.
        val network = cm.activeNetwork!!
        shadow.setDefaultNetworkActive(false)
        shadow.setActiveNetworkInfo(null)
        shadow.networkCallbacks.forEach { it.onLost(network) }
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        compose.waitForIdle()
        assertEquals(false, online)
        compose.onNodeWithText("Offline · downloaded areas").assertIsDisplayed()
    }
}
