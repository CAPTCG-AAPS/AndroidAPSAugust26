package app.aaps.pump.tandem.common.concurrency

import app.aaps.shared.tests.AAPSLoggerTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommSuspendGateTest {

    /**
     * The pump can emit PUMP_COMMUNICATIONS_SUSPENDED multiple times during one back-off window.
     * If a later (shorter) pause silently cut the original pause short, we'd resume sending while
     * the pump still can't accept it — exactly the bug this gate is meant to prevent.
     */
    @Test
    fun `pauseSends extends an active pause but never shortens it`() = runTest {
        val gate = CommSuspendGate(AAPSLoggerTest()) { testScheduler.currentTime }
        gate.pauseSends(1000, "first")
        advanceTimeBy(200)
        gate.pauseSends(300, "shorter")
        val start = testScheduler.currentTime
        gate.await()
        val elapsed = testScheduler.currentTime - start
        // Original pause had 800ms remaining; the shorter follow-up was ignored.
        assertTrue(elapsed in 800L..900L, "elapsed=$elapsed (should be ~800ms)")
    }
}
