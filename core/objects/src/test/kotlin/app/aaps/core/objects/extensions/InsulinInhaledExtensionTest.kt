package app.aaps.core.objects.extensions

import app.aaps.core.data.model.ICfg
import app.aaps.core.interfaces.insulin.InsulinType
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.json.JSONObject
import org.junit.jupiter.api.Test

/**
 * [ICfg.isInhaled] is stored, not re-derived from the peak - see the field's KDoc. These tests lock
 * down the two things that has to get right: the flag survives every persistence round-trip, and an
 * entry written before the field existed still comes back with the correct identity.
 */
class InsulinInhaledExtensionTest {

    private fun afrezza(peakMinutes: Int) =
        ICfg(insulinLabel = "Afrezza", peak = peakMinutes, dia = 3.0, concentration = 1.0, isInhaled = true)

    // ---- InsulinType legacy detection and templates --------------------------------------------

    @Test fun `isLegacyInhaled matches the old inhaled peak range or an Afrezza label`() {
        // Older builds: inhaled 10..30 min, injected 35..120 min.
        for (m in intArrayOf(10, 15, 20, 30))
            assertThat(InsulinType.isLegacyInhaled(m * 60_000L, "x")).isTrue()
        for (m in intArrayOf(35, 45, 55, 75, 120))
            assertThat(InsulinType.isLegacyInhaled(m * 60_000L, "Fiasp")).isFalse()
        // Some older builds allowed an Afrezza peak above 30 min.
        assertThat(InsulinType.isLegacyInhaled(40 * 60_000L, "Afrezza (Inhaled) 40m 3h U100")).isTrue()
    }

    @Test fun `the Afrezza template seeds an inhaled iCfg at 55 min and 4_5 h`() {
        val cfg = InsulinType.OREF_INHALED_AFREZZA.iCfg
        assertThat(cfg.isInhaled).isTrue()
        assertThat(cfg.peak).isEqualTo(55)
        assertThat(cfg.dia).isEqualTo(4.5)
        assertThat(InsulinType.OREF_RAPID_ACTING.iCfg.isInhaled).isFalse()
    }

    @Test fun `fromPeak never returns the inhaled template, fromICfg uses the flag`() {
        // Afrezza and ultra rapid share the 55 min peak.
        assertThat(InsulinType.fromPeak(55 * 60_000L)).isEqualTo(InsulinType.OREF_ULTRA_RAPID_ACTING)
        assertThat(InsulinType.fromICfg(afrezza(peakMinutes = 55))).isEqualTo(InsulinType.OREF_INHALED_AFREZZA)
        assertThat(InsulinType.fromICfg(ICfg(insulinLabel = "Fiasp", peak = 55, dia = 10.0, concentration = 1.0)))
            .isEqualTo(InsulinType.OREF_ULTRA_RAPID_ACTING)
    }

    // ---- catalogue round-trips -----------------------------------------------------------------

    @Test fun `kotlinx round-trip preserves the flag at a peak shared with injected insulin`() {
        val restored = ICfg.fromJsonObject(afrezza(peakMinutes = 55).toJsonObject())

        assertThat(restored.isInhaled).isTrue()
        assertThat(restored.peak).isEqualTo(55)
        assertThat(restored.dia).isEqualTo(3.0)
    }

    @Test fun `org-json round-trip preserves the flag at a peak shared with injected insulin`() {
        val restored = ICfg.fromJson(JSONObject(afrezza(peakMinutes = 55).toJson().toString()))

        assertThat(restored.isInhaled).isTrue()
        assertThat(restored.peak).isEqualTo(55)
    }

    @Test fun `a non-inhaled insulin stays non-inhaled through a round-trip`() {
        val fiasp = ICfg(insulinLabel = "Fiasp", peak = 55, dia = 8.0, concentration = 1.0)
        assertThat(ICfg.fromJsonObject(fiasp.toJsonObject()).isInhaled).isFalse()
        assertThat(ICfg.fromJson(JSONObject(fiasp.toJson().toString())).isInhaled).isFalse()
    }

    // ---- legacy entries, written before the field existed --------------------------------------

    @Test fun `legacy entry without the key is reconstructed from an Afrezza label`() {
        val legacy40 = Json.decodeFromString<JsonObject>(
            """{"insulinLabel":"Afrezza (Inhaled) 40m 3h U100","insulinEndTime":10800000,"insulinPeakTime":2400000,"concentration":1.0}"""
        )
        assertThat(ICfg.fromJsonObject(legacy40).isInhaled).isTrue()
        assertThat(ICfg.fromJson(JSONObject(legacy40.toString())).isInhaled).isTrue()
    }

    @Test fun `legacy kotlinx entry without the key is reconstructed from the peak`() {
        val legacyInhaled = Json.decodeFromString<JsonObject>(
            """{"insulinLabel":"Afrezza","insulinEndTime":10800000,"insulinPeakTime":1800000,"concentration":1.0}"""
        )
        val legacyInjected = Json.decodeFromString<JsonObject>(
            """{"insulinLabel":"Fiasp","insulinEndTime":28800000,"insulinPeakTime":3300000,"concentration":1.0}"""
        )

        assertThat(ICfg.fromJsonObject(legacyInhaled).isInhaled).isTrue()   // peak 30 min
        assertThat(ICfg.fromJsonObject(legacyInjected).isInhaled).isFalse() // peak 55 min
    }

    @Test fun `legacy org-json entry without the key is reconstructed from the peak`() {
        val legacyInhaled = JSONObject("""{"insulinLabel":"Afrezza","insulinEndTime":10800000,"insulinPeakTime":1800000}""")
        val legacyInjected = JSONObject("""{"insulinLabel":"Fiasp","insulinEndTime":28800000,"insulinPeakTime":3300000}""")

        assertThat(ICfg.fromJson(legacyInhaled).isInhaled).isTrue()
        assertThat(ICfg.fromJson(legacyInjected).isInhaled).isFalse()
    }

    // ---- equality / cloning --------------------------------------------------------------------

    @Test fun `isEqual and deepClone account for the flag`() {
        val inhaled = afrezza(peakMinutes = 30)
        val sameButInjected = inhaled.deepClone().also { it.isInhaled = false }

        assertThat(inhaled.deepClone().isInhaled).isTrue()
        assertThat(inhaled.isEqual(inhaled.deepClone())).isTrue()
        assertThat(inhaled.isEqual(sameButInjected)).isFalse()
    }
}
