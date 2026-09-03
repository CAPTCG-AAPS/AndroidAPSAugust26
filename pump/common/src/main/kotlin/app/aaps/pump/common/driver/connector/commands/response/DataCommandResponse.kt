package app.aaps.pump.common.driver.connector.commands.response

import app.aaps.pump.common.driver.connector.defs.PumpCommandType

class DataCommandResponse<E> : CommandResponseAbstract<E> {
    constructor()

    constructor(
        commandType: PumpCommandType?, success: Boolean, errorDescription: String?,
        value: E
    ) : super(commandType, success, errorDescription, value)

    fun cloneWithNewCommandType(pumpCommandType: PumpCommandType?): DataCommandResponse<E?> {
        return DataCommandResponse(
            pumpCommandType, isSuccess, errorDescription,
            value
        )
    } //    public SimpleDataCommandResponse<E> withCommandType(PumpCommandType commandType) {
    //        this.setCommandType(commandType);
    //        return this;
    //    }
    //
    //
    //    public SimpleDataCommandResponse<E> withResult(boolean success) {
    //        this.setSuccess(success);
    //        return this;
    //    }
    //
    //
    //    public SimpleDataCommandResponse<E> withErrorDescription(String errorDescription) {
    //        this.setErrorDescription(errorDescription);
    //        return this;
    //    }
    //
    //
    //    public SimpleDataCommandResponse<E> withValue(E value) {
    //        this.setValue(value);
    //        return this;
    //    }
    //    public static SimpleDataCommandResponse.Builder builder() {
    //        return new SimpleDataCommandResponse.Builder();
    //    }
    //    public static class Builder {
    //
    //        PumpCommandType commandType;
    //        boolean success;
    //        String errorDescription;
    //        Integer integerData;
    //        Double doubleData;
    //        String stringData;
    //        Long longData;
    //
    //
    //        Builder() {
    //        }
    //
    //
    //        public Builder commandType(PumpCommandType commandType) {
    //            this.commandType = commandType;
    //            return this;
    //        }
    //
    //
    //        public Builder success(boolean success) {
    //            this.success = success;
    //            return this;
    //        }
    //
    //
    //        public Builder errorDescription(String errorDescription) {
    //            this.errorDescription = errorDescription;
    //            return this;
    //        }
    //
    //
    //        public Builder withIntegerData(Integer integerData) {
    //            this.integerData = integerData;
    //            return this;
    //        }
    //
    //
    //        public Builder withLongData(Long longData) {
    //            this.longData = longData;
    //            return this;
    //        }
    //
    //
    //        public Builder withDoubleData(Double doubleData) {
    //            this.doubleData = doubleData;
    //            return this;
    //        }
    //
    //
    //        public Builder withStringData(String stringData) {
    //            this.stringData = stringData;
    //            return this;
    //        }
    //
    //
    //        public SimpleDataCommandResponse build() {
    //            return new SimpleDataCommandResponse(commandType, success, errorDescription,
    //                    this.integerData, this.doubleData, this.stringData, this.longData);
    //        }
    //
    //    }
    //    public Builder builder() {
    //        return new Builder();
    //    }
    //
    //
    //    public static class Builder {
    //
    //        PumpCommandType commandType;
    //        boolean success;
    //        String errorDescription;
    //        Object value;
    //
    //        Builder() {
    //        }
    //
    //        public Builder commandType(PumpCommandType commandType) {
    //            this.commandType = commandType;
    //            return this;
    //        }
    //
    //        public Builder<E> success(boolean success) {
    //            this.success = success;
    //            return this;
    //        }
    //
    //
    //        public Builder<E> errorDescription(String errorDescription) {
    //            this.errorDescription = errorDescription;
    //            return this;
    //        }
    //
    //        public SimpleDataCommandResponse<E> build() {
    //            return new SimpleDataCommandResponse<E>(this.commandType,
    //                    this.success, this.errorDescription, this.value);
    //        }
    //
    //    }
}
