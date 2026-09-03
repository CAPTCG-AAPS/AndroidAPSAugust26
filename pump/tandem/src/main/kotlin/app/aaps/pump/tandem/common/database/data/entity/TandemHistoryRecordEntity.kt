package app.aaps.pump.tandem.common.database.data.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(tableName = "history_records",
        primaryKeys = ["sequenceId", "pumpSerial"],
        indices = [
            Index(name = "idx_history_records_pumpTime", value = ["pumpTime"])
        ]
)
data class TandemHistoryRecordEntity(
    var sequenceId: Long,
    var pumpSerial: Int,
    var typeId: Int,
    var pumpTime: Long,   // EpochInMillis (pump stores time as EpochSeconds from Jan2008, we don't)
    var payload: ByteArray,
    var entitySubId: Int? = null, // some entities have special id (for example TBR has tempRateId)
    var entitySubIdName: String? = null,
    var createdAt: Long, // creation date of the record
    var updatedAt: Long  // update date of the record
)
