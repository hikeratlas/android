package com.hikeratlas.android.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.hikeratlas.android.track.StepsPermission
import com.hikeratlas.android.track.StepsSetting
import com.hikeratlas.android.track.canCountSteps
import com.hikeratlas.android.track.hasStepCounter

/**
 * What the steps switch shows and does. The asking (Android's permission
 * question, the phone's settings) comes in from outside, so a test can
 * stand in for it.
 */
class StepsActions(
    /** Whether the phone has a step counter: without one, there's no switch. */
    val available: Boolean,
    /** The setting: not turned off (never chosen is on). */
    val setting: () -> Boolean,
    /** Whether the app may count steps (always, before Android 10). */
    val permitted: () -> Boolean,
    /** Keeps the choice, and tells a track being recorded. */
    val set: (Boolean) -> Unit,
    /** Asks Android for the permission; [done] hears whether it was given. */
    val request: (done: (Boolean) -> Unit) -> Unit,
    /** After a refusal: whether Android would ask again. Not, it's for the phone's settings. */
    val canAskAgain: () -> Boolean,
    val openSettings: () -> Unit,
    /** The permission may have changed (given, or back from the phone's settings): tells a track being recorded. */
    val recheck: () -> Unit,
)

/**
 * Counting steps while recording, on or off: this phone's setting, shown
 * signed in or not. On only where it's on and allowed, so the switch says
 * whether steps will be counted; read again on coming back to the app, as
 * the permission can be changed in the phone's settings meanwhile.
 */
@Composable
internal fun StepsCard(steps: StepsActions) {
    if (!steps.available) return
    fun effective() = steps.setting() && steps.permitted()
    var on by remember { mutableStateOf(effective()) }
    var offerSettings by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        on = effective()
        steps.recheck()
    }
    fun turn(want: Boolean) {
        steps.set(want)
        if (!want || steps.permitted()) {
            on = want
            return
        }
        steps.request { granted ->
            on = effective()
            if (granted) steps.recheck() else if (!steps.canAskAgain()) offerSettings = true
        }
    }
    Card("Recording") {
        Row(
            Modifier.fillMaxWidth().toggleable(value = on, role = Role.Switch, onValueChange = ::turn)
                .heightIn(min = 52.dp).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Count steps while recording", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Steps from your phone's step counter, shown with your tracks and posted with them. " +
                        "Tracks already recorded keep theirs.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = on, onCheckedChange = null)
        }
    }
    if (offerSettings) {
        AlertDialog(
            onDismissRequest = { offerSettings = false },
            title = { Text("Allow physical activity") },
            text = { Text("Android won't ask again. To count your steps, allow Physical activity for Hiker Atlas in the phone's settings.") },
            confirmButton = { TextButton(onClick = { offerSettings = false; steps.openSettings() }) { Text("Open settings") } },
            dismissButton = { TextButton(onClick = { offerSettings = false }) { Text("Not now") } },
        )
    }
}

/** The steps switch's actions, for real: the setting in prefs, Android's permission question, the app's system settings. */
@Composable
fun rememberStepsActions(onChanged: () -> Unit): StepsActions {
    val context = LocalContext.current
    val changed by rememberUpdatedState(onChanged)
    // The question's answer goes to whoever asked it.
    val waiting = remember { arrayOfNulls<(Boolean) -> Unit>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        waiting[0]?.invoke(granted)
        waiting[0] = null
    }
    return remember(context, launcher) {
        StepsActions(
            available = hasStepCounter(context),
            setting = { StepsSetting.on(context) },
            permitted = { canCountSteps(context) },
            set = { StepsSetting.set(context, it); changed() },
            request = { done ->
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    done(true)
                } else {
                    // Asked here, so not asked again as recording starts.
                    StepsPermission.asked(context)
                    waiting[0] = done
                    launcher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                }
            },
            canAskAgain = {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && context.activity()?.let {
                    ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.ACTIVITY_RECOGNITION)
                } == true
            },
            openSettings = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                )
            },
            recheck = { changed() },
        )
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
