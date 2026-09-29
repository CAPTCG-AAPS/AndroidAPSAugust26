package app.aaps.pump.omnipod.common

import app.aaps.core.data.model.BS
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.pump.defs.ManufacturerType
import app.aaps.core.data.pump.defs.PumpDescription
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.configuration.ExternalOptions
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.pump.BlePreCheck
import app.aaps.core.interfaces.pump.BolusProgressData
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.pump.PumpInsulin
import app.aaps.core.interfaces.pump.PumpPluginBase
import app.aaps.core.interfaces.pump.PumpProfile
import app.aaps.core.interfaces.pump.PumpRate
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.pump.defs.fillFor
import app.aaps.core.interfaces.protection.ProtectionCheck
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.queue.CustomCommand
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.collectResilient
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.withCompose
import app.aaps.core.ui.compose.ComposeScreenContent
import app.aaps.core.ui.compose.icons.IcPluginOmnipod
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.pump.omnipod.common.bledriver.comm.O5BleManager
import app.aaps.pump.omnipod.common.bledriver.pod.command.DeactivateCommand
import app.aaps.pump.omnipod.common.bledriver.pod.command.GetStatusCommand
import app.aaps.pump.omnipod.common.bledriver.pod.command.ProgramAlertsCommand
import app.aaps.pump.omnipod.common.bledriver.pod.command.ProgramBasalCommand
import app.aaps.pump.omnipod.common.bledriver.pod.command.ProgramBeepsCommand
import app.aaps.pump.omnipod.common.bledriver.pod.command.ProgramBolusCommand
import app.aaps.pump.omnipod.common.bledriver.pod.command.ProgramTempBasalCommand
import app.aaps.pump.omnipod.common.bledriver.pod.command.SilenceAlertsCommand
import app.aaps.pump.omnipod.common.bledriver.pod.command.StopDeliveryCommand
import app.aaps.pump.omnipod.common.bledriver.pod.command.SuspendDeliveryCommand
import app.aaps.pump.omnipod.common.bledriver.pod.definition.ActivationProgress
import app.aaps.pump.omnipod.common.bledriver.pod.definition.AlertConfiguration
import app.aaps.pump.omnipod.common.bledriver.pod.definition.AlertTrigger
import app.aaps.pump.omnipod.common.bledriver.pod.definition.AlertType
import app.aaps.pump.omnipod.common.bledriver.pod.definition.BeepRepetitionType
import app.aaps.pump.omnipod.common.bledriver.pod.definition.BeepType
import app.aaps.pump.omnipod.common.bledriver.pod.definition.O5_FIXED_NONCE
import app.aaps.pump.omnipod.common.bledriver.pod.definition.PodConstants
import app.aaps.pump.omnipod.common.bledriver.pod.definition.ProgramReminder
import app.aaps.pump.omnipod.common.bledriver.pod.response.AlarmStatusResponse
import app.aaps.pump.omnipod.common.bledriver.pod.response.DefaultStatusResponse
import app.aaps.pump.omnipod.common.bledriver.pod.response.PodInfoActivationTimeResponse
import app.aaps.pump.omnipod.common.bledriver.pod.response.PodInfoTriggeredAlertsResponse
import app.aaps.pump.omnipod.common.bledriver.pod.response.ResponseType
import app.aaps.pump.omnipod.common.bledriver.pod.state.O5PodStateManager
import app.aaps.pump.omnipod.common.bledriver.pod.state.basalDrift
import app.aaps.pump.omnipod.common.bledriver.pod.state.basalDelivered
import app.aaps.pump.omnipod.common.bledriver.pod.util.buildO5ExpirationAlerts
import app.aaps.pump.omnipod.common.keys.DashBooleanPreferenceKey
import app.aaps.pump.omnipod.common.keys.O5IntentKey
import app.aaps.pump.omnipod.common.keys.OmnipodBooleanPreferenceKey
import app.aaps.pump.omnipod.common.keys.OmnipodIntPreferenceKey
import app.aaps.pump.omnipod.common.ui.O5CertificateStoreScreen
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
import app.aaps.pump.omnipod.common.ui.compose.OmnipodO5ComposeContent
import app.aaps.pump.omnipod.common.util.mapProfileToBasalProgram
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Single
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.rx3.rxCompletable
import org.json.JSONObject
import java.util.Date
import java.util.concurrent.CountDownLatch
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.concurrent.thread
import kotlin.math.ceil

/**
 * `Pump`-interface implementation for Omnipod 5 - the missing piece [O5Module]'s doc
 * comment describes. Connects the already-built pairing/BLE/command/persistence layers
 * (see [O5BleManager], [O5PodStateManager]) to AAPS's dosing/control surface.
 *
 * Runs O5 as a manual pod under AAPS's control, identical capabilities to Omnipod Dash -
 * the pod's own onboard automated-delivery mode is never engaged (mirrors
 * [PumpType.OMNIPOD_DASH]'s capability fields).
 *
 * Every dose-affecting command (bolus, temp basal, basal program) records a
 * [O5PodStateManager.pendingDoseCommand] marker *before* sending, and clears it only once
 * the outcome is confirmed. If the BLE response never arrives, the marker survives (it's
 * part of the persisted pod state) and [reconcilePendingDose] - run at the end of every
 * [getPumpStatus] call, including the periodic [statusChecker] heartbeat - resolves it on
 * the next successful status read. This is what makes an uncertain delivery outcome
 * recoverable instead of silently lost; see [O5PodStateManager]'s class doc for why this
 * doesn't need a Dash-style persisted command ledger to do so.
 */
@Singleton
class O5PumpPlugin @Inject constructor(
    aapsLogger: AAPSLogger,
    rh: ResourceHelper,
    preferences: Preferences,
    commandQueue: CommandQueue,
    private val bleManager: O5BleManager,
    private val podStateManager: O5PodStateManager,
    private val pumpSync: PumpSync,
    private val notificationManager: NotificationManager,
    private val pumpEnactResultProvider: Provider<PumpEnactResult>,
    private val bolusProgressData: BolusProgressData,
    private val protectionCheck: ProtectionCheck,
    private val blePreCheck: BlePreCheck,
    private val config: Config
) : PumpPluginBase(
    pluginDescription = PluginDescription()
        .mainType(PluginType.PUMP)
        .composeContent { _ ->
            OmnipodO5ComposeContent(
                pluginName = rh.gs(R.string.omnipod_5_name),
                protectionCheck = protectionCheck,
                blePreCheck = blePreCheck,
                rh = rh
            )
        }
        .icon(IcPluginOmnipod)
        .pluginName(R.string.omnipod_5_name)
        .shortName(R.string.omnipod_5_name_short)
        .description(R.string.omnipod_5_pump_description),
    ownPreferences = listOf(
        OmnipodBooleanPreferenceKey::class.java, OmnipodIntPreferenceKey::class.java,
        DashBooleanPreferenceKey::class.java, O5IntentKey::class.java
    ),
    aapsLogger, rh, preferences, commandQueue
), Pump {

    /** Scopes the alert-preference observer started in [onStart]; cancelled in [onStop]. */
    private var scope: CoroutineScope? = null

    @Volatile private var bolusCanceled = false
    @Volatile private var bolusDeliveryInProgress = false
    @Volatile private var stopConnecting: CountDownLatch? = null

    private var statusChecker: Runnable

    companion object {

        private const val BOLUS_RETRY_INTERVAL_MS = 2000L
        private const val BOLUS_RETRIES = 5
        private const val STATUS_CHECK_INTERVAL_MS = 60L * 1000
        private const val RESERVOIR_OVER_50_UNITS_DEFAULT = 75.0

        private const val FIXED_NONCE = O5_FIXED_NONCE

        /** Matches Dash's fixed pulse-delay constant for bolus delivery pacing - a
         *  pod-firmware-level property of the shared command layer. */
        private const val BOLUS_DELAY_BETWEEN_PULSES_EIGHTH_SECONDS: Byte = 16

        private val pumpDescription = PumpDescription().fillFor(PumpType.OMNIPOD_5)
    }

    init {
        statusChecker = Runnable {
            refreshStatusOnUnacknowledgedCommands()
            // Re-arm only while a dose is still unconfirmed, so the tick stops on its own
            // once there is nothing left to reconcile.
            if (podStateManager.pendingDoseCommand != null) {
                handler?.postDelayed(statusChecker, STATUS_CHECK_INTERVAL_MS)
            }
        }
    }

    /**
     * (Re)starts the 60s [statusChecker] tick. Called wherever a dose-affecting command records a
     * pending marker, so an unconfirmed dose is reconciled promptly instead of waiting for the next
     * loop-driven read. The tick self-stops once the marker clears.
     */
    private fun armStatusChecker() {
        handler?.removeCallbacks(statusChecker)
        handler?.postDelayed(statusChecker, STATUS_CHECK_INTERVAL_MS)
    }

    /**
     * Queues one status read when a dose-affecting command was left unconfirmed.
     *
     * The periodic tick used to call `getPumpStatus` unconditionally, which forced a BLE connect
     * every 60s for the life of the plugin and drained the phone battery. Routine polling is
     * already driven by the loop cycle and `KeepAliveWorker` (as it is for Dash), so the pod can
     * stay disconnected between cycles; this only reaches out when
     * [O5PodStateManager.pendingDoseCommand] survived, which is what makes an uncertain delivery
     * outcome recoverable rather than silently lost. The queued read routes through
     * [getPumpStatus], which runs reconciliation at the end.
     *
     * Mirrors `OmnipodDashPumpPlugin.refreshStatusOnUnacknowledgedCommands`.
     */
    private fun refreshStatusOnUnacknowledgedCommands() {
        if (podStateManager.activationProgress == ActivationProgress.COMPLETED &&
            podStateManager.pendingDoseCommand != null &&
            commandQueue.size() == 0 &&
            commandQueue.performing() == null
        ) {
            scope?.launch { commandQueue.readStatus(rh.gs(R.string.omnipod_5_unconfirmed_command)) }
        }
    }

    override suspend fun onStart() {
        super.onStart()
        if (podStateManager.pendingDoseCommand != null) {
            handler?.postDelayed(statusChecker, STATUS_CHECK_INTERVAL_MS)
        }
        val newScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope = newScope
        // Push the pod's alert configuration as soon as any alert preference changes. Without
        // this nothing ever raises CommandUpdateAlertConfiguration, so edits to the expiration
        // or low-reservoir settings sat in preferences and never reached the pod. drop(1) skips
        // the replayed current value, which would otherwise fire a command on every start.
        // Mirrors OmnipodDashPumpPlugin.
        merge(
            preferences.observe(OmnipodBooleanPreferenceKey.ExpirationReminder).drop(1).map {},
            preferences.observe(OmnipodIntPreferenceKey.ExpirationReminderHours).drop(1).map {},
            preferences.observe(OmnipodBooleanPreferenceKey.ExpirationAlarm).drop(1).map {},
            preferences.observe(OmnipodIntPreferenceKey.ExpirationAlarmHours).drop(1).map {},
            preferences.observe(OmnipodBooleanPreferenceKey.LowReservoirAlert).drop(1).map {},
            preferences.observe(OmnipodIntPreferenceKey.LowReservoirAlertUnits).drop(1).map {},
        ).collectResilient(newScope, aapsLogger, LTag.PUMP) { commandQueue.customCommand(CommandUpdateAlertConfiguration()) }
    }

    override suspend fun onStop() {
        super.onStop()
        handler?.removeCallbacks(statusChecker)
        scope?.cancel()
        scope = null
    }

    // -- connection lifecycle -------------------------------------------------------------

    override fun isInitialized(): Boolean = podStateManager.activationProgress == ActivationProgress.COMPLETED
    override fun isSuspended(): Boolean = podStateManager.deliverySuspended

    // isBusy() gates the ENTIRE command queue, including custom commands like
    // CommandPairNewPod (see QueueWorker's isBusy() check) - NOT_STARTED is deliberately
    // excluded so pairing itself is never blocked (that's the step that transitions out of
    // NOT_STARTED). Only an activation already in progress blocks the queue, matching Dash's
    // ActivationProgress-based isBusy(). O5OmnipodWizardViewModel calls bleManager/command
    // classes directly (not via CommandQueue), so this gate never blocks its own steps.
    override fun isBusy(): Boolean =
        podStateManager.activationProgress != ActivationProgress.NOT_STARTED &&
            podStateManager.activationProgress.isBefore(ActivationProgress.COMPLETED)

    override fun isConnected(): Boolean =
        podStateManager.ltk == null ||
            podStateManager.bluetoothConnectionState == O5PodStateManager.BluetoothConnectionState.CONNECTED

    override fun isConnecting(): Boolean = stopConnecting != null

    override fun isHandshakeInProgress(): Boolean =
        stopConnecting != null &&
            podStateManager.bluetoothConnectionState == O5PodStateManager.BluetoothConnectionState.CONNECTED

    override fun finishHandshaking() {}

    override fun connect(reason: String) {
        aapsLogger.info(LTag.PUMP, "O5 connect reason=$reason")
        podStateManager.bluetoothConnectionState = O5PodStateManager.BluetoothConnectionState.CONNECTING
        synchronized(this) {
            stopConnecting?.let {
                aapsLogger.warn(LTag.PUMP, "O5 already connecting: $it")
                return
            }
            stopConnecting = CountDownLatch(1)
        }
        thread(start = true, name = "O5ConnectionThread") {
            try {
                stopConnecting?.let { latch ->
                    bleManager.connect(latch).ignoreElements().blockingAwait()
                }
            } catch (e: Exception) {
                aapsLogger.info(LTag.PUMPCOMM, "O5 connect error=$e")
            } finally {
                synchronized(this) { stopConnecting = null }
            }
        }
    }

    override fun disconnect(reason: String) {
        aapsLogger.info(LTag.PUMP, "O5 disconnect reason=$reason")
        stopConnecting?.countDown()
        bleManager.disconnect(false)
    }

    override fun stopConnecting() {
        aapsLogger.info(LTag.PUMP, "O5 stopConnecting")
        stopConnecting?.countDown()
        bleManager.disconnect(true)
    }

    // -- status polling + uncertain-delivery reconciliation --------------------------------

    override suspend fun getPumpStatus(reason: String) {
        aapsLogger.debug(LTag.PUMP, "O5 getPumpStatus reason=$reason")
        if (podStateManager.ltk == null) {
            // Not paired yet - nothing to read, but AAPS still needs to be told that no
            // insulin is being delivered.
            syncStoppedDelivery()
            return
        }
        try {
            fetchStatus().blockingAwait()
            reconcilePendingDose()
            checkPodFault()
            // Self-healing preference sync: cheap no-op when nothing changed (see
            // updateAlertConfiguration()'s syncedAlertSettings guard), so piggybacking on
            // every status poll picks up a preference change within one poll cycle without
            // needing a dedicated Flow-based watcher.
            updateAlertConfiguration()
        } catch (e: Exception) {
            aapsLogger.error(LTag.PUMP, "Error in O5 getPumpStatus", e)
        }
        // Outside the try on purpose: a pod that is gone is exactly the case where the read
        // above fails, and also the case where this matters most.
        syncStoppedDelivery()
        syncPumpFlows()
    }

    /**
     * Records a zero-rate temporary basal while the pod is not delivering, so AAPS stops
     * counting the basal program as insulin that actually went in.
     *
     * Without this, a faulted, deactivated or not-yet-activated pod leaves AAPS believing the
     * basal program is still running. Insulin on board then includes insulin the pod never
     * delivered, and the loop corrects less than it should. Mirrors what the Dash driver does
     * in `OmnipodDashPumpPlugin.checkPodKaput` and `createFakeTBRWhenNoActivePod`.
     *
     * The zero basal is written for a whole pod lifetime, and re-checked on every status poll:
     * if a zero temporary basal is already running, nothing is written, so repeated polls do
     * not pile up records. Starting a new pod replaces it with that pod's real basal.
     *
     * Internal (rather than private) to allow unit testing within this module.
     */
    internal suspend fun syncStoppedDelivery() {
        val delivering = podStateManager.activationProgress == ActivationProgress.COMPLETED &&
            !podStateManager.isPodKaput
        if (delivering) return
        val runningTempBasal = pumpSync.expectedPumpState().temporaryBasal
        if (runningTempBasal != null && runningTempBasal.rate == 0.0) return
        aapsLogger.info(LTag.PUMP, "O5 pod is not delivering - recording a zero temporary basal")
        pumpSync.syncTemporaryBasalWithPumpId(
            timestamp = System.currentTimeMillis(),
            rate = PumpRate(0.0),
            duration = T.mins(PodConstants.MAX_POD_LIFETIME.toMinutes()).msecs(),
            isAbsolute = true,
            type = PumpSync.TemporaryBasalType.PUMP_SUSPEND,
            // Not read back anywhere - it only has to be unique.
            pumpId = System.currentTimeMillis(),
            pumpType = PumpType.OMNIPOD_5,
            pumpSerial = serialNumber()
        )
    }

    /**
     * Posts a user-facing [NotificationId.OMNIPOD_POD_FAULT] alert (with sound) plus a
     * [PumpSync.insertAnnouncement] entry the first time [O5PodStateManager.alarmType]
     * is seen non-null, mirroring Dash's `OmnipodDashPumpPlugin.checkPodKaput()` handling -
     * without this, a faulted O5 pod only shows CRITICAL status on
     * the Omnipod overview screen with no system notification/sound, so a fault could go
     * unnoticed if the user isn't actively looking at that screen. [O5PodStateManager
     * .alarmSynced] makes this idempotent across repeated status polls of the same fault;
     * the notification is skipped (but the announcement/sync flag are not) if a pod
     * deactivation is already queued, since the user is already acting on the fault.
     *
     * Internal (rather than private) to allow unit testing within this module, without
     * needing to drive [getPumpStatus]'s full status-poll chain.
     */
    internal suspend fun checkPodFault() {
        if (podStateManager.alarmSynced) return
        // [O5PodStateManager.isPodKaput] is the trigger, not alarmType. alarmType only ever
        // arrives with an alarm-status response, which the pod sends solely in reply to an
        // explicit request for that page - so gating on it alone meant a faulted pod was never
        // reported at all. podStatus comes back on every routine status poll.
        if (!podStateManager.isPodKaput && podStateManager.alarmType == null) return

        // Faulted but the reason is not known yet - ask for it. Best effort: the request fails
        // by design (an alarm-status reply is a failed command, see CommandReceiveError), but
        // the response is recorded on the way through, which is what populates alarmType.
        if (podStateManager.alarmType == null) {
            try {
                fetchAlarmStatus().blockingAwait()
            } catch (e: Exception) {
                aapsLogger.debug(LTag.PUMP, "O5 could not read the alarm status page: ${e.message}")
            }
        }

        // Fall back to the pod status itself when the fault code could not be read, so the user
        // is still told the pod has stopped rather than told nothing.
        val description = podStateManager.alarmType?.toString()
            ?: if (podStateManager.isPodActivationTimeExceeded) {
                rh.gs(R.string.omnipod_common_error_pod_fault_activation_time_exceeded)
            } else {
                null
            }
            ?: podStateManager.podStatus?.toString()
            ?: return

        if (!commandQueue.isCustomCommandInQueue(CommandDeactivatePod::class.java)) {
            notificationManager.post(
                NotificationId.OMNIPOD_POD_FAULT,
                description,
                soundRes = app.aaps.core.ui.R.raw.boluserror
            )
        }
        pumpSync.insertAnnouncement(
            error = description,
            pumpId = System.currentTimeMillis(),
            pumpType = PumpType.OMNIPOD_5,
            pumpSerial = serialNumber()
        )
        podStateManager.alarmSynced = true
    }

    /**
     * Reads status page 2, the pod's fault detail, to learn *why* it faulted. Mirrors the Dash
     * driver, which requests this page explicitly - the pod never volunteers it.
     */
    private fun fetchAlarmStatus(): Completable = Completable.defer {
        val cmd = GetStatusCommand.Builder()
            .setUniqueId(requirePodId())
            .setSequenceNumber(podStateManager.msgSequenceNumber.toShort())
            .setStatusResponseType(ResponseType.StatusResponseType.ALARM_STATUS)
            .build()
        bleManager.sendCommand(cmd, AlarmStatusResponse::class).ignoreElements()
    }

    /**
     * Reconnects to the pod if the BLE link has dropped, then completes. A no-op while the
     * session is up - `O5BleManagerImpl.connect` emits `PodEvent.AlreadyConnected` and
     * completes without sending anything.
     *
     * Guards the stop/status sends that run inside the long bolus-delivery wait, during which
     * the pod routinely drops the link. [O5BleManager.sendCommand] does not connect on its own,
     * so without this a cancel or status poll fails on a dropped link and the bolus keeps
     * running - the pod never receives the stop.
     */
    private fun ensureConnected(): Completable = Completable.defer {
        bleManager.connect().ignoreElements()
    }

    private fun fetchStatus(): Completable = ensureConnected().andThen(Completable.defer {
        val cmd = GetStatusCommand.Builder()
            .setUniqueId(requirePodId())
            .setSequenceNumber(podStateManager.msgSequenceNumber.toShort())
            .setStatusResponseType(ResponseType.StatusResponseType.DEFAULT_STATUS_RESPONSE)
            .build()
        bleManager.sendCommand(cmd, DefaultStatusResponse::class).ignoreElements()
    })
        .andThen(Completable.defer { fetchActivationTimeIfNeeded() })
        .andThen(Completable.defer { fetchTriggeredAlertsIfNeeded() })

    /**
     * Status pages 5/1 carry diagnostic data only relevant once something's actually
     * wrong or alerting - fetched conditionally, on top of the default status poll
     * above, rather than on every 15s poll. State is populated as a side effect of
     * [O5BleManager.sendCommand] via [O5BleManagerImpl]'s `recordStatusIfPresent`,
     * same as every other response type, so nothing further is done with the result
     * here - see [O5PodStateManager.podActivatedAt]/[O5PodStateManager.triggeredAlertTimes].
     */
    private fun fetchActivationTimeIfNeeded(): Completable {
        if (podStateManager.alarmType == null || podStateManager.podActivatedAt != null) return Completable.complete()
        val cmd = GetStatusCommand.Builder()
            .setUniqueId(requirePodId())
            .setSequenceNumber(podStateManager.msgSequenceNumber.toShort())
            .setStatusResponseType(ResponseType.StatusResponseType.STATUS_RESPONSE_PAGE_5)
            .build()
        return bleManager.sendCommand(cmd, PodInfoActivationTimeResponse::class).ignoreElements()
    }

    private fun fetchTriggeredAlertsIfNeeded(): Completable {
        if (podStateManager.activeAlerts?.isNotEmpty() != true) return Completable.complete()
        val cmd = GetStatusCommand.Builder()
            .setUniqueId(requirePodId())
            .setSequenceNumber(podStateManager.msgSequenceNumber.toShort())
            .setStatusResponseType(ResponseType.StatusResponseType.STATUS_RESPONSE_PAGE_1)
            .build()
        return bleManager.sendCommand(cmd, PodInfoTriggeredAlertsResponse::class).ignoreElements()
    }

    /**
     * Resolves a [O5PodStateManager.pendingDoseCommand] left over from a dose-affecting
     * call whose BLE response never arrived - see this class's doc comment. Called after
     * every successful status read, so an uncertain outcome is recovered automatically
     * rather than only on the next user-triggered action.
     *
     * Decides in the same order OmnipodKit's `PodCommsSession.recoverUnacknowledgedCommand`
     * does:
     *  1. **Authoritative** - the pod reports the sequence number of the last programming
     *     command it accepted ([O5PodStateManager.sequenceNumberOfLastProgrammingCommand]).
     *     If it equals the number this dose was sent with, the pod definitely got it.
     *  2. **Heuristic fallback** - otherwise, a *positive* sign of the delivery actually
     *     running counts as confirmation ([confirmedByDeliveryStatus]).
     *  3. **Otherwise the pod never got the command**, so the marker is dropped without
     *     recording any insulin.
     *
     * Step 3 is why the sequence number matters. With only the delivery-status heuristic, an
     * uncertain bolus that the pod never received looks identical to one that completed - both
     * report "not currently bolusing" - and the old code resolved that ambiguity by assuming
     * delivery, crediting `requestedUnits - bolusPulsesRemaining` as insulin that was never
     * given. That inflates IOB and suppresses later dosing.
     */
    internal suspend fun reconcilePendingDose() {
        val pending = podStateManager.pendingDoseCommand ?: return

        val podSequence = podStateManager.sequenceNumberOfLastProgrammingCommand
        val sentSequence = pending.sequenceNumber
        // confirmedByStatus is sticky: once the pod's delivery status showed this dose running,
        // a later pass must not re-read the sequence numbers and decide it was never received.
        val acceptedByPod = pending.confirmedByStatus || podSequence != null && sentSequence != null &&
            (podSequence.toInt() and 0x0f) == (sentSequence.toInt() and 0x0f)

        val confirmedByStatus = confirmedByDeliveryStatus(pending)
        if (!acceptedByPod && !confirmedByStatus) {
            // Only conclude "never received" when the sequence number actually told us so. A
            // pre-existing pending command restored from an older state file has no recorded
            // sequence, and dropping it here would silently discard a dose that may well have
            // been delivered - fall through to the original delivery-status resolution instead.
            if (sentSequence != null && podSequence != null) {
                aapsLogger.info(
                    LTag.PUMP,
                    "O5 pending ${pending.type} was not received by the pod " +
                        "(sent sequence $sentSequence, pod's last programming sequence $podSequence) - recording no delivery"
                )
                // A bolus record was already written for this dose, so cancel it out with a
                // zero-amount record. Without the flag we would write that correction even when
                // no record was ever made, inventing a treatment.
                if (pending.type == O5PodStateManager.PendingDoseType.BOLUS && pending.bolusRecordExpected) {
                    pumpSync.syncBolusWithPumpId(
                        timestamp = pending.startedAt,
                        amount = PumpInsulin(0.0),
                        type = pending.bolusType ?: BS.Type.NORMAL,
                        pumpId = pending.startedAt,
                        pumpType = PumpType.OMNIPOD_5,
                        pumpSerial = serialNumber()
                    )
                }
                podStateManager.pendingDoseCommand = null
                return
            }
        }
        // The sequence numbers do not confirm it, but the delivery status does. Record that so
        // the next pass keeps treating it as accepted, and wait for the bolus to finish.
        if (!acceptedByPod && confirmedByStatus && pending.type == O5PodStateManager.PendingDoseType.BOLUS) {
            podStateManager.pendingDoseCommand = pending.copy(confirmedByStatus = true)
            return
        }

        when (pending.type) {
            O5PodStateManager.PendingDoseType.BOLUS              ->
                if (podStateManager.deliveryStatus?.bolusDeliveringActive() != true) {
                    // No pulse count means the status read did not tell us how much is left, so
                    // there is nothing to reconcile against yet - leave the dose pending.
                    val remainingPulses = podStateManager.bolusPulsesRemaining ?: return
                    val deliveredUnits = (pending.requestedUnits ?: 0.0) -
                        remainingPulses.toInt() * PodConstants.POD_PULSE_BOLUS_UNITS
                    finalizeBolus(pending, deliveredUnits)
                }

            O5PodStateManager.PendingDoseType.TEMP_BASAL_START   ->
                if (podStateManager.deliveryStatus?.tempBasalActive() == true) {
                    podStateManager.pendingDoseCommand = null
                }

            O5PodStateManager.PendingDoseType.TEMP_BASAL_CANCEL  ->
                if (podStateManager.deliveryStatus?.tempBasalActive() != true) {
                    podStateManager.activeTempBasalStartTime = null
                    podStateManager.activeTempBasalRate = null
                    podStateManager.activeTempBasalDurationMinutes = null
                    podStateManager.pendingDoseCommand = null
                }

            O5PodStateManager.PendingDoseType.BASAL_PROGRAM      ->
                if (podStateManager.deliveryStatus?.basalActive() == true) {
                    podStateManager.pendingDoseCommand = null
                }
        }
    }

    /**
     * Clears the way for a new dose-affecting command, returning false if it must not be sent.
     *
     * A [O5PodStateManager.pendingDoseCommand] means an earlier dose may or may not have
     * reached the pod. Issuing another one on top of that risks stacking a second dose onto a
     * first that did land, so this first tries to settle the question the same way OmnipodKit's
     * `PodCommsSession.tryToResolvePendingCommand` does - read status, reconcile, and proceed
     * only if the pending marker actually cleared.
     *
     * Note this resolves rather than merely refuses: in the ordinary case where the pod is
     * reachable again, the status read settles the outcome and the new command goes ahead
     * normally. Refusal is reserved for a pod that still cannot be reached or whose status
     * leaves the earlier dose undecided.
     *
     * Deliberately *not* applied to pod deactivation. Blocking a user from taking a pod off
     * because the driver is unsure about a temp basal is the wrong trade, and deactivation
     * never sets a pending marker of its own.
     */
    private suspend fun pendingDoseResolved(): Boolean {
        if (podStateManager.pendingDoseCommand == null) return true
        return try {
            fetchStatus().blockingAwait()
            reconcilePendingDose()
            val resolved = podStateManager.pendingDoseCommand == null
            if (!resolved) {
                aapsLogger.warn(LTag.PUMP, "O5 refusing new dose command: an earlier dose is still unresolved after a status read")
            }
            resolved
        } catch (e: Exception) {
            aapsLogger.warn(LTag.PUMP, "O5 refusing new dose command: could not read status to resolve the pending dose", e)
            false
        }
    }

    private fun unresolvedDoseResult(): PumpEnactResult =
        pumpEnactResultProvider.get().success(false).enacted(false)
            .comment(rh.gs(R.string.omnipod_5_error_unresolved_dose_pending))

    /**
     * Whether the pod's delivery status positively shows [pending] took effect - the fallback
     * for when the sequence number cannot decide it (mirrors OmnipodKit's
     * `checkCommandAgainstStatus`).
     *
     * Deliberately only ever answers true on a *positive* observation. "Not currently
     * bolusing" is not evidence a bolus happened, so [O5PodStateManager.PendingDoseType.BOLUS]
     * has no fallback at all: a bolus whose sequence number did not match is treated as never
     * received. The cancel cases are the mirror image - delivery having stopped is the
     * positive observation there.
     */
    private fun confirmedByDeliveryStatus(pending: O5PodStateManager.PendingDoseCommand): Boolean {
        val status = podStateManager.deliveryStatus ?: return false
        return when (pending.type) {
            O5PodStateManager.PendingDoseType.BOLUS             -> status.bolusDeliveringActive()
            O5PodStateManager.PendingDoseType.TEMP_BASAL_START  -> status.tempBasalActive()
            O5PodStateManager.PendingDoseType.TEMP_BASAL_CANCEL -> !status.tempBasalActive()
            O5PodStateManager.PendingDoseType.BASAL_PROGRAM     -> status.basalActive()
        }
    }

    private fun requirePodId(): Int =
        podStateManager.podId?.toInt() ?: throw IllegalStateException("O5 pod not paired")

    // -- basal profile ----------------------------------------------------------------------

    override suspend fun setNewBasalProfile(profile: PumpProfile): PumpEnactResult {
        if (podStateManager.ltk == null) {
            // nothing paired yet - same "prevent setBasal requests" guard Dash uses
            return pumpEnactResultProvider.get().success(true).enacted(true)
        }
        if (!pendingDoseResolved()) return unresolvedDoseResult()
        val basalProgram = mapProfileToBasalProgram(profile, PumpType.OMNIPOD_5)
        val basalBeeps = preferences.get(OmnipodBooleanPreferenceKey.BasalBeepsEnabled)
        return try {
            if (podStateManager.deliveryStatus?.suspended() != true) {
                // Suspending to write the new basal makes the pod beep at once unless we silence
                // it. Gate that beep on the basal-beep setting, the same way Dash does -
                // otherwise every profile change beeps even with beeps turned off.
                val cmd = SuspendDeliveryCommand.Builder()
                    .setUniqueId(requirePodId())
                    .setSequenceNumber(podStateManager.msgSequenceNumber.toShort())
                    .setNonce(FIXED_NONCE)
                    .setBeepType(if (basalBeeps) BeepType.LONG_SINGLE_BEEP else BeepType.SILENT)
                    .build()
                bleManager.sendCommand(cmd, DefaultStatusResponse::class).ignoreElements().blockingAwait()
                podStateManager.deliverySuspended = true
                // This suspend arms the pod's SUSPEND_ENDED alert (see SuspendDeliveryCommand),
                // so record it - disableSuspendAlerts() below relies on this to silence it again,
                // the same way the manual suspendDelivery() path does. Without this the pod keeps
                // beeping "insulin delivery is suspended" after every profile change.
                podStateManager.suspendAlertsEnabled = true
            }

            podStateManager.pendingDoseCommand = O5PodStateManager.PendingDoseCommand(
                type = O5PodStateManager.PendingDoseType.BASAL_PROGRAM,
                startedAt = System.currentTimeMillis(),
                sequenceNumber = podStateManager.msgSequenceNumber.toShort()
            )
            armStatusChecker()
            val cmd = ProgramBasalCommand.Builder()
                .setUniqueId(requirePodId())
                .setSequenceNumber(podStateManager.msgSequenceNumber.toShort())
                .setNonce(FIXED_NONCE)
                .setBasalProgram(basalProgram)
                .setProgramReminder(ProgramReminder(atStart = basalBeeps, atEnd = false, atInterval = 0))
                .setCurrentTime(Date())
                .build()
            bleManager.sendCommand(cmd, DefaultStatusResponse::class).ignoreElements().blockingAwait()
            podStateManager.basalProgram = basalProgram
            podStateManager.deliverySuspended = false
            podStateManager.pendingDoseCommand = null
            notificationManager.post(NotificationId.PROFILE_SET_OK, app.aaps.core.ui.R.string.profile_set_ok)
            disableSuspendAlerts()
            pumpEnactResultProvider.get().success(true).enacted(true)
        } catch (e: Exception) {
            aapsLogger.error(LTag.PUMP, "Error in O5 setNewBasalProfile", e)
            notifyUncertain(NotificationId.FAILED_UPDATE_PROFILE, rh.gs(R.string.omnipod_5_error_setting_basal_profile_might_have_failed))
            pumpEnactResultProvider.get().success(false).enacted(false)
        }
    }

    override fun isThisProfileSet(profile: PumpProfile): Boolean {
        if (podStateManager.ltk == null) return true
        if (podStateManager.deliverySuspended) return false
        return mapProfileToBasalProgram(profile, PumpType.OMNIPOD_5) == podStateManager.basalProgram
    }

    // -- StateFlow status surface -------------------------------------------------------------

    private val _lastDataTime = MutableStateFlow(0L)
    override val lastDataTime: StateFlow<Long> = _lastDataTime

    private val _lastBolusTime = MutableStateFlow<Long?>(null)
    override val lastBolusTime: StateFlow<Long?> = _lastBolusTime

    private val _lastBolusAmount = MutableStateFlow<PumpInsulin?>(null)
    override val lastBolusAmount: StateFlow<PumpInsulin?> = _lastBolusAmount

    override val baseBasalRate: PumpRate
        get() {
            val rate = if (podStateManager.alarmType != null) 0.0
            else podStateManager.basalProgram?.rateAt(System.currentTimeMillis()) ?: 0.0
            return PumpRate(rate)
        }

    private val _reservoirLevel = MutableStateFlow(PumpInsulin(0.0))
    override val reservoirLevel: StateFlow<PumpInsulin> = _reservoirLevel

    // Omnipod 5 doesn't report its battery level (same finding as Dash)
    override val batteryLevel: StateFlow<Int?> = MutableStateFlow(null)

    private fun syncPumpFlows() {
        _lastDataTime.value = podStateManager.lastStatusResponseReceived ?: 0L
        // lastUserBolus*, not lastBolus*: these two flows feed the Nightscout device status
        // ("LastBolus"/"LastBolusAmount"), and a drift correction is not a bolus the user gave.
        _lastBolusTime.value = podStateManager.lastUserBolusStartTime
        _lastBolusAmount.value = podStateManager.lastUserBolusRequestedUnits?.let { PumpInsulin(it) }
        _reservoirLevel.value = PumpInsulin(
            podStateManager.reservoirPulsesRemaining?.let { it * PodConstants.POD_PULSE_BOLUS_UNITS }
                ?: RESERVOIR_OVER_50_UNITS_DEFAULT
        )
    }

    // -- bolus ----------------------------------------------------------------------------

    override suspend fun deliverTreatment(detailedBolusInfo: DetailedBolusInfo): PumpEnactResult {
        require(detailedBolusInfo.carbs == 0.0) { detailedBolusInfo.toString() }
        require(detailedBolusInfo.insulin > 0) { detailedBolusInfo.toString() }

        if (!pendingDoseResolved()) {
            return pumpEnactResultProvider.get().success(false).enacted(false).bolusDelivered(0.0)
                .comment(rh.gs(R.string.omnipod_5_error_unresolved_dose_pending))
        }
        try {
            bolusDeliveryInProgress = true
            // Refresh the reservoir from current pod state before the gate below, same staleness
            // guard Dash uses: right after activation _reservoirLevel is otherwise still its 0.0
            // init until the next status poll.
            syncPumpFlows()
            val requestedUnits = detailedBolusInfo.insulin
            if (requestedUnits > reservoirLevel.value.cU) {
                return pumpEnactResultProvider.get().success(false).enacted(false).bolusDelivered(0.0)
                    .comment(rh.gs(R.string.omnipod_5_error_not_enough_insulin))
            }
            if (podStateManager.deliveryStatus?.bolusDeliveringActive() == true) {
                return pumpEnactResultProvider.get().success(false).enacted(false).bolusDelivered(0.0)
                    .comment(rh.gs(R.string.omnipod_5_error_bolus_already_in_progress))
            }

            val bolusBeepsKey = if (detailedBolusInfo.bolusType == BS.Type.SMB) OmnipodBooleanPreferenceKey.SmbBeepsEnabled
            else OmnipodBooleanPreferenceKey.BolusBeepsEnabled
            val bolusBeeps = preferences.get(bolusBeepsKey)
            val startedAt = System.currentTimeMillis()

            val pendingDose = O5PodStateManager.PendingDoseCommand(
                type = O5PodStateManager.PendingDoseType.BOLUS,
                requestedUnits = requestedUnits,
                bolusType = detailedBolusInfo.bolusType,
                startedAt = startedAt,
                sequenceNumber = podStateManager.msgSequenceNumber.toShort()
            )
            podStateManager.pendingDoseCommand = pendingDose
            podStateManager.lastBolusIsBasalCorrection = false

            val cmd = ProgramBolusCommand.Builder()
                .setUniqueId(requirePodId())
                .setSequenceNumber(podStateManager.msgSequenceNumber.toShort())
                .setNonce(FIXED_NONCE)
                .setNumberOfUnits(requestedUnits)
                .setDelayBetweenPulsesInEighthSeconds(BOLUS_DELAY_BETWEEN_PULSES_EIGHTH_SECONDS)
                .setProgramReminder(ProgramReminder(atStart = bolusBeeps, atEnd = bolusBeeps, atInterval = 0))
                // AAPS doesn't distinguish meal vs. correction insulin by the time a bolus
                // reaches the pump driver (deliverTreatment requires carbs == 0 above), so the
                // whole requested amount is reported as "correction" - matches this codebase's
                // own accurate characterization better than "meal" would, since every bolus
                // here comes from AAPS's own dosing algorithm, not a user-entered meal amount.
                .setO5BolusInfo(mealUnits = 0.0, correctionUnits = requestedUnits)
                .build()

            // Tracks whether the pod may already have the command. If it does, the pending
            // marker must survive an error so reconcilePendingDose() can settle it; if it does
            // not, clearing the marker avoids reporting a dose that was never sent.
            var commandMayHaveBeenSent = false
            val ret = try {
                bleManager.sendCommand(cmd, DefaultStatusResponse::class)
                    .filter { it.isCommandSent() }
                    .doOnNext {
                        commandMayHaveBeenSent = true
                        markBolusRecordExpected(startedAt)
                    }
                    .concatMapCompletable {
                        rxCompletable(Dispatchers.IO) {
                            pumpSync.syncBolusWithPumpId(
                                timestamp = startedAt,
                                amount = PumpInsulin(requestedUnits),
                                type = detailedBolusInfo.bolusType,
                                pumpId = startedAt,
                                pumpType = PumpType.OMNIPOD_5,
                                pumpSerial = serialNumber()
                            )
                        }
                    }
                    .blockingAwait()
                // Armed only once the command is away - before that there is nothing pending
                // for the tick to reconcile.
                armStatusChecker()
                val deliveredUnits = waitForBolusDeliveryToComplete(requestedUnits).blockingGet().deliveredUnits
                if (deliveredUnits != null) {
                    finalizeBolus(pendingDose, deliveredUnits)
                    pumpEnactResultProvider.get().success(true).enacted(true).bolusDelivered(deliveredUnits)
                } else {
                    // The pod never reported how much it gave. Report the requested amount and
                    // leave the dose pending so a later status read can correct it.
                    pumpEnactResultProvider.get().success(true).enacted(true).bolusDelivered(requestedUnits)
                        .comment(rh.gs(R.string.omnipod_5_error_bolus_delivery_status_uncertain))
                }
            } catch (throwable: Throwable) {
                aapsLogger.error(LTag.PUMP, "O5 deliverTreatment error: $throwable")
                if (!commandMayHaveBeenSent) {
                    podStateManager.pendingDoseCommand = null
                } else {
                    armStatusChecker()
                }
                pumpEnactResultProvider.get().success(bolusCanceled).enacted(false)
            }

            if (detailedBolusInfo.bolusType == BS.Type.SMB && podStateManager.pendingDoseCommand != null) {
                notifyUncertain(NotificationId.OMNIPOD_UNCERTAIN_SMB, rh.gs(R.string.omnipod_5_error_uncertain_smb, requestedUnits))
            } else if (podStateManager.pendingDoseCommand != null) {
                notifyUncertain(NotificationId.OMNIPOD_POD_FAULT, rh.gs(R.string.omnipod_5_error_bolus_delivery_status_uncertain))
            }
            return ret
        } finally {
            bolusCanceled = false
            bolusDeliveryInProgress = false
        }
    }

    private data class BolusCompletion(val deliveredUnits: Double?)

    private fun waitForBolusDeliveryToComplete(requestedUnits: Double): Single<BolusCompletion> = Single.defer {
        val estimatedSeconds = ceil(requestedUnits / PodConstants.POD_PULSE_BOLUS_UNITS).toLong() * 2 + 3
        var waited = 0L
        while (waited < estimatedSeconds && !bolusCanceled) {
            waited += 1
            Thread.sleep(1000)
            val percent = (waited.toFloat() / estimatedSeconds) * 100
            bolusProgressData.updateProgress(percent.toInt())
        }

        repeat(BOLUS_RETRIES) {
            val cmd = if (bolusCanceled) cancelBolus() else fetchStatus()
            try {
                cmd.blockingAwait()
            } catch (e: Exception) {
                aapsLogger.debug(LTag.PUMP, "waitForBolusDeliveryToComplete errorGettingStatus=$e")
                Thread.sleep(BOLUS_RETRY_INTERVAL_MS)
                return@repeat
            }
            val bolusActive = podStateManager.deliveryStatus?.bolusDeliveringActive() == true
            if (bolusActive) {
                val remainingPulses = podStateManager.bolusPulsesRemaining
                if (remainingPulses == null) {
                    Thread.sleep(BOLUS_RETRY_INTERVAL_MS)
                    return@repeat
                }
                val remainingUnits = remainingPulses.toInt() * PodConstants.POD_PULSE_BOLUS_UNITS
                val delivered = requestedUnits - remainingUnits
                val percent = (delivered / requestedUnits) * 100
                bolusProgressData.updateProgress(percent.toInt())
                val sleepSeconds = if (bolusCanceled) BOLUS_RETRY_INTERVAL_MS / 1000
                else ceil(remainingUnits / PodConstants.POD_PULSE_BOLUS_UNITS).toLong() * 2 + 3
                Thread.sleep(sleepSeconds * 1000)
            } else {
                // Only report a delivered amount when the pod actually told us how many pulses
                // are left. Without that number we cannot say what was given, so report nothing
                // and let reconcilePendingDose() settle it on the next status read - the caller
                // no longer has to guess from a bare Double.
                val remainingPulses = podStateManager.bolusPulsesRemaining
                if (remainingPulses != null) {
                    val deliveredUnits = requestedUnits - remainingPulses.toInt() * PodConstants.POD_PULSE_BOLUS_UNITS
                    return@defer Single.just(BolusCompletion(deliveredUnits))
                }
            }
        }
        Single.just(BolusCompletion(null)) // still uncertain - left for reconcilePendingDose()
    }

    /**
     * Records that a bolus treatment has been written to pumpSync for the dose started at
     * [startedAt]. If reconciliation later finds the pod never received the command, that record
     * has to be cancelled out with a zero-amount one - and this flag is how we know a record
     * exists to cancel.
     */
    private fun markBolusRecordExpected(startedAt: Long) {
        val pending = podStateManager.pendingDoseCommand ?: return
        if (pending.type == O5PodStateManager.PendingDoseType.BOLUS && pending.startedAt == startedAt) {
            podStateManager.pendingDoseCommand = pending.copy(bolusRecordExpected = true)
        }
    }

    /**
     * Writes the final delivered amount for [pending] and clears it.
     *
     * One place does this for both paths - the normal end of [deliverTreatment] and the recovery
     * in [reconcilePendingDose] - so a dose cannot be counted twice or with two different amounts.
     * The amount is clamped to what was requested, since a pulse count read back mid-delivery can
     * otherwise produce a figure above it.
     */
    private suspend fun finalizeBolus(pending: O5PodStateManager.PendingDoseCommand, deliveredUnits: Double) {
        val finalUnits = deliveredUnits.coerceIn(0.0, pending.requestedUnits ?: deliveredUnits)
        pumpSync.syncBolusWithPumpId(
            timestamp = pending.startedAt,
            amount = PumpInsulin(finalUnits),
            type = pending.bolusType ?: BS.Type.NORMAL,
            pumpId = pending.startedAt,
            pumpType = PumpType.OMNIPOD_5,
            pumpSerial = serialNumber()
        )
        // Basal-correction boluses count toward delivered *basal* insulin, not bolus insulin -
        // see O5PodStateManager.cumulativeBolusPulsesDelivered's doc comment.
        val deliveredPulses = if (pending.isBasalCorrection) {
            null
        } else {
            Math.round(finalUnits / PodConstants.POD_PULSE_BOLUS_UNITS).toShort()
        }
        podStateManager.completeBolus(
            startedAt = pending.startedAt,
            requestedUnits = pending.requestedUnits ?: finalUnits,
            deliveredUnits = finalUnits,
            deliveredPulses = deliveredPulses
        )
    }

    private fun cancelBolus(): Completable = ensureConnected().andThen(Completable.defer {
        val cmd = StopDeliveryCommand.Builder()
            .setUniqueId(requirePodId())
            .setSequenceNumber(podStateManager.msgSequenceNumber.toShort())
            .setNonce(FIXED_NONCE)
            .setDeliveryType(StopDeliveryCommand.DeliveryType.BOLUS)
            .build()
        bleManager.sendCommand(cmd, DefaultStatusResponse::class).ignoreElements()
    })

    override fun stopBolusDelivering() {
        aapsLogger.info(LTag.PUMP, "O5 stopBolusDelivering called")
        if (bolusDeliveryInProgress) {
            bolusCanceled = true
        }
    }

    // -- temp basal -------------------------------------------------------------------------

    override suspend fun setTempBasalAbsolute(
        absoluteRate: Double,
        durationInMinutes: Int,
        enforceNew: Boolean,
        tbrType: PumpSync.TemporaryBasalType
    ): PumpEnactResult {
        aapsLogger.info(LTag.PUMP, "O5 setTempBasalAbsolute: rate=$absoluteRate U/h duration=$durationInMinutes min enforce=$enforceNew type=$tbrType")
        if (!pendingDoseResolved()) return unresolvedDoseResult()
        return try {
            if (podStateManager.deliveryStatus?.tempBasalActive() == true) {
                cancelActiveTempBasal()
            }
            val tempBasalBeeps = preferences.get(OmnipodBooleanPreferenceKey.TbrBeepsEnabled)
            val startedAt = System.currentTimeMillis()
            podStateManager.pendingDoseCommand = O5PodStateManager.PendingDoseCommand(
                type = O5PodStateManager.PendingDoseType.TEMP_BASAL_START,
                requestedRate = absoluteRate,
                requestedDurationMinutes = durationInMinutes.toShort(),
                startedAt = startedAt,
                sequenceNumber = podStateManager.msgSequenceNumber.toShort()
            )
            armStatusChecker()
            val cmd = ProgramTempBasalCommand.Builder()
                .setUniqueId(requirePodId())
                .setSequenceNumber(podStateManager.msgSequenceNumber.toShort())
                .setNonce(FIXED_NONCE)
                .setProgramReminder(ProgramReminder(atStart = tempBasalBeeps, atEnd = tempBasalBeeps, atInterval = 0))
                .setRateInUnitsPerHour(absoluteRate)
                .setDurationInMinutes(durationInMinutes.toShort())
                .build()
            bleManager.sendCommand(cmd, DefaultStatusResponse::class).ignoreElements().blockingAwait()

            pumpSync.syncTemporaryBasalWithPumpId(
                timestamp = startedAt,
                rate = PumpRate(absoluteRate),
                duration = T.mins(durationInMinutes.toLong()).msecs(),
                isAbsolute = true,
                type = tbrType,
                pumpId = startedAt,
                pumpType = PumpType.OMNIPOD_5,
                pumpSerial = serialNumber()
            )
            podStateManager.activeTempBasalStartTime = startedAt
            podStateManager.activeTempBasalRate = absoluteRate
            podStateManager.activeTempBasalDurationMinutes = durationInMinutes.toShort()
            podStateManager.pendingDoseCommand = null
            if (needsBasalCorrection()) deliverBasalCorrection()
            pumpEnactResultProvider.get().success(true).enacted(true).isPercent(false).absolute(absoluteRate).duration(durationInMinutes)
        } catch (e: Exception) {
            aapsLogger.error(LTag.PUMP, "Error in O5 setTempBasalAbsolute", e)
            notifyUncertain(NotificationId.OMNIPOD_TBR_ALERTS, rh.gs(R.string.omnipod_5_error_setting_temp_basal_might_have_failed))
            pumpEnactResultProvider.get().success(false).enacted(false)
        }
    }

    override suspend fun setTempBasalPercent(percent: Int, durationInMinutes: Int, enforceNew: Boolean, tbrType: PumpSync.TemporaryBasalType): PumpEnactResult =
        error("Pump doesn't support percent basal rate")

    override suspend fun cancelTempBasal(enforceNew: Boolean): PumpEnactResult {
        if (podStateManager.deliveryStatus?.tempBasalActive() != true && pumpSync.expectedPumpState().temporaryBasal == null) {
            return pumpEnactResultProvider.get().success(true).enacted(false)
        }
        if (!pendingDoseResolved()) return unresolvedDoseResult()
        return try {
            cancelActiveTempBasal()
            pumpEnactResultProvider.get().success(true).enacted(true)
        } catch (e: Exception) {
            aapsLogger.error(LTag.PUMP, "Error in O5 cancelTempBasal", e)
            notifyUncertain(NotificationId.OMNIPOD_TBR_ALERTS, rh.gs(R.string.omnipod_5_error_cancel_temp_basal_result_is_uncertain))
            pumpEnactResultProvider.get().success(false).enacted(false)
        }
    }

    private fun cancelActiveTempBasal() {
        podStateManager.pendingDoseCommand = O5PodStateManager.PendingDoseCommand(
            type = O5PodStateManager.PendingDoseType.TEMP_BASAL_CANCEL,
            startedAt = System.currentTimeMillis(),
            sequenceNumber = podStateManager.msgSequenceNumber.toShort()
        )
        armStatusChecker()
        // StopDeliveryCommand defaults its beep to LONG_SINGLE_BEEP, and the loop stops the temp
        // basal before setting a new one on most cycles - so the pod beeped once every cycle,
        // regardless of the confirmation-beep settings (those gate the ProgramReminder on the new
        // delivery, not the beep on this stop). Honour the TBR-beep preference here, the way Dash
        // gates its own stop/suspend beep: silent unless the user asked for a beep.
        val tbrBeep = if (preferences.get(OmnipodBooleanPreferenceKey.TbrBeepsEnabled)) BeepType.LONG_SINGLE_BEEP else BeepType.SILENT
        val cmd = StopDeliveryCommand.Builder()
            .setUniqueId(requirePodId())
            .setSequenceNumber(podStateManager.msgSequenceNumber.toShort())
            .setNonce(FIXED_NONCE)
            .setDeliveryType(StopDeliveryCommand.DeliveryType.TEMP_BASAL)
            .setBeepType(tbrBeep)
            .build()
        bleManager.sendCommand(cmd, DefaultStatusResponse::class).ignoreElements().blockingAwait()
        podStateManager.activeTempBasalStartTime = null
        podStateManager.activeTempBasalRate = null
        podStateManager.activeTempBasalDurationMinutes = null
        podStateManager.pendingDoseCommand = null
        if (needsBasalCorrection()) deliverBasalCorrection()
    }

    override suspend fun setExtendedBolus(insulin: Double, durationInMinutes: Int): PumpEnactResult =
        pumpEnactResultProvider.get().success(false).enacted(false)
            .comment(rh.gs(R.string.omnipod_5_error_extended_bolus_not_supported))

    override suspend fun cancelExtendedBolus(): PumpEnactResult =
        pumpEnactResultProvider.get().success(false).enacted(false)
            .comment(rh.gs(R.string.omnipod_5_error_extended_bolus_not_supported))

    // -- misc ---------------------------------------------------------------------------------

    override fun updateExtendedJsonStatus(extendedStatus: JSONObject) {}

    override val pumpDescription: PumpDescription = Companion.pumpDescription
    override fun manufacturer(): ManufacturerType = ManufacturerType.Insulet
    override fun model(): PumpType = pumpDescription.pumpType
    override fun serialNumber(): String = podStateManager.podId?.toString() ?: "O5-unpaired"
    override val isFakingTempsByExtendedBoluses: Boolean = false

    override suspend fun loadTDDs(): PumpEnactResult =
        pumpEnactResultProvider.get().success(false).enacted(false)
            .comment(rh.gs(R.string.omnipod_5_error_tdd_not_supported))

    override fun canHandleDST(): Boolean = false

    override fun executeCustomCommand(customCommand: CustomCommand): PumpEnactResult =
        when (customCommand) {
            is CommandPairNewPod      -> pairNewPod()
            is CommandDeactivatePod   -> deactivatePod()
            is CommandSilenceAlerts   -> silenceAlerts()
            is CommandResumeDelivery  -> runBlocking { resumeOrHandleTimeChange() }
            is CommandSuspendDelivery -> suspendDelivery()
            is CommandPlayTestBeep    -> playTestBeep()
            is CommandHandleTimeChange -> runBlocking { resumeOrHandleTimeChange() }
            is CommandUpdateAlertConfiguration -> updateAlertConfiguration()
            is CommandDisableSuspendAlerts     -> disableSuspendAlerts()
            is CommandDeliverBasalCorrection   -> deliverBasalCorrection()
            else                      -> {
                aapsLogger.warn(LTag.PUMP, "Unsupported custom command: " + customCommand.javaClass.name)
                pumpEnactResultProvider.get().success(false).enacted(false).comment(
                    rh.gs(R.string.omnipod_common_error_unsupported_custom_command, customCommand.javaClass.name)
                )
            }
        }

    private fun pairNewPod(): PumpEnactResult =
        try {
            bleManager.pairNewPod().ignoreElements().blockingAwait()
            pumpEnactResultProvider.get().success(true).enacted(true)
        } catch (e: Exception) {
            aapsLogger.error(LTag.PUMP, "Error pairing new O5 pod", e)
            pumpEnactResultProvider.get().success(false).enacted(false)
        }

    private fun deactivatePod(): PumpEnactResult =
        try {
            val cmd = DeactivateCommand.Builder()
                .setUniqueId(requirePodId())
                .setSequenceNumber(podStateManager.msgSequenceNumber.toShort())
                .setNonce(FIXED_NONCE)
                .build()
            bleManager.sendCommand(cmd, DefaultStatusResponse::class).ignoreElements().blockingAwait()
            bleManager.removeBond()
            podStateManager.reset()
            notificationManager.dismiss(NotificationId.OMNIPOD_POD_FAULT)
            pumpEnactResultProvider.get().success(true).enacted(true)
        } catch (e: Exception) {
            aapsLogger.error(LTag.PUMP, "Error deactivating O5 pod", e)
            pumpEnactResultProvider.get().success(false).enacted(false)
        }

    private fun silenceAlerts(): PumpEnactResult =
        podStateManager.activeAlerts?.let { alerts ->
            try {
                val cmd = SilenceAlertsCommand.Builder()
                    .setUniqueId(requirePodId())
                    .setSequenceNumber(podStateManager.msgSequenceNumber.toShort())
                    .setNonce(FIXED_NONCE)
                    .setAlertTypes(alerts)
                    .build()
                bleManager.sendCommand(cmd, DefaultStatusResponse::class).ignoreElements().blockingAwait()
                pumpEnactResultProvider.get().success(true).enacted(true)
            } catch (e: Exception) {
                aapsLogger.error(LTag.PUMP, "Error silencing O5 alerts", e)
                pumpEnactResultProvider.get().success(false).enacted(false)
            }
        } ?: pumpEnactResultProvider.get().success(false).enacted(false).comment(rh.gs(R.string.omnipod_5_error_no_active_alerts))

    private fun suspendDelivery(): PumpEnactResult =
        try {
            val cmd = SuspendDeliveryCommand.Builder()
                .setUniqueId(requirePodId())
                .setSequenceNumber(podStateManager.msgSequenceNumber.toShort())
                .setNonce(FIXED_NONCE)
                .build()
            bleManager.sendCommand(cmd, DefaultStatusResponse::class).ignoreElements().blockingAwait()
            podStateManager.deliverySuspended = true
            // Suspending delivery arms the pod's own SUSPEND_ENDED alert (fires once delivery
            // resumes) - track that so disableSuspendAlerts() knows there's something to silence.
            podStateManager.suspendAlertsEnabled = true
            pumpEnactResultProvider.get().success(true).enacted(true)
        } catch (e: Exception) {
            aapsLogger.error(LTag.PUMP, "Error suspending O5 delivery", e)
            pumpEnactResultProvider.get().success(false).enacted(false)
        }

    private fun playTestBeep(): PumpEnactResult =
        try {
            val silentReminder = ProgramReminder(atStart = false, atEnd = false, atInterval = 0)
            val cmd = ProgramBeepsCommand.Builder()
                .setUniqueId(requirePodId())
                .setSequenceNumber(podStateManager.msgSequenceNumber.toShort())
                .setImmediateBeepType(BeepType.LONG_SINGLE_BEEP)
                .setBasalReminder(silentReminder)
                .setTempBasalReminder(silentReminder)
                .setBolusReminder(silentReminder)
                .build()
            bleManager.sendCommand(cmd, DefaultStatusResponse::class).ignoreElements().blockingAwait()
            pumpEnactResultProvider.get().success(true).enacted(true)
        } catch (e: Exception) {
            aapsLogger.error(LTag.PUMP, "Error playing O5 test beep", e)
            pumpEnactResultProvider.get().success(false).enacted(false)
        }

    /** Resume Delivery and Handle Time Change are both just "(re)program the current
     *  basal profile" - [ProgramBasalCommand] always sets the pod's current time and
     *  implicitly resumes delivery, same as Dash's identical handling of both. */
    private suspend fun resumeOrHandleTimeChange(): PumpEnactResult =
        pumpSync.expectedPumpState().profile?.let { setNewBasalProfile(it) }
            ?: pumpEnactResultProvider.get().success(false).enacted(false).comment(rh.gs(R.string.omnipod_5_error_no_active_profile))

    private fun notifyUncertain(id: NotificationId, message: String) {
        if (podStateManager.pendingDoseCommand != null) {
            notificationManager.post(id, message, soundRes = app.aaps.core.ui.R.raw.boluserror)
        }
    }

    // -- alert config sync -----------------------------------------------------------------

    /** Re-pushes expiration/low-reservoir alert config to the pod when preferences have
     *  changed since the last successful push - safe to call often (see [getPumpStatus]'s
     *  call site), since it's a no-op whenever nothing has changed. Mirrors Dash's
     *  updateAlertConfiguration(), minus its isPodRunning/expiry-negative guards (O5's
     *  activationProgress check below covers the same intent). */
    private fun updateAlertConfiguration(): PumpEnactResult {
        val expirationReminderEnabled = preferences.get(OmnipodBooleanPreferenceKey.ExpirationReminder)
        val expirationReminderHours = preferences.get(OmnipodIntPreferenceKey.ExpirationReminderHours)
        val expirationAlarmEnabled = preferences.get(OmnipodBooleanPreferenceKey.ExpirationAlarm)
        val expirationAlarmHours = preferences.get(OmnipodIntPreferenceKey.ExpirationAlarmHours)
        val lowReservoirAlertEnabled = preferences.get(OmnipodBooleanPreferenceKey.LowReservoirAlert)
        val lowReservoirAlertUnits = preferences.get(OmnipodIntPreferenceKey.LowReservoirAlertUnits)
        val current = O5PodStateManager.SyncedAlertSettings(
            expirationReminderEnabled, expirationReminderHours,
            expirationAlarmEnabled, expirationAlarmHours,
            lowReservoirAlertEnabled, lowReservoirAlertUnits
        )

        if (podStateManager.syncedAlertSettings == current) {
            return pumpEnactResultProvider.get().success(true).enacted(false)
        }
        if (podStateManager.activationProgress != ActivationProgress.COMPLETED) {
            // Nothing paired/running to push to yet - the wizard programs the initial
            // config itself during activation (see buildO5ExpirationAlerts's call site).
            return pumpEnactResultProvider.get().success(true).enacted(false)
        }

        return try {
            val alerts = buildO5ExpirationAlerts(podStateManager, preferences, aapsLogger) + AlertConfiguration(
                AlertType.LOW_RESERVOIR,
                enabled = lowReservoirAlertEnabled,
                durationInMinutes = 0,
                autoOff = false,
                AlertTrigger.ReservoirVolumeTrigger((lowReservoirAlertUnits * 10).toShort()),
                BeepType.FOUR_TIMES_BIP_BEEP,
                BeepRepetitionType.XXX
            )
            val cmd = ProgramAlertsCommand.Builder()
                .setUniqueId(requirePodId())
                .setSequenceNumber(podStateManager.msgSequenceNumber.toShort())
                .setNonce(FIXED_NONCE)
                .setAlertConfigurations(alerts)
                .build()
            bleManager.sendCommand(cmd, DefaultStatusResponse::class).ignoreElements().blockingAwait()
            podStateManager.syncedAlertSettings = current
            pumpEnactResultProvider.get().success(true).enacted(true)
        } catch (e: Exception) {
            aapsLogger.error(LTag.PUMP, "Error updating O5 alert configuration", e)
            pumpEnactResultProvider.get().success(false).enacted(false)
        }
    }

    /** Silences the pod's SUSPEND_ENDED alert once delivery has resumed - see
     *  [podStateManager]'s suspendAlertsEnabled doc comment.
     *
     *  Always sends the disable command (no early return on the tracking flag), the same
     *  as Dash's disableSuspendAlerts(). The flag is only used to decide whether to clear
     *  it afterwards. Gating the send on the flag left the SUSPEND_ENDED alert armed after
     *  a basal profile change, so the pod kept beeping "insulin delivery is suspended". */
    private fun disableSuspendAlerts(): PumpEnactResult {
        return try {
            val cmd = ProgramAlertsCommand.Builder()
                .setUniqueId(requirePodId())
                .setSequenceNumber(podStateManager.msgSequenceNumber.toShort())
                .setNonce(FIXED_NONCE)
                .setAlertConfigurations(
                    listOf(
                        AlertConfiguration(
                            AlertType.SUSPEND_ENDED, enabled = false, durationInMinutes = 0, autoOff = false,
                            AlertTrigger.TimerTrigger(0), BeepType.FOUR_TIMES_BIP_BEEP, BeepRepetitionType.EVERY_MINUTE_AND_EVERY_15_MIN
                        )
                    )
                )
                .build()
            bleManager.sendCommand(cmd, DefaultStatusResponse::class).ignoreElements().blockingAwait()
            podStateManager.suspendAlertsEnabled = false
            pumpEnactResultProvider.get().success(true).enacted(true)
        } catch (e: Exception) {
            aapsLogger.error(LTag.PUMP, "Error disabling O5 suspend alerts", e)
            pumpEnactResultProvider.get().success(false).enacted(false)
        }
    }

    // -- basal drift correction --------------------------------------------------------------

    /** Mirrors OmnipodDashPodStateManagerImpl.needsBasalCorrection() exactly (thresholds,
     *  cooldown, drift-reset/zero-TBR safety checks), adapted to O5's flat temp-basal
     *  fields in place of Dash's TempBasal object. Opt-in via the same
     *  [ExternalOptions.ENABLE_OMNIPOD_DRIFT_COMPENSATION] semaphore file Dash uses. */
    private fun needsBasalCorrection(): Boolean {
        if (!config.isEnabled(ExternalOptions.ENABLE_OMNIPOD_DRIFT_COMPENSATION)) return false

        val correctionThreshold = -PodConstants.POD_PULSE_BOLUS_UNITS / 2 // -0.025U

        if (podStateManager.activationProgress != ActivationProgress.COMPLETED) return false
        if (podStateManager.deliverySuspended || podStateManager.alarmType != null) return false

        podStateManager.lastBasalCorrectionTime?.let {
            if (System.currentTimeMillis() - it < 2 * 60 * 1000L) return false
        }

        val drift = podStateManager.basalDrift

        // Reset if drift exceeds boundaries (over-delivery or severe under-delivery). Thresholds
        // are intentionally tight: a reset is preferred over risking over-correction.
        if (drift >= PodConstants.POD_PULSE_BOLUS_UNITS * 2 || drift <= -PodConstants.POD_PULSE_BOLUS_UNITS * 2) {
            aapsLogger.warn(LTag.PUMP, "Resetting O5 basal drift: drift=${"%.3f".format(drift)}U")
            podStateManager.basalExpected = podStateManager.basalDelivered
            return false
        }

        if (drift > correctionThreshold) return false

        // Safety check: don't correct when TBR = 0 (algorithm explicitly requested zero insulin),
        // except a zero temp due to recent bolus delivery, where corrections are still allowed.
        // lastUserBolusStartTime, not lastBolusStartTime: a correction is itself recorded as the last
        // bolus, so without the filter every correction would refresh the 5-minute window that permits
        // the next one, leaving the 2-minute cooldown as the only limiter - 0.05U every 2 min against a
        // zero TBR, i.e. exactly when the algorithm asked for nothing.
        if (podStateManager.activeTempBasalRate == 0.0) {
            val timeSinceLastBolus = podStateManager.lastUserBolusStartTime?.let { System.currentTimeMillis() - it }
            if (timeSinceLastBolus == null || timeSinceLastBolus >= 5 * 60 * 1000L) return false
        }

        return true
    }

    /** Delivers a single POD_PULSE_BOLUS_UNITS (0.05U) correction bolus to true up basal
     *  drift - see [needsBasalCorrection]. Reuses [waitForBolusDeliveryToComplete] for the
     *  same progress-polling/confirmation logic a regular bolus uses. */
    private fun deliverBasalCorrection(): PumpEnactResult {
        if (!needsBasalCorrection()) {
            aapsLogger.info(LTag.PUMP, "O5 basal correction no longer appropriate")
            return pumpEnactResultProvider.get().success(true).enacted(false)
        }
        // Same stacking guard the dose entry points get, but plain refusal rather than
        // [pendingDoseResolved]'s resolve-first: this is not a suspend function, and making it
        // one would ripple out through the non-suspend executeCustomCommand path for the sake
        // of a single 0.05 U pulse. Skipping one correction is harmless - the next status poll
        // reconciles the pending dose and the correction is reconsidered from scratch.
        if (podStateManager.pendingDoseCommand != null) {
            aapsLogger.info(LTag.PUMP, "O5 basal correction skipped: an earlier dose is still unresolved")
            return pumpEnactResultProvider.get().success(false).enacted(false)
        }
        podStateManager.lastBasalCorrectionTime = System.currentTimeMillis()

        val requestedInsulinAmount = PodConstants.POD_PULSE_BOLUS_UNITS
        syncPumpFlows()
        if (requestedInsulinAmount > reservoirLevel.value.cU) {
            aapsLogger.info(LTag.PUMP, "O5 basal correction skipped: not enough insulin in reservoir")
            return pumpEnactResultProvider.get().success(false).enacted(false)
        }
        if (podStateManager.deliveryStatus?.bolusDeliveringActive() == true) {
            aapsLogger.info(LTag.PUMP, "O5 basal correction skipped: bolus already in progress")
            return pumpEnactResultProvider.get().success(false).enacted(false)
        }

        var commandMayHaveBeenSent = false
        return try {
            bolusDeliveryInProgress = true
            podStateManager.basalCorrectionInProgress = true
            aapsLogger.info(LTag.PUMP, "Delivering O5 basal correction")

            val startedAt = System.currentTimeMillis()
            val pendingDose = O5PodStateManager.PendingDoseCommand(
                type = O5PodStateManager.PendingDoseType.BOLUS,
                requestedUnits = requestedInsulinAmount,
                bolusType = BS.Type.NORMAL,
                startedAt = startedAt,
                isBasalCorrection = true,
                sequenceNumber = podStateManager.msgSequenceNumber.toShort()
            )
            podStateManager.pendingDoseCommand = pendingDose
            // Tag it, so this correction cannot satisfy the zero-TBR exemption in
            // needsBasalCorrection and re-arm the 5-minute window for the next one.
            podStateManager.lastBolusIsBasalCorrection = true

            val cmd = ProgramBolusCommand.Builder()
                .setUniqueId(requirePodId())
                .setSequenceNumber(podStateManager.msgSequenceNumber.toShort())
                .setNonce(FIXED_NONCE)
                .setNumberOfUnits(requestedInsulinAmount)
                .setDelayBetweenPulsesInEighthSeconds(BOLUS_DELAY_BETWEEN_PULSES_EIGHTH_SECONDS)
                .setProgramReminder(ProgramReminder(atStart = false, atEnd = false, atInterval = 0))
                .setO5BolusInfo(mealUnits = 0.0, correctionUnits = requestedInsulinAmount)
                .build()
            bleManager.sendCommand(cmd, DefaultStatusResponse::class)
                .filter { it.isCommandSent() }
                .doOnNext {
                    commandMayHaveBeenSent = true
                    markBolusRecordExpected(startedAt)
                }
                .ignoreElements()
                .blockingAwait()
            armStatusChecker()
            runBlocking {
                pumpSync.syncBolusWithPumpId(
                    timestamp = startedAt,
                    amount = PumpInsulin(requestedInsulinAmount),
                    type = BS.Type.NORMAL,
                    pumpId = startedAt,
                    pumpType = PumpType.OMNIPOD_5,
                    pumpSerial = serialNumber()
                )
            }
            val completion = waitForBolusDeliveryToComplete(requestedInsulinAmount).blockingGet()
            val deliveredUnits = completion.deliveredUnits
            if (deliveredUnits != null) {
                runBlocking { finalizeBolus(pendingDose, deliveredUnits) }
                aapsLogger.info(LTag.PUMP, "O5 basal correction delivered: $deliveredUnits U")
                pumpEnactResultProvider.get().success(true).enacted(true).bolusDelivered(deliveredUnits)
            } else {
                pumpEnactResultProvider.get().success(true).enacted(true).bolusDelivered(requestedInsulinAmount)
                    .comment(rh.gs(R.string.omnipod_5_error_bolus_delivery_status_uncertain))
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.PUMP, "O5 basal correction delivery failed", e)
            if (!commandMayHaveBeenSent) {
                podStateManager.pendingDoseCommand = null
            } else {
                armStatusChecker()
            }
            pumpEnactResultProvider.get().success(false).enacted(false)
        } finally {
            bolusDeliveryInProgress = false
            podStateManager.basalCorrectionInProgress = false
        }
    }

    // -- preference screen ------------------------------------------------------------------

    /**
     * Mirrors `OmnipodDashPumpPlugin.getPreferenceScreenContent`'s grouping, minus
     * [DashBooleanPreferenceKey.UseBonding] - O5 does not honour it, so offering the toggle
     * would be a control that silently does nothing.
     *
     * Without this the O5 alert and beep preferences were read at runtime but had no screen to
     * set them from, so they were stuck at their defaults.
     */
    override fun getPreferenceScreenContent() = PreferenceSubScreenDef(
        key = "omnipod_5_settings",
        titleResId = R.string.omnipod_5_name,
        items = listOf(
            PreferenceSubScreenDef(
                key = "omnipod_5_beeps",
                titleResId = R.string.omnipod_common_preferences_category_confirmation_beeps,
                items = listOf(
                    OmnipodBooleanPreferenceKey.BolusBeepsEnabled,
                    OmnipodBooleanPreferenceKey.BasalBeepsEnabled,
                    OmnipodBooleanPreferenceKey.SmbBeepsEnabled,
                    OmnipodBooleanPreferenceKey.TbrBeepsEnabled
                )
            ),
            PreferenceSubScreenDef(
                key = "omnipod_5_alerts",
                titleResId = R.string.omnipod_common_preferences_category_alerts,
                items = listOf(
                    OmnipodBooleanPreferenceKey.ExpirationReminder,
                    OmnipodIntPreferenceKey.ExpirationReminderHours,
                    OmnipodBooleanPreferenceKey.ExpirationAlarm,
                    OmnipodIntPreferenceKey.ExpirationAlarmHours,
                    OmnipodBooleanPreferenceKey.LowReservoirAlert,
                    OmnipodIntPreferenceKey.LowReservoirAlertUnits
                )
            ),
            PreferenceSubScreenDef(
                key = "omnipod_5_notifications",
                titleResId = R.string.omnipod_common_preferences_category_notifications,
                items = listOf(
                    OmnipodBooleanPreferenceKey.SoundUncertainTbrNotification,
                    OmnipodBooleanPreferenceKey.SoundUncertainSmbNotification,
                    OmnipodBooleanPreferenceKey.SoundUncertainBolusNotification,
                    DashBooleanPreferenceKey.SoundDeliverySuspendedNotification
                )
            ),
            O5IntentKey.CertificateStore.withCompose(
                ComposeScreenContent { onBack ->
                    O5CertificateStoreScreen(rh = rh, onBack = onBack)
                }
            )
        ),
        icon = pluginDescription.icon
    )

}
