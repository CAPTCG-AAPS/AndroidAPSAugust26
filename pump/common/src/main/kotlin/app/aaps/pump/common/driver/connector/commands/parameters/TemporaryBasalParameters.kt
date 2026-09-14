package app.aaps.pump.common.driver.connector.commands.parameters

import app.aaps.pump.common.driver.connector.defs.PumpCommandType

class TemporaryBasalParameters(
    commandType: PumpCommandType,
    var percent: Int, var duration: Int, var absoluteAmount: Double, var cancel: Boolean
) : CommandParameters(true, commandType, null) {

    class TemporaryBasalParametersBuilder internal constructor() {

        var commandType: PumpCommandType = PumpCommandType.GetTemporaryBasal
        var percent: Int = 0
        var duration: Int = 0
        var absoluteAmount: Double = 0.0
        var cancel: Boolean = false

        fun commandType(commandType: PumpCommandType): TemporaryBasalParametersBuilder {
            this.commandType = commandType
            return this
        }

        fun percent(percent: Int): TemporaryBasalParametersBuilder {
            this.percent = percent
            return this
        }

        fun duration(duration: Int): TemporaryBasalParametersBuilder {
            this.duration = duration
            return this
        }

        fun absoluteAmount(amount: Double): TemporaryBasalParametersBuilder {
            this.absoluteAmount = amount
            return this
        }

        fun cancel(isCancel: Boolean): TemporaryBasalParametersBuilder {
            this.cancel = isCancel
            return this
        }

        fun build(): TemporaryBasalParameters {
            return TemporaryBasalParameters(
                commandType,
                this.percent, this.duration, this.absoluteAmount, this.cancel
            )
        }
    }

    companion object {

        fun builder(): TemporaryBasalParametersBuilder {
            return TemporaryBasalParametersBuilder()
        }
    }
}
