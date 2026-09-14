package app.aaps.pump.tandem.mobi.ui.data

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.pump.tandem.R
import app.aaps.pump.tandem.common.database.data.dto.TandemHistoryRecordDto
import app.aaps.pump.tandem.mobi.ui.util.DottedDivider
import app.aaps.pump.tandem.mobi.ui.util.dateTimeFormatter24h
import app.aaps.pump.tandem.mobi.ui.util.timeInMillisToLocalDateTime

@Composable
fun HistoryEntryDisplay(
    title: String,
    historyRecordDto: TandemHistoryRecordDto,
    onDismiss: () -> Unit,
    resourceHelper: ResourceHelper,
    labelWidth: Dp = 100.dp                // tweak to align the left column
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                KeyValueRow(label = resourceHelper.gs(R.string.data_history_log_date_time) + ":",
                            value = timeInMillisToLocalDateTime(historyRecordDto.pumpTime).format(dateTimeFormatter24h),
                            labelWidth = labelWidth)
                DottedDivider(dotRadius = 1.dp, spaceBetween = 2.dp)

                KeyValueRow(label = resourceHelper.gs(R.string.data_history_log_name) + ":",
                            value = historyRecordDto.name,
                            labelWidth = labelWidth)
                DottedDivider(dotRadius = 1.dp, spaceBetween = 2.dp)

                KeyValueRow(label = resourceHelper.gs(R.string.data_history_log_group) + ":",
                            value = historyRecordDto.group.getDisplayValue(),
                            labelWidth = labelWidth)
                DottedDivider(dotRadius = 1.dp, spaceBetween = 2.dp)

                KeyValueRow(label = resourceHelper.gs(R.string.data_history_log_details) + ":",
                            value = historyRecordDto.descriptionLines,
                            labelWidth = labelWidth)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) {
            Text(text = resourceHelper.gs(R.string.common_close))
        } }
    )
}


@Composable
private fun KeyValueRow(
    label: String,
    value: String,
    labelWidth: Dp
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            modifier = Modifier.width(labelWidth),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            // Support multi-line values: split on '\n'
            value.split('\n').forEachIndexed { idx, line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodyMedium
                )
                if (idx != 0) Spacer(Modifier.height(2.dp))
            }
        }
    }
}
