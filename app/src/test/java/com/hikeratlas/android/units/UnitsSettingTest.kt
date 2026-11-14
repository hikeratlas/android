package com.hikeratlas.android.units

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hikeratlas.android.atproto.Account
import com.hikeratlas.android.atproto.AccountState
import com.hikeratlas.android.atproto.FakeAtproto
import com.hikeratlas.android.atproto.MemoryDpopKeys
import com.hikeratlas.android.atproto.MemorySecretStore
import com.hikeratlas.android.atproto.ProfileRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Which units show: the phone's when signed out, the profile record's when signed in, and where a change goes. */
@RunWith(RobolectricTestRunner::class)
// The application plain, not HikerAtlasApp: these tests make their own.
@Config(sdk = [35], application = android.app.Application::class)
class UnitsSettingTest {
    private lateinit var fake: FakeAtproto
    private val http = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val secrets = MemorySecretStore()
    private val keys = MemoryDpopKeys()
    private val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("units-test", Context.MODE_PRIVATE)
    private val imperial = Units(DistanceUnit.MI, ElevationUnit.FT)
    private val key = "${ProfileRecord.NSID}/self"

    @Before
    fun setUp() {
        fake = FakeAtproto().start()
        prefs.edit().clear().commit()
    }

    @After
    fun tearDown() {
        scope.cancel()
        fake.close()
        Units.current = Units.METRIC
    }

    private fun account() = Account(http, secrets, keys, fake.config(), scope)

    private fun signIn(account: Account) = runBlocking {
        account.beginSignIn(fake.authOrigin)
        account.finishSignIn(fake.callback(fake.pars.last()["state"]!!))
    }

    private fun await(what: String, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < end) {
            if (ok()) return
            Thread.sleep(10)
        }
        fail(what)
    }

    @Test
    fun signedOutIsThePhonesOwn() = runBlocking {
        val account = account()
        val setting = UnitsSetting(prefs, account, scope)
        await("signed out") { account.state.value is AccountState.Out }
        assertEquals("metric by default, as the website", Units.METRIC, Units.current)
        assertFalse(setting.signedIn)
        setting.set(imperial)
        assertEquals(imperial, Units.current)
        assertEquals("nothing asked of a PDS", 0, fake.pdsRequests.size)
        // Kept for the next launch.
        Units.current = Units.METRIC
        UnitsSetting(prefs, account(), scope)
        assertEquals(imperial, Units.current)
    }

    @Test
    fun signedInIsTheProfiles() = runBlocking {
        fake.records[key] = Json.parseToJsonElement(
            """{"${'$'}type":"${ProfileRecord.NSID}","createdAt":"2025-03-01T10:00:00.000Z","units":{"distance":"mi","elevation":"ft"},"license":"CC-BY-4.0"}""",
        ).jsonObject
        val account = account()
        val setting = UnitsSetting(prefs, account, scope)
        await("signed out") { account.state.value is AccountState.Out }
        setting.set(Units(DistanceUnit.KM, ElevationUnit.FT))
        signIn(account)
        await("the profile's units") { Units.current == imperial }

        // A change goes to the record, keeping its other fields.
        setting.set(Units(DistanceUnit.MI, ElevationUnit.M))
        assertEquals(Units(DistanceUnit.MI, ElevationUnit.M), Units.current)
        val stored = fake.records[key]!!
        assertEquals("""{"distance":"mi","elevation":"m"}""", stored["units"].toString())
        assertEquals("CC-BY-4.0", stored["license"]!!.jsonPrimitive.content)
        assertEquals("2025-03-01T10:00:00.000Z", stored["createdAt"]!!.jsonPrimitive.content)

        // Next launch, before the PDS answers: the profile's, as last read.
        fake.getRecordFails = 503
        Units.current = Units.METRIC
        val again = account()
        UnitsSetting(prefs, again, scope)
        await("the profile's units, kept") { Units.current == Units(DistanceUnit.MI, ElevationUnit.M) }

        // Signed out: back to the phone's own.
        again.signOut()
        await("the phone's units") { Units.current == Units(DistanceUnit.KM, ElevationUnit.FT) }
    }

    @Test
    fun aNewAccountsProfileTakesThePhonesUnits() {
        val account = account()
        val setting = UnitsSetting(prefs, account, scope)
        await("signed out") { account.state.value is AccountState.Out }
        runBlocking { setting.set(imperial) }
        signIn(account)
        await("the profile made") { fake.records[key] != null }
        // Chosen before signing in: kept, in the new record.
        assertEquals(setOf("\$type", "createdAt", "units"), fake.records[key]!!.keys)
        assertEquals("""{"distance":"mi","elevation":"ft"}""", fake.records[key]!!["units"].toString())
        Thread.sleep(200)
        assertEquals(imperial, Units.current)
    }
}
