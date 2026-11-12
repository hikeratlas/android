package com.hikeratlas.android.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi", application = android.app.Application::class)
class AboutCardTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun versionIsTheCommitDateAndHashLinkingToTheCommit() {
        val opened = mutableListOf<String>()
        compose.setContent {
            HikerAtlasTheme { VersionRow("50de746870b7112dfb4722b972e2762679cc8c88", "2026-11-12") { opened += it } }
        }
        compose.onNodeWithText("2026-11-12 · ").fetchSemanticsNode()
        compose.onNodeWithContentDescription("Opens in browser").fetchSemanticsNode()
        compose.onNodeWithText("50de746").performClick()
        assertEquals(listOf("https://github.com/hikeratlas/android/commit/50de746870b7112dfb4722b972e2762679cc8c88"), opened)
    }

    @Test
    fun aBuildOutsideGitSaysUnknown() {
        compose.setContent { HikerAtlasTheme { VersionRow("", "") {} } }
        compose.onNodeWithText("Unknown").fetchSemanticsNode()
    }

    @Test
    fun aboutHasTheSiteAndNoBlog() {
        compose.setContent { HikerAtlasTheme { YouScreen() } }
        compose.onNodeWithText("hikeratlas.com").fetchSemanticsNode()
        compose.onNodeWithText("Blog").assertDoesNotExist()
        compose.onNodeWithText("Hiker Atlas on the web").assertDoesNotExist()
    }
}
