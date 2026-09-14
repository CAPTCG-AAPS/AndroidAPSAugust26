package app.aaps.pump.omnipod.common

import app.aaps.core.data.pump.defs.ManufacturerType
import app.aaps.core.data.model.BS
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.protection.ProtectionCheck
import app.aaps.core.interfaces.pump.BlePreCheck
import app.aaps.core.interfaces.pump.BolusProgressData
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.pump.PumpInsulin
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.queue.CustomCommand
import app.aaps.pump.omnipod.common.bledriver.comm.O5BleManager
import app.aaps.pump.omnipod.common.bledriver.event.PodEvent
import app.aaps.pump.omnipod.common.bledriver.pod.definition.ActivationProgress
import app.aaps.pump.omnipod.common.bledriver.pod.definition.AlarmType
import app.aaps.pump.omnipod.common.bledriver.pod.definition.AlertType
import app.aaps.pump.omnipod.common.bledriver.pod.definition.DeliveryStatus
import app.aaps.pump.omnipod.common.bledriver.pod.definition.PodStatus
import app.aaps.pump.omnipod.common.bledriver.pod.state.O5PodStateManager
import app.aaps.pump.omnipod.common.queue.command.CommandDeactivatePod
import app.aaps.pump.omnipod.common.queue.command.CommandDeliverBasalCorrection
import app.aaps.pump.omnipod.common.queue.command.CommandDisableSuspendAlerts
import app.aaps.pump.omnipod.common.queue.command.CommandHandleTimeChange
import app.aaps.pump.omnipod.common.queue.command.CommandPairNewPod
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
import org.mockito.kotlin.argThat
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.atLeast
import org.mockito.kotlin.eq
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
        // TestBaseWithProfile's rh mock only stubs a handful of generic strings (ok/error/
        // mgdl/mmol) - these are the ones O5PumpPlugin itself uses for PumpEnactResult
        // comments/warnings, needed so String.format(rh.gs(id), ...) doesn't NPE on a null
        // template in the gs(Int, String) etc. overload stubs.
        whenever(rh.gs(R.string.omnipod_5_error_not_enough_insulin)).thenReturn("Not enough insulin")
        whenever(rh.gs(R.string.omnipod_5_error_bolus_already_in_progress)).thenReturn("Bolus already in progress")
        whenever(rh.gs(R.string.omnipod_5_error_extended_bolus_not_supported)).thenReturn("Extended bolus not supported")
        whenever(rh.gs(R.string.omnipod_common_error_unsupported_custom_command)).thenReturn("Unsupported custom command: %1\$s")
        whenever(rh.gs(R.string.omnipod_5_error_no_active_profile)).thenReturn("No active profile")
        whenever(rh.gs(R.string.omnipod_5_error_no_active_alerts)).thenReturn("No active alerts")
        whenever(rh.gs(R.string.omnipod_5_error_unresolved_dose_pending)).thenReturn("Earlier dose unconfirmed")
    }

    // -- isBusy / isConnected / isInitialized (the exact bug class already hit once) -------

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

    // -- deliverTreatment: guards that must reject before any BLE command is sent ----------

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
        whenever(podStateManager.reservoirPulsesRemaining).thenReturn(20) // 20 * 0.05 = 1.0 U
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
        whenever(podStateManager.reservoirPulsesRemaining).thenReturn(2000) // plenty
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

    // -- temp basal --------------------------------------------------------------------------

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

    // -- executeCustomCommand routing ---------------------------------------------------------

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

    // -- simple property surface --------------------------------------------------------------

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

    // -- isThisProfileSet early-outs ----------------------------------------------------------

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

    // -- alert config sync / suspend-alert silencing / basal-drift correction --------------
    // (the 3 previously-dead command classes wired this session)

    @Test
    fun `executeCustomCommand rejects CommandUpdateAlertConfiguration when settings already match`() {
        whenever(podStateManager.activationProgress).thenReturn(ActivationProgress.COMPLETED)
        // Every OmnipodBooleanPreferenceKey/OmnipodIntPreferenceKey read below is unstubbed on
        // the mock preferences, i.e. Mockito's false/0 defaults - matching this exactly proves
        // the comparison is real, not incidentally always-different.
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
        whenever(podStateManager.syncedAlertSettings).thenReturn(null) // never synced
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
    fun `executeCustomCommand silences the pod for CommandDisableSuspendAlerts even when nothing is armed`() {
        // disableSuspendAlerts now always sends the silence command, the same as Dash - it no
        // longer skips when suspendAlertsEnabled is false, so a stuck SUSPEND_ENDED alert is
        // always cleared (see O5PumpPlugin.disableSuspendAlerts).
        whenever(podStateManager.suspendAlertsEnabled).thenReturn(false)
        whenever(podStateManager.podId).thenReturn(12345L)
        whenever(bleManager.sendCommand(any(), any())).thenReturn(Observable.empty())

        val result = plugin.executeCustomCommand(CommandDisableSuspendAlerts(rh))

        assertThat(result!!.success).isTrue()
        assertThat(result.enacted).isTrue()
        verify(bleManager).sendCommand(any(), any())
        verify(podStateManager).suspendAlertsEnabled = false
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
        // config.isEnabled(ENABLE_OMNIPOD_DRIFT_COMPENSATION) is unstubbed - Mockito's false
        // default - proving this experimental feature stays off unless explicitly enabled.
        val result = plugin.executeCustomCommand(CommandDeliverBasalCorrection())

        assertThat(result!!.success).isTrue()
        assertThat(result.enacted).isFalse()
        verify(bleManager, never()).sendCommand(any(), any())
    }

    // -- checkPodFault: a real pod fault must produce a user-facing notification, not just -
    // -- silent internal state (the gap Dash didn't have but O5 originally did) ------------

    // The regression these guard: checkPodFault() used to start `alarmType ?: return`, but
    // alarmType is only ever set from an alarm-status response - which the pod sends solely when
    // asked, and which Session turns into a failed command whose response was then discarded. So
    // a faulted pod produced no notification at all. The trigger is now isPodKaput, refreshed by
    // every routine status poll. (isPodKaput is stubbed here because it is a default interface
    // getter that a mock bypasses; its derivation from podStatus is covered in
    // PersistedO5PodStateManagerTest.)

    @Test
    fun `a faulted pod is reported even when the fault code was never read`() {
        whenever(podStateManager.alarmSynced).thenReturn(false)
        whenever(podStateManager.isPodKaput).thenReturn(true)
        whenever(podStateManager.alarmType).thenReturn(null)
        whenever(podStateManager.podStatus).thenReturn(PodStatus.ALARM)
        whenever(podStateManager.podId).thenReturn(9999L)
        whenever(commandQueue.isCustomCommandInQueue(CommandDeactivatePod::class.java)).thenReturn(false)
        // The alarm-page read fails, as it does in the field when the pod has stopped responding.
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

    // -- reconcilePendingDose: deciding an uncertain dose from the pod's own sequence number -
    //
    // The pod reports the sequence number of the last programming command it accepted. Comparing
    // it against the number the dose was sent with is the only way to tell a bolus that completed
    // from one the pod never received - both look like "not currently bolusing" afterwards.
    // Mirrors OmnipodKit's PodCommsSession.recoverUnacknowledgedCommand.

    private fun pendingBolus(sequenceNumber: Short?) = O5PodStateManager.PendingDoseCommand(
        type = O5PodStateManager.PendingDoseType.BOLUS,
        requestedUnits = 3.0,
        bolusType = BS.Type.NORMAL,
        startedAt = 1_000L,
        sequenceNumber = sequenceNumber
    )

    @Test
    fun `an uncertain bolus the pod never received records no insulin`() {
        // The regression this whole change exists for. Sequence numbers disagree and the pod is
        // not bolusing, so the command never landed - crediting insulin here would inflate IOB.
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
    fun `an uncertain bolus the pod never received corrects conservative accounting to zero`() {
        whenever(podStateManager.pendingDoseCommand).thenReturn(
            pendingBolus(sequenceNumber = 4).copy(bolusRecordExpected = true)
        )
        whenever(podStateManager.sequenceNumberOfLastProgrammingCommand).thenReturn(9)
        whenever(podStateManager.deliveryStatus).thenReturn(DeliveryStatus.BASAL_ACTIVE)
        whenever(podStateManager.podId).thenReturn(9999L)

        runBlocking {
            plugin.reconcilePendingDose()

            verify(pumpSync).syncBolusWithPumpId(
                eq(1_000L),
                argThat<PumpInsulin> { cU == 0.0 },
                eq(BS.Type.NORMAL),
                eq(1_000L),
                eq(PumpType.OMNIPOD_5),
                eq("9999")
            )
        }
        verify(podStateManager).pendingDoseCommand = null
    }

    @Test
    fun `an uncertain bolus the pod did receive is finalized`() {
        // Same observable delivery status as above - only the sequence number distinguishes them.
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
        verify(podStateManager).completeBolus(1_000L, 3.0, 3.0, 60)
    }

    @Test
    fun `a confirmed partial bolus updates accounting and cumulative pulses`() {
        whenever(podStateManager.pendingDoseCommand).thenReturn(pendingBolus(sequenceNumber = 9))
        whenever(podStateManager.sequenceNumberOfLastProgrammingCommand).thenReturn(9)
        whenever(podStateManager.deliveryStatus).thenReturn(DeliveryStatus.BASAL_ACTIVE)
        whenever(podStateManager.bolusPulsesRemaining).thenReturn(40)
        whenever(podStateManager.lastBolusDeliveredUnits).thenReturn(null)
        whenever(podStateManager.cumulativeBolusPulsesDelivered).thenReturn(10)
        whenever(podStateManager.podId).thenReturn(9999L)

        runBlocking {
            plugin.reconcilePendingDose()

            verify(pumpSync).syncBolusWithPumpId(
                eq(1_000L),
                argThat<PumpInsulin> { cU == 1.0 },
                eq(BS.Type.NORMAL),
                eq(1_000L),
                eq(PumpType.OMNIPOD_5),
                eq("9999")
            )
        }
        verify(podStateManager).completeBolus(1_000L, 3.0, 1.0, 20)
    }

    @Test
    fun `a confirmed bolus remains unresolved when remaining pulses are unavailable`() {
        // No pulse count means we cannot say how much was given, so the dose has to stay pending.
        whenever(podStateManager.pendingDoseCommand).thenReturn(pendingBolus(sequenceNumber = 9))
        whenever(podStateManager.sequenceNumberOfLastProgrammingCommand).thenReturn(9)
        whenever(podStateManager.deliveryStatus).thenReturn(DeliveryStatus.BASAL_ACTIVE)
        whenever(podStateManager.bolusPulsesRemaining).thenReturn(null)

        runBlocking {
            plugin.reconcilePendingDose()

            verify(pumpSync, never()).syncBolusWithPumpId(
                any<Long>(), any(), any(), any<Long>(), any(), any<String>()
            )
        }
        verify(podStateManager, never()).pendingDoseCommand = null
    }

    @Test
    fun `a still-running bolus is confirmed by delivery status even when the sequence disagrees`() {
        // The positive-observation fallback: the pod is actively bolusing, so it clearly got it.
        // Left pending here because the bolus has not finished yet.
        val pending = pendingBolus(sequenceNumber = 4)
        whenever(podStateManager.pendingDoseCommand).thenReturn(pending)
        whenever(podStateManager.sequenceNumberOfLastProgrammingCommand).thenReturn(9)
        whenever(podStateManager.deliveryStatus).thenReturn(DeliveryStatus.BOLUS_AND_BASAL_ACTIVE)

        runBlocking { plugin.reconcilePendingDose() }

        verify(podStateManager).pendingDoseCommand = pending.copy(confirmedByStatus = true)
    }

    @Test
    fun `sequence numbers are compared as 4 bits so a wrapped counter still matches`() {
        // msgSequenceNumber is a Byte that wraps at 16; the pod reports 4 bits. Comparing the
        // raw values would fail to match here and wrongly discard a delivered dose.
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
        // Migration case: state files written before the sequence number was recorded must not
        // have their dose silently discarded.
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

    // -- the stacking guard: never send a dose on top of one whose outcome is still unknown ---
    //
    // Mirrors OmnipodKit's tryToResolvePendingCommand: read status first and only refuse if the
    // earlier dose is *still* undecided afterwards, so the common "pod is back" case proceeds.

    @Test
    fun `a temp basal is refused while an earlier dose stays unresolved`() {
        whenever(podStateManager.ltk).thenReturn(ByteArray(16))
        whenever(podStateManager.podId).thenReturn(12345L)
        // Never resolves: the pod keeps reporting a sequence that does not match.
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
        // The pod is reachable again and its sequence number matches, so the pending dose
        // resolves and the new command must not be blocked. Modelled by the state manager
        // reporting a pending dose first and null after reconciliation clears it.
        whenever(podStateManager.ltk).thenReturn(ByteArray(16))
        whenever(podStateManager.podId).thenReturn(12345L)
        whenever(podStateManager.pendingDoseCommand)
            .thenReturn(pendingBolus(sequenceNumber = 9))
            .thenReturn(null)
        whenever(podStateManager.sequenceNumberOfLastProgrammingCommand).thenReturn(9)
        whenever(podStateManager.deliveryStatus).thenReturn(DeliveryStatus.BOLUS_AND_BASAL_ACTIVE)
        whenever(bleManager.sendCommand(any(), any())).thenReturn(Observable.empty())
        // The status read reconnects first (see O5PumpPlugin.ensureConnected).
        whenever(bleManager.connect()).thenReturn(Observable.empty())

        runBlocking { plugin.setTempBasalAbsolute(1.0, 30, false, PumpSync.TemporaryBasalType.NORMAL) }

        // A status read plus the temp basal itself - not refused before reaching the pod.
        verify(bleManager, atLeast(2)).sendCommand(any(), any())
    }

    @Test
    fun `a status read reconnects first, so it cannot fail on a link the pod has dropped`() {
        // The pod routinely drops the BLE link during a long bolus, and sendCommand does not
        // connect on its own - without the reconnect a cancel or status poll fails and the
        // bolus keeps running. connect() is a no-op when the session is still up.
        whenever(podStateManager.ltk).thenReturn(ByteArray(16))
        whenever(podStateManager.podId).thenReturn(12345L)
        whenever(podStateManager.pendingDoseCommand)
            .thenReturn(pendingBolus(sequenceNumber = 9))
            .thenReturn(null)
        whenever(podStateManager.sequenceNumberOfLastProgrammingCommand).thenReturn(9)
        whenever(podStateManager.deliveryStatus).thenReturn(DeliveryStatus.BOLUS_AND_BASAL_ACTIVE)
        whenever(bleManager.sendCommand(any(), any())).thenReturn(Observable.empty())
        whenever(bleManager.connect()).thenReturn(Observable.empty())

        runBlocking { plugin.setTempBasalAbsolute(1.0, 30, false, PumpSync.TemporaryBasalType.NORMAL) }

        verify(bleManager, atLeast(1)).connect()
    }

    @Test
    fun `nothing pending means no extra status read before a dose`() {
        whenever(podStateManager.ltk).thenReturn(ByteArray(16))
        whenever(podStateManager.podId).thenReturn(12345L)
        whenever(podStateManager.pendingDoseCommand).thenReturn(null)
        whenever(podStateManager.deliveryStatus).thenReturn(DeliveryStatus.BASAL_ACTIVE)
        whenever(bleManager.sendCommand(any(), any())).thenReturn(Observable.empty())

        runBlocking { plugin.setTempBasalAbsolute(1.0, 30, false, PumpSync.TemporaryBasalType.NORMAL) }

        // Only the temp basal command itself, no resolution round trip.
        verify(bleManager, times(1)).sendCommand(any(), any())
    }
}
