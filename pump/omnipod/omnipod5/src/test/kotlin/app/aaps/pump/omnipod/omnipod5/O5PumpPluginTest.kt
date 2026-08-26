package app.aaps.pump.omnipod.omnipod5
import app.aaps.pump.omnipod.common.R

import app.aaps.core.data.pump.defs.ManufacturerType
import app.aaps.core.data.model.BS
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.protection.ProtectionCheck
import app.aaps.core.interfaces.pump.BlePreCheck
import app.aaps.core.interfaces.pump.BolusProgressData
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.queue.CustomCommand
import app.aaps.pump.omnipod.omnipod5.bledriver.comm.O5BleManager
import app.aaps.pump.omnipod.common.bledriver.event.PodEvent
import app.aaps.pump.omnipod.common.bledriver.pod.definition.ActivationProgress
import app.aaps.pump.omnipod.common.bledriver.pod.definition.AlarmType
import app.aaps.pump.omnipod.common.bledriver.pod.definition.AlertType
import app.aaps.pump.omnipod.common.bledriver.pod.definition.DeliveryStatus
import app.aaps.pump.omnipod.common.bledriver.pod.definition.PodStatus
import app.aaps.pump.omnipod.omnipod5.bledriver.pod.state.O5PodStateManager
import app.aaps.pump.omnipod.common.bledriver.pod.command.StopDeliveryCommand
import app.aaps.pump.omnipod.common.queue.command.CommandDeactivatePod
import app.aaps.pump.omnipod.common.queue.command.CommandDeliverBasalCorrection
import app.aaps.pump.omnipod.common.queue.command.CommandDisableSuspendAlerts
import app.aaps.pump.omnipod.common.queue.command.CommandHandleTimeChange
import app.aaps.pump.omnipod.omnipod5.queue.command.CommandPairNewPod
import app.aaps.pump.omnipod.common.queue.command.CommandPlayTestBeep
import app.aaps.pump.omnipod.common.queue.command.CommandResumeDelivery
import app.aaps.pump.omnipod.common.queue.command.CommandSilenceAlerts
import app.aaps.pump.omnipod.common.queue.command.CommandSuspendDelivery
import app.aaps.pump.omnipod.common.queue.command.CommandUpdateAlertConfiguration
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import io.reactivex.rxjava3.core.Observable
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.atLeast
import org.mockito.kotlin.eq
import org.mockito.kotlin.argThat
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Covers the safety-relevant gating logic and simple property surface of [O5PumpPlugin] -
 * not exhaustive RxJava-flow coverage (no other pump plugin in this codebase unit-tests its
 * full blocking-RxJava dosing flow either, see [app.aaps.pump.omnipod.eros
 * .OmnipodErosPumpPluginTest] for the closest precedent). Focus: the gates that must reject
 * *before* any BLE command is sent (reservoir check, bolus-already-in-progress check,
 * no-op temp-basal-cancel), the `isBusy()`/`isConnected()`/`isInitialized()` state
 * transitions (the exact thing that caused the queue-deadlock bug during development), and
 * `executeCustomCommand` routing.
 */
class O5PumpPluginTest : TestBaseWithProfile() {

    @Mock lateinit var bleManager: O5BleManager
    @Mock lateinit var podStateManager: O5PodStateManager
    @Mock lateinit var pumpSync: PumpSync
    @Mock lateinit var commandQueue: CommandQueue
    @Mock lateinit var bolusProgressData: BolusProgressData
    @Mock lateinit var protectionCheck: ProtectionCheck
    @Mock lateinit var blePreCheck: BlePreCheck

    private lateinit var plugin: O5PumpPlugin

    @BeforeEach
    fun setup() {
        plugin = O5PumpPlugin(
            aapsLogger, rh, preferences, commandQueue, bleManager, podStateManager, pumpSync,
            notificationManager, pumpEnactResultProvider, bolusProgressData, protectionCheck, blePreCheck, config
        )
        whenever(rh.gs(R.string.omnipod_5_error_not_enough_insulin)).thenReturn("Not enough insulin")
        whenever(rh.gs(R.string.omnipod_5_error_bolus_already_in_progress)).thenReturn("Bolus already in progress")
        whenever(rh.gs(R.string.omnipod_5_error_extended_bolus_not_supported)).thenReturn("Extended bolus not supported")
        whenever(rh.gs(R.string.omnipod_common_error_unsupported_custom_command)).thenReturn("Unsupported custom command: %1\$s")
        whenever(rh.gs(R.string.omnipod_5_error_no_active_profile)).thenReturn("No active profile")
        whenever(rh.gs(R.string.omnipod_5_error_no_active_alerts)).thenReturn("No active alerts")
        whenever(rh.gs(R.string.omnipod_5_error_unresolved_dose_pending)).thenReturn("Earlier dose unconfirmed")
    }


    @Test
    fun `isBusy is false when activation has not started - pairing must never be blocked`() {
        whenever(podStateManager.activationProgress).thenReturn(ActivationProgress.NOT_STARTED)

        assertThat(plugin.isBusy()).isFalse()
    }

    @Test
    fun `isBusy is true while activation is in progress`() {
        whenever(podStateManager.activationProgress).thenReturn(ActivationProgress.GOT_POD_VERSION)

        assertThat(plugin.isBusy()).isTrue()
    }

    @Test
    fun `isBusy is false once activation is completed`() {
        whenever(podStateManager.activationProgress).thenReturn(ActivationProgress.COMPLETED)

        assertThat(plugin.isBusy()).isFalse()
    }

    @Test
    fun `isInitialized is true only when activation is fully completed`() {
        whenever(podStateManager.activationProgress).thenReturn(ActivationProgress.COMPLETED)
        assertThat(plugin.isInitialized()).isTrue()

        whenever(podStateManager.activationProgress).thenReturn(ActivationProgress.PRIME_COMPLETED)
        assertThat(plugin.isInitialized()).isFalse()
    }

    @Test
    fun `isConnected is true when unpaired - nothing to connect to yet`() {
        whenever(podStateManager.ltk).thenReturn(null)

        assertThat(plugin.isConnected()).isTrue()
    }

    @Test
    fun `isConnected reflects bluetoothConnectionState once paired`() {
        whenever(podStateManager.ltk).thenReturn(byteArrayOf(1))
        whenever(podStateManager.bluetoothConnectionState).thenReturn(O5PodStateManager.BluetoothConnectionState.CONNECTED)
        assertThat(plugin.isConnected()).isTrue()

        whenever(podStateManager.bluetoothConnectionState).thenReturn(O5PodStateManager.BluetoothConnectionState.DISCONNECTED)
        assertThat(plugin.isConnected()).isFalse()
    }


    @Test
    fun `deliverTreatment rejects carbs`() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { plugin.deliverTreatment(DetailedBolusInfo().also { it.carbs = 5.0; it.insulin = 1.0 }) }
        }
    }

    @Test
    fun `deliverTreatment rejects non-positive insulin`() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { plugin.deliverTreatment(DetailedBolusInfo().also { it.carbs = 0.0; it.insulin = 0.0 }) }
        }
    }

    @Test
    fun `deliverTreatment rejects a bolus larger than the reservoir without sending any command`() {
        whenever(podStateManager.reservoirPulsesRemaining).thenReturn(20)
        whenever(podStateManager.lastStatusResponseReceived).thenReturn(System.currentTimeMillis())
        whenever(podStateManager.lastBolusStartTime).thenReturn(null)
        whenever(podStateManager.lastBolusRequestedUnits).thenReturn(null)
        whenever(podStateManager.deliveryStatus).thenReturn(DeliveryStatus.BASAL_ACTIVE)

        val result = runBlocking {
            plugin.deliverTreatment(DetailedBolusInfo().also { it.carbs = 0.0; it.insulin = 5.0 })
        }

        assertThat(result.success).isFalse()
        assertThat(result.enacted).isFalse()
        assertThat(result.bolusDelivered).isEqualTo(0.0)
        verify(bleManager, never()).sendCommand(any(), any())
    }

    @Test
    fun `deliverTreatment rejects when a bolus is already delivering without sending any command`() {
        whenever(podStateManager.reservoirPulsesRemaining).thenReturn(2000)
        whenever(podStateManager.lastStatusResponseReceived).thenReturn(System.currentTimeMillis())
        whenever(podStateManager.lastBolusStartTime).thenReturn(null)
        whenever(podStateManager.lastBolusRequestedUnits).thenReturn(null)
        whenever(podStateManager.deliveryStatus).thenReturn(DeliveryStatus.BOLUS_AND_BASAL_ACTIVE)

        val result = runBlocking {
            plugin.deliverTreatment(DetailedBolusInfo().also { it.carbs = 0.0; it.insulin = 1.0 })
        }

        assertThat(result.success).isFalse()
        assertThat(result.enacted).isFalse()
        verify(bleManager, never()).sendCommand(any(), any())
    }


    @Test
    fun `setTempBasalPercent is unsupported`() {
        assertThrows(IllegalStateException::class.java) {
            runBlocking { plugin.setTempBasalPercent(80, 30, false, PumpSync.TemporaryBasalType.NORMAL) }
        }
    }

    @Test
    fun `cancelTempBasal is a no-op when nothing is running - never touches the pod`() {
        whenever(podStateManager.deliveryStatus).thenReturn(DeliveryStatus.BASAL_ACTIVE)

        val result = runBlocking {
            whenever(pumpSync.expectedPumpState()).thenReturn(
                PumpSync.PumpState(temporaryBasal = null, extendedBolus = null, bolus = null, profile = null, serialNumber = "")
            )
            plugin.cancelTempBasal(false)
        }

        assertThat(result.success).isTrue()
        assertThat(result.enacted).isFalse()
        verify(bleManager, never()).sendCommand(any(), any())
    }


    @Test
    fun `executeCustomCommand pairs a new pod for CommandPairNewPod`() {
        whenever(bleManager.pairNewPod()).thenReturn(Observable.empty<PodEvent>())

        val result = plugin.executeCustomCommand(CommandPairNewPod())

        assertThat(result).isNotNull()
        assertThat(result!!.success).isTrue()
        assertThat(result.enacted).isTrue()
        verify(bleManager).pairNewPod()
    }

    @Test
    fun `executeCustomCommand rejects an unrecognized custom command`() {
        val unknown = object : CustomCommand {
            override val statusDescription = "UNKNOWN"
        }

        val result = plugin.executeCustomCommand(unknown)

        assertThat(result).isNotNull()
        assertThat(result!!.success).isFalse()
        assertThat(result.enacted).isFalse()
    }

    @Test
    fun `executeCustomCommand deactivates the pod for CommandDeactivatePod`() {
        whenever(podStateManager.podId).thenReturn(12345L)
        whenever(bleManager.sendCommand(any(), any())).thenReturn(Observable.empty())

        val result = plugin.executeCustomCommand(CommandDeactivatePod())

        assertThat(result!!.success).isTrue()
        assertThat(result.enacted).isTrue()
        verify(bleManager).sendCommand(any(), any())
        verify(bleManager).removeBond()
        verify(podStateManager).reset()
    }

    @Test
    fun `executeCustomCommand silences alerts for CommandSilenceAlerts when there are active alerts`() {
        whenever(podStateManager.podId).thenReturn(12345L)
        whenever(podStateManager.activeAlerts).thenReturn(java.util.EnumSet.of(AlertType.LOW_RESERVOIR))
        whenever(bleManager.sendCommand(any(), any())).thenReturn(Observable.empty())

        val result = plugin.executeCustomCommand(CommandSilenceAlerts())

        assertThat(result!!.success).isTrue()
        assertThat(result.enacted).isTrue()
        verify(bleManager).sendCommand(any(), any())
    }

    @Test
    fun `executeCustomCommand rejects CommandSilenceAlerts when there are no active alerts`() {
        whenever(podStateManager.activeAlerts).thenReturn(null)

        val result = plugin.executeCustomCommand(CommandSilenceAlerts())

        assertThat(result!!.success).isFalse()
        assertThat(result.enacted).isFalse()
        verify(bleManager, never()).sendCommand(any(), any())
    }

    @Test
    fun `executeCustomCommand suspends delivery for CommandSuspendDelivery`() {
        whenever(podStateManager.podId).thenReturn(12345L)
        whenever(bleManager.sendCommand(any(), any())).thenReturn(Observable.empty())

        val result = plugin.executeCustomCommand(CommandSuspendDelivery())

        assertThat(result!!.success).isTrue()
        assertThat(result.enacted).isTrue()
        verify(bleManager).sendCommand(any(), any())
        verify(podStateManager).deliverySuspended = true
    }

    @Test
    fun `executeCustomCommand plays a test beep for CommandPlayTestBeep`() {
        whenever(podStateManager.podId).thenReturn(12345L)
        whenever(bleManager.sendCommand(any(), any())).thenReturn(Observable.empty())

        val result = plugin.executeCustomCommand(CommandPlayTestBeep())

        assertThat(result!!.success).isTrue()
        assertThat(result.enacted).isTrue()
        verify(bleManager).sendCommand(any(), any())
    }

    @Test
    fun `executeCustomCommand for CommandResumeDelivery fails cleanly when there is no active profile`() {
        runBlocking {
            whenever(pumpSync.expectedPumpState()).thenReturn(
                PumpSync.PumpState(temporaryBasal = null, extendedBolus = null, bolus = null, profile = null, serialNumber = "")
            )

            val result = plugin.executeCustomCommand(CommandResumeDelivery())

            assertThat(result!!.success).isFalse()
            assertThat(result.enacted).isFalse()
            verify(bleManager, never()).sendCommand(any(), any())
        }
    }

    @Test
    fun `executeCustomCommand for CommandHandleTimeChange fails cleanly when there is no active profile`() {
        runBlocking {
            whenever(pumpSync.expectedPumpState()).thenReturn(
                PumpSync.PumpState(temporaryBasal = null, extendedBolus = null, bolus = null, profile = null, serialNumber = "")
            )

            val result = plugin.executeCustomCommand(CommandHandleTimeChange(true))

            assertThat(result!!.success).isFalse()
            assertThat(result.enacted).isFalse()
            verify(bleManager, never()).sendCommand(any(), any())
        }
    }


    @Test
    fun `manufacturer, model, and DST support are fixed`() {
        assertThat(plugin.manufacturer()).isEqualTo(ManufacturerType.Insulet)
        assertThat(plugin.model()).isEqualTo(PumpType.OMNIPOD_5)
        assertThat(plugin.canHandleDST()).isFalse()
        assertThat(plugin.isFakingTempsByExtendedBoluses).isFalse()
    }

    @Test
    fun `serialNumber falls back to a placeholder before pairing`() {
        whenever(podStateManager.podId).thenReturn(null)
        assertThat(plugin.serialNumber()).isEqualTo("O5-unpaired")

        whenever(podStateManager.podId).thenReturn(4242L)
        assertThat(plugin.serialNumber()).isEqualTo("4242")
    }

    @Test
    fun `extended bolus is unsupported`() {
        val deliver = runBlocking { plugin.setExtendedBolus(1.0, 60) }
        assertThat(deliver.success).isFalse()

        val cancel = runBlocking { plugin.cancelExtendedBolus() }
        assertThat(cancel.success).isFalse()
    }


    @Test
    fun `isThisProfileSet is true before pairing - prevents premature basal-set attempts`() {
        whenever(podStateManager.ltk).thenReturn(null)

        assertThat(plugin.isThisProfileSet(mock())).isTrue()
    }

    @Test
    fun `isThisProfileSet is false while delivery is suspended - a set is genuinely needed`() {
        whenever(podStateManager.ltk).thenReturn(byteArrayOf(1))
        whenever(podStateManager.deliverySuspended).thenReturn(true)

        assertThat(plugin.isThisProfileSet(mock())).isFalse()
    }


    @Test
    fun `executeCustomCommand rejects CommandUpdateAlertConfiguration when settings already match`() {
        whenever(podStateManager.activationProgress).thenReturn(ActivationProgress.COMPLETED)
        whenever(podStateManager.syncedAlertSettings).thenReturn(
            O5PodStateManager.SyncedAlertSettings(
                expirationReminderEnabled = false, expirationReminderHours = 0,
                expirationAlarmEnabled = false, expirationAlarmHours = 0,
                lowReservoirAlertEnabled = false, lowReservoirAlertUnits = 0
            )
        )

        val result = plugin.executeCustomCommand(CommandUpdateAlertConfiguration())

        assertThat(result!!.success).isTrue()
        assertThat(result.enacted).isFalse()
        verify(bleManager, never()).sendCommand(any(), any())
    }

    @Test
    fun `executeCustomCommand rejects CommandUpdateAlertConfiguration before activation completes`() {
        whenever(podStateManager.activationProgress).thenReturn(ActivationProgress.PRIME_COMPLETED)

        val result = plugin.executeCustomCommand(CommandUpdateAlertConfiguration())

        assertThat(result!!.success).isTrue()
        assertThat(result.enacted).isFalse()
        verify(bleManager, never()).sendCommand(any(), any())
    }

    @Test
    fun `executeCustomCommand pushes CommandUpdateAlertConfiguration when settings changed and records the sync`() {
        whenever(podStateManager.activationProgress).thenReturn(ActivationProgress.COMPLETED)
        whenever(podStateManager.syncedAlertSettings).thenReturn(null)
        whenever(podStateManager.podId).thenReturn(12345L)
        whenever(podStateManager.podLifeInHours).thenReturn(80.toShort())
        whenever(podStateManager.minutesSinceActivation).thenReturn(60.toShort())
        whenever(podStateManager.lastStatusResponseReceived).thenReturn(System.currentTimeMillis())
        whenever(bleManager.sendCommand(any(), any())).thenReturn(Observable.empty())

        val result = plugin.executeCustomCommand(CommandUpdateAlertConfiguration())

        assertThat(result!!.success).isTrue()
        assertThat(result.enacted).isTrue()
        verify(bleManager).sendCommand(any(), any())
        verify(podStateManager).syncedAlertSettings = any()
    }

    @Test
    fun `executeCustomCommand rejects CommandDisableSuspendAlerts when nothing is armed`() {
        whenever(podStateManager.suspendAlertsEnabled).thenReturn(false)

        val result = plugin.executeCustomCommand(CommandDisableSuspendAlerts(rh))

        assertThat(result!!.success).isTrue()
        assertThat(result.enacted).isFalse()
        verify(bleManager, never()).sendCommand(any(), any())
    }

    @Test
    fun `executeCustomCommand silences the pod for CommandDisableSuspendAlerts when armed`() {
        whenever(podStateManager.suspendAlertsEnabled).thenReturn(true)
        whenever(podStateManager.podId).thenReturn(12345L)
        whenever(bleManager.sendCommand(any(), any())).thenReturn(Observable.empty())

        val result = plugin.executeCustomCommand(CommandDisableSuspendAlerts(rh))

        assertThat(result!!.success).isTrue()
        assertThat(result.enacted).isTrue()
        verify(bleManager).sendCommand(any(), any())
        verify(podStateManager).suspendAlertsEnabled = false
    }

    @Test
    fun `executeCustomCommand rejects CommandDeliverBasalCorrection when drift compensation is disabled - the default`() {
        val result = plugin.executeCustomCommand(CommandDeliverBasalCorrection())

        assertThat(result!!.success).isTrue()
        assertThat(result.enacted).isFalse()
        verify(bleManager, never()).sendCommand(any(), any())
    }



    @Test
    fun `a faulted pod is reported even when the fault code was never read`() {
        whenever(podStateManager.alarmSynced).thenReturn(false)
        whenever(podStateManager.isPodKaput).thenReturn(true)
        whenever(podStateManager.alarmType).thenReturn(null)
        whenever(podStateManager.podStatus).thenReturn(PodStatus.ALARM)
        whenever(podStateManager.podId).thenReturn(9999L)
        whenever(commandQueue.isCustomCommandInQueue(CommandDeactivatePod::class.java)).thenReturn(false)
        whenever(bleManager.sendCommand(any(), any())).thenReturn(Observable.error(RuntimeException("pod unreachable")))

        runBlocking {
            plugin.checkPodFault()

            verify(notificationManager).post(
                eq(NotificationId.OMNIPOD_POD_FAULT), any<String>(), level = any(), validMinutes = any(),
                soundRes = anyOrNull(), actions = any(), validityCheck = anyOrNull()
            )
            verify(pumpSync).insertAnnouncement(any<String>(), any<Long>(), eq(PumpType.OMNIPOD_5), eq("9999"))
        }
        verify(podStateManager).alarmSynced = true
    }

    @Test
    fun `a healthy pod is not reported`() {
        whenever(podStateManager.alarmSynced).thenReturn(false)
        whenever(podStateManager.isPodKaput).thenReturn(false)
        whenever(podStateManager.alarmType).thenReturn(null)

        runBlocking { plugin.checkPodFault() }

        verify(notificationManager, never()).post(
            any(), any<String>(), level = any(), validMinutes = any(),
            soundRes = anyOrNull(), actions = any(), validityCheck = anyOrNull()
        )
        verify(podStateManager, never()).alarmSynced = true
    }

    @Test
    fun `checkPodFault posts a notification and records an announcement on a new alarm`() {
        whenever(podStateManager.alarmSynced).thenReturn(false)
        whenever(podStateManager.alarmType).thenReturn(AlarmType.ALARM_OCCLUDED)
        whenever(commandQueue.isCustomCommandInQueue(CommandDeactivatePod::class.java)).thenReturn(false)
        whenever(podStateManager.podId).thenReturn(9999L)

        runBlocking {
            plugin.checkPodFault()

            verify(notificationManager).post(
                eq(NotificationId.OMNIPOD_POD_FAULT), any<String>(), level = any(), validMinutes = any(),
                soundRes = anyOrNull(), actions = any(), validityCheck = anyOrNull()
            )
            verify(pumpSync).insertAnnouncement(any<String>(), any<Long>(), eq(PumpType.OMNIPOD_5), eq("9999"))
        }
        verify(podStateManager).alarmSynced = true
    }

    @Test
    fun `checkPodFault is a no-op once the current alarm is already synced - not re-posted on every poll`() {
        whenever(podStateManager.alarmSynced).thenReturn(true)

        runBlocking { plugin.checkPodFault() }

        verify(notificationManager, never()).post(
            any(), any<String>(), level = any(), validMinutes = any(),
            soundRes = anyOrNull(), actions = any(), validityCheck = anyOrNull()
        )
    }

    @Test
    fun `checkPodFault is a no-op when there is no alarm`() {
        whenever(podStateManager.alarmSynced).thenReturn(false)
        whenever(podStateManager.alarmType).thenReturn(null)

        runBlocking { plugin.checkPodFault() }

        verify(notificationManager, never()).post(
            any(), any<String>(), level = any(), validMinutes = any(),
            soundRes = anyOrNull(), actions = any(), validityCheck = anyOrNull()
        )
        verify(podStateManager, never()).alarmSynced = any()
    }

    @Test
    fun `checkPodFault skips the notification but still records the announcement when deactivation is already queued`() {
        whenever(podStateManager.alarmSynced).thenReturn(false)
        whenever(podStateManager.alarmType).thenReturn(AlarmType.ALARM_OCCLUDED)
        whenever(commandQueue.isCustomCommandInQueue(CommandDeactivatePod::class.java)).thenReturn(true)
        whenever(podStateManager.podId).thenReturn(9999L)

        runBlocking {
            plugin.checkPodFault()

            verify(notificationManager, never()).post(
                any(), any<String>(), level = any(), validMinutes = any(),
                soundRes = anyOrNull(), actions = any(), validityCheck = anyOrNull()
            )
            verify(pumpSync).insertAnnouncement(any<String>(), any<Long>(), eq(PumpType.OMNIPOD_5), eq("9999"))
        }
        verify(podStateManager).alarmSynced = true
    }


    private fun pendingBolus(sequenceNumber: Short?) = O5PodStateManager.PendingDoseCommand(
        type = O5PodStateManager.PendingDoseType.BOLUS,
        requestedUnits = 3.0,
        bolusType = BS.Type.NORMAL,
        startedAt = 1_000L,
        sequenceNumber = sequenceNumber
    )

    @Test
    fun `an uncertain bolus the pod never received records no insulin`() {
        whenever(podStateManager.pendingDoseCommand).thenReturn(pendingBolus(sequenceNumber = 4))
        whenever(podStateManager.sequenceNumberOfLastProgrammingCommand).thenReturn(9)
        whenever(podStateManager.deliveryStatus).thenReturn(DeliveryStatus.BASAL_ACTIVE)

        runBlocking {
            plugin.reconcilePendingDose()

            verify(pumpSync, never()).syncBolusWithPumpId(
                any<Long>(), any(), any(), any<Long>(), any(), any<String>()
            )
        }
        verify(podStateManager).pendingDoseCommand = null
    }

    @Test
    fun `an uncertain bolus the pod did receive is finalized`() {
        whenever(podStateManager.pendingDoseCommand).thenReturn(pendingBolus(sequenceNumber = 9))
        whenever(podStateManager.sequenceNumberOfLastProgrammingCommand).thenReturn(9)
        whenever(podStateManager.deliveryStatus).thenReturn(DeliveryStatus.BASAL_ACTIVE)
        whenever(podStateManager.bolusPulsesRemaining).thenReturn(0)
        whenever(podStateManager.lastBolusDeliveredUnits).thenReturn(null)
        whenever(podStateManager.podId).thenReturn(9999L)

        runBlocking {
            plugin.reconcilePendingDose()

            verify(pumpSync).syncBolusWithPumpId(
                any<Long>(), any(), any(), any<Long>(), eq(PumpType.OMNIPOD_5), eq("9999")
            )
        }
        verify(podStateManager).pendingDoseCommand = null
    }

    @Test
    fun `a still-running bolus is confirmed by delivery status even when the sequence disagrees`() {
        whenever(podStateManager.pendingDoseCommand).thenReturn(pendingBolus(sequenceNumber = 4))
        whenever(podStateManager.sequenceNumberOfLastProgrammingCommand).thenReturn(9)
        whenever(podStateManager.deliveryStatus).thenReturn(DeliveryStatus.BOLUS_AND_BASAL_ACTIVE)

        runBlocking { plugin.reconcilePendingDose() }

        verify(podStateManager, never()).pendingDoseCommand = null
    }

    @Test
    fun `sequence numbers are compared as 4 bits so a wrapped counter still matches`() {
        whenever(podStateManager.pendingDoseCommand).thenReturn(pendingBolus(sequenceNumber = 0x1F))
        whenever(podStateManager.sequenceNumberOfLastProgrammingCommand).thenReturn(0x0F)
        whenever(podStateManager.deliveryStatus).thenReturn(DeliveryStatus.BASAL_ACTIVE)
        whenever(podStateManager.bolusPulsesRemaining).thenReturn(0)
        whenever(podStateManager.lastBolusDeliveredUnits).thenReturn(null)
        whenever(podStateManager.podId).thenReturn(9999L)

        runBlocking {
            plugin.reconcilePendingDose()

            verify(pumpSync).syncBolusWithPumpId(
                any<Long>(), any(), any(), any<Long>(), eq(PumpType.OMNIPOD_5), eq("9999")
            )
        }
    }

    @Test
    fun `a pending dose restored without a sequence number falls back to the old resolution`() {
        whenever(podStateManager.pendingDoseCommand).thenReturn(pendingBolus(sequenceNumber = null))
        whenever(podStateManager.sequenceNumberOfLastProgrammingCommand).thenReturn(9)
        whenever(podStateManager.deliveryStatus).thenReturn(DeliveryStatus.BASAL_ACTIVE)
        whenever(podStateManager.bolusPulsesRemaining).thenReturn(0)
        whenever(podStateManager.lastBolusDeliveredUnits).thenReturn(null)
        whenever(podStateManager.podId).thenReturn(9999L)

        runBlocking {
            plugin.reconcilePendingDose()

            verify(pumpSync).syncBolusWithPumpId(
                any<Long>(), any(), any(), any<Long>(), eq(PumpType.OMNIPOD_5), eq("9999")
            )
        }
    }

    @Test
    fun `an uncertain temp basal the pod never received is dropped without activating one`() {
        whenever(podStateManager.pendingDoseCommand).thenReturn(
            O5PodStateManager.PendingDoseCommand(
                type = O5PodStateManager.PendingDoseType.TEMP_BASAL_START,
                requestedRate = 0.5,
                requestedDurationMinutes = 30,
                startedAt = 1_000L,
                sequenceNumber = 4
            )
        )
        whenever(podStateManager.sequenceNumberOfLastProgrammingCommand).thenReturn(9)
        whenever(podStateManager.deliveryStatus).thenReturn(DeliveryStatus.BASAL_ACTIVE)

        runBlocking { plugin.reconcilePendingDose() }

        verify(podStateManager).pendingDoseCommand = null
    }

    @Test
    fun `reconcile is a no-op when nothing is pending`() {
        whenever(podStateManager.pendingDoseCommand).thenReturn(null)

        runBlocking { plugin.reconcilePendingDose() }

        verify(podStateManager, never()).pendingDoseCommand = anyOrNull()
    }


    @Test
    fun `a temp basal is refused while an earlier dose stays unresolved`() {
        whenever(podStateManager.ltk).thenReturn(ByteArray(16))
        whenever(podStateManager.podId).thenReturn(12345L)
        whenever(podStateManager.pendingDoseCommand).thenReturn(pendingBolus(sequenceNumber = 4))
        whenever(podStateManager.sequenceNumberOfLastProgrammingCommand).thenReturn(9)
        whenever(podStateManager.deliveryStatus).thenReturn(DeliveryStatus.BOLUS_AND_BASAL_ACTIVE)
        whenever(bleManager.sendCommand(any(), any())).thenReturn(Observable.empty())

        val result = runBlocking {
            plugin.setTempBasalAbsolute(1.0, 30, false, PumpSync.TemporaryBasalType.NORMAL)
        }

        assertThat(result.success).isFalse()
        assertThat(result.enacted).isFalse()
    }

    @Test
    fun `a bolus is refused while an earlier dose stays unresolved and no insulin is reported`() {
        whenever(podStateManager.ltk).thenReturn(ByteArray(16))
        whenever(podStateManager.podId).thenReturn(12345L)
        whenever(podStateManager.pendingDoseCommand).thenReturn(pendingBolus(sequenceNumber = 4))
        whenever(podStateManager.sequenceNumberOfLastProgrammingCommand).thenReturn(9)
        whenever(podStateManager.deliveryStatus).thenReturn(DeliveryStatus.BOLUS_AND_BASAL_ACTIVE)
        whenever(bleManager.sendCommand(any(), any())).thenReturn(Observable.empty())

        val result = runBlocking {
            plugin.deliverTreatment(DetailedBolusInfo().also { it.insulin = 2.0; it.carbs = 0.0 })
        }

        assertThat(result.success).isFalse()
        assertThat(result.enacted).isFalse()
        assertThat(result.bolusDelivered).isEqualTo(0.0)
    }

    @Test
    fun `the guard lets a command through once the status read settles the earlier dose`() {
        whenever(podStateManager.ltk).thenReturn(ByteArray(16))
        whenever(podStateManager.podId).thenReturn(12345L)
        whenever(podStateManager.pendingDoseCommand)
            .thenReturn(pendingBolus(sequenceNumber = 9))
            .thenReturn(null)
        whenever(podStateManager.sequenceNumberOfLastProgrammingCommand).thenReturn(9)
        whenever(podStateManager.deliveryStatus).thenReturn(DeliveryStatus.BOLUS_AND_BASAL_ACTIVE)
        whenever(bleManager.connect()).thenReturn(Observable.empty())
        whenever(bleManager.sendCommand(any(), any())).thenReturn(Observable.empty())

        runBlocking { plugin.setTempBasalAbsolute(1.0, 30, false, PumpSync.TemporaryBasalType.NORMAL) }

        verify(bleManager, atLeast(2)).sendCommand(any(), any())
    }

    @Test
    fun `nothing pending means no extra status read before a dose`() {
        whenever(podStateManager.ltk).thenReturn(ByteArray(16))
        whenever(podStateManager.podId).thenReturn(12345L)
        whenever(podStateManager.pendingDoseCommand).thenReturn(null)
        whenever(podStateManager.deliveryStatus).thenReturn(DeliveryStatus.BASAL_ACTIVE)
        whenever(bleManager.sendCommand(any(), any())).thenReturn(Observable.empty())

        runBlocking { plugin.setTempBasalAbsolute(1.0, 30, false, PumpSync.TemporaryBasalType.NORMAL) }

        verify(bleManager, times(1)).sendCommand(any(), any())
    }

    @Test
    fun `a canceled bolus reconnects and re-sends the stop command when the link dropped mid-delivery`() {
        whenever(podStateManager.pendingDoseCommand).thenReturn(null)
        whenever(podStateManager.reservoirPulsesRemaining).thenReturn(2000)
        whenever(podStateManager.lastStatusResponseReceived).thenReturn(System.currentTimeMillis())
        whenever(podStateManager.lastBolusStartTime).thenReturn(null)
        whenever(podStateManager.lastBolusRequestedUnits).thenReturn(null)
        whenever(podStateManager.podId).thenReturn(12345L)
        // Not bolus-active: lets the initial gate pass and the retry loop settle after one cancel.
        whenever(podStateManager.deliveryStatus).thenReturn(DeliveryStatus.BASAL_ACTIVE)
        // Reconnect is a no-op success - simulates the link being brought back up before the stop.
        whenever(bleManager.connect()).thenReturn(Observable.empty())
        // The user presses cancel while the bolus program command is in flight.
        whenever(bleManager.sendCommand(any(), any())).thenAnswer {
            plugin.stopBolusDelivering()
            Observable.empty<PodEvent>()
        }

        val result = runBlocking {
            plugin.deliverTreatment(DetailedBolusInfo().also { it.carbs = 0.0; it.insulin = 0.5 })
        }

        assertThat(result.success).isTrue()
        // The stop must reach the pod, and only after a (re)connect.
        verify(bleManager).connect()
        verify(bleManager).sendCommand(argThat { this is StopDeliveryCommand }, any())
    }
}
