package app.aaps.pump.common.defs

import app.aaps.pump.common.driver.connector.commands.data.AdditionalResponseDataInterface
import com.google.gson.annotations.Expose

class BolusData constructor(
    @Expose var timestamp: Long = System.currentTimeMillis(),
    @Expose var amountImmediateRequested : Double? = null,
    @Expose var amountImmediateDelivered : Double? = null,
    @Expose var fullyDelivered: Boolean = false,
    @Expose var carbs : Double = 0.0,
    @Expose var durationMinutes : Int? = null,
    @Expose var amountExtended : Double? = null,
    @Expose var bolusType: BolusType = BolusType.NORMAL,

    var bolusId: Long? = null,
    @Expose var bolusStatus: BolusStatus = BolusStatus.DONE,
    var additionalData: MutableMap<String,Any?>? = null

    ): AdditionalResponseDataInterface {



}
