package app.aaps.pump.omnipod.common.ui

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.omnipod.common.bledriver.comm.pair.O5RegistrationData
import app.aaps.pump.omnipod.common.bledriver.pod.definition.ActivationProgress
import app.aaps.pump.omnipod.common.bledriver.pod.security.SecureO5RegistrationStorage
import app.aaps.pump.omnipod.common.bledriver.pod.state.O5PodStateManager
import app.aaps.pump.omnipod.common.keys.O5StringNonPreferenceKey
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

/** One row of the "currently installed credentials" list shown in the import screen. */
data class InstalledCredentialRow(
    val controllerId: Long,
    val source: O5RegistrationData.O5RegistrationSource
)

/** Result of the last import attempt, so the screen can show a success/error message. */
sealed class ImportResult {
    object None : ImportResult()
    data class Success(val controllerId: Long) : ImportResult()
    data class Failure(val reason: String) : ImportResult()
}

/**
 * Drives a settings screen for importing an Omnipod 5 credential and viewing/removing
 * already-installed credentials. A credential can arrive two ways:
 *
 * - **With a token** (the easy path for shared builds): the user pastes a token they were
 *   given, the app downloads one credential from the builder's own token server (see
 *   [O5CredentialClaimClient]) and installs it.
 * - **By pasting** a credential directly, auto-detected from the text:
 *   - a `.o5keypair`-shaped JSON object (as produced by OmnipodKit's own `toJSON()` on
 *     iOS - `controllerId`/`privateKey`/`publicKey`/`intermediateCA`/`tlsCertificate`,
 *     keys hex-encoded and certs base64-encoded) - see [O5RegistrationData.fromJsonMap]
 *   - the packed `"controllerId|priv|pub|ica|tls"` string format - see
 *     [O5RegistrationData.installPacked]
 *
 * Deliberately has no dosing-related functionality whatsoever - this only manages which
 * credentials [O5RegistrationData] knows about, nothing about pairing, connection, or
 * pod control.
 */
@HiltViewModel
class O5CredentialImportViewModel @Inject constructor(
    private val secureO5RegistrationStorage: SecureO5RegistrationStorage,
    private val preferences: Preferences,
    private val podStateManager: O5PodStateManager,
    private val aapsLogger: AAPSLogger
) : ViewModel() {

    /**
     * Records what the certificate sign-in page does. The WebView itself writes nothing to the
     * AAPS log, so without this a failed sign-in leaves no trace to diagnose.
     */
    fun logSignIn(message: String) {
        aapsLogger.info(LTag.PUMP, "O5 certificate sign-in: $message")
    }

    /** The credential download client. Overridable so tests can supply a fake. */
    @Stable
    var claimClient: O5CredentialClaimClient = O5CredentialClaimClient()

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText

    private val _tokenInput = MutableStateFlow("")
    val tokenInput: StateFlow<String> = _tokenInput

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading

    private val _importResult = MutableStateFlow<ImportResult>(ImportResult.None)
    val importResult: StateFlow<ImportResult> = _importResult

    private val _installedCredentials = MutableStateFlow<List<InstalledCredentialRow>>(emptyList())
    val installedCredentials: StateFlow<List<InstalledCredentialRow>> = _installedCredentials

    /** True when this build has a token server set, so the screen can show the token path. */
    val tokenDownloadAvailable: Boolean get() = CREDENTIAL_SERVER_URL.isNotBlank()

    init {
        refreshInstalledCredentials()
    }

    fun onInputChanged(text: String) {
        _inputText.value = text
        // Clear any stale result once the user starts editing again.
        if (_importResult.value != ImportResult.None) {
            _importResult.value = ImportResult.None
        }
    }

    fun onTokenChanged(text: String) {
        _tokenInput.value = text
        if (_importResult.value != ImportResult.None) {
            _importResult.value = ImportResult.None
        }
    }

    /**
     * Downloads a credential using the token in [tokenInput] from this build's token server,
     * then installs it exactly as a pasted credential would be. Runs the network call off
     * the main thread. On failure, shows the server's message and leaves the token in place.
     */
    fun downloadWithToken() {
        val token = _tokenInput.value.trim()
        if (token.isEmpty()) {
            _importResult.value = ImportResult.Failure("Enter your token first")
            return
        }
        val serverUrl = CREDENTIAL_SERVER_URL.trim()
        if (serverUrl.isEmpty()) {
            _importResult.value = ImportResult.Failure("This build has no credential server set")
            return
        }
        _isDownloading.value = true
        _importResult.value = ImportResult.None
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                claimClient.claim(serverUrl, token, deviceId())
            }
            when (result) {
                is O5CredentialClaimClient.ClaimResult.Success -> installCredential(result.credential, clearTokenOnSuccess = true)
                is O5CredentialClaimClient.ClaimResult.Failure -> _importResult.value = ImportResult.Failure(result.message)
            }
            _isDownloading.value = false
        }
    }

    /**
     * Attempts to parse and install [inputText]'s current value, auto-detecting whether
     * it's a `.o5keypair`-shaped JSON object or a packed credential string (see class doc).
     * On success, also persists it (encrypted) so it survives app restarts, and clears the
     * input field. On failure, leaves the input as-is so the user can correct it.
     */
    fun importCurrentInput() {
        val text = _inputText.value.trim()
        if (text.isEmpty()) {
            _importResult.value = ImportResult.Failure("Paste a credential string first")
            return
        }
        installCredential(text, clearTokenOnSuccess = false)
    }

    /**
     * Installs a certificate handed back by the key manager page (see [O5CredentialWebViewScreen]).
     * The page posts the same JSON that its downloadable file contains, so this goes through the
     * usual install path.
     */
    fun importFromWebMessage(json: String) {
        val text = json.trim()
        if (text.isEmpty()) {
            _importResult.value = ImportResult.Failure("The sign-in page did not return a certificate")
            return
        }
        installCredential(text, clearTokenOnSuccess = false)
    }

    /** Reports a failure raised by the key manager page itself, rather than by parsing. */
    fun importFailed(message: String) {
        _importResult.value = ImportResult.Failure(message)
    }

    /**
     * Installs a credential from a file the user picked, for example the `.o5keypair` file the
     * key manager hands out. [readText] reads that file and runs off the main thread, since the
     * picked file can come from a slow provider such as cloud storage. The contents go through
     * the same install path as a pasted credential, so both formats are accepted.
     */
    fun importFromFile(readText: () -> String?) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) { runCatching(readText).getOrNull() }
            if (text.isNullOrBlank()) {
                _importResult.value = ImportResult.Failure("Could not read that file")
                return@launch
            }
            installCredential(text.trim(), clearTokenOnSuccess = false)
        }
    }

    /**
     * Shared install path for both a pasted credential and a downloaded one. Parses [text],
     * persists it (encrypted), and reports the result. Clears the paste field on success,
     * and the token field too when [clearTokenOnSuccess] is set.
     */
    private fun installCredential(text: String, clearTokenOnSuccess: Boolean) {
        val controllerId = O5RegistrationData.installFromText(text, O5RegistrationData.O5RegistrationSource.IMPORTED)
        if (controllerId == null) {
            _importResult.value = ImportResult.Failure(
                "Could not parse that credential - check it was copied completely"
            )
            return
        }

        val installed = O5RegistrationData.get(controllerId)
        if (installed == null) {
            // Shouldn't happen given install() just ran for this controllerId, but guard
            // anyway rather than reporting success for something that didn't actually register.
            _importResult.value = ImportResult.Failure("Import failed unexpectedly")
            return
        }

        secureO5RegistrationStorage.persistEntry(installed, O5RegistrationData.O5RegistrationSource.IMPORTED)
        _importResult.value = ImportResult.Success(controllerId)
        _inputText.value = ""
        if (clearTokenOnSuccess) _tokenInput.value = ""
        refreshInstalledCredentials()
    }

    /**
     * True when [controllerId] is the identity the currently active pod was paired with.
     * Removing that credential would leave the running pod with no way to authenticate, so
     * the Certificate Store must refuse it. Any other credential is safe to remove, which is
     * the common case - an unused or replaced one sitting alongside the active one. Both rows
     * look alike on screen (they are all "Imported"), so the guard has to be in code.
     */
    private fun isInUseByActivePod(controllerId: Long): Boolean =
        podStateManager.activationProgress == ActivationProgress.COMPLETED &&
            podStateManager.controllerId == controllerId

    /** Removes a credential from both the in-memory registry and persisted storage. */
    fun removeCredential(controllerId: Long) {
        if (isInUseByActivePod(controllerId)) {
            _importResult.value = ImportResult.Failure(
                "That credential is in use by the active pod - deactivate the pod first"
            )
            return
        }
        O5RegistrationData.remove(controllerId)
        secureO5RegistrationStorage.removeEntry(controllerId)
        refreshInstalledCredentials()
    }

    /** A stable per-install id, made once and kept, so the server can bind a token to this phone. */
    private fun deviceId(): String {
        val existing = preferences.getIfExists(O5StringNonPreferenceKey.CredentialClaimDeviceId)
        if (!existing.isNullOrEmpty()) return existing
        val id = UUID.randomUUID().toString()
        preferences.put(O5StringNonPreferenceKey.CredentialClaimDeviceId, id)
        return id
    }

    private fun refreshInstalledCredentials() {
        _installedCredentials.value = O5RegistrationData.allValues.mapNotNull { data ->
            O5RegistrationData.source(data.controllerId)?.let { source ->
                InstalledCredentialRow(data.controllerId, source)
            }
        }
    }

    companion object {

        /**
         * The builder's own token server, e.g. "https://o5-credential-server.<sub>.workers.dev".
         * Set this to the URL you get after deploying the "o5-credential-server" Worker. Leave
         * it empty to hide the token download path (paste-only). This URL is not a secret - it
         * is fine to commit; the token the user enters is what gates access.
         */
        const val CREDENTIAL_SERVER_URL = "https://o5-credential-server.captcg-o5-8842.workers.dev"
    }
}
