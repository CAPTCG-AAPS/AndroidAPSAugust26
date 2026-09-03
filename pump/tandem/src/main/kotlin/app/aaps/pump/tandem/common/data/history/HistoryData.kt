package app.aaps.pump.tandem.common.data.history

import com.jwoglom.pumpx2.pump.messages.response.historyLog.HistoryLog

data class HistorySummaryDto(
    var serialNumber: Int,  // serial
    var startRecord: Long, // first history record on pump
    var modifiedStartRecord: Long, // first history record in database (we keep only 1 month of history, so we would never retrieve anything over 1.5 month
    var lastRecord: Long, // current last record in database
    var missedRanges: ArrayList<HistoryRange> = arrayListOf(),
    var activeProcessing: MutableCollection<HistoryRequestInfo> = arrayListOf()
)


data class HistoryRange(var start: Long, var end: Long) {

    fun getItemAmount(): Int {
        return ((end - start) +1).toInt()
    }

}


data class HistoryRequestInfo(
    val startSequence: Long,
    val endSequence: Long,
    val numberOfLogs: Int = (endSequence - startSequence +1).toInt(),
    val downloadStatus: HistoryDownloadStatus = HistoryDownloadStatus.Initializing,
    val historyLogMap: MutableMap<Long, HistoryLog> = mutableMapOf()
) {
    fun chunkComplete(): Boolean {
        return historyLogMap.size == numberOfLogs ||
            ( historyLogMap.contains(startSequence) && historyLogMap.contains(endSequence) )
    }

    fun chunkHasAllItems(): Boolean {
        return historyLogMap.size == numberOfLogs
    }

    override fun toString(): String {
        return "HistoryRequestInfo [startSequence=$startSequence, endSequence=$endSequence, numberOfLogs=$numberOfLogs, downloadStatus=${downloadStatus.name}]";
    }
}

enum class HistoryDownloadStatus {
    Initializing,
    Waiting,
    Downloading,
    Complete
}