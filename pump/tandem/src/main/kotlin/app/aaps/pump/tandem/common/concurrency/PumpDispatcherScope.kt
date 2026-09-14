package app.aaps.pump.tandem.common.concurrency

import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.pump.common.defs.BolusData
import app.aaps.pump.common.driver.connector.commands.data.CustomCommandTypeInterface
import app.aaps.pump.tandem.common.comm.ui.TandemUICommunication
import app.aaps.pump.tandem.common.driver.connector.TandemPumpConnectionManager
import com.jwoglom.pumpx2.pump.messages.Message

/**
 * Compile-time receiver scope for pump-talking work. The only place a [PumpDispatcherScope] is
 * available is inside a `submit*` lambda on [TandemDispatcher]. The extension functions below
 * delegate to [pumpConnectionManager] / [tandemUICommunication]; outside the scope they're
 * unresolvable, so pump comm cannot escape the queue.
 *
 * The interface is marked `internal` so cross-module callers can't fabricate a scope; within
 * the tandem module the receiver discipline plus dropping the `@Inject` of
 * [TandemPumpConnectionManager] from non-dispatcher classes is what enforces the rule.
 */
internal interface PumpDispatcherScope {
    val pumpConnectionManager: TandemPumpConnectionManager
    val tandemUICommunication: TandemUICommunication
}

// ─── Pump-comm extensions (delegate to TandemPumpConnectionManager) ─────────────────────────

internal fun PumpDispatcherScope.deliverBolus(info: DetailedBolusInfo) =
    pumpConnectionManager.deliverBolus(info)

internal fun PumpDispatcherScope.cancelBolus(data: BolusData) =
    pumpConnectionManager.cancelBolus(data)

internal fun PumpDispatcherScope.getBolus() =
    pumpConnectionManager.getBolus()

internal fun PumpDispatcherScope.setTemporaryBasal(percent: Int, durationInMinutes: Int) =
    pumpConnectionManager.setTemporaryBasal(percent, durationInMinutes)

internal fun PumpDispatcherScope.cancelTemporaryBasal() =
    pumpConnectionManager.cancelTemporaryBasal()

internal fun PumpDispatcherScope.getTemporaryBasal() =
    pumpConnectionManager.getTemporaryBasal()

internal fun PumpDispatcherScope.setBasalProfile(profile: Profile?) =
    pumpConnectionManager.setBasalProfile(profile)

internal fun PumpDispatcherScope.getBasalProfile() =
    pumpConnectionManager.getBasalProfile()

internal fun PumpDispatcherScope.getPumpStatus() =
    pumpConnectionManager.getPumpStatus()

internal fun PumpDispatcherScope.getRemainingInsulin() =
    pumpConnectionManager.getRemainingInsulin()

internal fun PumpDispatcherScope.getBatteryLevel() =
    pumpConnectionManager.getBatteryLevel()

internal fun PumpDispatcherScope.getConfiguration() =
    pumpConnectionManager.getConfiguration()

internal fun PumpDispatcherScope.getTime() =
    pumpConnectionManager.getTime()

internal fun PumpDispatcherScope.setTime() =
    pumpConnectionManager.setTime()

internal fun PumpDispatcherScope.executeCustomCommand(command: CustomCommandTypeInterface, data: Any? = null) =
    pumpConnectionManager.executeCustomCommand(command, data)

// ─── UI-comm extension (delegate to TandemUICommunication) ─────────────────────────────────

/** Fire-and-forget UI / history wire send. Response arrives via the listener path. */
internal fun PumpDispatcherScope.sendUiCommand(msg: Message) =
    tandemUICommunication.sendCommand(msg)
