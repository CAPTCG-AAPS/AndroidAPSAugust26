package app.aaps.pump.common.defs

enum class PumpDriverMode {
    Demo, // used for testing only, each bolus/tbr will be automatically accepted and "faked"
    ForcedOpenLoop, // there are no commands, so UI instructions are shown
    Automatic, // open loop and closed loop fully supported


    OpenLoop,
    OpenLoopWithCommunication,

}