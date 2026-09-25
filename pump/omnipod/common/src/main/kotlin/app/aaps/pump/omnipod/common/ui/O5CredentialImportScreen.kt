package app.aaps.pump.omnipod.common.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.pump.omnipod.common.bledriver.comm.pair.O5RegistrationData

/**
 * Settings screen for importing an Omnipod 5 credential and viewing/removing already-installed
 * ones. No dosing/pairing/connection actions live here - purely credential management, feeding
 * [O5RegistrationData] for whenever actual O5 pairing is attempted elsewhere.
 *
 * Two ways to add a credential: download one with a token (shown only when this build has a
 * token server set), or paste a credential string by hand.
 *
 * Wired in via [app.aaps.pump.omnipod.common.ui.compose.OmnipodO5ComposeContent] - reached
 * from the settings gear icon, and auto-routed to from "Activate Pod" when no registration
 * credentials are installed yet.
 */
@Composable
fun O5CredentialImportScreen(
    viewModel: O5CredentialImportViewModel,
    rh: ResourceHelper
) {
    val inputText by viewModel.inputText.collectAsState()
    val tokenInput by viewModel.tokenInput.collectAsState()
    val isDownloading by viewModel.isDownloading.collectAsState()
    val importResult by viewModel.importResult.collectAsState()
    val installedCredentials by viewModel.installedCredentials.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Omnipod 5 Credential",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        // Token download path - shown only when this build points at a token server.
        if (viewModel.tokenDownloadAvailable) {
            Text(
                text = "Enter the token you were given to download a credential. This does not " +
                    "pair with a pod by itself - it only makes the credential available for pairing.",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
                value = tokenInput,
                onValueChange = viewModel::onTokenChanged,
                label = { Text("Token") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false
                ),
                enabled = !isDownloading
            )
            Button(
                onClick = viewModel::downloadWithToken,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isDownloading
            ) {
                Text(if (isDownloading) "Downloading…" else "Download credential")
            }

            HorizontalDivider()
            Text(
                text = "Or paste a credential string",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
        }

        Text(
            text = "Paste a credential string obtained from a trusted source. This does not " +
                "pair with a pod by itself - it only makes the credential available for pairing.",
            style = MaterialTheme.typography.bodySmall
        )

        OutlinedTextField(
            value = inputText,
            onValueChange = viewModel::onInputChanged,
            label = { Text("Credential string") },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 180.dp),
            singleLine = false,
            enabled = !isDownloading
        )

        when (val result = importResult) {
            is ImportResult.Success -> Text(
                text = "Imported credential for controller 0x%08X".format(result.controllerId),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall
            )

            is ImportResult.Failure -> Text(
                text = result.reason,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )

            ImportResult.None       -> Unit
        }

        Button(
            onClick = viewModel::importCurrentInput,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isDownloading
        ) {
            Text("Import")
        }

        if (installedCredentials.isNotEmpty()) {
            Text(
                text = "Installed credentials",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                installedCredentials.forEach { row ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = "Controller 0x%08X".format(row.controllerId),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = row.source.name.lowercase().replaceFirstChar { it.uppercase() },
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            TextButton(onClick = { viewModel.removeCredential(row.controllerId) }) {
                                Text("Remove")
                            }
                        }
                    }
                }
            }
        }
    }
}
