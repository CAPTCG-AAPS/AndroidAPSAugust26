package app.aaps.pump.omnipod.omnipod5.ui.compose

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.interfaces.protection.ProtectionCheck
import app.aaps.core.interfaces.pump.BlePreCheck
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.ui.compose.ComposablePluginContent
import app.aaps.core.ui.compose.ToolbarConfig
import app.aaps.core.ui.compose.dialogs.OkCancelDialog
import app.aaps.core.ui.compose.dialogs.OkDialog
import app.aaps.core.ui.compose.pump.BlePreCheckHost
import app.aaps.core.ui.compose.pump.KeepScreenOnEffect
import app.aaps.core.ui.compose.pump.PumpOverviewScreen
import app.aaps.pump.omnipod.common.R
import app.aaps.pump.omnipod.omnipod5.bledriver.comm.pair.O5RegistrationData
import app.aaps.pump.omnipod.omnipod5.ui.O5CredentialImportScreen
import app.aaps.pump.omnipod.omnipod5.ui.O5CredentialImportViewModel
import app.aaps.pump.omnipod.common.ui.compose.PodImage
import app.aaps.pump.omnipod.common.ui.wizard.compose.ActivationType
import app.aaps.pump.omnipod.omnipod5.ui.wizard.compose.O5OmnipodWizardViewModel
import app.aaps.pump.omnipod.common.ui.wizard.compose.OmnipodOverviewEvent
import app.aaps.pump.omnipod.common.ui.wizard.compose.OmnipodWizardScreen

/**
 * O5's plugin-settings entry composable - the `.composeContent {}` render target for
 * [app.aaps.pump.omnipod.omnipod5.O5PumpPlugin]. Mirrors
 * [app.aaps.pump.omnipod.dash.ui.compose.OmnipodDashComposeContent]'s overview/wizard
 * toggle, but with a credentials screen in place of Dash's pod-history screen (O5 has no
 * history feature) - the overview is always shown first; the settings gear opens the plugin
 * preferences screen (which hosts the certificate store as a subpage), and "Activate Pod"
 * routes to [O5CredentialImportScreen] first only if no registration credentials are installed
 * yet (matches the confirmed UX choice - no auto-gating the whole screen).
 */
class OmnipodO5ComposeContent(
    private val pluginName: String,
    private val protectionCheck: ProtectionCheck,
    private val blePreCheck: BlePreCheck,
    private val rh: ResourceHelper
) : ComposablePluginContent {

    @Composable
    override fun Render(
        setToolbarConfig: (ToolbarConfig) -> Unit,
        onNavigateBack: () -> Unit,
        onSettings: (() -> Unit)?
    ) {
        val overviewViewModel: O5OverviewViewModel = hiltViewModel()
        val context = LocalContext.current

        var showWizard by remember { mutableStateOf(false) }
        var showCredentials by remember { mutableStateOf(false) }
        var wizardActivationType by remember { mutableStateOf<ActivationType?>(null) }
        var isDeactivation by remember { mutableStateOf(false) }

        var showDialog by remember { mutableStateOf(false) }
        var dialogTitle by remember { mutableStateOf("") }
        var dialogMessage by remember { mutableStateOf("") }
        var showDiscardConfirm by remember { mutableStateOf(false) }
        var showErrorDialog by remember { mutableStateOf(false) }
        var errorTitle by remember { mutableStateOf("") }
        var errorMessage by remember { mutableStateOf("") }

        val overviewNavIcon: @Composable () -> Unit = {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(app.aaps.core.ui.R.string.back))
            }
        }
        val settingsAction: @Composable RowScope.() -> Unit = {
            onSettings?.let { action ->
                IconButton(onClick = action) {
                    Icon(Icons.Filled.Settings, contentDescription = stringResource(app.aaps.core.ui.R.string.settings))
                }
            }
        }

        LaunchedEffect(showWizard, showCredentials) {
            if (!showWizard && !showCredentials) {
                setToolbarConfig(ToolbarConfig(title = pluginName, navigationIcon = overviewNavIcon, actions = settingsAction))
            }
        }

        LaunchedEffect(overviewViewModel) {
            overviewViewModel.events.collect { event ->
                when (event) {
                    is OmnipodOverviewEvent.StartActivation         -> {
                        if (O5RegistrationData.pickControllerId == 0L) {
                            showCredentials = true
                        } else {
                            wizardActivationType = event.activationType
                            isDeactivation = false
                            showWizard = true
                        }
                    }

                    is OmnipodOverviewEvent.StartDeactivation       -> {
                        isDeactivation = true
                        showWizard = true
                    }

                    is OmnipodOverviewEvent.ShowHistory             -> Unit

                    is OmnipodOverviewEvent.ShowDialog              -> {
                        if (event.title == context.getString(R.string.omnipod_common_pod_management_button_discard_pod)) {
                            showDiscardConfirm = true
                            dialogTitle = event.title
                            dialogMessage = event.message
                        } else {
                            dialogTitle = event.title
                            dialogMessage = event.message
                            showDialog = true
                        }
                    }

                    is OmnipodOverviewEvent.ShowErrorDialog         -> {
                        errorTitle = event.title
                        errorMessage = event.message
                        showErrorDialog = true
                    }

                    is OmnipodOverviewEvent.StartActivity           -> context.startActivity(event.intent)
                    is OmnipodOverviewEvent.ShowRileyLinkPairWizard -> Unit
                    is OmnipodOverviewEvent.ShowRileyLinkStats      -> Unit
                    is OmnipodOverviewEvent.ShowSnackbar            -> Unit
                }
            }
        }

        if (showDialog) {
            OkDialog(title = dialogTitle, message = dialogMessage, onDismiss = { showDialog = false })
        }
        if (showErrorDialog) {
            OkDialog(title = errorTitle, message = errorMessage, onDismiss = { showErrorDialog = false })
        }
        if (showDiscardConfirm) {
            OkCancelDialog(
                title = dialogTitle,
                message = dialogMessage,
                onConfirm = {
                    showDiscardConfirm = false
                    overviewViewModel.confirmDiscardPod()
                },
                onDismiss = { showDiscardConfirm = false }
            )
        }

        when {
            showWizard      -> {
                KeepScreenOnEffect()
                BlePreCheckHost(blePreCheck = blePreCheck, onFailed = { showWizard = false })

                val wizardViewModel: O5OmnipodWizardViewModel = hiltViewModel()
                val wizardReady by wizardViewModel.ready.collectAsStateWithLifecycle()
                LaunchedEffect(wizardReady, isDeactivation, wizardActivationType) {
                    if (!wizardReady) return@LaunchedEffect
                    if (isDeactivation) {
                        wizardViewModel.initializeDeactivation()
                    } else {
                        wizardActivationType?.let { wizardViewModel.initializeActivation(it) }
                    }
                }

                OmnipodWizardScreen(
                    viewModel = wizardViewModel,
                    onFinish = { showWizard = false },
                    setToolbarConfig = setToolbarConfig
                )
            }

            showCredentials -> {
                LaunchedEffect(Unit) {
                    setToolbarConfig(
                        ToolbarConfig(
                            title = rh.gs(R.string.omnipod_5_name),
                            navigationIcon = {
                                IconButton(onClick = { showCredentials = false }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(app.aaps.core.ui.R.string.back))
                                }
                            },
                            actions = {}
                        )
                    )
                }
                val credentialViewModel: O5CredentialImportViewModel = hiltViewModel()
                O5CredentialImportScreen(viewModel = credentialViewModel, rh = rh)
            }

            else            -> {
                val uiState by overviewViewModel.uiState.collectAsStateWithLifecycle()
                PumpOverviewScreen(state = uiState, customContent = { PodImage() })
            }
        }
    }
}
