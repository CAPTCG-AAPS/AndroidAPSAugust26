package app.aaps.pump.common.data

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import com.google.gson.Gson
import org.joda.time.DateTime
import org.joda.time.Seconds

/**
 * Created by andy on 28/05/2021.
 */
class PumpTimeDifferenceDto(
    var localDeviceTime: DateTime,
    var pumpTime: DateTime
) {

    var timeDifference = 0

    fun calculateDifference() {
        val secondsBetween = Seconds.secondsBetween(localDeviceTime, pumpTime)
        timeDifference = secondsBetween.seconds

        // val diff = localDeviceTime - pumpTime
        // timeDifference = (diff / 1000.0).toInt()
    }

    fun displayTime(gson: Gson, aapsLogger: AAPSLogger) {

        val gsonTime = gson.toJson(this)

        aapsLogger.info(LTag.PUMP, "PumpTimeDifference: ${gsonTime}")

    }

    init {
        calculateDifference()
    }
}