package app.aaps.pump.tandem.common.concurrency

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tri-state availability of the pump for delivery-affecting operations.
 *
 *  - [DeliveryEnabled] — pump is running and can accept mutating ops (bolus, TBR, profile)
 *  - [DeliveryDisabled] — pump is suspended (user suspend, cartridge change, fill, alarm, etc.)
 *  - [Unknown] — pre-connect / post-disconnect / pre-first-status-read; treated conservatively the
 *    same as [DeliveryDisabled] until a status read resolves it.
 */
enum class PumpAvailability {
    DeliveryEnabled,
    DeliveryDisabled,
    Unknown;

    /** True when mutating ops are allowed. Both [DeliveryDisabled] and [Unknown] block. */
    val allowsDelivery: Boolean get() = this == DeliveryEnabled
}

/**
 * Holds the current [PumpAvailability]. The value is observation-driven: response handlers that
 * already maintain `pumpRunningState` (HomeScreenMirrorResponse, ResumePumpingResponse, etc.) push
 * the derived availability here. Disconnect handlers push [PumpAvailability.Unknown].
 *
 * Thread-safe: mutations and reads go through `synchronized(lock)`. [state] exposes a StateFlow
 * for reactive consumers.
 */
class PumpAvailabilityState(
    private val logger: AAPSLogger
) {

    private val lock = Any()
    private var value: PumpAvailability = PumpAvailability.Unknown

    private val _flow = MutableStateFlow(PumpAvailability.Unknown)
    val state: StateFlow<PumpAvailability> = _flow.asStateFlow()

    val current: PumpAvailability get() = synchronized(lock) { value }

    /** Pump is running and accepting mutating ops. */
    fun markEnabled(reason: String = "pump running") {
        set(PumpAvailability.DeliveryEnabled, reason)
    }

    /** Pump is suspended / in cartridge change / blocking alarm — mutating ops fast-fail. */
    fun markDisabled(reason: String) {
        set(PumpAvailability.DeliveryDisabled, reason)
    }

    /** Pre-connect / post-disconnect — mutating ops fast-fail until a status read resolves. */
    fun markUnknown(reason: String) {
        set(PumpAvailability.Unknown, reason)
    }

    private fun set(next: PumpAvailability, reason: String) {
        val changed = synchronized(lock) {
            val prior = value
            value = next
            _flow.value = next
            prior != next
        }
        if (changed) {
            logger.debug(LTag.PUMP, "PumpAvailability: → $next (reason='$reason')")
        }
    }
}
