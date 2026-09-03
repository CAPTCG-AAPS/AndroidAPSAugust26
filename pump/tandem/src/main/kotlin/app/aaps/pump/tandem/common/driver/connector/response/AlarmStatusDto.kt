package app.aaps.pump.tandem.common.driver.connector.response

import app.aaps.pump.common.driver.connector.commands.data.AdditionalResponseDataInterface
import com.jwoglom.pumpx2.pump.messages.MessageType
import com.jwoglom.pumpx2.pump.messages.annotations.MessageProps
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.AlarmStatusRequest
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.AlarmStatusResponse

@MessageProps(opCode = 71, size = 8, type = MessageType.RESPONSE, request = AlarmStatusRequest::class)
class AlarmStatusDto: AlarmStatusResponse(), AdditionalResponseDataInterface {




}