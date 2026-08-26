package app.aaps.pump.omnipod.omnipod5.bledriver.comm

import app.aaps.pump.omnipod.common.bledriver.comm.interfaces.session.BleConnection
import app.aaps.pump.omnipod.common.bledriver.comm.session.ConnectionState
import app.aaps.pump.omnipod.common.bledriver.event.PodEvent
import app.aaps.pump.omnipod.common.bledriver.pod.command.base.Command
import app.aaps.pump.omnipod.common.bledriver.pod.response.Response
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Observable
import java.util.concurrent.CountDownLatch
import kotlin.reflect.KClass

/**
 * Omnipod 5 equivalent of [OmnipodDashBleManager]. Identical surface deliberately - the
 * two pod types share the same [Command]/[Response]/[PodEvent] model (see
 * [O5BleManagerImpl]'s class doc for why), so callers that already work against
 * [OmnipodDashBleManager] can use this the same way.
 */
interface O5BleManager {

    fun sendCommand(cmd: Command, responseType: KClass<out Response>): Observable<PodEvent>

    fun getStatus(): ConnectionState

    fun connect(timeoutMs: Long = BleConnection.DEFAULT_CONNECT_TIMEOUT_MS): Observable<PodEvent>

    fun connect(stopConnectionLatch: CountDownLatch): Observable<PodEvent>

    fun pairNewPod(): Observable<PodEvent>

    /** Sends the O5-only AID setup command batch (see [app.aaps.pump.omnipod.common
     *  .bledriver.pod.command.aid.O5AidSetupCommands]) over the already-established
     *  session. Must be called after [pairNewPod] completes and before any other
     *  activation command - see that object's class doc for why. */
    fun sendAidSetupCommands(): Completable

    fun disconnect(closeGatt: Boolean = false)
    fun removeBond()
}
