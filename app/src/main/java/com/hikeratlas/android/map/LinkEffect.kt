package com.hikeratlas.android.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

/**
 * Opens each [link] once [ready], then calls [onHandled] (which clears it).
 *
 * Only after: the effect is keyed on the link, so clearing it first would
 * restart the effect and cancel [open] midway, e.g. while it loads the park.
 * A new link meanwhile does cancel it, and is opened instead.
 */
@Composable
fun <T : Any> LinkEffect(link: T?, ready: Boolean, onHandled: () -> Unit, open: suspend (T) -> Unit) {
    val handled by rememberUpdatedState(onHandled)
    val run by rememberUpdatedState(open)
    LaunchedEffect(link, ready) {
        val l = link ?: return@LaunchedEffect
        if (!ready) return@LaunchedEffect
        run(l)
        handled()
    }
}
