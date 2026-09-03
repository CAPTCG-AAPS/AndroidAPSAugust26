package app.aaps.pump.tandem.common.concurrency

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import kotlinx.coroutines.delay

/**
 * Gates wire sends when the pump signals "comm suspended / buffer full".
 *
 * Lives inside the dispatcher's send mutex so the dispatcher loop awaits naturally without
 * blocking a real OS thread (suspending [delay], never `Thread.sleep`). Independent of the queue
 * and of [PumpAvailability]: a comm-suspend can apply to any op including pure status reads.
 */
class CommSuspendGate(
    private val logger: AAPSLogger,
    private val timeSource: () -> Long = System::currentTimeMillis
) {

    @Volatile private var pausedUntilMs: Long = 0L

    /** Called when the pump emits a comm-suspended event. */
    fun pauseSends(durationMs: Long, reason: String = "pump-signal") {
        if (durationMs <= 0) return
        val now = timeSource()
        val until = now + durationMs
        // Extend, never shorten, an already-active pause.
        if (until > pausedUntilMs) {
            pausedUntilMs = until
            logger.warn(LTag.PUMPCOMM, "CommSuspendGate: pausing sends for ${durationMs}ms (reason=$reason)")
        }
    }

    fun clear() {
        pausedUntilMs = 0L
    }

    /**
     * @return ms remaining on the active pause, or 0 if the gate is currently open. Cheap;
     *   intended for use from the queue's dispatch loop inside `synchronized(lock)`.
     */
    fun remainingPauseMs(): Long {
        val remaining = pausedUntilMs - timeSource()
        return if (remaining > 0) remaining else 0L
    }

    /** Suspend until the gate is open. Returns immediately if no pause is active. */
    suspend fun await() {
        while (true) {
            val now = timeSource()
            val remaining = pausedUntilMs - now
            if (remaining <= 0) return
            delay(remaining)
        }
    }
}
