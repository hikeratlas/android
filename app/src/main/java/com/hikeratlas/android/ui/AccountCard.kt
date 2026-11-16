package com.hikeratlas.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.hikeratlas.android.atproto.Account
import com.hikeratlas.android.atproto.AccountState
import com.hikeratlas.android.atproto.AtConfig
import com.hikeratlas.android.atproto.SignInException
import com.hikeratlas.android.map.RemoteThumb
import com.hikeratlas.android.map.openUrl
import androidx.compose.ui.platform.LocalContext
import com.hikeratlas.android.routes.SITE
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** What the account card shows and does. */
class AccountActions(
    val state: AccountState,
    /** Starts signing in at Bluesky ([AtConfig.BLUESKY]) or a handle's PDS; gives the page to open. */
    val signIn: suspend (who: String) -> String,
    /** Opens the sign-in page (a Custom Tab). */
    val openPage: (String) -> Unit,
    val signOut: suspend () -> Unit,
    val clearError: () -> Unit = {},
)

/**
 * Signed out: a "Sign in" button. Signed in: the account's
 * picture, @handle, and Sign out. [title] null where a heading above says it.
 */
@Composable
internal fun AccountCard(account: AccountActions, title: String? = "Account") {
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf(false) }
    var signingOut by remember { mutableStateOf(false) }
    Card(title) {
        when (val s = account.state) {
            AccountState.Loading -> Row(Modifier.heightIn(min = 52.dp)) {}
            AccountState.SigningIn -> Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Signing in…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is AccountState.Out -> {
                // A primary button, so it reads as something to press.
                Button(
                    onClick = { account.clearError(); asking = true },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).height(48.dp),
                ) { Text("Sign in", fontWeight = FontWeight.SemiBold) }
                if (s.error != null) {
                    Text(s.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
                }
            }
            is AccountState.In -> Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(s.avatar, s.handle ?: "", 40)
                Text(
                    s.handle?.let { "@$it" } ?: s.did, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, modifier = Modifier.weight(1f).padding(start = 12.dp),
                )
                TextButton(onClick = {
                    signingOut = true
                    scope.launch {
                        try { account.signOut() } finally { signingOut = false }
                    }
                }, enabled = !signingOut) { Text(if (signingOut) "Signing out…" else "Sign out") }
            }
        }
    }
    if (asking) SignInDialog(account, onClose = { asking = false })
}

/**
 * An account's picture, as Bluesky shows one: a circle with a faint ring.
 * The handle's first letter on green without one, as the website's.
 */
@Composable
internal fun Avatar(url: String?, name: String, size: Int) {
    val shape = CircleShape
    val m = Modifier.size(size.dp).clip(shape).border(1.dp, Color.Black.copy(alpha = 0.1f), shape)
    if (url != null) {
        Box(m.background(MaterialTheme.colorScheme.surfaceVariant)) { RemoteThumb(url, Modifier.size(size.dp)) }
    } else {
        Box(m.background(Color(0xFF166534)), contentAlignment = Alignment.Center) {
            Text(name.removePrefix("@").take(1).uppercase().ifEmpty { "?" }, color = Color.White, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * The website's sign-in dialog: "Continue with Bluesky" (its page asks
 * who), or a handle for another account. [why] says what it's for, as
 * "Sign in to save X to your faves."
 */
@Composable
internal fun SignInDialog(account: AccountActions, onClose: () -> Unit, why: String? = null) {
    Dialog(onDismissRequest = onClose) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
            SignInForm(account, onClose, why)
        }
    }
}

/** The dialog's content (apart, to test: Robolectric never lets a text field in a dialog go idle). */
@Composable
internal fun SignInForm(account: AccountActions, onClose: () -> Unit, why: String? = null) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var other by remember { mutableStateOf(false) }
    var handle by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun go(who: String) {
        busy = true
        error = null
        scope.launch {
            try {
                val page = account.signIn(who)
                onClose()
                account.openPage(page)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                busy = false
                error = (e as? SignInException)?.message ?: Account.FAILED
            }
        }
    }
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Sign in", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
        // What signing in is for, when asked from something that needs it (a fave).
        if (why != null) Text(why, style = MaterialTheme.typography.bodyMedium)
        // The whole line opens the terms (the privacy policy is a
        // section of them): a LinkAnnotation per phrase would be
        // nicer, but never lets Robolectric's tests go idle. The
        // open-in-browser icon after the links says they're links. It's
        // beside the text, not inline content in it: an inline icon in
        // the dialog's window kept it laying out and drawing without end
        // (Robolectric never went idle).
        val link = SpanStyle(color = MaterialTheme.colorScheme.primary)
        Row(Modifier.clickable { openUrl(context, "$SITE/terms") }, verticalAlignment = Alignment.Bottom) {
            Text(
                buildAnnotatedString {
                    append("Use of Hiker Atlas is bound by our ")
                    withStyle(link) { append("terms of service") }
                    append(" and ")
                    withStyle(link) { append("privacy policy") }
                    append(".")
                },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(
                Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Opens in browser",
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, bottom = 2.dp).size(16.dp),
            )
        }
        Button(
            onClick = { go(AtConfig.BLUESKY) }, enabled = !busy,
            colors = ButtonDefaults.buttonColors(containerColor = BLUESKY_BLUE, contentColor = Color.White),
            shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().height(48.dp),
        ) { Text(if (busy && !other) "Going to Bluesky…" else "Continue with Bluesky", fontWeight = FontWeight.SemiBold) }
        if (other) {
            OutlinedTextField(
                handle, { handle = it }, label = { Text("Your handle") }, placeholder = { Text("you.example.com") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { if (handle.isNotBlank() && !busy) go(handle) }),
            )
            OutlinedButton(
                onClick = { go(handle) }, enabled = !busy && handle.isNotBlank(),
                shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { Text(if (busy) "Going to your account…" else "Continue") }
        }
        if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                if (other) {
                    Text("Any AT Protocol account works.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    TextButton(onClick = { other = true }) {
                        Text("Use a non-Bluesky account", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            TextButton(onClick = onClose) { Text("Cancel") }
        }
    }
}

private val BLUESKY_BLUE = Color(0xFF1185FE)
