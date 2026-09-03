package app.aaps.pump.common.driver

import app.aaps.core.data.pump.defs.PumpType

import app.aaps.pump.common.driver.db.PumpDriverDatabaseOperation
import app.aaps.pump.common.driver.db.PumpDriverDummyDatabaseOperation
import app.aaps.pump.common.driver.history.PumpHistoryDataProvider

/**
 * This class is to be used by older pump drivers, so that they become compatible with pump-common V2
 */
class PumpDriverDummyConfiguration : PumpDriverConfiguration {

    var pumpDriverDatabaseOperationHandler: PumpDriverDatabaseOperation = PumpDriverDummyDatabaseOperation()

    // override fun getPumpBLESelector(): PumpBLESelector? {
    //     return null
    // }

    override fun getPumpHistoryDataProvider(): PumpHistoryDataProvider? {
        return null
    }

    override fun getPumpDriverDatabaseOperation(): PumpDriverDatabaseOperation {
        return pumpDriverDatabaseOperationHandler
    }

    override fun getPumpType(): PumpType {
        return PumpType.GENERIC_AAPS
    }

    override var logPrefix: String = "Dummy"

    override var canHandleDST: Boolean = false

    override var hasService: Boolean = false

}