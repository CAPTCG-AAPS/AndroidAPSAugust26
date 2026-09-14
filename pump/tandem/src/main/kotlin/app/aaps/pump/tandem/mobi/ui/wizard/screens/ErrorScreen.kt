package app.aaps.pump.tandem.mobi.ui.wizard.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.aaps.pump.tandem.R
import app.aaps.pump.tandem.common.events.PairingError

@Composable
fun ErrorScreen(
    error: PairingError,
    retryCount: Int,
    onEditPIN: () -> Unit,
    onRetry: () -> Unit,
    onCancelAndRescan: () -> Unit,
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
            imageVector = Icons.Default.Error,
            contentDescription = "Error",
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.error
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.tandem_wizard_error_title),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.error
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = getErrorMessage(error),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )

        if (retryCount > 1) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.tandem_wizard_retry_count, retryCount),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Show Edit PIN button for incorrect PIN errors
        if (error is PairingError.IncorrectPIN) {
            Button(
                onClick = onEditPIN,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.tandem_wizard_edit_pin),
                    style = MaterialTheme.typography.labelLarge
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        OutlinedButton(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(R.string.tandem_wizard_retry),
                style = MaterialTheme.typography.labelLarge
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        TextButton(
            onClick = onCancelAndRescan,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.tandem_wizard_cancel_rescan))
        }
    }
}

@Composable
private fun getErrorMessage(error: PairingError): String {
    return when (error) {
        is PairingError.IncorrectPIN ->
            stringResource(R.string.tandem_wizard_error_incorrect_pin)
        is PairingError.ConnectionTimeout ->
            stringResource(R.string.tandem_wizard_error_timeout)
        is PairingError.BluetoothError ->
            stringResource(R.string.tandem_wizard_error_bluetooth)
        is PairingError.UnknownError ->
            stringResource(R.string.tandem_wizard_error_unknown)
    }
}
