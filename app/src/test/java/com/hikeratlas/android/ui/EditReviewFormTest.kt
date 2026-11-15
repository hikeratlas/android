package com.hikeratlas.android.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.hikeratlas.android.atproto.PostException
import com.hikeratlas.android.atproto.Posts
import com.hikeratlas.android.atproto.Profiles
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId

/** The review edit form: what it starts with, what it saves, its limits, and a failure keeping what was typed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h2000dp-xxhdpi", application = android.app.Application::class)
class EditReviewFormTest {
    @get:Rule val compose = createComposeRule()

    private val did = "did:plc:abcdefghijklmnopqrstuvwx"
    private val zone = ZoneId.of("America/Vancouver")
    // Nothing listens there: thumbnails just don't load.
    private val pds = "http://127.0.0.1:1"

    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject
    private fun blob(cid: String) = """{"${'$'}type":"blob","ref":{"${'$'}link":"$cid"},"mimeType":"image/jpeg","size":1}"""

    private fun review(license: String? = "CC-BY-4.0", hikedAt: String? = "2026-10-31T16:00:00.000Z"): Profiles.Review {
        val value = obj(
            """{"${'$'}type":"${Posts.REVIEW}","text":"Muddy","createdAt":"2026-11-01T10:00:00.000Z"
            ${hikedAt?.let { ""","hikedAt":"$it"""" } ?: ""}${license?.let { ""","license":"$it"""" } ?: ""},
            "tags":["mud"],"photos":[{"image":${blob("a")},"alt":"one"},{"image":${blob("b")}}]}""",
        )
        return Profiles.parseReview(obj("""{"uri":"at://$did/${Posts.REVIEW}/3abc","cid":"bafy-1","value":$value}"""))!!
    }

    private fun show(r: Profiles.Review, save: suspend (Posts.ReviewEdit) -> Unit, onClose: () -> Unit = {}) {
        compose.setContent {
            HikerAtlasTheme { EditReviewForm(r, pds, did, save, onClose, today = LocalDate.of(2026, 11, 15), zone = zone) }
        }
    }

    private fun waitFor(text: String) = compose.waitUntil(5_000) {
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    private val reviewField get() = compose.onNode(hasSetTextAction() and hasText("Muddy", substring = true))

    @Test
    fun startsFromTheReviewAndSavesWhatChanged() {
        var got: Posts.ReviewEdit? = null
        var closed = false
        val r = review()
        show(r, { got = it }, { closed = true })
        compose.onNodeWithText("Edit your review").assertExists()
        compose.onNodeWithText("Sat 31 Oct 2026").assertExists()
        compose.onNodeWithText("Mud").assertIsSelected()
        compose.onNodeWithText("What others may do with this review and its photos.").assertExists()
        compose.onNodeWithText("CC BY 4.0").assertIsSelected()

        reviewField.performTextClearance()
        compose.onNode(hasSetTextAction() and !hasContentDescription("Description", substring = true)).performTextInput("Muddy, then snow")
        compose.onNodeWithText("Snow").performScrollTo().performClick()
        compose.onNode(hasContentDescription("Description of photo 1")).performTextClearance()
        compose.onNode(hasContentDescription("Description of photo 1")).performTextInput(" The bridge ")
        compose.onNodeWithContentDescription("Remove photo 2").performScrollTo().performClick()
        compose.onNodeWithText("Public domain (CC0)").performScrollTo().performClick()
        compose.onNodeWithText(
            "Applies to this review and its photos from now on. Anyone who already copied it under CC BY 4.0 keeps what that allowed.",
        ).assertExists()

        compose.onNodeWithText("Save").assertIsEnabled().performClick()
        compose.waitUntil(5_000) { closed }
        assertEquals(
            Posts.ReviewEdit(
                "Muddy, then snow", "2026-10-31T16:00:00.000Z", listOf("mud", "snow"),
                listOf(obj("""{"image":${blob("a")},"alt":"The bridge"}""")), "CC0-1.0",
            ),
            got,
        )
    }

    @Test
    fun unchangedItKeepsNoLicenceAndNoDate() {
        var got: Posts.ReviewEdit? = null
        show(review(license = null, hikedAt = null), { got = it })
        compose.onNodeWithText("Not given").assertExists()
        // Unlicensed, it reads as Hiker Atlas only.
        compose.onNodeWithText("Hiker Atlas only").assertIsSelected()
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5_000) { got != null }
        assertNull(got!!.license)
        assertNull(got!!.hikedAt)
        assertEquals(listOf("mud"), got!!.tags)
        // Photos as they were: the one without a description still without.
        assertEquals(listOf(obj("""{"image":${blob("a")},"alt":"one"}"""), obj("""{"image":${blob("b")}}""")), got!!.photos)
    }

    @Test
    fun aClearedDateIsntSaved() {
        var got: Posts.ReviewEdit? = null
        show(review(), { got = it })
        compose.onNodeWithText("Clear").performClick()
        compose.onNodeWithText("Not given").assertExists()
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5_000) { got != null }
        assertNull(got!!.hikedAt)
    }

    @Test
    fun aFailureSaysWhatFailedAndKeepsWhatWasTyped() {
        var closed = false
        show(review(), { throw PostException(Posts.EDIT_CHANGED, IOException()) }, { closed = true })
        reviewField.performTextInput("Edited ")
        compose.onNodeWithText("Save").performClick()
        waitFor(Posts.EDIT_CHANGED)
        compose.onNode(hasText("Edited Muddy")).assertExists()
        assertFalse(closed)
        compose.onNodeWithText("Save").assertIsEnabled()
    }

    @Test
    fun theLimitsAreThePostForms() {
        show(review(), {})
        reviewField.performTextInput("x".repeat(Posts.MAX_TEXT))
        waitFor("${Posts.MAX_TEXT + 5} / ${Posts.MAX_TEXT}")
        compose.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun atMostTenTags() {
        show(review(), {})
        // Mud is on already: nine more.
        for ((_, name) in Posts.TAGS.filter { it.first != "mud" }.take(Posts.MAX_TAGS - 1)) {
            compose.onNodeWithText(name).performScrollTo().performClick()
        }
        compose.onNodeWithText("Up to ${Posts.MAX_TAGS} tags.").assertExists()
        val off = Posts.TAGS.filter { it.first != "mud" }[Posts.MAX_TAGS - 1].second
        compose.onNodeWithText(off).assertIsNotEnabled()
    }

    @Test
    fun emptyTextCantBeSaved() {
        show(review(), {})
        reviewField.performTextClearance()
        compose.onNodeWithText("Save").assertIsNotEnabled()
    }
}
