package app.aaps.pump.tandem.common.driver.config

import app.aaps.pump.common.driver.history.PumpHistoryDataProviderAbstract
import app.aaps.pump.common.driver.history.PumpHistoryEntry
import app.aaps.pump.common.driver.history.PumpHistoryPeriod
import app.aaps.pump.common.driver.history.PumpHistoryText
import app.aaps.pump.tandem.R
import app.aaps.pump.tandem.common.util.TandemPumpUtil
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.pump.common.defs.PumpDriverMode
import app.aaps.pump.common.defs.PumpHistoryEntryGroup
import app.aaps.pump.tandem.common.database.data.entity.TandemHistoryRecordEntity

// This class was meant to be used with old History UI, no longer needed for Compose implementation
// but it might be useful for future implementation, so it will stay here for now

// TODOX needs to be removed
class TandemHistoryDataProvider constructor(
    var resourceHelper: ResourceHelper,
    var aapsLogger: AAPSLogger,
    var tandemPumpUtil: TandemPumpUtil,
) : PumpHistoryDataProviderAbstract() {

    private var groupList: List<PumpHistoryEntryGroup> = listOf()

    override fun getData(period: PumpHistoryPeriod): List<PumpHistoryEntry> {
        val dbHistoryList: List<TandemHistoryRecordEntity>
        val outList: MutableList<PumpHistoryEntry> = mutableListOf()

        if (tandemPumpUtil.tandemPumpStatus.pumpDriverMode== PumpDriverMode.Demo) {
            return prepareDemoData(outList)
        }

        if (period == PumpHistoryPeriod.ALL) {
            //dbHistoryList = tandemPumpHistory.pumpHistoryDatabase.historyRecordDao().allBlocking()
        } else {
            val startingTimeForData = getStartingTimeForData(period)
            //dbHistoryList = tandemPumpHistory.pumpHistoryDatabase.historyRecordDao().allSinceBlocking(DateTimeUtil.toATechDate(startingTimeForData))
        }

        // for (historyRecordEntity in dbHistoryList) {
        //     val domainObject = tandemPumpHistory.historyMapper.entityToDomain(historyRecordEntity)
        //     domainObject.prepareEntryData(resourceHelper = resourceHelper, pumpDataConverter = tandemDataConverter)
        //     outList.add(domainObject)
        // }

        return outList
    }

    private fun prepareDemoData(outList: MutableList<PumpHistoryEntry>): List<PumpHistoryEntry> {

        val serial : Long = 48758748

        // XTODO()

        // outList.add(HistoryLogDto(47, serial, 1,
        //                           TandemPumpHistoryType.BOLUS_COMPLETED, 1,
        //                           System.currentTimeMillis() - 20000, "",
        //                           Bolus(immediateAmount = 2.0, bolusId = 1, isCancelled = false, isRunning = false)) as PumpHistoryEntry)
        //
        // outList.add(HistoryLogDto(47, serial, 1,
        //                           TandemPumpHistoryType.BOLUS_COMPLETED, 1,
        //                           System.currentTimeMillis() - 10000, "",
        //                           Bolus(immediateAmount = 3.0, bolusId = 1, isCancelled = false, isRunning = false)) as PumpHistoryEntry)
        //
        // outList.add(HistoryLogDto(47, serial, 1,
        //                           TandemPumpHistoryType.BOLUS_COMPLETED, 1,
        //                           System.currentTimeMillis() - 5000, "",
        //                           Bolus(immediateAmount = 4.0, bolusId = 1, isCancelled = false, isRunning = false)) as PumpHistoryEntry)
        //
        // for (pumpHistoryEntry in outList) {
        //     pumpHistoryEntry.prepareEntryData(resourceHelper = resourceHelper, pumpDataConverter = tandemDataConverter)
        // }

        return outList

    }

    override fun getInitialPeriod(): PumpHistoryPeriod {
        return PumpHistoryPeriod.ALL
    }

    override fun getSpinnerWidthInPixels(): Int {
        return 180
    }

    override fun getAllowedPumpHistoryGroups(): List<PumpHistoryEntryGroup> {

        if (groupList.isNotEmpty())
            return groupList

        PumpHistoryEntryGroup.doTranslation(resourceHelper)

        val groupListInternal: MutableList<PumpHistoryEntryGroup> = mutableListOf()

        groupListInternal.add(PumpHistoryEntryGroup.All)
        groupListInternal.add(PumpHistoryEntryGroup.EventsOnly)
        groupListInternal.add(PumpHistoryEntryGroup.EventsNoStat)
        groupListInternal.add(PumpHistoryEntryGroup.Bolus)
        groupListInternal.add(PumpHistoryEntryGroup.Basal)
        groupListInternal.add(PumpHistoryEntryGroup.Base)
        groupListInternal.add(PumpHistoryEntryGroup.Configuration)
        groupListInternal.add(PumpHistoryEntryGroup.Statistic)
        groupListInternal.add(PumpHistoryEntryGroup.Other)
        groupListInternal.add(PumpHistoryEntryGroup.Alarm)
        groupListInternal.add(PumpHistoryEntryGroup.Unknown)

        this.groupList = groupListInternal

        return this.groupList
    }

    override fun getText(key: PumpHistoryText): String {

        val stringId: Int

        when (key) {
            PumpHistoryText.PUMP_HISTORY -> stringId = R.string.tandem_pump_history
            else                         -> return key.name
        }

        return resourceHelper.gs(stringId)

    }

    override fun isItemInSelection(itemGroup: PumpHistoryEntryGroup, targetGroup: PumpHistoryEntryGroup): Boolean {
        // TODOX
        return if (targetGroup == PumpHistoryEntryGroup.EventsNoStat || targetGroup == PumpHistoryEntryGroup.EventsOnly) {
            if (targetGroup == PumpHistoryEntryGroup.EventsOnly) {
                itemGroup != PumpHistoryEntryGroup.Alarm
            } else {
                (itemGroup != PumpHistoryEntryGroup.Alarm && itemGroup != PumpHistoryEntryGroup.Statistic)
            }
        } else {
            itemGroup === targetGroup
        }

    }

}