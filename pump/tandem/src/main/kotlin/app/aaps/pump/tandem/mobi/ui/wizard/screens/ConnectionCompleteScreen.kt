package app.aaps.pump.tandem.mobi.ui.wizard.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.aaps.pump.tandem.R
import app.aaps.pump.tandem.common.data.defs.TandemPumpApiVersion

@Composable
fun ConnectionCompleteScreen(
    pumpSerial: String,
    pumpName: String,
    pumpApiVersion: TandemPumpApiVersion = TandemPumpApiVersion.Unknown,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = "Success",
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.tandem_wizard_success_title),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.tandem_wizard_success_description),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = stringResource(R.string.tandem_wizard_pump_name_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = pumpName,
                style = MaterialTheme.typography.bodyLarge
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.tandem_wizard_pump_serial_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = pumpSerial,
                style = MaterialTheme.typography.bodyLarge
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.tandem_wizard_pump_supported_capabilities),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SupportedCapabilitiesForVersion(pumpApiVersion)
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onFinish,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(R.string.tandem_wizard_finish),
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@Composable
fun SupportedCapabilitiesForVersion(apiVersion: TandemPumpApiVersion) {

    @Composable
    fun IconAndText(ok: Boolean, text: String) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            Icon(
                imageVector = if (ok) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = if (ok) "Supported" else "Warning",
                modifier = Modifier.size(20.dp),
                tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }

    Column(
        modifier = Modifier.padding(start = 8.dp, top = 4.dp)
    ) {
        if (apiVersion.isClosedLoopPossible) {
            IconAndText(true, stringResource(R.string.tandem_wizard_pump_closed_loop_supported))
        } else if (apiVersion.hasBolus) {
            IconAndText(true, stringResource(R.string.tandem_wizard_pump_bolus_only_supported))
        } else {
            IconAndText(true, stringResource(R.string.tandem_wizard_pump_open_loop_only))
        }
    }
}
