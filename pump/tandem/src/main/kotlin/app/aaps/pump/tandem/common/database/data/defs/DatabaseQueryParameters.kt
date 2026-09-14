package app.aaps.pump.tandem.common.database.data.defs

import app.aaps.pump.common.defs.PumpHistoryEntryGroup
import app.aaps.pump.common.driver.history.PumpHistoryPeriod

class DatabaseQueryParameters (var historyTime: PumpHistoryPeriod? = null,
                               var groupType: PumpHistoryEntryGroup? = null)