package com.hikeratlas.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hikeratlas.android.ui.AppScreen
import com.hikeratlas.android.ui.HikerAtlasTheme

class MainActivity : ComponentActivity() {
    /** A website link to open (from the site's "Open in app", or a shared link), until the map has. */
    private var link by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as HikerAtlasApp
        // Back from signing in, even into a recreated activity (the app was
        // killed while the browser was open): Account ignores a redirect
        // it's already had.
        signInCallback(intent)
        // Not again when the activity is recreated: the map already moved.
        if (savedInstanceState == null) link = linkOf(intent)
        setContent {
            HikerAtlasTheme {
                AppScreen(app, link = link, onLinkHandled = { link = null })
            }
        }
    }

    // The app was already open (it's singleTask): the link comes here.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        signInCallback(intent)
        linkOf(intent)?.let { link = it }
    }

    private fun viewed(intent: Intent?): Uri? = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data

    /** A website link: anything viewed but the sign-in redirect. */
    private fun linkOf(intent: Intent?): Uri? = viewed(intent)?.takeIf { it.scheme == "https" }

    private fun signInCallback(intent: Intent?) {
        val uri = viewed(intent)?.toString() ?: return
        val account = (application as HikerAtlasApp).account
        if (account.isCallback(uri)) account.completeSignIn(uri)
    }
}
