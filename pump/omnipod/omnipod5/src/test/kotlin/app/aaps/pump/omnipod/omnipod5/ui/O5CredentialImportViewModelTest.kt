package app.aaps.pump.omnipod.omnipod5.ui

import app.aaps.pump.omnipod.omnipod5.bledriver.comm.pair.O5RegistrationData
import app.aaps.pump.omnipod.omnipod5.bledriver.pod.security.SecureO5RegistrationStorage
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import java.util.Base64

/**
 * [O5CredentialImportViewModel] - manipulates the process-wide [O5RegistrationData] registry,
 * so every test cleans it up in @BeforeEach/@AfterEach to avoid cross-test pollution (same
 * discipline as [app.aaps.pump.omnipod.omnipod5.bledriver.comm.pair.O5CertificateStoreTest] etc).
 */
class O5CredentialImportViewModelTest {

    private val secureO5RegistrationStorage = mock<SecureO5RegistrationStorage>()

    private fun newViewModel() = O5CredentialImportViewModel(secureO5RegistrationStorage)

    private fun packedCredential(controllerId: Long): String {
        val privB64 = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4))
        val pubB64 = Base64.getEncoder().encodeToString(byteArrayOf(5, 6, 7, 8))
        return "$controllerId|$privB64|$pubB64||"
    }

    @BeforeEach
    fun clearRegistrationData() {
        O5RegistrationData.allValues.forEach { O5RegistrationData.remove(it.controllerId) }
    }

    @AfterEach
    fun tearDown() {
        O5RegistrationData.allValues.forEach { O5RegistrationData.remove(it.controllerId) }
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
}
