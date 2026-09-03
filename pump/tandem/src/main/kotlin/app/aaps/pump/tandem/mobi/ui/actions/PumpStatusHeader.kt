@file:OptIn(ExperimentalMaterial3Api::class)

package app.aaps.pump.tandem.mobi.ui.actions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.pump.common.defs.PumpRunningState
import app.aaps.pump.tandem.R
import app.aaps.pump.tandem.common.driver.LocalTandemDataStore
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.LoadStatusResponse

private const val MOBI_CAPACITY_U = 200

@Composable
fun PumpStatusHeader(resourceHelper: ResourceHelper) {
    val ds = LocalTandemDataStore.current
    val pumpRunningState = ds.pumpRunningState.observeAsState()
    val loadStatus = ds.loadStatus.observeAsState()
    val insulinStatus = ds.insulinStatus.observeAsState()

    val cartridgeUnits = insulinStatus.value?.currentInsulinAmount ?: 0
    val fillFraction = (cartridgeUnits.toFloat() / MOBI_CAPACITY_U).coerceIn(0f, 1f)
    val cartridgeEmpty = cartridgeUnits <= 0

    val workflowText: String? = if (loadStatus.value?.isLoadingActive == true) {
        when (loadStatus.value?.loadState) {
            LoadStatusResponse.LoadState.CHANGE_CARTRIDGE,
            LoadStatusResponse.LoadState.LOAD_CARTRIDGE -> resourceHelper.gs(R.string.ps_workflow_changing)
            LoadStatusResponse.LoadState.PRIME_TUBING   -> resourceHelper.gs(R.string.ps_workflow_filling_tubing)
            LoadStatusResponse.LoadState.PRIME_CANNULA  -> resourceHelper.gs(R.string.ps_workflow_filling_cannula)
            else                                        -> null
        }
    } else null

    val running = pumpRunningState.value == PumpRunningState.Running
    val deliveryIcon: ImageVector = if (running) Icons.Filled.PlayCircle else Icons.Filled.PauseCircle
    val deliveryTint = if (running) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant
    val deliveryText = resourceHelper.gs(
        if (running) R.string.ps_insulin_delivering else R.string.ps_insulin_suspended
    )

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
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MobiSilhouetteWithFill(
                fillFraction = fillFraction,
                modifier = Modifier.size(96.dp),
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = resourceHelper.gs(R.string.ps_cartridge_units, cartridgeUnits, MOBI_CAPACITY_U),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                LinearProgressIndicator(
                    progress = { fillFraction },
                    modifier = Modifier.fillMaxWidth(),
                    color = InsulinFillColor,
                )
                if (cartridgeEmpty) {
                    StatusLine(
                        icon = Icons.Filled.Warning,
                        text = resourceHelper.gs(R.string.ps_cartridge_empty),
                        tint = MaterialTheme.colorScheme.error,
                    )
                } else if (workflowText != null) {
                    StatusLine(
                        icon = Icons.Filled.Settings,
                        text = workflowText,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusLine(
                    icon = deliveryIcon,
                    text = deliveryText,
                    tint = deliveryTint,
                )
            }
        }
    }
}

@Composable
private fun StatusLine(icon: ImageVector, text: String, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = tint)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = tint,
        )
    }
}
