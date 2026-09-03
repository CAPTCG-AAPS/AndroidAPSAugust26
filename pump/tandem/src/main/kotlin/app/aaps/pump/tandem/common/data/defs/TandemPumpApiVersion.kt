package app.aaps.pump.tandem.common.data.defs

import com.jwoglom.pumpx2.pump.messages.response.currentStatus.ApiVersionResponse
import app.aaps.pump.common.driver.connector.commands.data.FirmwareVersionInterface

// TODO(jwoglom): replace with https://github.com/jwoglom/pumpX2/blob/main/messages/src/main/java/com/jwoglom/pumpx2/pump/messages/models/KnownApiVersion.java
enum class TandemPumpApiVersion(val description: String,
                                val majorVersion: Int,
                                val minorVersion: Int,
                                val isClosedLoopPossible: Boolean,
                                val hasBolus: Boolean = false,
                                val hasFullControlSet : Boolean = false,
                                val children: Set<TandemPumpApiVersion>? = null) : FirmwareVersionInterface {

    //VERSION_0_x("Version 0.x", 0, 0, isClosedLoopPossible = false),
    //VERSION_1_x("Version 1.x", 1, 0, isClosedLoopPossible = false),
    VERSION_2_0_UNDEFINED("Version 2.0", 2, 0, isClosedLoopPossible = false),  // Falback
    // v2.1 is the API version used by software v7.1 and v7.4. It is the earliest known API version,
    // previous pump firmware did not have Bluetooth connection compatibility.
    VERSION_2_1_to_2_4("Version 2.1-2.4", 2, 1, isClosedLoopPossible = false),  // Yes
    // v2.5 is the API version used by software v7.6 and includes remote bolus
    VERSION_2_5_OR_HIGHER("Version 2.5", 2, 5, isClosedLoopPossible = false, hasBolus = true), // Yes
    VERSION_3_0("Version 3.0", 3, 0, isClosedLoopPossible = false, hasBolus = true),
    // v3.2 is the API version used by software v7.7 and utilizes a 6-character numeric pairing PIN
    VERSION_3_2("Version 3.2", 3, 2, isClosedLoopPossible = false, hasBolus = true),
    VERSION_3_4("Version 3.4", 3, 4, isClosedLoopPossible = false, hasBolus = true),
    VERSION_3_5_MOBI("Version 3.5 Mobi", 3, 5, isClosedLoopPossible = true, hasBolus = true, hasFullControlSet = true),
    VERSION_3_6_MOBI("Version 3.6 Mobi", 3, 6, isClosedLoopPossible = true, hasBolus = true, hasFullControlSet = true),
    VERSION_3_8_MOBI("Version 3.8 Mobi", 3, 8, isClosedLoopPossible = true, hasBolus = true, hasFullControlSet = true),
    //VERSION_2_5_OR_HIGHER("Version 2.5 or higher", 2, 5, isClosedLoopPossible = false, hasBolus = true),
    VERSION_4_x("Version 4.x", 4, 0, isClosedLoopPossible = true, hasBolus = true, hasFullControlSet = true),

    Unknown("Unknown", 0, 0, false);

    //constructor()



    fun isSameVersion(requestedVersion: TandemPumpApiVersion) : Boolean {
        return (this == requestedVersion ||
            (requestedVersion.children!=null && requestedVersion.children.contains(this)))
    }

    fun isMobi(): Boolean {
        return (this == VERSION_3_5_MOBI || this == VERSION_3_6_MOBI || this == VERSION_3_8_MOBI)
    }



    companion object {

        @JvmStatic
        fun isMobi(requestedVersion: TandemPumpApiVersion) : Boolean {
            return requestedVersion.isMobi()
        }

        @JvmStatic
        fun getApiVersionFromResponse(response: ApiVersionResponse): TandemPumpApiVersion {

            return (
                when (response.majorVersion) {

                    2    -> {
                        if (response.minorVersion<=4)
                            VERSION_2_1_to_2_4
                        else
                            VERSION_2_5_OR_HIGHER
                    }

                    3    -> {
                        if (response.minorVersion<2) {
                            VERSION_3_0
                        } else if (response.minorVersion<4) {
                            VERSION_3_2
                        } else if (response.minorVersion==4) {
                            VERSION_3_4
                        } else if (response.minorVersion==5) {
                            VERSION_3_5_MOBI
                        } else if (response.minorVersion==6) {
                            VERSION_3_6_MOBI
                        } else {
                            VERSION_3_8_MOBI
                        }

                    }

                    4 -> VERSION_4_x

                    else ->  Unknown
                })
        }
    }



}