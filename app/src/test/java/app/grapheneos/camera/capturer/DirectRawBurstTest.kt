package app.grapheneos.camera.capturer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/** A direct burst's frame delivery and closure over a fake reader, camera and main thread. */
class DirectRawBurstTest {
    private class Frame(val timestamp: Long) { var closed = false }

    /** An image reader with a fixed number of slots, as Camera2's: acquiring fails while all are held. */
    private class FakeReader(private val slots: Int) : RawFrameQueue.Reader<Frame> {
        val pending = ArrayDeque<Frame>()
        val held = arrayListOf<Frame>()
        val drains = ArrayDeque<() -> Unit>()
        override fun acquire(): Frame? {
            if (pending.isEmpty()) return null
            check(held.size < slots) { "maxImages has already been acquired" }
            return pending.removeFirst().also { held += it }
        }
        override fun close(image: Frame) { image.closed = true; held.remove(image) }
        override fun timestamp(image: Frame) = image.timestamp
        override fun requestDrain(drain: () -> Unit) { drains.addLast(drain) }
        fun runDrains() { while (drains.isNotEmpty()) drains.removeFirst()() }
    }

    private val closed = AtomicBoolean()
    private val cancelled = AtomicBoolean()
    private var now = 0L
    private fun queue(reader: FakeReader) = RawFrameQueue<Frame, String>(reader, closed, cancelled, { now })

    @Test fun framesArePairedWithTheirResultsInEitherOrder() {
        val reader = FakeReader(3); val frames = queue(reader)
        frames.result(10, "result 10")
        reader.pending += Frame(10); reader.pending += Frame(20)
        frames.drain()
        frames.result(20, "result 20")
        val seen = arrayListOf<String>()
        frames.next(1000, {}) { frame, result -> seen += "${frame.timestamp} $result" }
        frames.next(1000, {}) { frame, result -> seen += "${frame.timestamp} $result" }
        assertEquals(listOf("10 result 10", "20 result 20"), seen)
        assertTrue(reader.held.isEmpty())
    }

    @Test fun aFullReaderLosesNoFrame() {
        val reader = FakeReader(3); val frames = queue(reader)
        (1L..8L).forEach { reader.pending += Frame(it); frames.result(it, "r$it") }
        // Three frames fill the reader and wait in the queue. The rest stay in the reader.
        assertEquals(3, frames.drain())
        val consumed = arrayListOf<Long>()
        repeat(8) {
            frames.next(1000, {}) { frame, _ -> consumed += frame.timestamp }
            reader.runDrains()
        }
        assertEquals((1L..8L).toList(), consumed)
        assertTrue(reader.pending.isEmpty() && reader.held.isEmpty())
    }

    @Test fun aCameraFailureReachesTheConsumerAfterEarlierFrames() {
        val reader = FakeReader(3); val frames = queue(reader)
        reader.pending += Frame(1); frames.result(1, "r1")
        frames.drain()
        frames.fail(IllegalStateException("RAW burst buffer lost"))
        frames.next(1000, {}) { _, _ -> }
        try { frames.next(1000, {}) { _, _ -> fail() }; fail() }
        catch (error: IllegalStateException) { assertEquals("RAW burst buffer lost", error.message) }
    }

    @Test fun missingMetadataTimesOutAndStillClosesTheFrame() {
        val reader = FakeReader(3)
        var clock = 0L
        val frames = RawFrameQueue<Frame, String>(reader, closed, cancelled, { clock += 100; clock })
        val frame = Frame(5)
        reader.pending += frame; frames.drain()
        try { frames.next(1000, {}) { _, _ -> fail() }; fail() } catch (error: IllegalStateException) {
            assertEquals("RAW burst metadata missing", error.message)
        }
        assertTrue(frame.closed)
    }

    @Test fun framesAfterCloseOrCancelAreClosedAtOnce() {
        val reader = FakeReader(3); val frames = queue(reader)
        val early = Frame(1)
        reader.pending += early; frames.drain()
        cancelled.set(true)
        val late = Frame(2)
        reader.pending += late; frames.drain()
        assertTrue(late.closed)
        closed.set(true); frames.close()
        assertTrue(early.closed)
        assertTrue(reader.held.isEmpty())
    }

    private class FakeEnv : BurstClosure.Env {
        var time = 0L
        var previousClosed = false
        var owns = true
        var unbinds = 0
        var threadEnds = true
        var finishes = 0
        var destroyed = false
        var notices = 0
        val main = ArrayDeque<Pair<Long, () -> Unit>>()
        override fun now() = time
        override fun previousCameraClosed() = previousClosed
        override fun ownsPreviousBinding() = owns
        override fun unbindPrevious() { unbinds++ }
        override fun runOffMain(action: () -> Unit) = action()
        override fun joinThread() = threadEnds
        override fun post(delayMs: Long, action: () -> Unit) { main.addLast(time + delayMs to action) }
        override fun finish() { finishes++ }
        override fun activityDestroyed() = destroyed
        override fun activityVisible() = !destroyed
        override fun notifySlowRecovery() { notices++ }
        /** Runs posted work in time order up to [until]. */
        fun run(until: Long) {
            while (true) {
                val next = main.filter { it.first <= until }.minByOrNull { it.first } ?: break
                main.remove(next); time = maxOf(time, next.first); next.second()
            }
            time = until
        }
    }

    private fun readyLease(): DirectCameraLease<Any> {
        val lease = DirectCameraLease<Any>(); val camera = Any()
        lease.acquire {}; lease.beginOpen(); lease.arrived(camera)
        lease.requestClose(); lease.closed(camera); lease.resourcesClosed()
        return lease
    }

    @Test fun theBurstFinishesExactlyOnceHoweverOftenClosureIsReported() {
        val env = FakeEnv(); env.previousClosed = true
        val closure = BurstClosure(readyLease(), env)
        closure.begin()
        // The device's onClosed, the burst's own end and every retry ask for recovery.
        repeat(3) { closure.recover() }
        env.run(1000)
        closure.recover(); env.run(2000)
        assertEquals(1, env.finishes)
        assertEquals(0L, closure.recovered.count)
    }

    @Test fun aSlowThreadIsWaitedForBeforeTheOnlyFinish() {
        val env = FakeEnv(); env.previousClosed = true; env.threadEnds = false
        val closure = BurstClosure(readyLease(), env)
        closure.begin(); closure.recover(); closure.recover()
        env.run(1000)
        assertEquals(0, env.finishes)
        env.threadEnds = true
        env.run(2000)
        assertEquals(1, env.finishes)
    }

    @Test fun nothingFinishesBeforeTheBurstEnded() {
        val env = FakeEnv(); env.previousClosed = true
        val closure = BurstClosure(readyLease(), env)
        closure.recover(); env.run(1000)
        assertEquals(0, env.finishes)
        assertEquals(1L, closure.recovered.count)
    }

    @Test fun anOpenPreviousCameraIsUnboundAndWaitedFor() {
        val env = FakeEnv()
        val lease = DirectCameraLease<Any>()
        lease.acquire {}; lease.requestClose(); lease.resourcesClosed()
        val closure = BurstClosure(lease, env)
        closure.begin(); closure.recover()
        env.run(1000)
        assertTrue(env.unbinds > 0)
        assertEquals(0, env.finishes)
        // Only the binding this burst took over is unbound, never a later one.
        env.owns = false; val unbinds = env.unbinds
        env.run(2000)
        assertEquals(unbinds, env.unbinds)
        env.previousClosed = true; env.run(3000)
        assertEquals(1, env.finishes)
    }

    @Test fun aDeviceThatNeverClosesIsReportedOnceAndGivenUpWhenTheActivityIsGone() {
        val env = FakeEnv(); env.previousClosed = true
        val lease = DirectCameraLease<Any>(); val camera = Any()
        lease.acquire {}; lease.beginOpen(); lease.arrived(camera); lease.requestClose(); lease.resourcesClosed()
        val closure = BurstClosure(lease, env)
        closure.begin(); closure.recover()
        env.run(10_000)
        assertEquals(1, env.notices)
        assertEquals(1L, closure.recovered.count)
        env.destroyed = true
        env.run(31_000)
        assertEquals(0L, closure.recovered.count)
        assertEquals(0, env.finishes)
        assertFalse(env.main.any { it.first > 31_000 })
    }
}
