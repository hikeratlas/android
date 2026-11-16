package com.hikeratlas.android.ui

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import com.hikeratlas.android.atproto.Profiles
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/** The photo viewer: which photo of how many, its caption and licence, Show on map, Download, and closing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi", application = android.app.Application::class)
class PhotoViewerTest {
    @get:Rule val compose = createComposeRule()

    private val did = "did:plc:abcdefghijklmnopqrstuvwx"
    private fun photo(cid: String, alt: String? = null, lat: Double? = null, lon: Double? = null) =
        Profiles.Photo(Profiles.Blob(cid, "image/jpeg"), alt, lat = lat, lon = lon)

    private val three = listOf(
        photo("bafk-falls", "The falls"),
        photo("bafk-bare"),
        photo("bafk-lake", "The lake", lat = 47.512345, lon = -121.734567),
    )

    private fun set(photos: List<Profiles.Photo> = three, start: Int = 0, license: String? = "CC-BY-4.0", handle: String? = "alice.test") =
        PhotoSet("https://pds.example", did, handle, license, photos, start)

    private var open by mutableStateOf(true)
    private lateinit var back: OnBackPressedDispatcher

    private fun show(
        s: PhotoSet = set(),
        pins: MutableList<PhotoPin> = mutableListOf(),
        download: suspend (String, Profiles.Photo) -> String? = { _, _ -> null },
    ) {
        compose.setContent {
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            HikerAtlasTheme {
                if (open) PhotoViewer(s, PhotoViewerActions(download, showOnMap = { pins += it }), onClose = { open = false })
            }
        }
    }

    private fun count(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().size

    private fun swipe() {
        compose.onNodeWithTag("photo-pager").performTouchInput { swipeLeft() }
        compose.waitForIdle()
    }

    @Test
    fun swipingTurnsThePhotoAndItsCaption() {
        show()
        compose.onNodeWithText("1 / 3").assertIsDisplayed()
        compose.onNodeWithText("The falls").assertIsDisplayed()
        compose.onNodeWithText("CC BY 4.0 · @alice.test").assertIsDisplayed()
        swipe()
        compose.onNodeWithText("2 / 3").assertIsDisplayed()
        // No caption on this one.
        assertEquals(0, count("The falls"))
        swipe()
        compose.onNodeWithText("3 / 3").assertIsDisplayed()
        compose.onNodeWithText("The lake").assertIsDisplayed()
    }

    @Test
    fun opensAtTheOneTapped() {
        show(set(start = 2))
        compose.onNodeWithText("3 / 3").assertIsDisplayed()
        compose.onNodeWithText("The lake").assertIsDisplayed()
    }

    @Test
    fun oneAloneHasNoCount() {
        show(set(listOf(photo("bafk-one"))))
        compose.onNodeWithTag("photo-viewer").assertIsDisplayed()
        assertEquals(0, count("1 / 1"))
    }

    @Test
    fun noLicenceIsHikerAtlasOnlyAndNoHandleIsTheDid() {
        show(set(license = null, handle = null))
        compose.onNodeWithText("Hiker Atlas only · did:plc:abcdefgh…").assertIsDisplayed()
    }

    @Test
    fun theLicenceIsThePosts() {
        show(set(license = "CC0-1.0"))
        compose.onNodeWithText("public domain (CC0) · @alice.test").assertIsDisplayed()
    }

    @Test
    fun showOnMapOnlyWithALocation() {
        val pins = mutableListOf<PhotoPin>()
        show(pins = pins)
        assertEquals(0, count("Show on map"))
        swipe()
        assertEquals(0, count("Show on map"))
        swipe()
        compose.onNodeWithText("Show on map").performClick()
        assertEquals(
            listOf(PhotoPin(47.512345, -121.734567, Profiles.blobUrl("https://pds.example", did, "bafk-lake"), "The lake")),
            pins.toList(),
        )
    }

    @Test
    fun downloadSavesTheFullSizePhotoAndSaysWhere() {
        val asked = mutableListOf<Pair<String, String>>()
        show(set(start = 1), download = { url, p -> asked += url to p.image.cid; "Saved to Pictures/Hiker Atlas" })
        compose.onNodeWithContentDescription("Download").performClick()
        compose.waitUntil(5_000) { count("Saved to Pictures/Hiker Atlas") > 0 }
        assertEquals(listOf(Profiles.blobUrl("https://pds.example", did, "bafk-bare") to "bafk-bare"), asked.toList())
        // About that photo only.
        swipe()
        assertEquals(0, count("Saved to Pictures/Hiker Atlas"))
    }

    @Test
    fun aFailedDownloadSaysSo() {
        show(download = { _, _ -> throw IOException("HTTP 404") })
        compose.onNodeWithContentDescription("Download").performClick()
        compose.waitUntil(5_000) { count("Couldn't save the photo: HTTP 404") > 0 }
    }

    @Test
    fun closeCloses() {
        show()
        compose.onNodeWithContentDescription("Close").performClick()
        compose.waitForIdle()
        assertEquals(false, open)
    }

    @Test
    fun backCloses() {
        show()
        compose.runOnUiThread { back.onBackPressed() }
        compose.waitForIdle()
        assertEquals(false, open)
        assertEquals(0, count("1 / 3"))
    }

    @Test
    fun theMapsPanelForAPlaceClosesBack() {
        var closed = false
        compose.setContent {
            HikerAtlasTheme { PhotoPlacePanel(PhotoPin(47.5, -121.7, "https://pds.example/x", "The lake"), onClose = { closed = true }) }
        }
        compose.onNodeWithText("Where this photo was taken").assertIsDisplayed()
        compose.onNodeWithText("The lake").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close").performClick()
        assertEquals(true, closed)
    }
}
