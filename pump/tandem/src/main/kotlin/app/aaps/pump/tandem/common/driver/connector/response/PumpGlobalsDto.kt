package app.aaps.pump.tandem.common.driver.connector.response

import app.aaps.pump.common.driver.connector.commands.data.AdditionalResponseDataInterface
import com.jwoglom.pumpx2.pump.messages.MessageType
import com.jwoglom.pumpx2.pump.messages.annotations.MessageProps
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.AlarmStatusRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.PumpGlobalsRequest
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.AlertStatusResponse
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.PumpGlobalsResponse

@MessageProps(opCode = 87, size = 14, type = MessageType.RESPONSE, request = PumpGlobalsRequest::class)
class PumpGlobalsDto: PumpGlobalsResponse(), AdditionalResponseDataInterface {




}