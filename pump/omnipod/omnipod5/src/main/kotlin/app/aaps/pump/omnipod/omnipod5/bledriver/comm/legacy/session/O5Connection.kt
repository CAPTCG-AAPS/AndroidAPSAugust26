package app.aaps.pump.omnipod.omnipod5.bledriver.comm.legacy.session
import app.aaps.pump.omnipod.common.bledriver.comm.legacy.session.Connection
import app.aaps.pump.omnipod.common.bledriver.comm.legacy.session.ServiceDiscoverer

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.SystemClock
import app.aaps.core.data.configuration.Constants
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.utils.toHex
import app.aaps.pump.omnipod.common.bledriver.comm.Ids
import app.aaps.pump.omnipod.common.bledriver.comm.endecrypt.EnDecrypt
import app.aaps.pump.omnipod.common.bledriver.comm.exceptions.ConnectException
import app.aaps.pump.omnipod.common.bledriver.comm.exceptions.FailedToConnectException
import app.aaps.pump.omnipod.common.bledriver.comm.interfaces.io.CharacteristicType
import app.aaps.pump.omnipod.common.bledriver.comm.interfaces.session.BleConnection
import app.aaps.pump.omnipod.common.bledriver.comm.legacy.callbacks.BleCommCallbacks
import app.aaps.pump.omnipod.common.bledriver.comm.legacy.io.CmdBleIO
import app.aaps.pump.omnipod.common.bledriver.comm.legacy.io.DataBleIO
import app.aaps.pump.omnipod.common.bledriver.comm.legacy.io.IncomingPackets
import app.aaps.pump.omnipod.common.bledriver.comm.message.MessageIO
import app.aaps.pump.omnipod.omnipod5.bledriver.comm.pair.O5CertificateStore
import app.aaps.pump.omnipod.common.bledriver.comm.session.Connected
import app.aaps.pump.omnipod.common.bledriver.comm.session.ConnectionState
import app.aaps.pump.omnipod.common.bledriver.comm.session.ConnectionWaitCondition
import app.aaps.pump.omnipod.common.bledriver.comm.session.DisconnectHandler
import app.aaps.pump.omnipod.common.bledriver.comm.session.EapSqn
import app.aaps.pump.omnipod.common.bledriver.comm.session.NotConnected
import app.aaps.pump.omnipod.common.bledriver.comm.session.STOP_CONNECTING_CHECK_INTERVAL_MS
import app.aaps.pump.omnipod.common.bledriver.comm.session.Session
import app.aaps.pump.omnipod.common.bledriver.comm.session.SessionEstablisher
import app.aaps.pump.omnipod.common.bledriver.comm.session.SessionKeys
import app.aaps.pump.omnipod.common.bledriver.comm.session.SessionNegotiationResynchronization
import app.aaps.pump.omnipod.common.bledriver.pod.definition.PodType
import app.aaps.pump.omnipod.omnipod5.bledriver.pod.state.O5PodStateManager
import app.aaps.pump.omnipod.omnipod5.bledriver.pod.util.P256KeyGenerator

/**
 * BLE GATT connection lifecycle and session establishment for Omnipod 5, parallel to
 * Dash's [Connection] rather than a modification of it - [Connection] is coupled
 * throughout to the much larger [app.aaps.pump.omnipod.common.bledriver.pod.state
 * .OmnipodDashPodStateManager] (full pod status surface: basal programs, alerts,
 * delivery status, etc.), which doesn't fit O5's connection-only [O5PodStateManager].
 *
 * Everything below the GATT-characteristic-selection level - [ServiceDiscoverer] (called
 * here with [PodType.OMNIPOD_5]), [CmdBleIO], [DataBleIO], [MessageIO],
 * [SessionEstablisher], [Session], [EnDecrypt] - is reused unmodified from the existing
 * Dash connection path, since none of it is actually Dash-specific once given the right
 * pod type and identity.
 */
class O5Connection(
    private val podDevice: BluetoothDevice,
    private val aapsLogger: AAPSLogger,
    private val config: Config,
    private val context: Context,
    private val podState: O5PodStateManager,
    private val p256KeyGenerator: P256KeyGenerator,
    private val pairingControllerId: Long? = null
) : BleConnection, DisconnectHandler {

    private val incomingPackets = IncomingPackets()
    private val bleCommCallbacks = BleCommCallbacks(aapsLogger, incomingPackets, this)
    private var gattConnection: BluetoothGatt? = null

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?

    private var _connectionWaitCond: ConnectionWaitCondition? = null

    @Volatile
    override var session: Session? = null

    @Volatile
    override var msgIO: MessageIO? = null

    @Synchronized
    override fun connect(connectionWaitCond: ConnectionWaitCondition) {
        aapsLogger.debug(LTag.PUMPBTCOMM, "Connecting (O5) connectionWaitCond=$connectionWaitCond")
        _connectionWaitCond = connectionWaitCond
        podState.connectionAttempts++
        podState.bluetoothConnectionState = O5PodStateManager.BluetoothConnectionState.CONNECTING
        val autoConnect = false
        var gatt = gattConnection
        if (gatt == null) {
            gatt = podDevice.connectGatt(context, autoConnect, bleCommCallbacks, BluetoothDevice.TRANSPORT_LE)
            if (gatt == null) {
                Thread.sleep(SLEEP_WHEN_FAILING_TO_CONNECT_GATT)
                throw FailedToConnectException("connectGatt() returned null")
            }
            gattConnection = gatt
        } else if (!gatt.connect()) {
            throw FailedToConnectException("connect() returned false")
        }
        val before = SystemClock.elapsedRealtime()
        if (waitForConnection(connectionWaitCond) !is Connected) {
            podState.bluetoothConnectionState = O5PodStateManager.BluetoothConnectionState.DISCONNECTED
            _connectionWaitCond = null
            throw FailedToConnectException(podDevice.address)
        }
        val waitedMs = SystemClock.elapsedRealtime() - before
        val timeoutMs = connectionWaitCond.timeoutMs
        if (timeoutMs != null) {
            var newTimeout = timeoutMs - waitedMs
            if (newTimeout < MIN_DISCOVERY_TIMEOUT_MS) {
                newTimeout = MIN_DISCOVERY_TIMEOUT_MS
            }
            connectionWaitCond.timeoutMs = newTimeout
        }
        podState.bluetoothConnectionState = O5PodStateManager.BluetoothConnectionState.CONNECTED
        aapsLogger.debug(LTag.PUMPBTCOMM, "GATT connected (O5) to ${podDevice.address}, discovering services")

        val discoverer = ServiceDiscoverer(aapsLogger, gatt, bleCommCallbacks, this)
        val discovered = discoverer.discoverServices(connectionWaitCond, PodType.OMNIPOD_5)
        aapsLogger.debug(LTag.PUMPBTCOMM, "Service discovery complete (O5): characteristics=${discovered.keys}")

        requestLargeMtu(gatt)

        val cmdBleIO = CmdBleIO(
            aapsLogger,
            discovered.getValue(CharacteristicType.CMD),
            incomingPackets.cmdQueue,
            gatt,
            bleCommCallbacks
        )
        val dataBleIO = DataBleIO(
            aapsLogger,
            discovered.getValue(CharacteristicType.DATA),
            incomingPackets.dataQueue,
            gatt,
            bleCommCallbacks,
            CharacteristicType.DATA_O5
        )
        msgIO = MessageIO(aapsLogger, cmdBleIO, dataBleIO, PodType.OMNIPOD_5)
        val helloControllerId = pairingControllerId ?: podState.controllerId
        if (helloControllerId != null) {
            aapsLogger.debug(
                LTag.PUMPBTCOMM,
                "Performing O5 CMD 'hello' handshake (controllerId=0x%08x) and enabling reads".format(helloControllerId)
            )
            cmdBleIO.hello(helloControllerId.toInt())
        } else {
            aapsLogger.warn(
                LTag.PUMPBTCOMM,
                "O5 'hello' handshake has no controllerId available - falling back to default, pairing will likely fail"
            )
            cmdBleIO.hello()
        }
        cmdBleIO.readyToRead()
        dataBleIO.readyToRead()
        aapsLogger.debug(LTag.PUMPBTCOMM, "O5 BLE connection ready (message IO established) for ${podDevice.address}")
        _connectionWaitCond = null
    }

    @Synchronized
    override fun disconnect(closeGatt: Boolean) {
        aapsLogger.debug(LTag.PUMPBTCOMM, "Disconnecting (O5) closeGatt=$closeGatt")
        if (!closeGatt && gattConnection != null) {
            gattConnection?.disconnect()
            podState.bluetoothConnectionState = O5PodStateManager.BluetoothConnectionState.DISCONNECTED
        } else {
            gattConnection?.close()
            bleCommCallbacks.resetConnection()
            gattConnection = null
            session = null
            msgIO = null
            podState.bluetoothConnectionState = O5PodStateManager.BluetoothConnectionState.DISCONNECTED
        }
    }

    /**
     * O5 messages are sent as single GATT writes up to [PodType.OMNIPOD_5]'s 244-byte
     * packet payload (see BlePacketLayout.OMNIPOD_5), which needs an ATT MTU of at least
     * [MIN_REQUIRED_MTU]. Android starts every connection at the 23-byte default and does
     * NOT auto-negotiate a larger MTU (unlike iOS/CoreBluetooth, which is why OmnipodKit
     * never has to ask). Without this, the first pairing message (SP1+SP2, ~51 bytes on
     * the wire) is truncated to ~20 bytes and the pod replies ABORT. Dash is unaffected
     * because its packets fit in the default MTU.
     */
    private fun requestLargeMtu(gatt: BluetoothGatt) {
        if (!gatt.requestMtu(REQUESTED_MTU)) {
            aapsLogger.warn(LTag.PUMPBTCOMM, "O5 requestMtu($REQUESTED_MTU) call returned false - continuing with default MTU")
            return
        }
        val completed = bleCommCallbacks.waitForMtuChange(MTU_NEGOTIATION_TIMEOUT_MS)
        val mtu = bleCommCallbacks.negotiatedMtu
        if (!completed) {
            aapsLogger.warn(LTag.PUMPBTCOMM, "O5 MTU negotiation timed out - continuing with MTU=$mtu")
        } else {
            aapsLogger.debug(LTag.PUMPBTCOMM, "O5 MTU negotiated: $mtu")
        }
        if (mtu < MIN_REQUIRED_MTU) {
            aapsLogger.warn(
                LTag.PUMPBTCOMM,
                "O5 negotiated MTU=$mtu is below the required $MIN_REQUIRED_MTU - large pairing messages may be truncated and rejected by the pod"
            )
        }
    }

    private fun waitForConnection(connectionWaitCond: ConnectionWaitCondition): ConnectionState {
        aapsLogger.debug(LTag.PUMPBTCOMM, "waitForConnection (O5) connectionWaitCond=$connectionWaitCond")
        try {
            connectionWaitCond.timeoutMs?.let {
                bleCommCallbacks.waitForConnection(it)
            }
            val startWaiting = System.currentTimeMillis()
            connectionWaitCond.stopConnection?.let {
                while (!bleCommCallbacks.waitForConnection(STOP_CONNECTING_CHECK_INTERVAL_MS)) {
                    if (it.count == 0L) {
                        throw ConnectException("stopConnecting called")
                    }
                    val secondsElapsed = (System.currentTimeMillis() - startWaiting) / 1000
                    if (secondsElapsed > MAX_WAIT_FOR_CONNECTION_SECONDS) {
                        throw ConnectException("connection timeout")
                    }
                }
            }
        } catch (e: InterruptedException) {
            aapsLogger.info(LTag.PUMPBTCOMM, "Interrupted while waiting for connection (O5)")
        }
        return connectionState()
    }

    override fun connectionState(): ConnectionState {
        val connectionState = bluetoothManager?.getConnectionState(podDevice, BluetoothProfile.GATT)
        aapsLogger.debug(LTag.PUMPBTCOMM, "GATT connection state (O5): $connectionState")
        if (connectionState != BluetoothProfile.STATE_CONNECTED) {
            return NotConnected
        }
        return Connected
    }

    /**
     * [ids] here must be built via [Ids.forController] using the pod's certificate-derived
     * controller id and its own O5 pod id - O5 has no [Ids] constructor of its own since it
     * has no Dash-style pod state manager to derive one from (see [Ids]'s class doc).
     */
    override fun establishSession(ltk: ByteArray, msgSeq: Byte, ids: Ids, eapSqn: ByteArray): EapSqn? {
        val mIO = msgIO ?: throw ConnectException("Connection lost")
        aapsLogger.debug(
            LTag.PUMPBTCOMM,
            "Establishing O5 session (EAP-AKA): msgSeq=$msgSeq, ltk=${ltk.size} bytes, eapSqn=${eapSqn.toHex()}, ids=$ids"
        )

        val eapAkaExchanger = SessionEstablisher(aapsLogger, config, mIO, ltk, eapSqn, ids, msgSeq)
        return when (val keys = eapAkaExchanger.negotiateSessionKeys()) {
            is SessionNegotiationResynchronization -> {
                aapsLogger.debug(LTag.PUMPBTCOMM, "O5 EAP-AKA requested SQN resynchronization: ${keys.synchronizedEapSqn}")
                if (config.DEBUG) {
                    aapsLogger.info(LTag.PUMPCOMM, "EAP AKA resynchronization (O5): ${keys.synchronizedEapSqn}")
                }
                keys.synchronizedEapSqn
            }

            is SessionKeys                         -> {
                if (config.DEBUG) {
                    aapsLogger.info(LTag.PUMPCOMM, "CK (O5): ${keys.ck.toHex()}")
                    aapsLogger.info(LTag.PUMPCOMM, "msgSequenceNumber (O5): ${keys.msgSequenceNumber}")
                    aapsLogger.info(LTag.PUMPCOMM, "Nonce (O5): ${keys.nonce}")
                }
                val enDecrypt = EnDecrypt(aapsLogger, keys.nonce, keys.ck)
                val controllerId = requireNotNull(podState.controllerId) {
                    "Missing controllerId, cannot establish a signed O5 session"
                }
                val certStore = O5CertificateStore(aapsLogger, p256KeyGenerator, controllerId)
                session = Session(aapsLogger, mIO, ids, sessionKeys = keys, enDecrypt = enDecrypt, commandSigner = certStore)
                aapsLogger.debug(
                    LTag.PUMPBTCOMM,
                    "O5 session established - encrypted channel ready (msgSeq=${keys.msgSequenceNumber})"
                )
                null
            }
        }
    }

    override fun onConnectionLost(status: Int) {
        aapsLogger.info(LTag.PUMPBTCOMM, "Lost connection (O5) with status: $status")
        _connectionWaitCond?.stopConnection?.let {
            if (it.count > 0) {
                it.countDown()
            }
        }
        disconnect(true)
    }

    companion object {
        const val MIN_DISCOVERY_TIMEOUT_MS = 10000L
        const val MAX_WAIT_FOR_CONNECTION_SECONDS = Constants.PUMP_MAX_CONNECTION_TIME_IN_SECONDS + 10
        const val SLEEP_WHEN_FAILING_TO_CONNECT_GATT = 10000L

        /** MTU we ask the phone to negotiate. 512 lets the stack settle on the highest the
         *  phone and pod both support; O5 only needs [MIN_REQUIRED_MTU]. */
        const val REQUESTED_MTU = 512

        /** Minimum ATT MTU that fits a full 244-byte O5 packet payload (244 + 3-byte ATT
         *  header). Below this, large pairing writes get truncated and the pod aborts. */
        const val MIN_REQUIRED_MTU = 247

        const val MTU_NEGOTIATION_TIMEOUT_MS = 5000L
    }
}
