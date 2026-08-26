package app.aaps.pump.omnipod.omnipod5.bledriver.pod.state

import app.aaps.pump.omnipod.common.bledriver.pod.definition.AlarmType
import app.aaps.pump.omnipod.common.bledriver.pod.definition.AlertType
import app.aaps.pump.omnipod.common.bledriver.pod.response.PodInfoActivationTimeResponse
import app.aaps.pump.omnipod.common.bledriver.pod.response.PodInfoTriggeredAlertsResponse
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.Calendar

/**
 * [O5PodStateManager.updateFromActivationTimeResponse]/[O5PodStateManager
 * .updateFromTriggeredAlertsResponse] - status pages 5/1's update methods, fetched
 * on-demand by [app.aaps.pump.omnipod.omnipod5.O5PumpPlugin] (see its
 * `fetchActivationTimeIfNeeded`/`fetchTriggeredAlertsIfNeeded`).
 */
class InMemoryO5PodStateManagerTest {

    @Test
    fun `updateFromActivationTimeResponse sets podActivatedAt and reuses alarmType-alarmTime`() {
        val bytes = byteArrayOf(
            0x02, 0x11, 0x05,
            0x14,
            0x00, 0x7D,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x07,
            0x0F,
            0x1A,
            0x09,
            0x1E
        )
        val state = InMemoryO5PodStateManager()

        state.updateFromActivationTimeResponse(PodInfoActivationTimeResponse(bytes))

        assertThat(state.alarmType).isEqualTo(AlarmType.ALARM_OCCLUDED)
        assertThat(state.alarmTime).isEqualTo(125.toShort())
        val activatedAt = requireNotNull(state.podActivatedAt)
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = activatedAt
        assertThat(calendar[Calendar.YEAR]).isEqualTo(2026)
        assertThat(calendar[Calendar.MONTH]).isEqualTo(Calendar.JULY)
        assertThat(calendar[Calendar.DAY_OF_MONTH]).isEqualTo(15)
        assertThat(calendar[Calendar.HOUR_OF_DAY]).isEqualTo(9)
        assertThat(calendar[Calendar.MINUTE]).isEqualTo(30)
    }

    @Test
    fun `updateFromTriggeredAlertsResponse keeps only non-zero slots`() {
        val bytes = byteArrayOf(
            0x02, 0x13, 0x01,
            0x00, 0x00,
            0x00, 0x00,
            0x00, 0x0A,
            0x00, 0x00,
            0x00, 0x00,
            0x00, 0x78,
            0x00, 0x00,
            0x00, 0x00,
            0x01, 0x2C
        )
        val state = InMemoryO5PodStateManager()

        state.updateFromTriggeredAlertsResponse(PodInfoTriggeredAlertsResponse(bytes))

        val triggered = requireNotNull(state.triggeredAlertTimes)
        assertThat(triggered).containsExactly(
            AlertType.MULTI_COMMAND, 10.toShort(),
            AlertType.LOW_RESERVOIR, 120.toShort(),
            AlertType.EXPIRATION, 300.toShort()
        )
    }
}
