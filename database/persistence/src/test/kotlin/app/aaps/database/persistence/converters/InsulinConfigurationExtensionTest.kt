package app.aaps.database.persistence.converters

import app.aaps.core.data.model.ICfg
import app.aaps.database.entities.embedments.InsulinConfiguration
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * [ICfg.isInhaled] is stored in its own database column - see the KDoc on [fromDb]. The inhaled and
 * injected peak ranges overlap, so the flag must come from the column and never from the peak.
 */
class InsulinConfigurationExtensionTest {

    private fun stored(peakMinutes: Int, diaHours: Double, isInhaled: Boolean) =
        InsulinConfiguration(
            insulinLabel = "x",
            insulinEndTime = (diaHours * 3600 * 1000).toLong(),
            insulinPeakTime = peakMinutes * 60_000L,
            concentration = 1.0,
            isInhaled = isInhaled
        )

    @Test fun `the stored flag decides, not the peak`() {
        // 55 min is a valid peak for both Afrezza and Fiasp.
        assertThat(stored(peakMinutes = 55, diaHours = 4.5, isInhaled = true).fromDb().isInhaled).isTrue()
        assertThat(stored(peakMinutes = 55, diaHours = 10.0, isInhaled = false).fromDb().isInhaled).isFalse()
    }

    @Test fun `the flag defaults to injected`() {
        val row = InsulinConfiguration(insulinLabel = "x", insulinEndTime = 36_000_000L, insulinPeakTime = 3_300_000L, concentration = 1.0)
        assertThat(row.fromDb().isInhaled).isFalse()
    }

    @Test fun `toDb keeps every field, including the flag`() {
        for (inhaled in listOf(true, false)) {
            val iCfg = ICfg(insulinLabel = "Afrezza", peak = 55, dia = 4.5, concentration = 1.0, isInhaled = inhaled)

            val db = iCfg.toDb()

            assertThat(db.insulinLabel).isEqualTo("Afrezza")
            assertThat(db.insulinPeakTime).isEqualTo(55 * 60_000L)
            assertThat(db.insulinEndTime).isEqualTo((4.5 * 3600 * 1000).toLong())
            assertThat(db.concentration).isEqualTo(1.0)
            assertThat(db.isInhaled).isEqualTo(inhaled)
            assertThat(db.fromDb().isInhaled).isEqualTo(inhaled)
        }
    }
}
