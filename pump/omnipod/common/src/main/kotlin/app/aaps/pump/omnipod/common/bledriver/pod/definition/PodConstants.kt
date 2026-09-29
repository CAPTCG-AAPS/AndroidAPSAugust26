package app.aaps.pump.omnipod.common.bledriver.pod.definition

import java.time.Duration

class PodConstants {
    companion object {

        val MAX_POD_LIFETIME: Duration = Duration.ofHours(80)

        // Expiration alert time in hours before lifetime end
        const val POD_EXPIRATION_ALERT_HOURS_REMAINING_DEFAULT = 7L

        // Imminent expiration alert time in hours before lifetime end
        const val POD_EXPIRATION_IMMINENT_ALERT_HOURS_REMAINING = 1L

        // Bolus & Priming units
        const val POD_PULSE_BOLUS_UNITS = 0.05

        // Reservoir units alert threshold
        const val DEFAULT_MAX_RESERVOIR_ALERT_THRESHOLD: Short = 20

        // The pod sends this pulse count while it still holds more than 50 units. It means
        // "not measured", not a real reading: the pod only reports a number once the
        // reservoir drops below 50 units. Storing it would show a fixed 51.15 U instead.
        const val RESERVOIR_PULSES_UNKNOWN: Short = 1023
    }
}
