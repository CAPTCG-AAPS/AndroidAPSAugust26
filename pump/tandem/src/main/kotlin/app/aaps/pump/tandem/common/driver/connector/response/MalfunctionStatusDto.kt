package app.aaps.pump.tandem.common.driver.connector.response

import app.aaps.pump.common.driver.connector.commands.data.AdditionalResponseDataInterface
import com.jwoglom.pumpx2.pump.messages.MessageType
import com.jwoglom.pumpx2.pump.messages.annotations.MessageProps
import com.jwoglom.pumpx2.pump.messages.bluetooth.Characteristic
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.MalfunctionStatusRequest
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.MalfunctionBitmaskStatusResponse

@MessageProps(opCode = 119, size = 8, type = MessageType.RESPONSE, characteristic = Characteristic.CURRENT_STATUS, request = MalfunctionStatusRequest::class)
class MalfunctionStatusDto(
    val malfunctionMatchesActiveAlertOrAlarm: Boolean = false
) : MalfunctionBitmaskStatusResponse(), AdditionalResponseDataInterface {

    /** True if any malfunction bit is set. Replaces v1.8.3's no-arg hasMalfunction(). */
    fun hasMalfunction(): Boolean = malfunctions?.isNotEmpty() == true

    /** Human-readable description of active malfunctions. Replaces v1.8.3's errorString. */
    val errorString: String
        get() = if (!hasMalfunction()) ""
        else String.format("%d-%#x", codeA, codeB) +
            " (" + malfunctions.joinToString(",") { it.name } + ")"
}
