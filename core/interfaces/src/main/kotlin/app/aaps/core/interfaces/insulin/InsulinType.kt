package app.aaps.core.interfaces.insulin

import androidx.annotation.StringRes
import app.aaps.core.data.model.ICfg
import app.aaps.core.interfaces.R
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.HardLimits

enum class InsulinType(val value: Int, val insulinEndTime: Long, val insulinPeakTime: Long, @StringRes val label: Int, @StringRes val comment: Int, val isInhaled: Boolean = false) {
    UNKNOWN(-1, 0, 0, R.string.unknown, R.string.unknown),

    // int FAST_ACTING_INSULIN = 0; // old model no longer available
    // int FAST_ACTING_INSULIN_PROLONGED = 1; // old model no longer available
    OREF_RAPID_ACTING(2, 8 * 3600 * 1000, 75 * 60000, R.string.rapid_acting_oref, R.string.fast_acting_insulin_comment),
    OREF_ULTRA_RAPID_ACTING(3, 8 * 3600 * 1000, 55 * 60000, R.string.ultra_rapid_oref, R.string.ultra_fast_acting_insulin_comment),
    OREF_FREE_PEAK(4, 8 * 3600 * 1000, 50 * 60000, R.string.free_peak_oref, R.string.insulin_peak_time),
    OREF_LYUMJEV(5, 8 * 3600 * 1000, 45 * 60000, R.string.lyumjev, R.string.lyumjev),
    OREF_INHALED_AFREZZA(6, (4.5 * 3600 * 1000).toLong(), 55 * 60000L, R.string.inhaled_afrezza, R.string.inhaled_afrezza_comment, isInhaled = true);

    val iCfg: ICfg
        get() = ICfg(this.name, insulinEndTime, insulinPeakTime, 1.0, isInhaled)

    /** Provide iCfg with a default friendly name on insulin creation from template */
    fun getICfg(rh: ResourceHelper): ICfg = ICfg(rh.gs(this.label), insulinEndTime, insulinPeakTime, 1.0, isInhaled)

    companion object {

        private val map = entries.associateBy(InsulinType::value)
        fun fromInt(type: Int) = map[type] ?:OREF_RAPID_ACTING

        /**
         * Injected template with exactly this peak, or [OREF_FREE_PEAK]. Inhaled templates are never
         * returned: the Afrezza peak can be the same as an injected one (55 min = ultra rapid), so an
         * inhaled insulin must be found by its stored `ICfg.isInhaled` flag, not by its peak.
         */
        fun fromPeak(insulinPeakTime: Long) = entries.firstOrNull { !it.isInhaled && it.insulinPeakTime == insulinPeakTime } ?: OREF_FREE_PEAK

        /** Template for an existing insulin: the stored inhaled flag decides first, then the peak. */
        fun fromICfg(iCfg: ICfg) = if (iCfg.isInhaled) OREF_INHALED_AFREZZA else fromPeak(iCfg.insulinPeakTime)

        /**
         * Peak range (minutes) that inhaled insulin used before the flag was stored. Older builds only
         * allowed 10..30 min for inhaled insulin and 35..120 min for injected insulin, so inside data
         * written by those builds this range still tells them apart. It is NOT the current limit -
         * see [HardLimits.LIMIT_PEAK_INHALED].
         */
        private val LEGACY_PEAK_INHALED = 10..30

        /**
         * Whether an insulin config that has NO stored inhaled flag is inhaled.
         *
         * Only for data written before the flag existed: legacy catalogue JSON or a Nightscout
         * payload from an older build. Everything else must use the stored `ICfg.isInhaled`.
         * Matches the old inhaled peak range, or a label that names Afrezza (some older builds
         * allowed Afrezza peaks above 30 min).
         */
        fun isLegacyInhaled(insulinPeakTime: Long, insulinLabel: String): Boolean =
            (insulinPeakTime / 60_000L).toInt() in LEGACY_PEAK_INHALED || insulinLabel.contains("Afrezza", ignoreCase = true)
    }
}