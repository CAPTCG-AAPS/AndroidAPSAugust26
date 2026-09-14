package app.aaps.pump.common.utils

import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.Profile.ProfileValue
import app.aaps.core.interfaces.pump.defs.determineCorrectBasalSize
import java.util.Locale

object ProfileUtil {

    fun getProfileDisplayable(profile: Profile, pumpType: PumpType): String {
        val stringBuilder = StringBuilder()
        for (basalValue in profile.getBasalValues()) {
            val basalValueValue = pumpType.determineCorrectBasalSize(basalValue.value)
            val hour = basalValue.timeAsSeconds / (60 * 60)
            stringBuilder.append((if (hour < 10) "0" else "") + hour + ":00")
            stringBuilder.append(String.format(Locale.ENGLISH, "%.3f", basalValueValue))
            stringBuilder.append(", ")
        }
        return if (stringBuilder.length > 3) stringBuilder.substring(0, stringBuilder.length - 2) else stringBuilder.toString()
    }

    fun getBasalProfilesDisplayableAsStringOfArray(profile: Profile, pumpType: PumpType): String {
        val stringBuilder = java.lang.StringBuilder()

        val entriesCopy = profile.getBasalValues()

        for (i in entriesCopy.indices) {
            val current = entriesCopy[i]
            val currentTime = current.timeAsSeconds / (60 * 60)
            var lastHour: Int =
                if (i + 1 == entriesCopy.size) {
                    24
                } else {
                    val basalProfileEntry = entriesCopy[i + 1]
                    basalProfileEntry.timeAsSeconds / (60 * 60)
                }

            // System.out.println("Current time: " + currentTime + " Next Time: " + lastHour);
            (currentTime until lastHour).forEach { j ->
                stringBuilder.append(String.format(Locale.ENGLISH, "%.3f", pumpType.determineCorrectBasalSize(current.value)))
                stringBuilder.append(" ")
            }
        }

        return stringBuilder.toString().trim()

    }


    fun getBasalProfilesDisplayableAsStringOfArrayV2(profile: Profile, pumpType: PumpType): String {
        val stringBuilder = java.lang.StringBuilder()

        val basalsAsArray = getArrayOfHourlyBasals(profile)

        for (basal in basalsAsArray) {
            stringBuilder.append(String.format(Locale.ENGLISH, "%.3f", pumpType.determineCorrectBasalSize(basal)))
            stringBuilder.append(" ")
        }

        return stringBuilder.toString().trim()

    }

    fun getArrayOfHourlyBasals(profile: Profile) : DoubleArray {
        var resultArray: DoubleArray = doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
                                                     0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)

        for (index in 0..23) {
            resultArray[index] = findCorrectValueInProfile((index * 60* 60).toInt(), profile.getBasalValues())
        }

        return resultArray
    }


    private fun findCorrectValueInProfile(targetTimeAsSeconds: Int, profileValueList : Array<ProfileValue>) : Double {
        var targetValue = 0.0
        for (profileValue in profileValueList) {
            if (profileValue.timeAsSeconds <= targetTimeAsSeconds) {
                targetValue = profileValue.value
            }
        }

        return targetValue
    }





    @JvmStatic
    fun getProfilesByHourToString(data: DoubleArray?): String {
        if (data == null) {
            return " null "
        }

        val stringBuilder = StringBuilder()
        for (value in data) {
            stringBuilder.append(String.format("%.3f", value))
            stringBuilder.append(" ")
        }
        return stringBuilder.trim().toString()
    }

}