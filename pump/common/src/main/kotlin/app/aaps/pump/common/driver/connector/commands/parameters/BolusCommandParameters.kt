package app.aaps.pump.common.driver.connector.commands.parameters

import app.aaps.pump.common.driver.connector.defs.PumpCommandType

class BolusCommandParameters(
    commandType: PumpCommandType?,
    var bolus: Double, var carbs: Double, var duration: Int, var cancel: Boolean
) : CommandParameters(true, commandType!!, null) {

    class Builder internal constructor() {

        var commandType: PumpCommandType? = null
        var bolus: Double = 0.0
        var carbs: Double = 0.0
        var duration: Int = 0
        var cancel: Boolean = false

        fun commandType(commandType: PumpCommandType?): Builder {
            this.commandType = commandType
            return this
        }

        fun bolus(bolus: Double): Builder {
            this.bolus = bolus
            return this
        }

        fun carbs(carbs: Double): Builder {
            this.carbs = carbs
            return this
        }

        fun duration(duration: Int): Builder {
            this.duration = duration
            return this
        }

        fun cancel(isCancel: Boolean): Builder {
            this.cancel = isCancel
            return this
        }

        fun build(): BolusCommandParameters {
            return BolusCommandParameters(
                commandType,
                this.bolus, this.carbs, this.duration, this.cancel
            )
        }
    }

    companion object {

        fun builder(): Builder {
            return Builder()
        }
    }
}
