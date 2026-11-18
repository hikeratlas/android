package com.hikeratlas.android.track

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSensor
import org.robolectric.shadows.SensorEventBuilder

/**
 * A step counter as the recorder sees one: readings since the phone
 * started, some held back (batched, with the screen off) until flushed.
 */
class FakeSteps(var available: Boolean = true) : StepSource {
    private var onCount: ((Long) -> Unit)? = null
    /** Readings the counter holds back until it's flushed. */
    val held = mutableListOf<Long>()
    var starts = 0
    /** Flushes waiting to be done, when [slowFlush]: as a phone with the screen off takes its time. */
    var slowFlush = false
    val flushing = mutableListOf<() -> Unit>()

    val listening get() = onCount != null

    override fun start(onCount: (Long) -> Unit): Boolean {
        if (!available) return false
        starts++
        this.onCount = onCount
        return true
    }

    override fun flush(done: () -> Unit) {
        if (slowFlush) return run { flushing += done }
        held.forEach { onCount?.invoke(it) }
        held.clear()
        done()
    }

    override fun stop() {
        onCount = null
    }

    /** Finishes the flushes waiting: what was held back delivered first. */
    fun finishFlushes() {
        held.forEach { onCount?.invoke(it) }
        held.clear()
        flushing.toList().also { flushing.clear() }.forEach { it() }
    }

    /** A reading delivered now (dropped if not listened to, as Android would). */
    fun read(value: Long) {
        onCount?.invoke(value)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class StepsTest {
    private val store = TrackStore(ApplicationProvider.getApplicationContext(), name = null)
    private var now = 1_000_000L

    /** The You tab's switch, as the recorder asks it. */
    private var stepsOn = true

    @After
    fun tearDown() = store.close()

    private fun recorder(steps: StepSource?) =
        TrackRecorder(store, null, clock = { now }, background = { it.run() }, steps = steps, countSteps = { stepsOn })

    @Test
    fun stepsTurnedOffMidTrackKeepWhatWasCountedAndCountAgainWhenTurnedOn() {
        val counter = FakeSteps()
        val r = recorder(counter)
        val id = r.start().trackId
        counter.read(1_000)
        counter.read(1_100)
        stepsOn = false
        counter.held += 1_150
        r.stepsSettingChanged()
        // What the counter held back was walked before: counted, then no more.
        assertEquals(150L, r.state!!.steps)
        assertFalse(counter.listening)
        assertEquals(150L, store.track(id)!!.steps)
        assertNull(store.track(id)!!.stepsBase)
        counter.read(1_400)
        // Turned off again: nothing to do.
        r.stepsSettingChanged()
        assertEquals(1, counter.starts)
        stepsOn = true
        r.stepsSettingChanged()
        assertTrue(counter.listening)
        counter.read(2_000) // where it got to meanwhile: not counted
        counter.read(2_050)
        assertEquals(200L, r.state!!.steps)
        // Turned on again while counting: no new base, nothing lost.
        r.stepsSettingChanged()
        counter.read(2_060)
        assertEquals(210L, r.state!!.steps)
        r.stop()
        assertEquals(210L, store.track(id)!!.steps)
    }

    @Test
    fun aTrackStartedWithStepsOffStaysWithoutWhenTheyreTurnedOn() {
        stepsOn = false
        val counter = FakeSteps()
        val r = recorder(counter)
        val id = r.start().trackId
        assertNull(r.state!!.steps)
        assertEquals(0, counter.starts)
        stepsOn = true
        r.stepsSettingChanged()
        r.pause()
        r.resume()
        counter.read(100)
        counter.read(200)
        assertNull(r.state!!.steps)
        assertEquals(0, counter.starts)
        r.stop()
        assertNull(store.track(id)!!.steps)
    }

    @Test
    fun resumedWithStepsOffItDoesntCount() {
        val counter = FakeSteps()
        val r = recorder(counter)
        val id = r.start().trackId
        counter.read(100)
        counter.read(160)
        r.pause()
        stepsOn = false
        // Paused: nothing counting to stop.
        r.stepsSettingChanged()
        r.resume()
        assertFalse(counter.listening)
        counter.read(900)
        assertEquals(60L, r.state!!.steps)
        // Turned on while paused: counted again only once resumed.
        r.pause()
        stepsOn = true
        r.stepsSettingChanged()
        assertFalse(counter.listening)
        r.resume()
        counter.read(1_000)
        counter.read(1_040)
        r.stop()
        assertEquals(100L, store.track(id)!!.steps)
    }

    @Test
    fun turnedBackOnBeforeTheCounterFlushedItCountsOn() {
        val counter = FakeSteps().apply { slowFlush = true }
        val r = recorder(counter)
        val id = r.start().trackId
        counter.read(500)
        counter.read(520)
        stepsOn = false
        r.stepsSettingChanged()
        // On again before the flush is done: counting on, not stopped when it is.
        stepsOn = true
        r.stepsSettingChanged()
        counter.finishFlushes()
        assertTrue(counter.listening)
        counter.read(530)
        counter.read(560)
        assertEquals(50L, r.state!!.steps)
        counter.slowFlush = false
        r.stop()
        assertEquals(50L, store.track(id)!!.steps)
    }

    @Test
    fun stepsAreCountedOnlyWhileRecording() {
        val counter = FakeSteps()
        val r = recorder(counter)
        val id = r.start().trackId
        // Not counted until the counter gives a reading.
        assertNull(r.state!!.steps)
        assertNull(store.track(id)!!.steps)
        counter.read(10_000) // the counter as recording starts
        // Counted from then: none walked yet is a real 0.
        assertEquals(0L, r.state!!.steps)
        assertEquals(0L, store.track(id)!!.steps)
        counter.read(10_100)
        assertEquals(100L, r.state!!.steps)
        // Batched with the screen off: only read when it's paused, by flushing.
        counter.held += 10_150
        r.pause()
        assertEquals(150L, r.state!!.steps)
        assertFalse(counter.listening)
        // Walked while paused: not counted.
        counter.read(10_900)
        r.resume()
        counter.read(11_000) // where it got to while paused
        assertEquals(150L, r.state!!.steps)
        counter.read(11_250)
        assertEquals(400L, r.state!!.steps)
        counter.held += 11_300
        assertEquals(id, r.stop())
        assertFalse(counter.listening)
        val t = store.track(id)!!
        assertEquals(450L, t.steps)
        assertNull(t.stepsBase)
    }

    @Test
    fun aRecordingKilledAndRestartedKeepsWhatWasCountedAndWhatWasWalkedMeanwhile() {
        val first = FakeSteps()
        val r = recorder(first)
        val id = r.start().trackId
        first.read(5_000)
        now += 30_000
        first.read(5_200)
        // Killed. The counter went on counting; the system restarts recording.
        val second = FakeSteps()
        val again = recorder(second)
        assertTrue(again.resumeUnfinished(paused = false))
        assertEquals(id, again.state!!.trackId)
        assertEquals(200L, again.state!!.steps)
        second.read(5_300)
        assertEquals(300L, again.state!!.steps)
        again.stop()
        assertEquals(300L, store.track(id)!!.steps)
    }

    @Test
    fun aCounterThatStartedAgainIsANewBaseNotStepsBack() {
        val id = store.start(now)
        store.setSteps(id, 100, 5_000)
        val counter = FakeSteps()
        val r = recorder(counter)
        assertTrue(r.resumeUnfinished(paused = false))
        // The phone restarted: the counter's from 0 again.
        counter.read(40)
        assertEquals(100L, r.state!!.steps)
        counter.read(90)
        assertEquals(150L, r.state!!.steps)
    }

    @Test
    fun aTrackLeftUnfinishedComesBackPausedAndCountsAfreshWhenResumed() {
        val id = store.start(now)
        store.add(id, 0, Fix(now, 49.0, -123.0, 4f))
        store.setSteps(id, 120, 5_000)
        val counter = FakeSteps()
        val r = recorder(counter)
        assertTrue(r.resumeUnfinished(paused = true))
        assertEquals(120L, r.state!!.steps)
        assertNull(store.track(id)!!.stepsBase)
        assertFalse(counter.listening)
        r.resume()
        counter.read(9_000)
        counter.read(9_030)
        assertEquals(150L, r.state!!.steps)
    }

    @Test
    fun withoutAStepCounterOrItsPermissionTheTrackHasNoSteps() {
        for (counter in listOf(null, FakeSteps(available = false))) {
            val r = recorder(counter)
            val id = r.start().trackId
            assertNull(r.state!!.steps)
            r.onFix(Fix(now, 49.0, -123.0, 4f))
            r.pause()
            r.resume()
            r.stop()
            assertNull(store.track(id)!!.steps)
            // Nor in its GPX.
            assertFalse(Gpx.write("T", 0, store.points(id), store.track(id)!!.steps).contains("steps"))
        }
    }

    @Test
    fun aCounterThatNeverGivesAReadingLeavesTheTrackWithoutSteps() {
        val counter = FakeSteps()
        val r = recorder(counter)
        val id = r.start().trackId
        assertTrue(counter.listening)
        r.onFix(Fix(now, 49.0, -123.0, 4f))
        r.pause()
        r.resume()
        assertNull(r.state!!.steps)
        r.stop()
        val t = store.track(id)!!
        assertNull(t.steps)
        assertNull(t.stepsBase)
        // Nowhere: not in its GPX, nor its record.
        val gpx = Gpx.write("T", 0, store.points(id), t.steps)
        assertFalse(gpx.contains("steps"))
        assertNull(TrackMeasure.ofGpx(gpx).steps)
        assertFalse(summary(TrackRecorder.State(id, 0, 0, null, emptyList(), 0.0, null, null), 0).contains("step"))
    }

    @Test
    fun aReadingAfterAResumeStillCountsTheTrack() {
        val counter = FakeSteps()
        val r = recorder(counter)
        val id = r.start().trackId
        r.pause()
        r.resume()
        counter.read(700)
        counter.read(760)
        r.stop()
        assertEquals(60L, store.track(id)!!.steps)
    }

    @Test
    fun killedBeforeAnyReadingTheTrackIsntCounted() {
        val r = recorder(FakeSteps())
        val id = r.start().trackId
        // Killed and restarted: nothing was counted, so it isn't counted in part from here.
        val second = FakeSteps()
        val again = recorder(second)
        assertTrue(again.resumeUnfinished(paused = false))
        second.read(100)
        second.read(150)
        assertNull(again.state!!.steps)
        again.stop()
        assertNull(store.track(id)!!.steps)
    }

    @Test
    fun aTrackNotCountedFromItsStartIsntCountedLater() {
        val counter = FakeSteps(available = false)
        val r = recorder(counter)
        val id = r.start().trackId
        r.pause()
        // The permission granted meanwhile: still not counted, rather than counted in part.
        counter.available = true
        r.resume()
        counter.read(100)
        counter.read(200)
        assertNull(r.state!!.steps)
        assertEquals(0, counter.starts)
        r.stop()
        assertNull(store.track(id)!!.steps)
    }

    @Test
    fun aCountedTrackWhoseCounterIsGoneKeepsWhatWasCounted() {
        val counter = FakeSteps()
        val r = recorder(counter)
        val id = r.start().trackId
        counter.read(1_000)
        counter.read(1_080)
        r.pause()
        // The permission taken back.
        counter.available = false
        r.resume()
        r.stop()
        assertEquals(80L, store.track(id)!!.steps)
    }

    @Test
    fun stepsAreGroupedAsTheWebsiteShowsThem() {
        assertEquals("3,120 steps", stepsText(3120))
        assertEquals("1 step", stepsText(1))
        assertEquals("0 steps", stepsText(0))
        assertEquals("1,234,567 steps", stepsText(1_234_567))
        val s = TrackRecorder.State(1, 0, 0, null, emptyList(), 3200.0, null, 3120)
        assertTrue(summary(s, 0), summary(s, 0).endsWith(" · 3,120 steps"))
        assertFalse(summary(s.copy(steps = null), 0).contains("step"))
        val stats = TrackStore.Stats(1000.0, 60_000, 50_000, null, null)
        assertEquals("Moving 0:50 · 12 steps", moreLine(stats, 12))
        assertEquals("Moving 0:50", moreLine(stats, null))
    }
}

/** Android's step counter, as [SensorStepSource] reads it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SensorStepSourceTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val sm get() = app.getSystemService(SensorManager::class.java)

    private fun addCounter(): Sensor = ShadowSensor.newInstance(Sensor.TYPE_STEP_COUNTER).also { shadowOf(sm).addSensor(it) }

    @After
    fun tearDown() {
        app.getSharedPreferences("recording", android.content.Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun stepsTurnedOffArentCountedNorAskedFor() {
        addCounter()
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_SENSOR_STEP_COUNTER, true)
        shadowOf(app).grantPermissions(Manifest.permission.ACTIVITY_RECOGNITION)
        assertTrue(StepsSetting.counting(app))
        StepsSetting.set(app, false)
        assertEquals(false, StepsSetting.stored(app))
        assertFalse(StepsSetting.counting(app))
        assertFalse(SensorStepSource(app).start { })
        // A new recording has no steps.
        val store = TrackStore(app, name = null)
        try {
            val r = TrackRecorder(store, null, background = { it.run() }, steps = SensorStepSource(app), countSteps = { StepsSetting.on(app) })
            val id = r.start().trackId
            assertNull(r.state!!.steps)
            assertTrue(shadowOf(sm).listeners.isEmpty())
            r.stop()
            assertNull(store.track(id)!!.steps)
        } finally {
            store.close()
        }
        // Nor is the permission asked for.
        shadowOf(app).denyPermissions(Manifest.permission.ACTIVITY_RECOGNITION)
        assertFalse(StepsPermission.toAsk(app))
        StepsSetting.set(app, true)
        assertTrue(StepsPermission.toAsk(app))
    }

    @Test
    fun neverChosenStepsAreOn() {
        addCounter()
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_SENSOR_STEP_COUNTER, true)
        shadowOf(app).grantPermissions(Manifest.permission.ACTIVITY_RECOGNITION)
        assertNull(StepsSetting.stored(app))
        assertTrue(StepsSetting.on(app))
        assertTrue(StepsSetting.counting(app))
        val source = SensorStepSource(app)
        assertTrue(source.start { })
        source.stop()
    }

    @Test
    fun notCountingWhenAskedTurnsStepsOff() {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_SENSOR_STEP_COUNTER, true)
        shadowOf(app).denyPermissions(Manifest.permission.ACTIVITY_RECOGNITION)
        // Counting, then refusing Android's question: the setting's left be.
        StepsPermission.answered(app, count = true)
        assertNull(StepsSetting.stored(app))
        assertFalse(StepsPermission.toAsk(app))
        StepsPermission.answered(app, count = false)
        assertEquals(false, StepsSetting.stored(app))
        assertFalse(StepsSetting.on(app))
    }

    @Test
    fun readsTheStepCounterOnceAllowed() {
        val sensor = addCounter()
        val source = SensorStepSource(app)
        val readings = mutableListOf<Long>()
        // Refused: not counted.
        shadowOf(app).denyPermissions(Manifest.permission.ACTIVITY_RECOGNITION)
        assertFalse(source.start { readings += it })
        shadowOf(app).grantPermissions(Manifest.permission.ACTIVITY_RECOGNITION)
        assertTrue(source.start { readings += it })
        val event = SensorEventBuilder.newBuilder(sensor, floatArrayOf(4321f)).setTimestamp(1).build()
        shadowOf(sm).sendSensorEventToListeners(event)
        assertEquals(listOf(4321L), readings)
        var flushed = false
        source.flush { flushed = true }
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(3))
        assertTrue(flushed)
        source.stop()
        assertTrue(shadowOf(sm).listeners.isEmpty())
    }

    @Test
    fun aPhoneWithoutAStepCounterCountsNothing() {
        shadowOf(app).grantPermissions(Manifest.permission.ACTIVITY_RECOGNITION)
        assertFalse(SensorStepSource(app).start { })
        // Nor is it asked for.
        assertFalse(StepsPermission.toAsk(app))
    }

    @Test
    fun theStepsPermissionIsAskedOnce() {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_SENSOR_STEP_COUNTER, true)
        shadowOf(app).denyPermissions(Manifest.permission.ACTIVITY_RECOGNITION)
        assertTrue(StepsPermission.toAsk(app))
        StepsPermission.asked(app)
        assertFalse(StepsPermission.toAsk(app))
    }
}
