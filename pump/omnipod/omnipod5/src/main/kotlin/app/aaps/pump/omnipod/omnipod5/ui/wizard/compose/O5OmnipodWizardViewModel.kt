package app.aaps.pump.omnipod.omnipod5.ui.wizard.compose
import app.aaps.pump.omnipod.common.ui.wizard.compose.OmnipodWizardStep
import app.aaps.pump.omnipod.common.ui.wizard.compose.OmnipodWizardViewModel
import app.aaps.pump.omnipod.common.R

import androidx.annotation.StringRes
import androidx.compose.runtime.Stable
import androidx.lifecycle.viewModelScope
import app.aaps.core.data.model.ICfg
import app.aaps.core.data.model.TE
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.data.time.T
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.insulin.InsulinManager
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileRepository
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.compose.siteRotation.BodyType
import app.aaps.pump.omnipod.omnipod5.bledriver.comm.O5BleManager
import app.aaps.pump.omnipod.common.bledriver.pod.command.GetStatusCommand
import app.aaps.pump.omnipod.common.bledriver.pod.command.GetVersionCommand
import app.aaps.pump.omnipod.common.bledriver.pod.command.ProgramAlertsCommand
import app.aaps.pump.omnipod.common.bledriver.pod.command.ProgramBasalCommand
import app.aaps.pump.omnipod.common.bledriver.pod.command.ProgramBolusCommand
import app.aaps.pump.omnipod.common.bledriver.pod.command.SetUniqueIdCommand
import app.aaps.pump.omnipod.common.bledriver.pod.definition.ActivationProgress
import app.aaps.pump.omnipod.common.bledriver.pod.definition.AlertConfiguration
import app.aaps.pump.omnipod.common.bledriver.pod.definition.AlertTrigger
import app.aaps.pump.omnipod.common.bledriver.pod.definition.AlertType
import app.aaps.pump.omnipod.common.bledriver.pod.definition.BeepRepetitionType
import app.aaps.pump.omnipod.common.bledriver.pod.definition.BeepType
import app.aaps.pump.omnipod.omnipod5.bledriver.pod.definition.O5_FIXED_NONCE
import app.aaps.pump.omnipod.common.bledriver.pod.definition.PodConstants
import app.aaps.pump.omnipod.common.bledriver.pod.definition.PodStatus
import app.aaps.pump.omnipod.common.bledriver.pod.definition.ProgramReminder
import app.aaps.pump.omnipod.common.bledriver.pod.response.DefaultStatusResponse
import app.aaps.pump.omnipod.common.bledriver.pod.response.ResponseType
import app.aaps.pump.omnipod.common.bledriver.pod.response.SetUniqueIdResponse
import app.aaps.pump.omnipod.common.bledriver.pod.response.VersionResponse
import app.aaps.pump.omnipod.omnipod5.bledriver.pod.state.O5PodStateManager
import app.aaps.pump.omnipod.omnipod5.bledriver.pod.util.buildO5ExpirationAlerts
import app.aaps.pump.omnipod.common.keys.OmnipodBooleanPreferenceKey
import app.aaps.pump.omnipod.common.keys.OmnipodIntPreferenceKey
import app.aaps.pump.omnipod.common.queue.command.CommandDeactivatePod
import app.aaps.pump.omnipod.omnipod5.util.mapProfileToBasalProgram
import dagger.hilt.android.lifecycle.HiltViewModel
import io.reactivex.rxjava3.core.Single
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx3.rxSingle
import java.util.Date
import javax.inject.Inject
import javax.inject.Provider
import app.aaps.pump.omnipod.common.R as CommonR

/**
 * O5's concrete [OmnipodWizardViewModel] - physical pod activation (pairing, prime,
 * cannula insertion) and deactivation. Replicates
 * [app.aaps.pump.omnipod.dash.driver.OmnipodDashManagerImpl]'s `activatePodPart1`/
 * `activatePodPart2` command sequence exactly (see that class for the reference this was
 * built from), but calls [O5BleManager]/command classes directly rather than delegating
 * to a Dash-style manager layer - O5 has no such layer, and its per-call
 * `Observable<PodEvent>` correlation (see [O5BleManager.sendCommand]) makes the
 * sequential/blocking style used elsewhere in [app.aaps.pump.omnipod.omnipod5.O5PumpPlugin]
 * simpler here too than replicating Dash's reversed-Observable-list construction.
 *
 * Every sub-step is guarded on [O5PodStateManager.activationProgress] so the wizard's
 * built-in retry-on-failure button can be pressed repeatedly without resending
 * already-completed commands (matches Dash's identical resumability property).
 */
@Stable
@HiltViewModel
class O5OmnipodWizardViewModel @Inject constructor(
    private val bleManager: O5BleManager,
    private val podStateManager: O5PodStateManager,
    private val preferences: Preferences,
    private val rh: ResourceHelper,
    private val commandQueue: CommandQueue,
    private val notificationManager: NotificationManager,
    private val pumpSync: PumpSync,
    private val insulinManager: InsulinManager,
    private val persistenceLayer: PersistenceLayer,
    profileFunction: ProfileFunction,
    profileRepository: ProfileRepository,
    pumpEnactResultProvider: Provider<PumpEnactResult>,
    logger: AAPSLogger,
    aapsSchedulers: AapsSchedulers
) : OmnipodWizardViewModel(logger, aapsSchedulers, pumpEnactResultProvider, profileFunction, profileRepository) {

    private val _siteRotationEntries = MutableStateFlow<List<TE>>(emptyList())

    init {
        viewModelScope.launch {
            val insulins = insulinManager.insulins.map { it.deepClone() }
            val activeLabel = profileFunction.getProfile()?.iCfg?.insulinLabel
            loadInsulins(insulins, activeLabel)
            loadSiteRotationEntriesInternal()
            resolveProfileGate()
            _ready.value = true
        }
    }

    public override val pumpSource: Sources = Sources.Omnipod5

    override fun fallbackICfg(): ICfg? = insulinManager.insulins.firstOrNull()

    override val concentrationEnabled: Boolean
        get() = preferences.get(BooleanKey.GeneralInsulinConcentration)

    override val showSiteLocationStep: Boolean
        get() = preferences.get(BooleanKey.SiteRotationManagePump)

    override fun bodyType(): BodyType =
        BodyType.fromPref(preferences.get(IntKey.SiteRotationUserProfile))

    override fun siteRotationEntries(): List<TE> = _siteRotationEntries.value

    private suspend fun loadSiteRotationEntriesInternal() {
        _siteRotationEntries.value = persistenceLayer.getTherapyEventDataFromTime(
            System.currentTimeMillis() - T.days(45).msecs(), false
        ).filter { it.type == TE.Type.CANNULA_CHANGE || it.type == TE.Type.SENSOR_CHANGE }
    }

    override fun executeInsulinProfileSwitch() {
        val selected = selectedInsulin.value ?: return
        val activeLabel = activeInsulinLabel.value
        if (selected.insulinLabel == activeLabel) return
        viewModelScope.launch {
            profileFunction.createProfileSwitchWithNewInsulin(selected, Sources.Omnipod5)
        }
    }

    override fun saveSiteLocation() {
        val location = getSelectedSiteLocation().takeIf { it != TE.Location.NONE } ?: return
        val arrow = getSelectedSiteArrow().takeIf { it != TE.Arrow.NONE }
        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                val entries = persistenceLayer.getTherapyEventDataFromToTime(now - 60_000, now)
                    .filter { it.type == TE.Type.CANNULA_CHANGE }
                entries.firstOrNull()?.let { te ->
                    persistenceLayer.insertOrUpdateTherapyEvent(te.copy(location = location, arrow = arrow))
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun requirePodId(): Int =
        podStateManager.podId?.toInt() ?: throw IllegalStateException("O5 pod not paired")

    private fun nextSeq(): Short = podStateManager.msgSequenceNumber.toShort()


    override fun doInitializePod(): Single<PumpEnactResult> = rxSingle(Dispatchers.IO) {
        try {
            if (podStateManager.ltk == null) {
                bleManager.pairNewPod().ignoreElements().blockingAwait()
            }

            if (podStateManager.activationProgress.isBefore(ActivationProgress.GOT_POD_VERSION)) {
                val cmd = GetVersionCommand.Builder()
                    .setUniqueId(GetVersionCommand.DEFAULT_UNIQUE_ID)
                    .setSequenceNumber(nextSeq())
                    .build()
                bleManager.sendCommand(cmd, VersionResponse::class).ignoreElements().blockingAwait()
                podStateManager.activationProgress = ActivationProgress.GOT_POD_VERSION
            }

            if (podStateManager.activationProgress.isBefore(ActivationProgress.AID_SETUP)) {
                bleManager.sendAidSetupCommands().blockingAwait()
                podStateManager.activationProgress = ActivationProgress.AID_SETUP
            }

            if (podStateManager.activationProgress.isBefore(ActivationProgress.SET_UNIQUE_ID)) {
                val cmd = SetUniqueIdCommand.Builder()
                    .setUniqueId(requirePodId())
                    .setSequenceNumber(nextSeq())
                    .setLotNumber(requireNotNull(podStateManager.lotNumber) { "Missing lotNumber" }.toInt())
                    .setPodSequenceNumber(requireNotNull(podStateManager.podSequenceNumber) { "Missing podSequenceNumber" }.toInt())
                    .setInitializationTime(Date())
                    .build()
                bleManager.sendCommand(cmd, SetUniqueIdResponse::class).ignoreElements().blockingAwait()
                podStateManager.activationProgress = ActivationProgress.SET_UNIQUE_ID
            }

            if (podStateManager.activationProgress.isBefore(ActivationProgress.PROGRAMMED_LOW_RESERVOIR_ALERTS)) {
                if (preferences.get(OmnipodBooleanPreferenceKey.LowReservoirAlert)) {
                    val lowReservoirAlertUnits = preferences.get(OmnipodIntPreferenceKey.LowReservoirAlertUnits)
                    val cmd = ProgramAlertsCommand.Builder()
                        .setUniqueId(requirePodId())
                        .setSequenceNumber(nextSeq())
                        .setNonce(O5_FIXED_NONCE)
                        .setAlertConfigurations(
                            listOf(
                                AlertConfiguration(
                                    AlertType.LOW_RESERVOIR, enabled = true, durationInMinutes = 0, autoOff = false,
                                    AlertTrigger.ReservoirVolumeTrigger((lowReservoirAlertUnits * 10).toShort()),
                                    BeepType.FOUR_TIMES_BIP_BEEP, BeepRepetitionType.XXX
                                )
                            )
                        )
                        .build()
                    bleManager.sendCommand(cmd, DefaultStatusResponse::class).ignoreElements().blockingAwait()
                }
                podStateManager.activationProgress = ActivationProgress.PROGRAMMED_LOW_RESERVOIR_ALERTS
            }

            if (podStateManager.activationProgress.isBefore(ActivationProgress.REPROGRAMMED_LUMP_OF_COAL_ALERT)) {
                val cmd = ProgramAlertsCommand.Builder()
                    .setUniqueId(requirePodId())
                    .setSequenceNumber(nextSeq())
                    .setNonce(O5_FIXED_NONCE)
                    .setAlertConfigurations(
                        listOf(
                            AlertConfiguration(
                                AlertType.EXPIRATION, enabled = true, durationInMinutes = 55, autoOff = false,
                                AlertTrigger.TimerTrigger(5), BeepType.FOUR_TIMES_BIP_BEEP, BeepRepetitionType.XXX5
                            )
                        )
                    )
                    .build()
                bleManager.sendCommand(cmd, DefaultStatusResponse::class).ignoreElements().blockingAwait()
                podStateManager.activationProgress = ActivationProgress.REPROGRAMMED_LUMP_OF_COAL_ALERT
            }

            if (podStateManager.activationProgress.isBefore(ActivationProgress.PRIMING)) {
                bleManager.connect().ignoreElements().blockingAwait()
                val firstPrimeBolusVolume = requireNotNull(podStateManager.firstPrimeBolusVolume) { "Missing firstPrimeBolusVolume" }
                val primePulseRate = requireNotNull(podStateManager.primePulseRate) { "Missing primePulseRate" }
                val cmd = ProgramBolusCommand.Builder()
                    .setUniqueId(requirePodId())
                    .setSequenceNumber(nextSeq())
                    .setNonce(O5_FIXED_NONCE)
                    .setNumberOfUnits(firstPrimeBolusVolume * PodConstants.POD_PULSE_BOLUS_UNITS)
                    .setDelayBetweenPulsesInEighthSeconds(primePulseRate.toByte())
                    .setProgramReminder(ProgramReminder(atStart = false, atEnd = false, atInterval = 0))
                    .setO5BolusInfo(mealUnits = 0.0, correctionUnits = 0.0)
                    .build()
                bleManager.sendCommand(cmd, DefaultStatusResponse::class).ignoreElements().blockingAwait()
                podStateManager.activationProgress = ActivationProgress.PRIMING
            }

            if (podStateManager.activationProgress == ActivationProgress.PRIMING) {
                val pulses = podStateManager.firstPrimeBolusVolume ?: 0
                val pulseRateEighthSeconds = podStateManager.primePulseRate ?: 8
                Thread.sleep(pulses.toLong() * pulseRateEighthSeconds.toLong() * 1000 / 8)
            }

            if (podStateManager.activationProgress.isBefore(ActivationProgress.PRIME_COMPLETED)) {
                bleManager.connect().ignoreElements().blockingAwait()
                val cmd = GetStatusCommand.Builder()
                    .setUniqueId(requirePodId())
                    .setSequenceNumber(nextSeq())
                    .setStatusResponseType(ResponseType.StatusResponseType.DEFAULT_STATUS_RESPONSE)
                    .build()
                bleManager.sendCommand(cmd, DefaultStatusResponse::class).ignoreElements().blockingAwait()
                check(podStateManager.podStatus == PodStatus.CLUTCH_DRIVE_ENGAGED) {
                    "Unexpected Pod status: got ${podStateManager.podStatus}, expected CLUTCH_DRIVE_ENGAGED"
                }
                podStateManager.activationProgress = ActivationProgress.PRIME_COMPLETED
            }

            podStateManager.activationProgress = ActivationProgress.PHASE_1_COMPLETED
            pumpEnactResultProvider.get().success(true)
        } catch (throwable: Throwable) {
            logger.error(LTag.PUMP, "Error in O5 Pod activation part 1", throwable)
            pumpEnactResultProvider.get().success(false).comment(throwable.message ?: throwable.javaClass.simpleName)
        }
    }

    override fun doInsertCannula(): Single<PumpEnactResult> = rxSingle(Dispatchers.IO) {
        try {
            val profile = pumpSync.expectedPumpState().profile ?: throw IllegalStateException("No profile set")
            val basalProgram = mapProfileToBasalProgram(profile, PumpType.OMNIPOD_5)

            if (podStateManager.activationProgress.isBefore(ActivationProgress.PROGRAMMED_BASAL)) {
                val basalBeeps = preferences.get(OmnipodBooleanPreferenceKey.BasalBeepsEnabled)
                val cmd = ProgramBasalCommand.Builder()
                    .setUniqueId(requirePodId())
                    .setSequenceNumber(nextSeq())
                    .setNonce(O5_FIXED_NONCE)
                    .setBasalProgram(basalProgram)
                    .setProgramReminder(ProgramReminder(atStart = basalBeeps, atEnd = false, atInterval = 0))
                    .setCurrentTime(Date())
                    .build()
                bleManager.sendCommand(cmd, DefaultStatusResponse::class).ignoreElements().blockingAwait()
                podStateManager.activationProgress = ActivationProgress.PROGRAMMED_BASAL
            }

            if (podStateManager.activationProgress.isBefore(ActivationProgress.UPDATED_EXPIRATION_ALERTS)) {
                val cmd = ProgramAlertsCommand.Builder()
                    .setUniqueId(requirePodId())
                    .setSequenceNumber(nextSeq())
                    .setNonce(O5_FIXED_NONCE)
                    .setMultiCommandFlag(true)
                    .setAlertConfigurations(buildO5ExpirationAlerts(podStateManager, preferences, logger))
                    .build()
                bleManager.sendCommand(cmd, DefaultStatusResponse::class).ignoreElements().blockingAwait()
                podStateManager.activationProgress = ActivationProgress.UPDATED_EXPIRATION_ALERTS
            }

            if (podStateManager.activationProgress.isBefore(ActivationProgress.INSERTING_CANNULA)) {
                bleManager.connect().ignoreElements().blockingAwait()
                val secondPrimeBolusVolume = requireNotNull(podStateManager.secondPrimeBolusVolume) { "Missing secondPrimeBolusVolume" }
                val primePulseRate = requireNotNull(podStateManager.primePulseRate) { "Missing primePulseRate" }
                val cmd = ProgramBolusCommand.Builder()
                    .setUniqueId(requirePodId())
                    .setSequenceNumber(nextSeq())
                    .setNonce(O5_FIXED_NONCE)
                    .setNumberOfUnits(secondPrimeBolusVolume * PodConstants.POD_PULSE_BOLUS_UNITS)
                    .setDelayBetweenPulsesInEighthSeconds(primePulseRate.toByte())
                    .setProgramReminder(ProgramReminder(atStart = false, atEnd = false, atInterval = 0))
                    .setO5BolusInfo(mealUnits = 0.0, correctionUnits = 0.0)
                    .build()
                bleManager.sendCommand(cmd, DefaultStatusResponse::class).ignoreElements().blockingAwait()
                podStateManager.activationProgress = ActivationProgress.INSERTING_CANNULA
            }

            if (podStateManager.activationProgress == ActivationProgress.INSERTING_CANNULA) {
                val pulses = podStateManager.secondPrimeBolusVolume ?: 0
                val pulseRateEighthSeconds = podStateManager.primePulseRate ?: 8
                Thread.sleep(pulses.toLong() * pulseRateEighthSeconds.toLong() * 1000 / 8)
            }

            if (podStateManager.activationProgress.isBefore(ActivationProgress.CANNULA_INSERTED)) {
                bleManager.connect().ignoreElements().blockingAwait()
                val cmd = GetStatusCommand.Builder()
                    .setUniqueId(requirePodId())
                    .setSequenceNumber(nextSeq())
                    .setStatusResponseType(ResponseType.StatusResponseType.DEFAULT_STATUS_RESPONSE)
                    .build()
                bleManager.sendCommand(cmd, DefaultStatusResponse::class).ignoreElements().blockingAwait()
                check(podStateManager.podStatus == PodStatus.RUNNING_ABOVE_MIN_VOLUME) {
                    "Unexpected Pod status: got ${podStateManager.podStatus}, expected RUNNING_ABOVE_MIN_VOLUME"
                }
                podStateManager.activationProgress = ActivationProgress.CANNULA_INSERTED
            }

            podStateManager.basalProgram = basalProgram
            pumpSync.connectNewPump()
            val serial = podStateManager.podId?.toString() ?: "n/a"
            pumpSync.insertTherapyEventIfNewWithTimestamp(
                timestamp = System.currentTimeMillis(), type = TE.Type.CANNULA_CHANGE, pumpType = PumpType.OMNIPOD_5, pumpSerial = serial
            )
            pumpSync.insertTherapyEventIfNewWithTimestamp(
                timestamp = System.currentTimeMillis(), type = TE.Type.INSULIN_CHANGE, pumpType = PumpType.OMNIPOD_5, pumpSerial = serial
            )
            notificationManager.dismiss(NotificationId.OMNIPOD_POD_NOT_ATTACHED)
            podStateManager.activationProgress = ActivationProgress.COMPLETED
            podStateManager.cumulativeBolusPulsesDelivered = podStateManager.totalPulsesDelivered ?: 0
            viewModelScope.launch { commandQueue.readStatus(rh.gs(CommonR.string.omnipod_common_pod_activation_wizard_pod_activated_title)) }
            pumpEnactResultProvider.get().success(true)
        } catch (throwable: Throwable) {
            logger.error(LTag.PUMP, "Error in O5 Pod activation part 2", throwable)
            pumpEnactResultProvider.get().success(false).comment(throwable.message ?: throwable.javaClass.simpleName)
        }
    }

    override fun doDeactivatePod(): Single<PumpEnactResult> = rxSingle {
        commandQueue.customCommand(CommandDeactivatePod())
    }



    override fun discardPod() {
        podStateManager.reset()
        notificationManager.dismiss(NotificationId.OMNIPOD_POD_FAULT)
    }

    override fun isPodInAlarm(): Boolean = podStateManager.alarmType != null

    override fun isPodActivationTimeExceeded(): Boolean = false

    override fun isPodDeactivatable(): Boolean = true



    @StringRes
    override fun getTitleForStep(step: OmnipodWizardStep): Int = when (step) {
        OmnipodWizardStep.PROFILE_GATE           -> app.aaps.core.ui.R.string.pump_wizard_profile_gate_title
        OmnipodWizardStep.START_POD_ACTIVATION   -> CommonR.string.omnipod_common_pod_activation_wizard_start_pod_activation_title
        OmnipodWizardStep.SELECT_INSULIN         -> app.aaps.core.ui.R.string.select_insulin
        OmnipodWizardStep.INITIALIZE_POD         -> CommonR.string.omnipod_common_pod_activation_wizard_initialize_pod_title
        OmnipodWizardStep.SITE_LOCATION          -> app.aaps.core.ui.R.string.site_location
        OmnipodWizardStep.ATTACH_POD             -> CommonR.string.omnipod_common_pod_activation_wizard_attach_pod_title
        OmnipodWizardStep.INSERT_CANNULA         -> CommonR.string.omnipod_common_pod_activation_wizard_insert_cannula_title
        OmnipodWizardStep.POD_ACTIVATED          -> CommonR.string.omnipod_common_pod_activation_wizard_pod_activated_title
        OmnipodWizardStep.START_POD_DEACTIVATION -> CommonR.string.omnipod_common_pod_deactivation_wizard_start_pod_deactivation_title
        OmnipodWizardStep.DEACTIVATE_POD         -> CommonR.string.omnipod_common_pod_deactivation_wizard_deactivating_pod_title
        OmnipodWizardStep.POD_DEACTIVATED        -> CommonR.string.omnipod_common_pod_deactivation_wizard_pod_deactivated_title
        OmnipodWizardStep.POD_DISCARDED          -> CommonR.string.omnipod_common_pod_deactivation_wizard_pod_discarded_title
    }

    @StringRes
    override fun getTextForStep(step: OmnipodWizardStep): Int = when (step) {
        OmnipodWizardStep.PROFILE_GATE           -> 0
        OmnipodWizardStep.START_POD_ACTIVATION   -> CommonR.string.omnipod_5_pod_activation_wizard_start_pod_activation_text
        OmnipodWizardStep.SELECT_INSULIN         -> app.aaps.core.ui.R.string.select_insulin_description
        OmnipodWizardStep.INITIALIZE_POD         -> CommonR.string.omnipod_5_pod_activation_wizard_initialize_pod_text
        OmnipodWizardStep.SITE_LOCATION          -> app.aaps.core.ui.R.string.select_site_location
        OmnipodWizardStep.ATTACH_POD             -> CommonR.string.omnipod_common_pod_activation_wizard_attach_pod_text
        OmnipodWizardStep.INSERT_CANNULA         -> CommonR.string.omnipod_common_pod_activation_wizard_insert_cannula_text
        OmnipodWizardStep.POD_ACTIVATED          -> CommonR.string.omnipod_common_pod_activation_wizard_pod_activated_text
        OmnipodWizardStep.START_POD_DEACTIVATION -> CommonR.string.omnipod_common_pod_deactivation_wizard_start_pod_deactivation_text
        OmnipodWizardStep.DEACTIVATE_POD         -> CommonR.string.omnipod_common_pod_deactivation_wizard_deactivating_pod_text
        OmnipodWizardStep.POD_DEACTIVATED        -> CommonR.string.omnipod_common_pod_deactivation_wizard_pod_deactivated_text
        OmnipodWizardStep.POD_DISCARDED          -> CommonR.string.omnipod_common_pod_deactivation_wizard_pod_discarded_text
    }

}
