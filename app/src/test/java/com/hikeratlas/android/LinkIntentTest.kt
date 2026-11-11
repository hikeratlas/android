package com.hikeratlas.android

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The website's links reach the app, as the site's "Open in app" sends them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LinkIntentTest {
    private fun handlers(url: String): List<String> {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // What Chrome makes of intent://...#Intent;scheme=https;package=...;end
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .setPackage(context.packageName)
        return context.packageManager.queryIntentActivities(intent, 0).map { it.activityInfo.name }
    }

    @Test
    fun hikePagesOpenTheApp() {
        val main = MainActivity::class.java.name
        assertEquals(listOf(main), handlers("https://www.hikeratlas.com/hikes/united-states/washington/raging-river-state-forest/upward-mobility"))
        assertEquals(listOf(main), handlers("https://hikeratlas.com/hikes/canada"))
    }

    @Test
    fun otherPagesDont() {
        assertTrue(handlers("https://www.hikeratlas.com/blog/arm-runners").isEmpty())
        assertTrue(handlers("https://example.com/hikes/canada").isEmpty())
        assertTrue(handlers("http://www.hikeratlas.com/hikes/canada").isEmpty())
    }
}
