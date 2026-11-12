package com.hikeratlas.android.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hikeratlas.android.routes.Regions
import com.hikeratlas.android.routes.StateCard
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi", application = android.app.Application::class)
class ExploreScreenTest {
    @get:Rule val compose = createComposeRule()

    private val regions = Regions(
        listOf(
            Regions.Country(
                "Canada", "canada", hikes = 9000, parks = 900,
                states = listOf(
                    Regions.State(
                        "British Columbia", "british-columbia", hikes = 4000, parks = 300,
                        areas = listOf(
                            Regions.Area("2226817", "Garibaldi Provincial Park", "garibaldi-provincial-park", hikes = 18),
                            Regions.Area("9", "Dundas Valley Conservation Area (near Hamilton)", "dundas", hikes = 1),
                        ),
                    ),
                    Regions.State("Alberta", "alberta", hikes = 2000, parks = 100),
                ),
            ),
            Regions.Country("United States", "united-states", hikes = 30000, parks = 4000),
        ),
    )

    @Test
    fun browsesCountriesStatesAndParks() {
        var path by mutableStateOf<List<String>>(emptyList())
        val opened = mutableListOf<Pair<String, String>>()
        compose.setContent {
            HikerAtlasTheme {
                ExploreScreen(
                    regions = { regions },
                    cards = { key -> if (key == "canada/british-columbia") mapOf("2226817" to StateCard("Garibaldi Lake", listOf("lakes"), "M0 0L100 0L50 100Z")) else emptyMap() },
                    path = path, onPath = { path = it }, onPark = { id, name -> opened += id to name },
                )
            }
        }
        compose.onNodeWithText("Hikes in North America").assertExists()
        compose.onNodeWithText("30,000").assertExists()
        compose.onNodeWithText("hikes in 4,000 parks").assertExists()
        compose.onNodeWithText("Most: British Columbia").assertExists()

        compose.onNodeWithText("Canada").performClick()
        compose.onNodeWithText("Hikes in Canada").assertExists()
        compose.onNodeWithText("Most: Garibaldi").assertExists()

        compose.onNodeWithText("British Columbia").performClick()
        assertEquals(listOf("canada", "british-columbia"), path)
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithText("18 hikes · top pick: Garibaldi Lake").assertExists() }.isSuccess }
        compose.onNodeWithText("Provincial park").assertExists()
        compose.onNodeWithText("lakes").assertExists()
        compose.onNodeWithText("near Hamilton").assertExists()
        compose.onNodeWithText("1 hike").assertExists()

        compose.onNodeWithText("Garibaldi").performClick()
        assertEquals(listOf("2226817" to "Garibaldi Provincial Park"), opened)

        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(listOf("canada"), path)
    }
}
