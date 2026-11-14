package com.hikeratlas.android.units

import android.content.SharedPreferences
import androidx.core.content.edit
import com.hikeratlas.android.atproto.Account
import com.hikeratlas.android.atproto.AccountState
import com.hikeratlas.android.atproto.ProfileRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Keeps [Units.current] to the reader's units. Signed in, they're the
 * Hiker Atlas profile's (its record's `units`, as the website reads them),
 * so they're the same on the website and every phone; read at sign-in and
 * at launch, and kept here so the app shows them before the PDS answers.
 * Signed out, they're this phone's own setting, metric unless changed (as
 * the website's default); signing out goes back to it.
 */
class UnitsSetting(
    private val prefs: SharedPreferences,
    private val account: Account,
    scope: CoroutineScope,
) {
    /** The account whose profile's units are shown, once known to be signed in. */
    @Volatile private var shown: String? = null
    /** Changes saved to the profile, so a read that started before one doesn't undo it. */
    @Volatile private var saves = 0

    init {
        Units.current = local()
        scope.launch {
            account.state.collect { s ->
                when (s) {
                    is AccountState.In -> if (s.did != shown) {
                        shown = s.did
                        // Until the profile answers: what it said last time, if
                        // it was this account; else the phone's own.
                        Units.current = cached(s.did) ?: local()
                        launch { load(s.did) }
                    }
                    is AccountState.Out -> {
                        shown = null
                        prefs.edit { remove(PROFILE) }
                        Units.current = local()
                    }
                    // Loading or mid-sign-in: what's shown stays.
                    else -> {}
                }
            }
        }
    }

    /** Whether a change goes to the profile record (else it's this phone's). */
    val signedIn: Boolean get() = account.state.value is AccountState.In

    /**
     * Changes the units: signed in, in the profile record (keeping its
     * other fields), which throws if the PDS can't be reached; signed out,
     * on this phone.
     */
    suspend fun set(u: Units) {
        val did = (account.state.value as? AccountState.In)?.did
        if (did == null) {
            prefs.edit { putString(LOCAL, u.toJson().toString()) }
            Units.current = u
            return
        }
        ProfileRecord.saveSettings(account.xrpc, did, mapOf("units" to u.toJson()))
        saves++
        remember(did, u)
    }

    /** The profile record's units, written first if the account has none (a PDS that's down: next time). */
    private suspend fun load(did: String) {
        val before = saves
        val record = ProfileRecord.ensure(account.xrpc, did) ?: return
        // Changed here meanwhile: that's newer than what was read.
        if (saves == before) remember(did, Units.of(record["units"]))
    }

    private fun remember(did: String, u: Units) {
        if (shown != did) return
        val cache = JsonObject(mapOf("did" to JsonPrimitive(did), "units" to u.toJson()))
        prefs.edit { putString(PROFILE, cache.toString()) }
        Units.current = u
    }

    private fun local(): Units = Units.of(parse(prefs.getString(LOCAL, null)))

    private fun cached(did: String): Units? {
        val o = parse(prefs.getString(PROFILE, null)) as? JsonObject ?: return null
        return if ((o["did"] as? JsonPrimitive)?.content == did) Units.of(o["units"]) else null
    }

    private fun parse(s: String?) = s?.let { try { Json.parseToJsonElement(it) } catch (e: IllegalArgumentException) { null } }

    private companion object {
        /** The phone's own setting, for when signed out. */
        const val LOCAL = "local"
        /** The signed-in profile's, as last read: {did, units}. */
        const val PROFILE = "profile"
    }
}
