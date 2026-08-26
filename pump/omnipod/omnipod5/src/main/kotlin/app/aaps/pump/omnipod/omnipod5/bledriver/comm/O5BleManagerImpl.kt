package app.aaps.pump.omnipod.omnipod5.bledriver.comm
import app.aaps.pump.omnipod.common.bledriver.comm.Id
import app.aaps.pump.omnipod.common.bledriver.comm.Ids

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.utils.toHex
import app.aaps.pump.omnipod.common.bledriver.comm.exceptions.BusyException
import app.aaps.pump.omnipod.common.bledriver.comm.exceptions.ConnectException
import app.aaps.pump.omnipod.common.bledriver.comm.exceptions.CouldNotSendCommandException
import app.aaps.pump.omnipod.common.bledriver.comm.exceptions.FailedToConnectException
import app.aaps.pump.omnipod.common.bledriver.comm.exceptions.MessageIOException
import app.aaps.pump.omnipod.common.bledriver.comm.exceptions.NotConnectedException
import app.aaps.pump.omnipod.common.bledriver.comm.exceptions.PairingException
import app.aaps.pump.omnipod.common.bledriver.comm.exceptions.SessionEstablishmentException
import app.aaps.pump.omnipod.common.bledriver.comm.interfaces.device.BleDeviceManager
import app.aaps.pump.omnipod.common.bledriver.comm.interfaces.session.BleConnection
import app.aaps.pump.omnipod.omnipod5.bledriver.comm.legacy.O5BleConnectionFactory
import app.aaps.pump.omnipod.omnipod5.bledriver.comm.pair.O5CertificateStore
import app.aaps.pump.omnipod.omnipod5.bledriver.comm.pair.O5LTKExchanger
import app.aaps.pump.omnipod.omnipod5.bledriver.comm.pair.O5RegistrationData
import app.aaps.pump.omnipod.common.bledriver.comm.session.CommandAckError
import app.aaps.pump.omnipod.common.bledriver.comm.session.CommandReceiveError
import app.aaps.pump.omnipod.common.bledriver.comm.session.CommandReceiveSuccess
import app.aaps.pump.omnipod.common.bledriver.comm.session.CommandSendErrorConfirming
import app.aaps.pump.omnipod.common.bledriver.comm.session.CommandSendErrorSending
import app.aaps.pump.omnipod.common.bledriver.comm.session.CommandSendSuccess
import app.aaps.pump.omnipod.common.bledriver.comm.session.Connected
import app.aaps.pump.omnipod.common.bledriver.comm.session.ConnectionState
import app.aaps.pump.omnipod.common.bledriver.comm.session.ConnectionWaitCondition
import app.aaps.pump.omnipod.common.bledriver.comm.session.NotConnected
import app.aaps.pump.omnipod.common.bledriver.comm.session.Session
import app.aaps.pump.omnipod.common.bledriver.event.PodEvent
import app.aaps.pump.omnipod.omnipod5.bledriver.pod.command.aid.O5AidSetupCommands
import app.aaps.pump.omnipod.common.bledriver.pod.command.base.Command
import app.aaps.pump.omnipod.common.bledriver.pod.definition.PodType
import app.aaps.pump.omnipod.common.bledriver.pod.response.AlarmStatusResponse
import app.aaps.pump.omnipod.common.bledriver.pod.response.DefaultStatusResponse
import app.aaps.pump.omnipod.common.bledriver.pod.response.PodInfoActivationTimeResponse
import app.aaps.pump.omnipod.common.bledriver.pod.response.PodInfoTriggeredAlertsResponse
import app.aaps.pump.omnipod.common.bledriver.pod.response.Response
import app.aaps.pump.omnipod.common.bledriver.pod.response.SetUniqueIdResponse
import app.aaps.pump.omnipod.common.bledriver.pod.response.VersionResponse
import app.aaps.pump.omnipod.omnipod5.bledriver.pod.state.O5PodStateManager
import app.aaps.pump.omnipod.omnipod5.bledriver.pod.security.SecureO5RegistrationStorage
import app.aaps.pump.omnipod.omnipod5.bledriver.pod.util.P256KeyGenerator
import app.aaps.pump.omnipod.omnipod5.bledriver.pod.util.PodTypeAwarePodScanner
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Observable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.reflect.KClass

/**
 * Omnipod 5 equivalent of [OmnipodDashBleManagerImpl] - parallel to it rather than a
 * modification, for the same reason [O5Connection] is parallel to [Connection]: that
 * class is coupled throughout to [app.aaps.pump.omnipod.common.bledriver.pod.state
 * .OmnipodDashPodStateManager]'s much larger state surface.
 *
 * The command/response layer itself ([Command], [Response], [PodEvent], [Session]) is
 * reused completely unmodified - it turns out to have no Dash-specific assumptions baked
 * in (see [app.aaps.pump.omnipod.common.bledriver.pod.command.base.HeaderEnabledCommand],
 * which only needs a generic `uniqueId: Int` supplied externally). Only pairing and
 * connection setup actually differ between the two pod types.
 *
 * Also restores previously-imported credentials (via [SecureO5RegistrationStorage]) into
 * [O5RegistrationData]'s in-memory registry on construction - see the `init` block below.
 */
@Singleton
class O5BleManagerImpl @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val podState: O5PodStateManager,
    private val config: Config,
    private val context: Context,
    private val bleConnectionFactory: O5BleConnectionFactory,
    private val bleDeviceManager: BleDeviceManager,
    private val secureO5RegistrationStorage: SecureO5RegistrationStorage,
    private val p256KeyGenerator: P256KeyGenerator
) : O5BleManager {

    init {
        secureO5RegistrationStorage.loadAndInstallAll()
    }

    private val busy = AtomicBoolean(false)
    private var connection: BleConnection? = null

    private val bluetoothAdapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?)?.adapter

    override fun sendCommand(cmd: Command, responseType: KClass<out Response>): Observable<PodEvent> =
        Observable.create { emitter ->
            if (!busy.compareAndSet(false, true)) {
                throw BusyException()
            }
            try {
                val session = assertSessionEstablished()

                emitter.onNext(PodEvent.CommandSending(cmd))
                when (session.sendCommand(cmd)) {
                    is CommandSendErrorSending    -> {
                        emitter.tryOnError(CouldNotSendCommandException())
                        return@create
                    }

                    is CommandSendSuccess         -> {
                        emitter.onNext(PodEvent.CommandSent(cmd))
                        podState.increaseMessageSequenceNumber()
                    }

                    is CommandSendErrorConfirming -> {
                        emitter.onNext(PodEvent.CommandSendNotConfirmed(cmd))
                        podState.increaseMessageSequenceNumber()
                    }
                }
                when (val readResult = session.readAndAckResponse()) {
                    is CommandReceiveSuccess -> {
                        recordStatusIfPresent(readResult.result)
                        emitter.onNext(PodEvent.ResponseReceived(cmd, readResult.result))
                        podState.increaseMessageSequenceNumber()
                    }

                    is CommandAckError       -> {
                        recordStatusIfPresent(readResult.result)
                        emitter.onNext(PodEvent.ResponseReceived(cmd, readResult.result))
                        podState.increaseMessageSequenceNumber()
                    }

                    is CommandReceiveError   -> {
                        readResult.result?.let { recordStatusIfPresent(it) }
                        emitter.tryOnError(MessageIOException("Could not read response: $readResult"))
                        return@create
                    }
                }
                busy.set(false)
                emitter.onComplete()
            } catch (ex: Exception) {
                disconnect(false)
                emitter.tryOnError(ex)
            } finally {
                busy.set(false)
            }
        }

    private fun recordStatusIfPresent(response: Response) {
        when (response) {
            is VersionResponse                -> podState.updateFromVersionResponse(response)
            is DefaultStatusResponse           -> podState.updateFromDefaultStatusResponse(response)
            is AlarmStatusResponse             -> podState.updateFromAlarmStatusResponse(response)
            is SetUniqueIdResponse             -> podState.updateFromSetUniqueIdResponse(response)
            is PodInfoActivationTimeResponse   -> podState.updateFromActivationTimeResponse(response)
            is PodInfoTriggeredAlertsResponse  -> podState.updateFromTriggeredAlertsResponse(response)
            else                               -> Unit
        }
    }

    private fun assertSessionEstablished(): Session {
        val conn = assertConnected()
        return conn.session
            ?: throw NotConnectedException("Missing session")
    }

    override fun getStatus(): ConnectionState =
        connection?.connectionState() ?: NotConnected

    override fun connect(timeoutMs: Long): Observable<PodEvent> =
        connect(ConnectionWaitCondition(timeoutMs = timeoutMs))

    override fun connect(stopConnectionLatch: CountDownLatch): Observable<PodEvent> =
        connect(ConnectionWaitCondition(stopConnection = stopConnectionLatch))

    private fun connect(connectionWaitCond: ConnectionWaitCondition): Observable<PodEvent> = Observable
        .create { emitter ->
            if (!busy.compareAndSet(false, true)) {
                throw BusyException()
            }
            try {
                emitter.onNext(PodEvent.BluetoothConnecting)

                val podAddress = podState.bluetoothAddress
                    ?: throw FailedToConnectException("Missing bluetoothAddress, activate the pod first")

                if (!bleDeviceManager.isBluetoothAvailable()) {
                    throw ConnectException("Bluetooth not available")
                }
                if (!bleDeviceManager.ensureBondedIfRequired(podAddress)) {
                    throw ConnectException("Bluetooth not available or bonding failed")
                }

                val conn = connection ?: bleConnectionFactory.createConnection(podAddress)
                connection = conn
                if (conn.connectionState() is Connected && conn.session != null) {
                    emitter.onNext(PodEvent.AlreadyConnected(podAddress))
                    busy.set(false)
                    emitter.onComplete()
                    return@create
                }

                conn.connect(connectionWaitCond)

                emitter.onNext(PodEvent.BluetoothConnected(podAddress))
                emitter.onNext(PodEvent.EstablishingSession)
                establishSession(1.toByte())
                emitter.onNext(PodEvent.Connected)

                busy.set(false)
                emitter.onComplete()
            } catch (ex: Exception) {
                aapsLogger.error(LTag.PUMPBTCOMM, "O5 connection failed", ex)
                disconnect(false)
                emitter.tryOnError(ex)
            } finally {
                busy.set(false)
            }
        }

    private fun establishSession(msgSeq: Byte) {
        val conn = assertConnected()
        val ltk = assertPaired()
        val ids = assertIdentity()

        val eapSqn = podState.increaseEapAkaSequenceNumber()
        var newSqn = conn.establishSession(ltk, msgSeq, ids, eapSqn)

        if (newSqn != null) {
            aapsLogger.info(LTag.PUMPBTCOMM, "Updating O5 EAP SQN to: $newSqn")
            podState.eapAkaSequenceNumber = newSqn.toLong()
            newSqn = conn.establishSession(ltk, msgSeq, ids, podState.increaseEapAkaSequenceNumber())
            if (newSqn != null) {
                throw SessionEstablishmentException("Received resynchronization SQN for the second time (O5)")
            }
        }
        podState.successfulConnections++
        podState.commitEapAkaSequenceNumber()
    }

    private fun assertPaired(): ByteArray =
        podState.ltk ?: throw FailedToConnectException("Missing LTK, activate the O5 pod first")

    private fun assertIdentity(): Ids {
        val controllerId = podState.controllerId
            ?: throw FailedToConnectException("Missing controllerId, activate the O5 pod first")
        val podId = podState.podId
            ?: throw FailedToConnectException("Missing podId, activate the O5 pod first")
        return Ids.forController(Id.fromLong(controllerId), Id.fromLong(podId))
    }

    private fun assertConnected(): BleConnection =
        connection ?: throw FailedToConnectException("connection lost (O5)")

    override fun pairNewPod(): Observable<PodEvent> = Observable.create { emitter ->
        if (!busy.compareAndSet(false, true)) {
            throw BusyException()
        }
        try {
            if (podState.ltk != null) {
                emitter.onNext(PodEvent.AlreadyPaired)
                emitter.onComplete()
                return@create
            }
            aapsLogger.info(LTag.PUMPBTCOMM, "Starting new O5 pod activation")

            val controllerId = O5RegistrationData.pickControllerId
            if (controllerId == 0L) {
                throw PairingException(
                    "No O5 registration data available - cannot pair an Omnipod 5 pod without " +
                        "real Insulet-issued controller credentials (see O5RegistrationData)"
                )
            }
            val certStore = O5CertificateStore(aapsLogger, p256KeyGenerator, controllerId)

            val adapter = bluetoothAdapter ?: throw ConnectException("Bluetooth not available")
            emitter.onNext(PodEvent.Scanning)
            val scanner = PodTypeAwarePodScanner(aapsLogger, adapter)
            val discovered = scanner.scanForPod(PodType.OMNIPOD_5)
            podState.bluetoothAddress = discovered.address

            emitter.onNext(PodEvent.BluetoothConnecting)
            val conn = bleConnectionFactory.createConnection(discovered.address, controllerId)
            connection = conn
            conn.connect(ConnectionWaitCondition(timeoutMs = BleConnection.DEFAULT_CONNECT_TIMEOUT_MS))
            emitter.onNext(PodEvent.BluetoothConnected(discovered.address))

            emitter.onNext(PodEvent.Pairing)
            val mIO = conn.msgIO ?: throw ConnectException("Connection lost")

            val myId = Id.fromLong(certStore.controllerId)
            val podId = myId.increment()

            val ltkExchanger = O5LTKExchanger(aapsLogger, mIO, certStore, myId, podId)
            val pairResult = ltkExchanger.o5NegotiateLTK()
            emitter.onNext(PodEvent.Paired(podId))

            podState.updateFromPairing(certStore.controllerId, podId.toLong(), pairResult)
            if (config.DEBUG) {
                aapsLogger.info(LTag.PUMPCOMM, "Got O5 LTK: ${pairResult.ltk.toHex()}")
            }

            emitter.onNext(PodEvent.EstablishingSession)
            establishSession(pairResult.msgSeq)
            podState.successfulConnections++
            emitter.onNext(PodEvent.Connected)
            emitter.onComplete()
        } catch (ex: Exception) {
            aapsLogger.error(LTag.PUMPBTCOMM, "O5 pod activation failed", ex)
            disconnect(false)
            emitter.tryOnError(ex)
        } finally {
            busy.set(false)
        }
    }

    override fun sendAidSetupCommands(): Completable = Completable.fromAction {
        if (!busy.compareAndSet(false, true)) {
            throw BusyException()
        }
        try {
            val session = assertSessionEstablished()
            O5AidSetupCommands.send(session)
        } catch (ex: Exception) {
            disconnect(false)
            throw ex
        } finally {
            busy.set(false)
        }
    }

    override fun disconnect(closeGatt: Boolean) {
        connection?.disconnect(closeGatt)
            ?: aapsLogger.info(LTag.PUMPBTCOMM, "Trying to disconnect a null O5 connection")
    }

    override fun removeBond() {
        val address = podState.bluetoothAddress
        if (address == null) {
            aapsLogger.error(LTag.PUMPBTCOMM, "removeBond (O5): MAC address not found")
            return
        }
        bleDeviceManager.removeBond(address)
    }
}
