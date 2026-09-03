package app.aaps.pump.tandem.common.data.defs

import androidx.annotation.StringRes
import app.aaps.pump.tandem.R

enum class QualifyingEventsFilter(@StringRes val friendlyName: Int) {

    ALL(R.string.data_qe_filter_all),
    AAPS_RELEVANT(R.string.pump_qe_filter_aaps_relevant),
    ;

}