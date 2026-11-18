package com.hikeratlas.android.track

import org.junit.Assert.assertEquals
import org.junit.Test

/** The battery lines logged while recording (to learn how much recording uses). */
class BatteryLogTest {
    private var now = 0L
    private var reading = BatteryReading(90, charging = false, chargeUah = 4_000_000)
    private val lines = mutableListOf<String>()
    private val log = BatteryLog({ reading }, { now }, checkEveryMs = 60_000, log = { lines += it })

    private fun minutes(m: Int) { now += m * 60_000L }

    @Test
    fun startPauseResumeAndStopSayWhatWasUsedAndHowFast() {
        log.recording()
        assertEquals("Battery (recording): 90%, 4000 mAh, on battery, screen off", lines.last())
        minutes(30)
        reading = reading.copy(percent = 86, chargeUah = 3_800_000)
        log.paused()
        assertEquals("Battery (paused): 86%, 3800 mAh, on battery, screen off · −4% in 30 min recording (8.0%/h), −200 mAh", lines.last())
        // Paused time isn't recording time.
        minutes(60)
        reading = reading.copy(percent = 85, chargeUah = 3_760_000)
        log.recording()
        minutes(30)
        reading = reading.copy(percent = 82, chargeUah = 3_600_000, screenOn = true)
        log.stopped()
        assertEquals("Battery (stopped): 82%, 3600 mAh, on battery, screen on · −8% in 1 h 00 min recording (8.0%/h), −400 mAh", lines.last())
    }

    @Test
    fun aDropIsLoggedAtMostOnceAMinuteAndOnlyWhenTheLevelChanges() {
        log.recording()
        val n = lines.size
        minutes(1)
        log.check()
        assertEquals("same level: nothing", n, lines.size)
        reading = reading.copy(percent = 89)
        now += 10_000
        log.check()
        assertEquals("checked 10 s ago: not read again yet", n, lines.size)
        now += 50_000
        log.check()
        assertEquals(n + 1, lines.size)
        assertEquals(true, lines.last().startsWith("Battery: 89%"))
    }

    @Test
    fun nothingBeforeRecordingNorAfterItStops() {
        log.check()
        assertEquals(0, lines.size)
        log.recording()
        log.stopped()
        minutes(5)
        reading = reading.copy(percent = 50)
        log.check()
        assertEquals(2, lines.size)
    }

    @Test
    fun whileChargingNoDrainIsWorkedOutAndUnpluggedItCountsFromThere() {
        reading = reading.copy(charging = true)
        log.recording()
        assertEquals("Battery (recording): 90%, 4000 mAh, charging, screen off", lines.last())
        minutes(5)
        reading = reading.copy(percent = 91, charging = false, chargeUah = 4_050_000)
        log.check()
        minutes(30)
        reading = reading.copy(percent = 88, chargeUah = 3_900_000)
        log.check()
        assertEquals("Battery: 88%, 3900 mAh, on battery, screen off · −3% in 30 min recording (6.0%/h), −150 mAh", lines.last())
    }

    @Test
    fun aPhoneThatDoesntReportChargeGivesThePercentOnly() {
        reading = BatteryReading(70, charging = false, powerSave = true)
        log.recording()
        minutes(60)
        reading = reading.copy(percent = 64)
        log.stopped()
        assertEquals("Battery (stopped): 64%, on battery, screen off, battery saver · −6% in 1 h 00 min recording (6.0%/h)", lines.last())
    }
}

/** Reading the phone's battery: from the sticky battery broadcast, with no receiver kept. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35], application = android.app.Application::class)
class ReadBatteryTest {
    @Test
    fun readsTheLevelAndWhetherItsPluggedIn() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        context.sendStickyBroadcast(
            android.content.Intent(android.content.Intent.ACTION_BATTERY_CHANGED)
                .putExtra(android.os.BatteryManager.EXTRA_LEVEL, 37)
                .putExtra(android.os.BatteryManager.EXTRA_SCALE, 50)
                .putExtra(android.os.BatteryManager.EXTRA_PLUGGED, android.os.BatteryManager.BATTERY_PLUGGED_USB),
        )
        val r = readBattery(context)
        assertEquals(74, r.percent)
        assertEquals(true, r.charging)
    }
}
