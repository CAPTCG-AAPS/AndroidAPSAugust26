@file:OptIn(ExperimentalMaterial3Api::class)

package app.aaps.pump.tandem.mobi.ui.actions.cartridge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.ui.R as Rco
import app.aaps.pump.tandem.R
import app.aaps.pump.tandem.mobi.ui.util.NotificationDismissPills
import com.jwoglom.pumpx2.pump.messages.Message
import com.jwoglom.pumpx2.pump.messages.request.control.DismissNotificationRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.AlarmStatusRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.AlertStatusRequest
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.AlertStatusResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Inline pill ("✓ No active Pump notifications.") for the empty state. */
@Composable
fun CartridgeNotificationsPanel(
    resourceHelper: ResourceHelper,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = Color(0xFF2E7D32) // Material Green 800
            )
            Text(
                text = resourceHelper.gs(R.string.ca_notifications_none),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
fun CartridgeNotificationsBlockingDialog(
    notifications: List<Any>,
    sendPumpCommands: (List<Message>) -> Boolean,
    refreshScope: CoroutineScope,
    resourceHelper: ResourceHelper,
) {
    val incompleteAlertBodies = linkedMapOf(
        AlertStatusResponse.AlertResponseType.INCOMPLETE_CARTRIDGE_CHANGE_ALERT to R.string.cc_incomplete_change_dialog_body,
        AlertStatusResponse.AlertResponseType.INCOMPLETE_FILL_TUBING_ALERT to R.string.ft_incomplete_fill_dialog_body,
        AlertStatusResponse.AlertResponseType.FILL_TUBING_STILL_IN_PROGRESS to R.string.ft_incomplete_fill_dialog_body,
        AlertStatusResponse.AlertResponseType.INCOMPLETE_FILL_CANNULA_ALERT to R.string.fc_incomplete_fill_dialog_body,
    )
    val matchedAlert = incompleteAlertBodies.keys.firstOrNull { it in notifications }
    if (matchedAlert != null) {
        IncompleteWorkflowConfirmDialog(
            alert = matchedAlert,
            bodyRes = incompleteAlertBodies.getValue(matchedAlert),
            sendPumpCommands = sendPumpCommands,
            refreshScope = refreshScope,
            resourceHelper = resourceHelper,
        )
        return
    }

    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
        icon = {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = {
            Text(
                text = resourceHelper.gs(R.string.ca_dismiss_notifications_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            NotificationDismissPills(
                notifications = notifications,
                sendPumpCommands = sendPumpCommands,
                refreshScope = refreshScope,
            )
        },
        confirmButton = {},
    )
}

@Composable
private fun IncompleteWorkflowConfirmDialog(
    alert: AlertStatusResponse.AlertResponseType,
    bodyRes: Int,
    sendPumpCommands: (List<Message>) -> Boolean,
    refreshScope: CoroutineScope,
    resourceHelper: ResourceHelper,
) {
    var dismissing by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
        icon = {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        text = {
            Text(
                text = resourceHelper.gs(bodyRes),
                style = MaterialTheme.typography.bodyLarge,
            )
        },
        confirmButton = {
            TextButton(
                enabled = !dismissing,
                onClick = {
                    dismissing = true
                    refreshScope.launch {
                        sendPumpCommands(listOf(
                            DismissNotificationRequest(
                                DismissNotificationRequest.NotificationType.ALERT,
                                alert.bitmask().toLong()
                            )
                        ))
                        delay(500)
                        sendPumpCommands(listOf(AlertStatusRequest(), AlarmStatusRequest()))
                        dismissing = false
                    }
                }
            ) { Text(resourceHelper.gs(Rco.string.ok)) }
        },
    )
}
