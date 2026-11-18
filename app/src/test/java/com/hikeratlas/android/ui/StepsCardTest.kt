package com.hikeratlas.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hikeratlas.android.atproto.AccountState
import com.hikeratlas.android.units.Units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #98: the You tab's switch for counting steps while recording. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi", application = android.app.Application::class)
class StepsCardTest {
    @get:Rule val compose = createComposeRule()

    private val label = "Count steps while recording"

    /** A phone's steps, with Android's asking stood in for: [answer] is what the question gets. */
    private inner class Phone(
        var setting: Boolean = true,
        var permitted: Boolean = true,
        var answer: Boolean = true,
        var canAskAgain: Boolean = true,
        val available: Boolean = true,
    ) {
        val sets = mutableListOf<Boolean>()
        var requests = 0
        var openedSettings = 0
        var rechecks = 0

        fun actions() = StepsActions(
            available = available,
            setting = { setting },
            permitted = { permitted },
            set = { setting = it; sets += it },
            request = { done ->
                requests++
                permitted = answer
                done(answer)
            },
            canAskAgain = { canAskAgain },
            openSettings = { openedSettings++ },
            recheck = { rechecks++ },
        )
    }

    private fun show(phone: Phone) = compose.setContent { HikerAtlasTheme { StepsCard(phone.actions()) } }

    @Test
    fun itShowsWhetherStepsWillBeCounted() {
        // Turned on, but not allowed: off, as none would be counted.
        val phone = Phone(setting = true, permitted = false)
        show(phone)
        compose.onNodeWithText(label).assertIsOff()
        compose.onNodeWithText("Tracks already recorded keep theirs.", substring = true).assertExists()
    }

    @Test
    fun onAndAllowedItsOn() {
        show(Phone(setting = true, permitted = true))
        compose.onNodeWithText(label).assertIsOn()
    }

    @Test
    fun turnedOffItsOff() {
        show(Phone(setting = false, permitted = true))
        compose.onNodeWithText(label).assertIsOff()
    }

    @Test
    fun turningItOffTurnsStepsOff() {
        val phone = Phone()
        show(phone)
        compose.onNodeWithText(label).performClick()
        compose.onNodeWithText(label).assertIsOff()
        assertEquals(listOf(false), phone.sets)
        assertEquals(0, phone.requests)
    }

    @Test
    fun turningItOnWithoutThePermissionAsksForIt() {
        val phone = Phone(setting = false, permitted = false, answer = true)
        show(phone)
        compose.onNodeWithText(label).performClick()
        compose.onNodeWithText(label).assertIsOn()
        assertEquals(listOf(true), phone.sets)
        assertEquals(1, phone.requests)
        // A track being recorded hears of it.
        assertTrue(phone.rechecks >= 2)
        compose.onNodeWithText("Allow physical activity").assertDoesNotExist()
    }

    @Test
    fun turningItOnAndAllowedAlreadyDoesntAsk() {
        val phone = Phone(setting = false, permitted = true)
        show(phone)
        compose.onNodeWithText(label).performClick()
        compose.onNodeWithText(label).assertIsOn()
        assertEquals(0, phone.requests)
    }

    @Test
    fun refusedItStaysOffAndAsksAgainNextTime() {
        val phone = Phone(setting = false, permitted = false, answer = false, canAskAgain = true)
        show(phone)
        compose.onNodeWithText(label).performClick()
        compose.onNodeWithText(label).assertIsOff()
        compose.onNodeWithText("Allow physical activity").assertDoesNotExist()
        // Left on: what the switch shows is the permission's want.
        assertEquals(listOf(true), phone.sets)
    }

    @Test
    fun refusedForGoodItOffersThePhonesSettings() {
        val phone = Phone(setting = true, permitted = false, answer = false, canAskAgain = false)
        show(phone)
        compose.onNodeWithText(label).performClick()
        compose.onNodeWithText(label).assertIsOff()
        compose.onNodeWithText("Allow physical activity").assertExists()
        compose.onNodeWithText("Open settings").performClick()
        assertEquals(1, phone.openedSettings)
        compose.onNodeWithText("Allow physical activity").assertDoesNotExist()
    }

    @Test
    fun withoutAStepCounterTheresNoSwitch() {
        show(Phone(available = false))
        compose.onNodeWithText(label).assertDoesNotExist()
    }

    @Test
    fun itsOnTheYouTabSignedInAndOut() {
        val account = AccountActions(AccountState.Out(), signIn = { "" }, openPage = {}, signOut = {})
        var signedIn by mutableStateOf(false)
        compose.setContent {
            HikerAtlasTheme {
                if (signedIn) {
                    Column {
                        YouSettings(account, UnitsActions(Units.METRIC, signedIn = true, save = {}), null, null, steps = Phone().actions())
                    }
                } else {
                    YouScreen(account = account, units = UnitsActions(Units.METRIC, signedIn = false, save = {}), steps = Phone().actions())
                }
            }
        }
        compose.onNodeWithText(label).assertExists()
        signedIn = true
        compose.waitForIdle()
        compose.onNodeWithText(label).assertExists()
        compose.onNodeWithText("On this phone").assertExists()
    }
}
