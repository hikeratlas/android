package com.hikeratlas.android.logs

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import com.hikeratlas.android.ui.HikerAtlasTheme
import com.hikeratlas.android.ui.YouScreen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi", application = android.app.Application::class)
class LogsScreenTest {
    @get:Rule val compose = createComposeRule()

    @After
    fun tearDown() {
        Log.resetForTest()
        forgetFileProviderPaths()
    }

    // FileProvider keeps each authority's roots, as files, for the process;
    // Robolectric gives each test its own cache folder, so roots left from
    // this test would refuse the next test's files (GpxShareTest's).
    private fun forgetFileProviderPaths() {
        val f = FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
        synchronized(f.get(null)!!) { (f.get(null) as MutableMap<*, *>).clear() }
    }

    private val sample = listOf(
        LogEntry("2026-11-16 12:00:03.000", Level.ERROR, "outbox", "Upload stopped: FAILED"),
        LogEntry("2026-11-16 12:00:02.000", Level.WARN, "network", "Offline: lost"),
        LogEntry("2026-11-16 12:00:01.000", Level.INFO, "account", "Signed in as did:plc:abc"),
        LogEntry("2026-11-15 23:59:00.000", Level.DEBUG, "recording", "Fix 47.45,-121.80"),
    )

    private class Calls {
        var shares = 0
        var clears = 0
        val detailed = mutableListOf<Boolean>()
    }

    private fun show(entries: List<LogEntry> = sample): Calls {
        val calls = Calls()
        var current = entries
        compose.setContent {
            HikerAtlasTheme {
                LogsScreen(
                    LogsActions(
                        header = "Hiker Atlas 0.1.0 (abc1234)\nAndroid 15 (API 35)\nGoogle Pixel 8",
                        load = { current },
                        detailed = false,
                        setDetailed = { calls.detailed += it },
                        share = { calls.shares++ },
                        clear = {
                            calls.clears++
                            current = emptyList()
                        },
                    ),
                    onClose = {},
                )
            }
        }
        return calls
    }

    private fun top(text: String) = compose.onNode(hasText(text, substring = true)).fetchSemanticsNode().boundsInRoot.top

    @Test
    fun newestFirstAsTimeLevelMessageUnderTheHeader() {
        show()
        compose.onNodeWithText("Google Pixel 8", substring = true).fetchSemanticsNode()
        compose.onNodeWithText("12:00:03 · E · outbox: Upload stopped: FAILED").fetchSemanticsNode()
        val order = listOf("12:00:03 ·", "12:00:02 ·", "12:00:01 ·", "2026-11-15", "23:59:00 ·").map(::top)
        assertEquals(order.sorted(), order)
    }

    @Test
    fun theFilterShowsALevelAndAbove() {
        show()
        compose.onNodeWithText("Warning+").performClick()
        compose.onNodeWithText("Upload stopped", substring = true).fetchSemanticsNode()
        compose.onNodeWithText("Offline: lost", substring = true).fetchSemanticsNode()
        compose.onNodeWithText("Signed in", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Fix 47.45", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Error+").performClick()
        compose.onNodeWithText("Offline: lost", substring = true).assertDoesNotExist()
        compose.onNodeWithText("All").performClick()
        compose.onNodeWithText("Fix 47.45", substring = true).fetchSemanticsNode()
    }

    @Test
    fun detailedLoggingSwitch() {
        val calls = show()
        compose.onNodeWithTag("detailed").assertIsOff().performClick()
        compose.onNodeWithTag("detailed").assertIsOn()
        assertEquals(listOf(true), calls.detailed)
    }

    @Test
    fun shareAndClear() {
        val calls = show()
        compose.onNodeWithContentDescription("Share").performClick()
        assertEquals(1, calls.shares)

        compose.onNodeWithContentDescription("Clear").performClick()
        compose.onNodeWithText("Keep").performClick()
        assertEquals(0, calls.clears)
        compose.onNodeWithContentDescription("Clear").performClick()
        compose.onNodeWithText("Clear").performClick()
        compose.waitForIdle()
        assertEquals(1, calls.clears)
        compose.onNodeWithText("Nothing logged").fetchSemanticsNode()
    }

    @Test
    fun theLogIsSharedAsATextFileThroughTheProvider() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val stale = File(context.cacheDir, "logs/old.txt").apply { parentFile!!.mkdirs(); writeText("old") }
        val file = logsFile(context.cacheDir, "Hiker Atlas 0.1.0", "2026-11-16 12:00:00.000 I app: Started\n")!!
        assertTrue("only the latest is kept", !stale.exists())
        assertEquals("hiker-atlas-logs.txt", file.name)
        assertEquals("Hiker Atlas 0.1.0\n\n2026-11-16 12:00:00.000 I app: Started\n", file.readText())

        val chooser = logsShareIntent(context, file)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        @Suppress("DEPRECATION")
        val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals("content", uri.scheme)
        assertEquals("${context.packageName}.files", uri.authority)
        assertEquals(uri, send.clipData!!.getItemAt(0).uri)
        Robolectric.buildContentProvider(FileProvider::class.java).create(
            android.content.pm.ProviderInfo().apply {
                authority = "${context.packageName}.files"
                grantUriPermissions = true
                exported = false
            },
        )
        val read = context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
        assertEquals(file.readText(), read)
    }

    @Test
    fun theHeaderHasTheVersionAndroidAndPhone() {
        val h = deviceHeader().lines()
        assertTrue(h[0], h[0].startsWith("Hiker Atlas "))
        assertTrue(h[1], h[1].startsWith("Android "))
        assertEquals(3, h.size)
    }

    @Test
    fun yousAboutOpensLogs() {
        var opened = 0
        compose.setContent { HikerAtlasTheme { YouScreen(logs = { opened++ }) } }
        compose.onNodeWithText("Logs").performClick()
        assertEquals(1, opened)
    }
}
