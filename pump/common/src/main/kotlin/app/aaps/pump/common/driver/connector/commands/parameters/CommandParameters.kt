package app.aaps.pump.common.driver.connector.commands.parameters

import app.aaps.pump.common.driver.connector.defs.PumpCommandType
import java.util.Objects

open class CommandParameters //    public CommandParameters(YpsoPumpCommandType commandType) {
//        this.singleCommand = true;
//        this.commandType = commandType;
//    }
//
//    public CommandParameters(List<YpsoPumpCommandType> commands) {
//        this.singleCommand = false;
//        this.commandTypeList = commands;
//    }
    (var isSingleCommand: Boolean, var commandType: PumpCommandType, var commandTypeList: List<PumpCommandType>?) {

    //
    //    public void setCommandType(YpsoPumpCommandType commandType) {
    //        this.commandType = commandType;
    //        this.singleCommand = true;
    //    }
    //
    //    public void setCommandTypeList(List<YpsoPumpCommandType> commandTypeList) {
    //        this.commandTypeList = commandTypeList;
    //        this.singleCommand = false;
    //    }
    //    private Integer bolus;
    //    private Integer carbs;
    //    private Integer duration;
    //    public CommandParameters(Integer bolus,
    //                             Integer carbs,
    //                             Integer duration
    //
    //                             ) {
    //        this.bolus = bolus;
    //        this.carbs = carbs;
    //        this.duration = duration;
    //    }
    //
    //    public static CommandParameters.CommandParametersBuilder builder() {
    //        return new CommandParameters.CommandParametersBuilder();
    //    }
    //
    //    public static class CommandParametersBuilder {
    //
    //        private Integer bolus;
    //        private Integer carbs;
    //        private Integer duration;
    //
    //        CommandParametersBuilder() {
    //        }
    //
    //        public CommandParameters.CommandParametersBuilder bolus(Integer bolus) {
    //            this.bolus = bolus;
    //            return this;
    //        }
    //
    //        public CommandParameters.CommandParametersBuilder carbs(Integer carbs) {
    //            this.carbs = carbs;
    //            return this;
    //        }
    //
    //        public CommandParameters.CommandParametersBuilder duration(Integer duration) {
    //            this.duration = duration;
    //            return this;
    //        }
    //
    //        public CommandParameters build() {
    //            return new CommandParameters(this.bolus,
    //                    this.carbs,
    //                    this.duration);
    //        }
    //
    //    }
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CommandParameters) return false
        val that = other
        return isSingleCommand == that.isSingleCommand && commandType == that.commandType && commandTypeList == that.commandTypeList
    }

    override fun hashCode(): Int {
        return Objects.hash(isSingleCommand, commandType, commandTypeList)
    }
}
