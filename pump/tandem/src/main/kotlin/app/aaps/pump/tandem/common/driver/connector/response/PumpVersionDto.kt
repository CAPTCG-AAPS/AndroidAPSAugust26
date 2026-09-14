package app.aaps.pump.tandem.common.driver.connector.response

import app.aaps.pump.common.driver.connector.commands.data.AdditionalResponseDataInterface
import com.jwoglom.pumpx2.pump.messages.MessageType
import com.jwoglom.pumpx2.pump.messages.annotations.MessageProps
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.PumpVersionRequest
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.PumpVersionResponse

@MessageProps(opCode = 85, size = 48, type = MessageType.RESPONSE, request = PumpVersionRequest::class)
class PumpVersionDto : PumpVersionResponse(), AdditionalResponseDataInterface {

}