package app.aaps.pump.omnipod.omnipod5.ui.compose

import android.content.Context
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.insulin.ConcentrationHelper
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
import app.aaps.pump.omnipod.omnipod5.bledriver.pod.state.O5PodStateManager
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

        whenever(rxBus.toFlow(EventPumpStatusChanged::class.java)).thenReturn(emptyFlow())
        whenever(rxBus.toFlow(EventQueueChanged::class.java)).thenReturn(emptyFlow())

        whenever(podStateManager.activationProgress).thenReturn(ActivationProgress.NOT_STARTED)

        whenever(rh.gs(any<Int>())).thenReturn("label")
        whenever(rh.gs(any<Int>(), any(), any())).thenReturn("label")
        whenever(dateUtil.dateAndTimeString(any())).thenReturn("date")
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

        val podStatusRow = state.infoRows.filterIsInstance<PumpInfoRow>().first { it.label == "Pod status" }
        assertThat(podStatusRow.level).isEqualTo(StatusLevel.CRITICAL)

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
}
