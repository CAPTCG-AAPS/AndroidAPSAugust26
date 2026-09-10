package app.aaps.pump.omnipod.common.ui

import app.aaps.pump.omnipod.common.bledriver.comm.pair.O5RegistrationData
import app.aaps.pump.omnipod.common.bledriver.pod.security.SecureO5RegistrationStorage
import app.aaps.shared.tests.AAPSLoggerTest
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import java.util.Base64

/**
 * [O5CredentialImportViewModel] - manipulates the process-wide [O5RegistrationData] registry,
 * so every test cleans it up in @BeforeEach/@AfterEach to avoid cross-test pollution (same
 * discipline as [app.aaps.pump.omnipod.common.bledriver.comm.pair.O5CertificateStoreTest] etc).
 */
class O5CredentialImportViewModelTest {

    private val secureO5RegistrationStorage = mock<SecureO5RegistrationStorage>()
    private val aapsLogger = AAPSLoggerTest()
    private val testDispatcher = StandardTestDispatcher()

    private fun newViewModel() = O5CredentialImportViewModel(secureO5RegistrationStorage, mock(), aapsLogger)

    /** A test double for the attestation service; the download tests inject it via the factory. */
    private fun fakeService(
        onFetch: () -> O5RegistrationData = { throw O5KeyAttestationService.AttestationException("boom") }
    ): O5KeyAttestationService = object : O5KeyAttestationService(mock(), aapsLogger) {
        override fun fetchCredential(progress: (Progress) -> Unit, requestToken: () -> String?): O5RegistrationData =
            onFetch()
    }

    private fun packedCredential(controllerId: Long): String {
        val privB64 = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4))
        val pubB64 = Base64.getEncoder().encodeToString(byteArrayOf(5, 6, 7, 8))
        return "$controllerId|$privB64|$pubB64||"
    }

    @BeforeEach
    fun clearRegistrationData() {
        Dispatchers.setMain(testDispatcher)
        O5RegistrationData.allValues.forEach { O5RegistrationData.remove(it.controllerId) }
    }

    @AfterEach
    fun tearDown() {
        O5RegistrationData.allValues.forEach { O5RegistrationData.remove(it.controllerId) }
        Dispatchers.resetMain()
    }

    @Test
    fun `importCurrentInput fails with a helpful message when the input is blank`() {
        val vm = newViewModel()
        vm.onInputChanged("   ")

        vm.importCurrentInput()

        assertThat(vm.importResult.value).isInstanceOf(ImportResult.Failure::class.java)
    }

    @Test
    fun `importCurrentInput fails when the credential string is malformed`() {
        val vm = newViewModel()
        vm.onInputChanged("not a valid credential string")

        vm.importCurrentInput()

        assertThat(vm.importResult.value).isInstanceOf(ImportResult.Failure::class.java)
    }

    @Test
    fun `importCurrentInput installs, persists, and reports success for a well-formed credential`() {
        val vm = newViewModel()
        val controllerId = 99887766L
        vm.onInputChanged(packedCredential(controllerId))

        vm.importCurrentInput()

        assertThat(vm.importResult.value).isEqualTo(ImportResult.Success(controllerId))
        assertThat(vm.inputText.value).isEmpty()
        assertThat(O5RegistrationData.contains(controllerId)).isTrue()
        verify(secureO5RegistrationStorage).persistEntry(
            O5RegistrationData.get(controllerId)!!,
            O5RegistrationData.O5RegistrationSource.IMPORTED
        )
    }

    @Test
    fun `importCurrentInput updates installedCredentials after a successful import`() {
        val vm = newViewModel()
        val controllerId = 55443322L
        vm.onInputChanged(packedCredential(controllerId))

        vm.importCurrentInput()

        assertThat(vm.installedCredentials.value).hasSize(1)
        assertThat(vm.installedCredentials.value[0].controllerId).isEqualTo(controllerId)
        assertThat(vm.installedCredentials.value[0].source).isEqualTo(O5RegistrationData.O5RegistrationSource.IMPORTED)
    }

    @Test
    fun `onInputChanged updates inputText and clears a stale import result`() {
        val vm = newViewModel()
        vm.onInputChanged("not valid")
        vm.importCurrentInput()
        assertThat(vm.importResult.value).isNotEqualTo(ImportResult.None)

        vm.onInputChanged("something new")

        assertThat(vm.inputText.value).isEqualTo("something new")
        assertThat(vm.importResult.value).isEqualTo(ImportResult.None)
    }

    @Test
    fun `removeCredential removes from the registry and persisted storage, then refreshes the list`() {
        val vm = newViewModel()
        val controllerId = 11223344L
        vm.onInputChanged(packedCredential(controllerId))
        vm.importCurrentInput()
        assertThat(vm.installedCredentials.value).hasSize(1)

        vm.removeCredential(controllerId)

        assertThat(O5RegistrationData.contains(controllerId)).isFalse()
        assertThat(vm.installedCredentials.value).isEmpty()
        verify(secureO5RegistrationStorage).removeEntry(controllerId)
    }

    // -- certificate download ----------------------------------------------------------------

    private fun registrationData(controllerId: Long) = O5RegistrationData(
        controllerId = controllerId,
        privateKeyHex = "01020304",
        publicKeyHex = "05060708",
        intermediateCABase64 = "",
        tlsCertificateBase64 = ""
    )

    @Test
    fun `downloadCredential installs, persists and reports success on a downloaded credential`() = runTest(testDispatcher) {
        val vm = newViewModel()
        val controllerId = 424242L
        vm.attestationServiceFactory = { fakeService(onFetch = { registrationData(controllerId) }) }
        vm.ioDispatcher = testDispatcher

        vm.downloadCredential()
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(vm.downloadState.value).isEqualTo(DownloadState.Success(controllerId))
        assertThat(O5RegistrationData.contains(controllerId)).isTrue()
        verify(secureO5RegistrationStorage).persistEntry(
            O5RegistrationData.get(controllerId)!!,
            O5RegistrationData.O5RegistrationSource.DOWNLOADED
        )
        assertThat(vm.installedCredentials.value.map { it.controllerId }).contains(controllerId)
    }

    @Test
    fun `downloadCredential surfaces the server message and recovery hint on failure`() = runTest(testDispatcher) {
        val vm = newViewModel()
        vm.attestationServiceFactory = {
            fakeService(onFetch = {
                throw O5KeyAttestationService.AttestationException("Android not supported yet", recoverySuggestion = "Try later")
            })
        }
        vm.ioDispatcher = testDispatcher

        vm.downloadCredential()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.downloadState.value
        assertThat(state).isInstanceOf(DownloadState.Failure::class.java)
        assertThat((state as DownloadState.Failure).reason).isEqualTo("Android not supported yet")
        assertThat(state.recovery).isEqualTo("Try later")
        // A failed download must not install or persist anything.
        assertThat(vm.installedCredentials.value).isEmpty()
    }

    @Test
    fun `clearDownloadState returns to idle`() = runTest(testDispatcher) {
        val vm = newViewModel()
        vm.attestationServiceFactory = { fakeService(onFetch = { throw O5KeyAttestationService.AttestationException("x") }) }
        vm.ioDispatcher = testDispatcher
        vm.downloadCredential()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.clearDownloadState()

        assertThat(vm.downloadState.value).isEqualTo(DownloadState.Idle)
    }
}
