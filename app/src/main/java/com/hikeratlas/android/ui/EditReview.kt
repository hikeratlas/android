package com.hikeratlas.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hikeratlas.android.atproto.Indexer
import com.hikeratlas.android.atproto.Photos
import com.hikeratlas.android.atproto.Posts
import com.hikeratlas.android.atproto.Profiles
import com.hikeratlas.android.atproto.SignedOutException
import com.hikeratlas.android.map.RemoteThumb
import com.hikeratlas.android.map.Thumbs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** A review's licence as the edit form names it: the post form's label, capitalised (an unknown one as it is). */
internal fun licenseName(l: String) = Posts.LICENSE_LABEL[l]?.replaceFirstChar { it.uppercase() } ?: l

/** What the edit form says under the licences: what changing it means, once it's changed (the website's dialog). */
internal fun licenseNote(was: String, now: String) =
    if (now != was) {
        "Applies to this review and its photos from now on. Anyone who already copied it under ${licenseName(was)} keeps what that allowed."
    } else {
        "What others may do with this review and its photos."
    }

/** A photo entry's image CID, for its thumbnail; null if it has none. */
private fun cidOf(entry: JsonObject): String? =
    ((entry["image"] as? JsonObject)?.get("ref") as? JsonObject)?.get("\$link")?.let { it as? JsonPrimitive }?.takeIf { it.isString }?.content

/**
 * Editing a review you posted, full screen, as the website's
 * EditReviewDialog: can't be dismissed while saving.
 */
@Composable
fun EditReviewDialog(review: Profiles.Review, pds: String, did: String, save: suspend (Posts.ReviewEdit) -> Unit, onClose: () -> Unit) {
    var busy by remember { mutableStateOf(false) }
    Dialog(
        onDismissRequest = { if (!busy) onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            EditReviewForm(review, pds, did, save, onClose, onBusy = { busy = it })
        }
    }
}

/**
 * The edit form (apart, to test: Robolectric never lets a text field in a
 * dialog go idle): its words, when you hiked it, its tags, its photos'
 * descriptions (or removing photos), and its licence, with the post form's
 * limits. [save] throws saying what failed; what was typed stays. When it
 * was posted, its hike and its track aren't offered: they stay as they were.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun EditReviewForm(
    review: Profiles.Review,
    pds: String,
    did: String,
    save: suspend (Posts.ReviewEdit) -> Unit,
    onClose: () -> Unit,
    onBusy: (Boolean) -> Unit = {},
    today: LocalDate = LocalDate.now(),
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val scope = rememberCoroutineScope()
    val v = review.value
    val wasDay = remember(review.uri) { review.hikedAt?.let { Posts.localDay(it, zone) } }
    var day by remember(review.uri) { mutableStateOf(wasDay) }
    // The record's own text and tags, not the profile's (cut to the limits for showing): nothing is lost unseen.
    var text by remember(review.uri) { mutableStateOf((v["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: review.text) }
    val tags = remember(review.uri) {
        val raw = (v["tags"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } ?: review.tags
        mutableStateListOf<String>().apply { addAll(raw) }
    }
    // Each photo's entry as it is in the record, and its description as typed.
    val photos = remember(review.uri) {
        mutableStateListOf<Pair<JsonObject, String>>().apply {
            addAll(Posts.photoEntries(v).map { it to ((it["alt"] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: "") })
        }
    }
    // A review without one is Hiker Atlas only (the lexicon's default).
    val was = review.license ?: "hikeratlas-only"
    var license by remember(review.uri) { mutableStateOf(was) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }

    val canSave = text.trim().isNotEmpty() && text.length <= Posts.MAX_TEXT && tags.size <= Posts.MAX_TAGS && !busy
    fun submit() {
        error = null
        busy = true
        onBusy(true)
        val edit = Posts.ReviewEdit(
            text = text.trim(),
            // Unchanged, it stays exactly as it was (none, or one this app can't read as a day).
            hikedAt = if (day == wasDay) review.hikedAt else Posts.editedHikedAt(review.hikedAt, day, zone),
            tags = tags.toList(),
            photos = photos.map { (entry, alt) -> Posts.withAlt(entry, alt) },
            // Written only when changed, so a review without one stays without.
            license = if (license != was) license else review.license,
        )
        scope.launch {
            try {
                save(edit)
                onClose()
            } catch (e: CancellationException) {
                throw e
            } catch (_: SignedOutException) {
                error = "You're signed out. Sign in again (You tab) to edit; what you wrote is kept here."
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            } finally {
                busy = false
                onBusy(false)
            }
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("Edit your review", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Label("When you hiked it")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { picking = true }, enabled = !busy) { Text(day?.let(FORM_DAY::format) ?: "Not given") }
                    if (day != null) TextButton(onClick = { day = null }, enabled = !busy) { Text("Clear") }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Label("Review")
                OutlinedTextField(
                    text, { text = it.take(Posts.MAX_TEXT + 500) },
                    minLines = 5, enabled = !busy, isError = text.length > Posts.MAX_TEXT,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (text.length > Posts.MAX_TEXT * 0.9) {
                    Text(
                        "${text.length} / ${Posts.MAX_TEXT}", style = MaterialTheme.typography.bodySmall,
                        color = if (text.length > Posts.MAX_TEXT) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Label("Conditions and highlights")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((k, name) in Posts.TAGS) {
                        val on = k in tags
                        FilterChip(
                            selected = on,
                            onClick = { if (on) tags.remove(k) else tags.add(k) },
                            label = { Text(name) },
                            enabled = !busy && (on || tags.size < Posts.MAX_TAGS),
                        )
                    }
                }
                if (tags.size >= Posts.MAX_TAGS) Hint("Up to ${Posts.MAX_TAGS} tags.")
            }

            if (photos.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Label("Photos")
                    photos.forEachIndexed { i, (entry, alt) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            val shape = RoundedCornerShape(8.dp)
                            Box(Modifier.size(56.dp).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant)) {
                                cidOf(entry)?.let {
                                    RemoteThumb(Indexer.photoUrl(did, it), Modifier.fillMaxSize(), Thumbs.GRID_PX, fallback = Profiles.blobUrl(pds, did, it))
                                }
                            }
                            OutlinedTextField(
                                alt, { a -> photos[i] = entry to a.take(Photos.MAX_ALT) },
                                placeholder = { Text("Describe it (for people who can't see it)") },
                                singleLine = true, enabled = !busy,
                                modifier = Modifier.weight(1f).padding(start = 8.dp).semantics { contentDescription = "Description of photo ${i + 1}" },
                            )
                            IconButton(onClick = { photos.removeAt(i) }, enabled = !busy) {
                                Icon(Icons.Filled.Close, contentDescription = "Remove photo ${i + 1}")
                            }
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Label("Licence")
                for (l in Posts.LICENSES) {
                    Row(
                        Modifier.fillMaxWidth().selectable(license == l, enabled = !busy, role = Role.RadioButton) { license = l }
                            .heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = license == l, onClick = null, enabled = !busy, modifier = Modifier.padding(end = 8.dp))
                        Text(licenseName(l), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Hint(licenseNote(was, license))
            }

            Hint("The edit replaces the review in your account. Apps that copied it while it was public may keep the old version.")
            error?.let { ErrorBox(it) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onClose, enabled = !busy) { Text("Cancel") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = ::submit, enabled = canSave) { Text(if (busy) "Saving…" else "Save") }
        }
    }

    if (picking) {
        val todayMillis = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (day ?: today).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= todayMillis
                override fun isSelectableYear(year: Int) = year <= today.year
            },
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { day = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    picking = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
}
