package app.aaps.pump.common.driver.connector

import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.pump.common.data.BasalProfileDto
import app.aaps.pump.common.driver.connector.commands.data.FirmwareVersionInterface
import app.aaps.pump.common.driver.connector.commands.parameters.PumpHistoryFilterInterface
import app.aaps.pump.common.driver.connector.defs.PumpCommandType
import app.aaps.pump.common.defs.PumpConfigurationTypeInterface
import app.aaps.pump.common.driver.connector.commands.data.AdditionalResponseDataInterface
import app.aaps.pump.common.driver.connector.commands.response.DataCommandResponse
import app.aaps.pump.common.driver.connector.commands.data.CustomCommandTypeInterface
import app.aaps.pump.common.data.PumpTimeDifferenceDto
import app.aaps.pump.common.defs.BolusData
import app.aaps.pump.common.defs.TempBasalPair

interface PumpConnectorInterface {

    fun connectToPump(): Boolean
    fun disconnectFromPump(): Boolean

    fun retrieveFirmwareVersion(): DataCommandResponse<FirmwareVersionInterface?>

    fun sendBolus(detailedBolusInfo: DetailedBolusInfo): DataCommandResponse<BolusData?>  //  ResultCommandResponse
    fun cancelBolus(bolusData: BolusData?): DataCommandResponse<AdditionalResponseDataInterface?>  //ResultCommandResponse
    fun getBolus(): DataCommandResponse<BolusData?>

    fun retrieveTemporaryBasal(): DataCommandResponse<TempBasalPair?>
    fun sendTemporaryBasal(value: Int, duration: Int): DataCommandResponse<TempBasalPair?>  //ResultCommandResponse
    fun cancelTemporaryBasal(): DataCommandResponse<AdditionalResponseDataInterface?>  //ResultCommandResponse

    fun retrieveBasalProfile(): DataCommandResponse<BasalProfileDto?>
    fun sendBasalProfile(profile: Profile): DataCommandResponse<Boolean?>  //ResultCommandResponse

    fun retrieveConfiguration(): DataCommandResponse<MutableMap<PumpConfigurationTypeInterface, Any>?>
    fun retrieveRemainingInsulin(): DataCommandResponse<Double?>
    fun retrieveBatteryStatus(): DataCommandResponse<Int?>
    fun getPumpStatus(): DataCommandResponse<AdditionalResponseDataInterface?>

    fun getTime(): DataCommandResponse<PumpTimeDifferenceDto?>
    fun setTime(): DataCommandResponse<Boolean?>

    fun getPumpHistory(): DataCommandResponse<List<Any>?>
    fun getFilteredPumpHistory(filter: PumpHistoryFilterInterface): DataCommandResponse<List<Any>?>

    fun executeCustomCommand(commandType: CustomCommandTypeInterface, data: Any?): DataCommandResponse<AdditionalResponseDataInterface?>

    fun getSupportedCommands(): Set<PumpCommandType>
}