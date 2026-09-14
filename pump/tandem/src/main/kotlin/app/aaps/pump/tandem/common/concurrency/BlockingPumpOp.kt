package app.aaps.pump.tandem.common.concurrency

import kotlin.time.Duration

/**
 * Wraps an existing blocking call site as a [PumpOp]. Used by the Phase A migration to route
 * legacy synchronous methods (e.g. `pumpConnectionManager.deliverBolus(...)`) through the queue
 * without rewriting their bodies. The block runs on the dispatcher's single thread, so blocking
 * is safe relative to other queued ops.
 *
 * Note: [block] is plain Java/Kotlin code and is not interruptible — `withTimeout` will fail the
 * Deferred at [maxDuration] but cannot abort an in-progress BLE round-trip. This matches existing
 * driver behaviour; the COMMAND_TIMEOUT inside TandemCommunicationManager is the real bound.
 */
class BlockingPumpOp<T>(
    override val name: String,
    override val maxDuration: Duration,
    override val requiresDeliveryEnabled: Boolean,
    override val coalesceKey: String? = null,
    private val block: () -> T
) : PumpOp<T>() {
    override suspend fun run(ctx: PumpOpContext): T = block()
}
