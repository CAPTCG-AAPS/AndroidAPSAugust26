package app.aaps.pump.omnipod.omnipod5.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.ui.compose.AapsTopAppBar
import app.aaps.pump.omnipod.common.R

@Composable
fun O5CertificateStoreScreen(
    rh: ResourceHelper,
    onBack: () -> Unit
) {
    val viewModel: O5CredentialImportViewModel = hiltViewModel()
    Scaffold(
        topBar = {
            AapsTopAppBar(
                title = { Text(stringResource(R.string.omnipod_5_certificate_store)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(app.aaps.core.ui.R.string.back)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            O5CredentialImportScreen(viewModel = viewModel, rh = rh)
        }
    }
}
