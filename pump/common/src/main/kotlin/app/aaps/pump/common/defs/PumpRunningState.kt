package app.aaps.pump.common.defs

import app.aaps.core.ui.compose.StatusLevel
import app.aaps.pump.common.R

// TODO there are 3 classes now, that do similar things, sort of, need to define exact rules: PumpDeviceState,
//     PumpDriverState, PumpStatusState

enum class PumpRunningState(val status: String, var resourceId: Int, var statusLevel: StatusLevel) {
    Unknown("unknown", R.string.pump_status_unknown, StatusLevel.UNSPECIFIED),
    Running("normal", R.string.pump_status_running, StatusLevel.NORMAL),
    Suspended("suspended", R.string.pump_status_suspended, StatusLevel.CRITICAL)
    ;
}