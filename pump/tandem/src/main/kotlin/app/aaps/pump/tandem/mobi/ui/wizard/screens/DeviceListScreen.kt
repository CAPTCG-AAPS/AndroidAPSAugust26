package app.aaps.pump.tandem.mobi.ui.wizard.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.aaps.pump.tandem.R
import app.aaps.pump.tandem.mobi.ui.wizard.ScannedDevice

@Composable
fun DeviceListScreen(
    scannedDevices: List<ScannedDevice>,
    isScanning: Boolean,
    currentlySelectedAddress: String,
    currentlySelectedName: String,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onDeviceSelected: (ScannedDevice) -> Unit,
    onBack: () -> Unit
) {
    LaunchedEffect(Unit) {
        onStartScan()
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        // Currently selected device section (if any)
        if (currentlySelectedAddress.isNotEmpty()) {
            CurrentlySelectedDeviceSection(
                deviceName = currentlySelectedName,
                deviceAddress = currentlySelectedAddress
            )
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Scan controls
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {

            if (isScanning) {
                Button(
                    onClick = onStopScan,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.ble_config_scan_stop))
                }
            } else {
                Button(
                    onClick = onStartScan,
                    enabled = !isScanning,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.ble_config_scan_start))
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Scanning indicator
        if (isScanning) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                Text(
                    text = stringResource(R.string.ble_config_scanning),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Device list
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(scannedDevices) { device ->
                DeviceListItem(
                    device = device,
                    onDeviceClick = { onDeviceSelected(device) }
                )
            }

            if (scannedDevices.isEmpty()) {
                item {
                    if (!isScanning) {
                        Text(
                            text = stringResource(R.string.ble_config_no_devices),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(16.dp)
                        )
                    }
                }
            }
        }

        // Back button
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Back")
        }
    }
}

@Composable
private fun CurrentlySelectedDeviceSection(
    deviceName: String,
    deviceAddress: String
) {
    Column {
        Text(
            text = stringResource(R.string.tandem_ble_config_selected_pump_title),
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = deviceName,
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            text = deviceAddress,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DeviceListItem(
    device: ScannedDevice,
    onDeviceClick: () -> Unit
) {
    Card(
        onClick = onDeviceClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = device.name,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    if (device.isCurrentlySelected) {
                        Text(
                            text = "(selected)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Text(
                    text = device.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Signal strength indicator
            SignalStrengthIndicator(rssi = device.rssi)
        }
    }
}

@Composable
private fun SignalStrengthIndicator(rssi: Int) {
    // RSSI typically ranges from -100 (weak) to -30 (strong)
    Text(
        text = "$rssi dBm",
        style = MaterialTheme.typography.bodySmall,
        color = when {
            rssi >= -50 -> Color.Green
            rssi >= -70 -> Color(0xFFFFEB3B) // Yellow
            else -> Color.Red
        }
    )
}
