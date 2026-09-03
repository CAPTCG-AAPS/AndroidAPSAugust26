package app.aaps.pump.tandem.common.driver.connector.response

import app.aaps.pump.common.driver.connector.commands.data.AdditionalResponseDataInterface
import com.jwoglom.pumpx2.pump.messages.MessageType
import com.jwoglom.pumpx2.pump.messages.annotations.MessageProps
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.HomeScreenMirrorRequest
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.HomeScreenMirrorResponse

@MessageProps(opCode = 57, size = 9, type = MessageType.RESPONSE, request = HomeScreenMirrorRequest::class)
class HomeScreenMirrorDto : HomeScreenMirrorResponse(), AdditionalResponseDataInterface {

    fun isTemporaryBasalRunning(): Boolean {
        return this.basalStatusIcon == BasalStatusIcon.TEMP_RATE ||
            this.basalStatusIcon == BasalStatusIcon.ZERO_TEMP_RATE
    }


}