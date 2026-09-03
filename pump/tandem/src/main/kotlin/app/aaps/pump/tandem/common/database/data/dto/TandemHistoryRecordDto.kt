package app.aaps.pump.tandem.common.database.data.dto

import app.aaps.pump.common.defs.PumpHistoryEntryGroup
import com.jwoglom.pumpx2.pump.messages.response.historyLog.HistoryLog


data class TandemHistoryRecordDto(
    var sequenceId: Long,
    var pumpTime: Long,   // EpochInMillis (pump stores time as EpochSeconds from Jan2008, we don't)
    var entitySubId: Int? = null, // some entities have special id (for example TBR has tempRateId)

    var name: String,
    var description: String? = null,
    var descriptionLines: String = "",

    var group: PumpHistoryEntryGroup,

    var historyLog : HistoryLog,
)
