package com.hikeratlas.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.hikeratlas.android.atproto.AccountState
import com.hikeratlas.android.atproto.AtConfig
import com.hikeratlas.android.atproto.SignInException
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi", application = android.app.Application::class)
class AccountCardTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun signInWithBlueskyOpensItsPage() {
        val asked = mutableListOf<String>()
        val opened = mutableListOf<String>()
        compose.setContent {
            HikerAtlasTheme {
                YouScreen(account = AccountActions(
                    AccountState.Out(),
                    signIn = { asked += it; "https://bsky.social/oauth/authorize?request_uri=x" },
                    openPage = { opened += it },
                    signOut = {},
                ))
            }
        }
        compose.onNodeWithText("Sign in").performClick()
        compose.onNodeWithText("Continue with Bluesky").performClick()
        compose.waitUntil(5_000) { opened.isNotEmpty() }
        assertEquals(listOf(AtConfig.BLUESKY), asked)
        assertEquals(listOf("https://bsky.social/oauth/authorize?request_uri=x"), opened)
        // The dialog's closed.
        assertEquals(0, compose.onAllNodesWithText("Continue with Bluesky").fetchSemanticsNodes().size)
    }

    // The form itself, not in its dialog: Robolectric never sees a text
    // field in a dialog's window go idle.
    @Test
    fun aHandleThatIsNotFound() {
        val asked = mutableListOf<String>()
        compose.setContent {
            HikerAtlasTheme {
                SignInForm(AccountActions(
                    AccountState.Out(),
                    signIn = { asked += it; throw SignInException("Couldn't find $it. Check the handle (e.g. you.example.com).") },
                    openPage = {},
                    signOut = {},
                ), onClose = {})
            }
        }
        compose.onNodeWithText("Use a non-Bluesky account").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("bob.example.com")
        compose.onNodeWithText("Continue").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Couldn't find bob.example.com. Check the handle (e.g. you.example.com).").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(listOf("bob.example.com"), asked)
        compose.onNodeWithText("Any AT Protocol account works.").fetchSemanticsNode()
    }

    @Test
    fun signedInShowsHandleAndSignsOut() {
        var state by mutableStateOf<AccountState>(AccountState.In("did:plc:abcdefghijklmnopqrstuvwx", "alice.bsky.social", null))
        compose.setContent {
            HikerAtlasTheme {
                Column { AccountCard(AccountActions(state, signIn = { "" }, openPage = {}, signOut = { state = AccountState.Out() })) }
            }
        }
        compose.onNodeWithText("@alice.bsky.social").fetchSemanticsNode()
        compose.onNodeWithText("A").fetchSemanticsNode() // no picture: the handle's first letter
        compose.onNodeWithText("Sign out").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Sign in").fetchSemanticsNodes().isNotEmpty() }
    }

    /** The card signed out and in, and the sign-in form, to look at (SCREENSHOTS=1, as SheetScreenshots). */
    @Test
    fun screenshots() {
        org.junit.Assume.assumeTrue(System.getenv("SCREENSHOTS") == "1")
        var state by mutableStateOf<AccountState>(AccountState.Out())
        var form by mutableStateOf(false)
        compose.setContent {
            HikerAtlasTheme {
                val actions = AccountActions(state, signIn = { "" }, openPage = {}, signOut = {})
                if (form) {
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.padding(24.dp)) {
                        androidx.compose.material3.Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)) { SignInForm(actions, onClose = {}) }
                    }
                } else {
                    YouScreen(account = actions)
                }
            }
        }
        fun shot(name: String) {
            compose.waitForIdle()
            val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
            val out = java.io.File("build/screenshots/$name.png").apply { parentFile!!.mkdirs() }
            out.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        shot("account-out")
        state = AccountState.Out("Signing in didn't work. Try again?")
        shot("account-error")
        state = AccountState.In("did:plc:abcdefghijklmnopqrstuvwx", "alice.bsky.social", null)
        shot("account-in")
        form = true
        shot("account-sign-in")
        compose.onNodeWithText("Use a non-Bluesky account").performClick()
        shot("account-sign-in-handle")
    }
}
