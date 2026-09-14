package app.aaps.pump.tandem.common.concurrency

import com.jwoglom.pumpx2.pump.messages.Message
import kotlin.time.Duration


/**
 * A unit of pump work dispatched serially by [PumpOpQueue].
 *
 * Single-shot ops issue one or a small number of messages; compound ops (e.g. cartridge change)
 * perform many internal sends and hold the dispatcher for the workflow's duration.
 */
abstract class PumpOp<T> {

    /** Short identifier used in logs and for status-op coalescing. */
    abstract val name: String

    /**
     * Hard upper bound for [run] enforced by the dispatcher via withTimeout. On expiry the op
     * fails, the wire mutex is released and the dispatcher continues with the next op.
     */
    abstract val maxDuration: Duration

    /**
     * If true, the dispatcher gates this op on [PumpAvailability]: when availability is not
     * [PumpAvailability.DeliveryEnabled] the op is fast-failed before its [run] is called.
     *
     * Mutating ops in normal flow (bolus, temp basal, profile) set this to true. Status reads
     * and emergency overrides — bolus cancel, alarm acknowledgement — set this to false because
     * they need to execute *during* the workflow that disabled delivery.
     */
    abstract val requiresDeliveryEnabled: Boolean

    /**
     * For status reads, ops with the same [coalesceKey] that are already pending in the queue at
     * submit time will share a single Deferred (the duplicate is dropped). Mutating ops should
     * leave this null so they are never coalesced.
     */
    open val coalesceKey: String? = null

    abstract suspend fun run(ctx: PumpOpContext): T
}

/**
 * The narrow surface a [PumpOp] uses to talk to the pump. Restricted to a single message send.
 * Ops never touch BLE state or availability directly — availability is observation-driven, set by
 * response handlers in the comm layer.
 */
interface PumpOpContext {

    /**
     * Send one message and wait for its response. Honours [CommSuspendGate]. Returns null on
     * timeout or send failure (matching the existing TandemCommunicationManager.sendCommand
     * contract).
     */
    suspend fun send(request: Message, forceSend: Boolean = false): Message?
}
