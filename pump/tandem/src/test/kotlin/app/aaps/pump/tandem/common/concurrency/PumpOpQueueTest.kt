package app.aaps.pump.tandem.common.concurrency

import app.aaps.shared.tests.AAPSLoggerTest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * Behavioural tests for the load-bearing semantics of [PumpOpQueue]:
 *  - priority ordering across all four tiers (regression risk: dispatch order swap)
 *  - delivery fast-fail (regression risk: unsafe bolus during cartridge change)
 *  - rate-limit preemption (regression risk: history sync starves user taps)
 *
 * Skipped as too trivial / tautological:
 *  - in-tier FIFO (tests the backing deque)
 *  - coalesceKey dedupe (tests a HashMap)
 *  - isBusy() state-readback
 *  - per-op timeout (tests withTimeout)
 */
class PumpOpQueueTest {

    private val logger = AAPSLoggerTest()
    private fun availability(initial: PumpAvailability = PumpAvailability.DeliveryEnabled) =
        PumpAvailabilityState(logger).also {
            when (initial) {
                PumpAvailability.DeliveryEnabled  -> it.markEnabled("test")
                PumpAvailability.DeliveryDisabled -> it.markDisabled("test")
                PumpAvailability.Unknown          -> it.markUnknown("test")
            }
        }
    private fun gate() = CommSuspendGate(logger)

    /** Block-and-record op: signals when it starts, optionally waits, then logs to [order]. */
    private class GatedOp(
        override val name: String,
        override val requiresDeliveryEnabled: Boolean = false,
        private val started: CountDownLatch? = null,
        private val release: CountDownLatch? = null,
        private val order: ConcurrentLinkedQueue<String>? = null
    ) : PumpOp<Unit>() {
        override val maxDuration = 5.seconds
        override suspend fun run(ctx: PumpOpContext) {
            started?.countDown()
            release?.await()
            order?.add(name)
        }
    }

    @Test
    fun `ops dispatch in priority order across all four tiers regardless of submit order`() {
        // Disable rate limiting so BACKGROUND vs DEFAULT differ only by priority, not throttle.
        val q = PumpOpQueue(logger, availability(), gate(), rateLimits = emptyMap())

        // Block the dispatcher with a slow op so the next 4 ops accumulate before any can run.
        val blockerStarted = CountDownLatch(1)
        val blockerRelease = CountDownLatch(1)
        val order = ConcurrentLinkedQueue<String>()
        q.submit(GatedOp("blocker", started = blockerStarted, release = blockerRelease, order = order), Priority.DEFAULT)
        assertTrue(blockerStarted.await(2, TimeUnit.SECONDS))

        val pass = CountDownLatch(0)
        // Submit out-of-priority order; dispatch must still pick CRITICAL → USER → DEFAULT → BACKGROUND.
        q.submit(GatedOp("background", release = pass, order = order), Priority.BACKGROUND)
        q.submit(GatedOp("default",    release = pass, order = order), Priority.DEFAULT)
        q.submit(GatedOp("user",       release = pass, order = order), Priority.USER_INITIATED)
        q.submit(GatedOp("critical",   release = pass, order = order), Priority.CRITICAL)

        blockerRelease.countDown()
        repeat(50) { if (order.size == 5) return@repeat; Thread.sleep(20) }

        assertEquals(listOf("blocker", "critical", "user", "default", "background"), order.toList())
        q.shutdown()
    }

    @Test
    fun `op with requiresDeliveryEnabled fast-fails without invoking run when availability is Disabled`() = runBlocking {
        val q = PumpOpQueue(logger, availability(PumpAvailability.DeliveryDisabled), gate(), rateLimits = emptyMap())
        val ran = AtomicInteger(0)
        val op = object : PumpOp<Unit>() {
            override val name = "deliverBolus"
            override val maxDuration = 5.seconds
            override val requiresDeliveryEnabled = true
            override suspend fun run(ctx: PumpOpContext) { ran.incrementAndGet() }
        }
        try {
            q.submit(op, Priority.DEFAULT).await()
            fail("expected PumpUnavailableException")
        } catch (e: PumpUnavailableException) {
            assertEquals(PumpAvailability.DeliveryDisabled, e.availability)
            assertEquals("deliverBolus", e.opName)
        }
        assertEquals(0, ran.get(), "op.run must not be called when fast-failed")
        q.shutdown()
    }

    @Test
    fun `non-mutating op still runs when availability is Disabled`() = runBlocking {
        // This is the bolus-cancel / alarm-ack semantic: an op that bypasses the availability gate
        // because it's the very thing that needs to run during a delivery-disabled workflow.
        val q = PumpOpQueue(logger, availability(PumpAvailability.DeliveryDisabled), gate(), rateLimits = emptyMap())
        val ran = AtomicInteger(0)
        val op = object : PumpOp<Int>() {
            override val name = "stopBolusDelivering"
            override val maxDuration = 5.seconds
            override val requiresDeliveryEnabled = false
            override suspend fun run(ctx: PumpOpContext): Int = ran.incrementAndGet()
        }
        assertEquals(1, q.submit(op, Priority.CRITICAL).await())
        assertEquals(1, ran.get())
        q.shutdown()
    }

    @Test
    fun `higher-priority op preempts a rate-gated BACKGROUND wait`() {
        // Tight BACKGROUND-only limit: burst 1, one token per 2s. Higher tiers are unrate-limited.
        val q = PumpOpQueue(
            logger, availability(), gate(),
            rateLimits = mapOf(Priority.BACKGROUND to RateLimit(rps = 0.5, burst = 1))
        )
        val order = ConcurrentLinkedQueue<String>()
        val pass = CountDownLatch(0)

        q.submit(GatedOp("bg-1", release = pass, order = order), Priority.BACKGROUND)        // burns the token
        q.submit(GatedOp("bg-2", release = pass, order = order), Priority.BACKGROUND)        // gated for ~2s
        q.submit(GatedOp("user", release = pass, order = order), Priority.USER_INITIATED)    // must preempt

        // Within 1s — well before bg-2's 2s wait — bg-1 and user should both have run, bg-2 not.
        val deadline = System.currentTimeMillis() + 1000
        while (System.currentTimeMillis() < deadline && (!order.contains("bg-1") || !order.contains("user"))) {
            Thread.sleep(20)
        }
        assertTrue(order.contains("bg-1"), "bg-1 should have run: $order")
        assertTrue(order.contains("user"), "user should have run: $order")
        assertFalse(order.contains("bg-2"), "bg-2 should still be rate-gated: $order")
        q.shutdown()
    }
}
