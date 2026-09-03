package app.aaps.pump.tandem.common.data.defs

import androidx.annotation.StringRes
import app.aaps.pump.tandem.R

enum class QualifyingEventsRange(@StringRes val friendlyName: Int) {

    LAST_15_ITEMS(R.string.data_qe_range_last_15_items),
    LAST_3_HOURS(R.string.pump_qe_range_last_3_hours),
    LAST_6_HOURS(R.string.pump_qe_range_last_6_hours),
    LAST_12_HOURS(R.string.pump_qe_range_last_12_hours),
    LAST_24_HOURS(R.string.pump_qe_range_last_24_hours)
    ;


}