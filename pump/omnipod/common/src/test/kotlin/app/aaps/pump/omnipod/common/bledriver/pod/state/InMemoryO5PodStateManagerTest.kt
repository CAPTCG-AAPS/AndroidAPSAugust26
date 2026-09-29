package app.aaps.pump.omnipod.common.bledriver.pod.state

import app.aaps.pump.omnipod.common.bledriver.pod.definition.AlarmType
import app.aaps.pump.omnipod.common.bledriver.pod.definition.AlertType
import app.aaps.pump.omnipod.common.bledriver.pod.response.AlarmStatusResponse
import app.aaps.pump.omnipod.common.bledriver.pod.response.DefaultStatusResponse
import app.aaps.pump.omnipod.common.bledriver.pod.response.PodInfoActivationTimeResponse
import app.aaps.pump.omnipod.common.bledriver.pod.response.PodInfoTriggeredAlertsResponse
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.Calendar

/**
 * [O5PodStateManager.updateFromActivationTimeResponse]/[O5PodStateManager
 * .updateFromTriggeredAlertsResponse] - status pages 5/1's update methods, fetched
 * on-demand by [app.aaps.pump.omnipod.common.O5PumpPlugin] (see its
 * `fetchActivationTimeIfNeeded`/`fetchTriggeredAlertsIfNeeded`).
 */
class InMemoryO5PodStateManagerTest {

    @Test
    fun `above 50 reservoir response does not replace a measured value`() {
        val measured = DefaultStatusResponse(hexToBytes("1D1800A02800000463E8"))
        val above50 = DefaultStatusResponse(hexToBytes("1D1800A02800000463FF"))
        val state = InMemoryO5PodStateManager()

        state.updateFromDefaultStatusResponse(above50)
        assertThat(state.reservoirPulsesRemaining).isNull()

        state.updateFromDefaultStatusResponse(measured)
        assertThat(state.reservoirPulsesRemaining).isEqualTo(1000.toShort())

        state.updateFromDefaultStatusResponse(above50)
        assertThat(state.reservoirPulsesRemaining).isEqualTo(1000.toShort())

        state.updateFromAlarmStatusResponse(AlarmStatusResponse(hexToBytes("021602080100000501BD00000003FF01950000000000670A")))
        assertThat(state.reservoirPulsesRemaining).isEqualTo(1000.toShort())
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    @Test
    fun `updateFromActivationTimeResponse sets podActivatedAt and reuses alarmType-alarmTime`() {
        val bytes = byteArrayOf(
            0x02, 0x11, 0x05,
            0x14, // faultEventCode = ALARM_OCCLUDED
            0x00, 0x7D, // faultTime = 125
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x07, // month = 7
            0x0F, // day = 15
            0x1A, // year = 26 (2026)
            0x09, // hour = 9
            0x1E  // minute = 30
        )
        val state = InMemoryO5PodStateManager()

        state.updateFromActivationTimeResponse(PodInfoActivationTimeResponse(bytes))

        assertThat(state.alarmType).isEqualTo(AlarmType.ALARM_OCCLUDED)
        assertThat(state.alarmTime).isEqualTo(125.toShort())
        val activatedAt = requireNotNull(state.podActivatedAt)
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = activatedAt
        assertThat(calendar[Calendar.YEAR]).isEqualTo(2026)
        assertThat(calendar[Calendar.MONTH]).isEqualTo(Calendar.JULY) // Calendar months are 0-based
        assertThat(calendar[Calendar.DAY_OF_MONTH]).isEqualTo(15)
        assertThat(calendar[Calendar.HOUR_OF_DAY]).isEqualTo(9)
        assertThat(calendar[Calendar.MINUTE]).isEqualTo(30)
    }

    @Test
    fun `updateFromTriggeredAlertsResponse keeps only non-zero slots`() {
        val bytes = byteArrayOf(
            0x02, 0x13, 0x01,
            0x00, 0x00, // unknownWord
            0x00, 0x00, // AUTO_OFF = 0 (never triggered - dropped)
            0x00, 0x0A, // MULTI_COMMAND = 10
            0x00, 0x00, // EXPIRATION_IMMINENT = 0 (dropped)
            0x00, 0x00, // USER_SET_EXPIRATION = 0 (dropped)
            0x00, 0x78, // LOW_RESERVOIR = 120
            0x00, 0x00, // SUSPEND_IN_PROGRESS = 0 (dropped)
            0x00, 0x00, // SUSPEND_ENDED = 0 (dropped)
            0x01, 0x2C  // EXPIRATION = 300
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
