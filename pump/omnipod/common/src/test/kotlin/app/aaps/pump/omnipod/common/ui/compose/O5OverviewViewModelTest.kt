package app.aaps.pump.omnipod.common.ui.compose

import android.content.Context
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.insulin.ConcentrationHelper
import app.aaps.core.interfaces.pump.PumpInsulin
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventPumpStatusChanged
import app.aaps.core.interfaces.rx.events.EventQueueChanged
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.ui.compose.StatusLevel
import app.aaps.core.ui.compose.pump.PumpInfoRow
import app.aaps.pump.omnipod.common.bledriver.pod.definition.ActivationProgress
import app.aaps.pump.omnipod.common.bledriver.pod.definition.PodStatus
import app.aaps.pump.omnipod.common.bledriver.pod.state.O5PodStateManager
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import app.aaps.core.ui.R as CoreUiR
import app.aaps.pump.omnipod.common.R as CommonR

/**
 * Mirrors [app.aaps.pump.omnipod.dash.ui.compose.DashOverviewViewModelTest]'s pattern for
 * O5's [O5OverviewViewModel] - covers [app.aaps.core.ui.compose.pump.PumpOverviewUiState]
 * assembly (the initial value produced by `buildUiState()`). Both tests keep the pod in the
 * pre-activation ("not initialized", `activationProgress = NOT_STARTED`) state, the
 * least stub-heavy branch of `buildInfoRows()`: it emits placeholder rows and never
 * dereferences the pod's optional dosing fields.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class O5OverviewViewModelTest {

    @Mock private lateinit var rh: ResourceHelper
    @Mock private lateinit var podStateManager: O5PodStateManager
    @Mock private lateinit var commandQueue: CommandQueue
    @Mock private lateinit var rxBus: RxBus
    @Mock private lateinit var dateUtil: DateUtil
    @Mock private lateinit var config: Config
    @Mock private lateinit var ch: ConcentrationHelper
    @Mock private lateinit var context: Context

    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        Dispatchers.setMain(UnconfinedTestDispatcher())

        // rx wiring touched at construction: PumpCommunicationStatus subscribes to these two.
        whenever(rxBus.toFlow(EventPumpStatusChanged::class.java)).thenReturn(emptyFlow())
        whenever(rxBus.toFlow(EventQueueChanged::class.java)).thenReturn(emptyFlow())

        // buildUiState() reads activationProgress heavily; NOT_STARTED keeps the simple pre-activation branch.
        whenever(podStateManager.activationProgress).thenReturn(ActivationProgress.NOT_STARTED)

        // Every info-row / action label goes through rh.gs(Int); unstubbed returns null -> non-null String NPE.
        whenever(rh.gs(any<Int>())).thenReturn("label")
        // Format-string rows (e.g. firmware_version_value, pod_fault_description) go through
        // the vararg gs(Int, vararg Any?) overload instead - stub the 2-arg shape used by the
        // activated-pod branch of buildInfoRows() so it doesn't return null there too.
        whenever(rh.gs(any<Int>(), any(), any())).thenReturn("label")
        // podStateManager.podActivatedAt (a mocked Long?) defaults to 0L, not null, so the
        // "Pod Activated At" row's dateUtil.dateAndTimeString(...) call is reached even in
        // tests that don't care about it - stub it so that doesn't NPE on an unstubbed String.
        whenever(dateUtil.dateAndTimeString(any())).thenReturn("date")
        // Same story for podStateManager.reservoirPulsesRemaining (a mocked Short?, defaults
        // to 0) - buildReservoir() always reaches ch.insulinAmountString(...) as a result.
        whenever(ch.insulinAmountString(any())).thenReturn("0 U")
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel() = O5OverviewViewModel(rh, podStateManager, commandQueue, rxBus, dateUtil, config, ch, context)

    @Test
    fun preActivation_buildsPlaceholderRows_criticalPodStatus_andOffersActivate() {
        whenever(rh.gs(CommonR.string.omnipod_common_overview_pod_status)).thenReturn("Pod status")
        whenever(rh.gs(CommonR.string.omnipod_common_pod_management_button_activate_pod)).thenReturn("Activate pod")
        whenever(rh.gs(CoreUiR.string.refresh)).thenReturn("Refresh")

        val state = createViewModel().uiState.value

        assertThat(state.infoRows).isNotEmpty()
        assertThat(state.statusBanner).isNull()
        assertThat(state.queueStatus).isNull()

        // Pre-activation pod is not completed -> pod status row is CRITICAL.
        val podStatusRow = state.infoRows.filterIsInstance<PumpInfoRow>().first { it.label == "Pod status" }
        assertThat(podStatusRow.level).isEqualTo(StatusLevel.CRITICAL)

        // Refresh needs at least SET_UNIQUE_ID -> disabled; management offers "Activate pod".
        val refresh = state.primaryActions.first { it.label == "Refresh" }
        assertThat(refresh.enabled).isFalse()
        val activate = state.managementActions.first { it.label == "Activate pod" }
        assertThat(activate.visible).isTrue()
    }

    @Test
    fun noConnectionAttemptsYet_showsPlaceholderBluetoothQuality() {
        whenever(rh.gs(CommonR.string.omnipod_common_overview_bluetooth_connection_quality)).thenReturn("BT quality")
        whenever(podStateManager.connectionAttempts).thenReturn(0)

        val state = createViewModel().uiState.value

        val btQualityRow = state.infoRows.filterIsInstance<PumpInfoRow>().first { it.label == "BT quality" }
        assertThat(btQualityRow.value).isEqualTo("-")
    }

    @Test
    fun connectionQuality_reportsSuccessfulOverAttemptedRatio() {
        whenever(rh.gs(CommonR.string.omnipod_common_overview_bluetooth_connection_quality)).thenReturn("BT quality")
        whenever(podStateManager.connectionAttempts).thenReturn(10)
        whenever(podStateManager.successfulConnections).thenReturn(8)

        val state = createViewModel().uiState.value

        val btQualityRow = state.infoRows.filterIsInstance<PumpInfoRow>().first { it.label == "BT quality" }
        assertThat(btQualityRow.value).contains("8/10")
    }

    @Test
    fun activatedPod_offersDeactivateNotActivate() {
        whenever(podStateManager.activationProgress).thenReturn(ActivationProgress.COMPLETED)
        whenever(rh.gs(CommonR.string.omnipod_common_pod_management_button_activate_pod)).thenReturn("Activate pod")
        whenever(rh.gs(CommonR.string.omnipod_common_pod_management_button_deactivate_pod)).thenReturn("Deactivate pod")

        val state = createViewModel().uiState.value

        val activate = state.managementActions.first { it.label == "Activate pod" }
        assertThat(activate.visible).isFalse()
        val deactivate = state.managementActions.first { it.label == "Deactivate pod" }
        assertThat(deactivate.visible).isTrue()
    }

    @Test
    fun suspendedActivePod_offersResumeNotSuspend() {
        whenever(podStateManager.activationProgress).thenReturn(ActivationProgress.COMPLETED)
        whenever(podStateManager.podStatus).thenReturn(PodStatus.RUNNING_ABOVE_MIN_VOLUME)
        whenever(podStateManager.deliverySuspended).thenReturn(true)
        whenever(rh.gs(CommonR.string.omnipod_common_overview_button_resume_delivery)).thenReturn("Resume")
        whenever(rh.gs(CommonR.string.omnipod_common_overview_button_suspend_delivery)).thenReturn("Suspend")

        val state = createViewModel().uiState.value

        val resume = state.primaryActions.first { it.label == "Resume" }
        assertThat(resume.visible).isTrue()
        val suspend = state.primaryActions.first { it.label == "Suspend" }
        assertThat(suspend.visible).isFalse()
    }

    @Test
    fun above50Reservoir_showsOver50RatherThanAnExactAmount() {
        whenever(podStateManager.activationProgress).thenReturn(ActivationProgress.COMPLETED)
        whenever(podStateManager.reservoirPulsesRemaining).thenReturn(null)
        whenever(rh.gs(CoreUiR.string.reservoir_label)).thenReturn("Reservoir")
        whenever(rh.gs(eq(CoreUiR.string.overview_reservoir_concentration_value_over), eq("0 U"))).thenReturn(">50 U")

        val row = createViewModel().uiState.value.infoRows.filterIsInstance<PumpInfoRow>().first { it.label == "Reservoir" }

        assertThat(row.value).isEqualTo(">50 U")
        assertThat(row.level).isEqualTo(StatusLevel.NORMAL)
    }

    @Test
    fun measuredReservoir_showsMeasuredAmount() {
        whenever(podStateManager.activationProgress).thenReturn(ActivationProgress.COMPLETED)
        whenever(podStateManager.reservoirPulsesRemaining).thenReturn(250)
        whenever(rh.gs(CoreUiR.string.reservoir_label)).thenReturn("Reservoir")
        whenever(ch.insulinAmountString(any())).thenReturn("12.5 U")
        whenever(ch.fromPump(any<PumpInsulin>(), eq(false))).thenReturn(12.5)

        val row = createViewModel().uiState.value.infoRows.filterIsInstance<PumpInfoRow>().first { it.label == "Reservoir" }

        assertThat(row.value).isEqualTo("12.5 U")
        assertThat(row.level).isEqualTo(StatusLevel.CRITICAL)
    }
}
