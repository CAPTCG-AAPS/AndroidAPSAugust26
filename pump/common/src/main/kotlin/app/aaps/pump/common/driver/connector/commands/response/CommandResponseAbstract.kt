package app.aaps.pump.common.driver.connector.commands.response

import app.aaps.pump.common.driver.connector.defs.PumpCommandType

open class CommandResponseAbstract<E> : CommandResponseInterface {

    var commandType: PumpCommandType? = null
    var isSuccess: Boolean = false
    var errorDescription: String? = null
    var value: E? = null

    constructor()

    constructor(
        commandType: PumpCommandType?, success: Boolean,
        errorDescription: String?, value: E
    ) {
        this.commandType = commandType
        this.isSuccess = success
        this.errorDescription = errorDescription
        this.value = value
    }

    // fun getValue(): E? {
    //     return value
    // }

    // fun setValue(value: E) {
    //     this.value = value
    // }

    //    public static Builder<E> builder() {
    //
    //    }
    fun withCommandType(commandType: PumpCommandType?): CommandResponseAbstract<E> {
        this.commandType = commandType
        return this
    }

    fun withResult(success: Boolean): CommandResponseAbstract<E> {
        this.isSuccess = success
        return this
    }

    fun withErrorDescription(errorDescription: String?): CommandResponseAbstract<E> {
        this.errorDescription = errorDescription
        return this
    }

    fun withValue(value: E): CommandResponseAbstract<E> {
        this.value = value
        return this
    }

    class Builder<E> internal constructor() {

        var commandType: PumpCommandType? = null
        var success: Boolean = false
        var errorDescription: String? = null
        var value: E? = null

        fun commandType(commandType: PumpCommandType?): Builder<E> {
            this.commandType = commandType
            return this
        }

        fun success(success: Boolean): Builder<E> {
            this.success = success
            return this
        }

        fun errorDescription(errorDescription: String?): Builder<E> {
            this.errorDescription = errorDescription
            return this
        }

        fun build(): CommandResponseAbstract<E> {
            return CommandResponseAbstract(
                this.commandType,
                this.success, this.errorDescription, this.value!!
            )
        }
    }
}
