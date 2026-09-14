package app.aaps.pump.tandem.mobi.driver

import app.aaps.core.data.pump.defs.PumpType
import app.aaps.pump.tandem.common.driver.config.TandemPumpDriverConfiguration
import javax.inject.Inject

//import javax.inject.Inject

class TandemMobiPumpDriverConfiguration @Inject constructor(

) : TandemPumpDriverConfiguration(PumpType.TANDEM_MOBI_BT) {

    override var logPrefix: String = "TandemMobiPumpPlugin::"


}


