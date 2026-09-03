package app.aaps.pump.common.driver.connector.commands.response

import app.aaps.pump.common.driver.connector.defs.PumpCommandType

class StatusResponse(commandType: PumpCommandType?, success: Boolean, errorDescription: String?) : CommandResponseAbstract<Boolean?>(commandType, success, errorDescription, success) {

    fun cloneWithNewCommandType(pumpCommandType: PumpCommandType?): DataCommandResponse<Boolean> {
        return DataCommandResponse(
            pumpCommandType,
            isSuccess,
            errorDescription, true
        )
    } //    public static CommandResponse.CommandResponseBuilder builder() {
    //        return new CommandResponse.CommandResponseBuilder();
    //    }
    //    public static abstract class CommandResponseBuilder<E> {
    //
    //        PumpCommandType commandType;
    //        boolean success;
    //        String errorDescription;
    //        E value;
    //
    //        CommandResponseBuilder() {
    //        }
    //
    //        public CommandResponse.CommandResponseBuilder commandType(PumpCommandType commandType) {
    //            this.commandType = commandType;
    //            return this;
    //        }
    //
    //        public CommandResponse.CommandResponseBuilder success(boolean success) {
    //            this.success = success;
    //            return this;
    //        }
    //
    //
    //        public CommandResponse.CommandResponseBuilder errorDescription(String errorDescription) {
    //            this.errorDescription = errorDescription;
    //            return this;
    //        }
    //
    //        public CommandResponse build() {
    //            return new CommandResponse(this.commandType,
    //                    this.success, this.errorDescription, this.value);
    //        }
    //
    //    }
}
