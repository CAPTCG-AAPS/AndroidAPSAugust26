package app.aaps.pump.tandem.mobi.ui.wizard

import android.bluetooth.BluetoothDevice
import app.aaps.pump.tandem.common.data.defs.TandemPumpApiVersion
import app.aaps.pump.tandem.common.events.PairingError

/**
 * State for the Tandem Mobi connection wizard
 * Navigation is now handled by NavController, not state
 */
data class TandemMobiWizardState(
    // Device selection
    val selectedDevice: BluetoothDevice? = null,
    val deviceAddress: String = "",
    val deviceName: String = "",

    // BLE scanning properties
    val isScanning: Boolean = false,
    val scannedDevices: List<ScannedDevice> = emptyList(),
    val scanError: String? = null,
    val hasBluetoothPermission: Boolean = false,
    val isBluetoothEnabled: Boolean = false,

    // PIN entry
    val enteredPIN: String = "",

    // Pairing
    val pairingStatus: Int = -1,
    val pairingLabel: String = "",
    val pairingError: PairingError? = null,
    val retryCount: Int = 0,

    // General wizard state
    val isRePairing: Boolean = false,
    val pairedPumpSerial: String = "",
    val pairedPumpName: String = "",
    val pairedPumpApiVersion: TandemPumpApiVersion = TandemPumpApiVersion.Unknown,

    // Existing pump info (for confirmation screen)
    val existingPumpName: String = "",
    val existingPumpSerial: String = "",
    val existingPumpAddress: String = ""
)

/**
 * Represents a scanned BLE device
 */
data class ScannedDevice(
    val address: String,
    val name: String,
    val rssi: Int,
    val device: BluetoothDevice,
    val isCurrentlySelected: Boolean = false
)
