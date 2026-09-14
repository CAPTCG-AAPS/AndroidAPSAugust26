package app.aaps.pump.tandem.common.driver.connector.def

import app.aaps.pump.common.driver.connector.commands.data.AdditionalResponseDataInterface

class ControlCommandResponse(var id: Int, var status: Int) : AdditionalResponseDataInterface
