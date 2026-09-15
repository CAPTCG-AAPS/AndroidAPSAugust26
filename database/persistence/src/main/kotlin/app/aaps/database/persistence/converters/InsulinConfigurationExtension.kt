package app.aaps.database.persistence.converters

import app.aaps.core.data.model.ICfg
import app.aaps.database.entities.embedments.InsulinConfiguration

/**
 * [ICfg.isInhaled] is stored in its own column of the embedded [InsulinConfiguration] (boluses,
 * profileSwitches and effectiveProfileSwitches tables). It is never re-derived from the peak, because
 * the inhaled and injected peak ranges overlap. Rows that existed before the column was added were
 * marked by the database migration 35 -> 36.
 */
fun InsulinConfiguration.fromDb(): ICfg =
    ICfg(
        insulinLabel = this.insulinLabel,
        insulinEndTime = this.insulinEndTime,
        insulinPeakTime = this.insulinPeakTime,
        concentration = this.concentration,
        isInhaled = this.isInhaled
    )

fun ICfg.toDb(): InsulinConfiguration =
    InsulinConfiguration(
        insulinLabel = this.insulinLabel,
        insulinEndTime = this.insulinEndTime,
        insulinPeakTime = this.insulinPeakTime,
        concentration = this.concentration,
        isInhaled = this.isInhaled
    )
