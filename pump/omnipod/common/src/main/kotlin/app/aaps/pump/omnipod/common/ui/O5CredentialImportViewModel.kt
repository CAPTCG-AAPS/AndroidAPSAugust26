package app.aaps.pump.omnipod.common.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.pump.omnipod.common.bledriver.comm.pair.O5RegistrationData
import app.aaps.pump.omnipod.common.bledriver.pod.security.SecureO5RegistrationStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
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

/** State of a certificate download or test-attestation run, so the screen can react. */
sealed class DownloadState {
    object Idle : DownloadState()
    data class InProgress(val message: String, val index: Int, val total: Int) : DownloadState()
    data class Success(val controllerId: Long) : DownloadState()
    data class Failure(val reason: String, val recovery: String?) : DownloadState()

    /** A test attestation was produced; [text] is the shareable block for the operator. */
    data class TestAttestationReady(val text: String) : DownloadState()
}

/**
 * Drives a settings screen for importing an Omnipod 5 credential and viewing/removing
 * already-installed credentials. Accepts either format, auto-detected from the pasted
 * text:
 * - a `.o5keypair`-shaped JSON object (as produced by OmnipodKit's own `toJSON()` on
 *   iOS - `controllerId`/`privateKey`/`publicKey`/`intermediateCA`/`tlsCertificate`,
 *   keys hex-encoded and certs base64-encoded) - see [O5RegistrationData.fromJsonMap]
 * - the packed `"controllerId|priv|pub|ica|tls"` string format - see
 *   [O5RegistrationData.installPacked]
 *
 * Deliberately has no dosing-related functionality whatsoever - this only manages which
 * credentials [O5RegistrationData] knows about, nothing about pairing, connection, or
 * pod control.
 */
@HiltViewModel
class O5CredentialImportViewModel @Inject constructor(
    private val secureO5RegistrationStorage: SecureO5RegistrationStorage,
    @ApplicationContext private val context: Context,
    private val aapsLogger: AAPSLogger
) : ViewModel() {

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText

    private val _importResult = MutableStateFlow<ImportResult>(ImportResult.None)
    val importResult: StateFlow<ImportResult> = _importResult

    private val _installedCredentials = MutableStateFlow<List<InstalledCredentialRow>>(emptyList())
    val installedCredentials: StateFlow<List<InstalledCredentialRow>> = _installedCredentials

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState

    /** Overridable so tests can supply a fake; production builds one per run against the real server. */
    var attestationServiceFactory: () -> O5KeyAttestationService = {
        O5KeyAttestationService(context, aapsLogger)
    }

    /** The dispatcher the blocking attestation/HTTP work runs on. Overridable in tests. */
    var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    init {
        refreshInstalledCredentials()
    }

    /**
     * Downloads a credential by proving this device's hardware to the OSAID key-manager, then
     * installs and persists it exactly like a pasted credential. Runs off the main thread. Until
     * the key-manager accepts Android, this surfaces the server's refusal as a [DownloadState.Failure].
     */
    fun downloadCredential(provideSetupToken: () -> String? = { null }) {
        if (_downloadState.value is DownloadState.InProgress) return
        _downloadState.value = DownloadState.InProgress(
            O5KeyAttestationService.Progress.CheckingServiceStatus.message, 1, O5KeyAttestationService.Progress.totalSteps
        )
        viewModelScope.launch {
            val result = withContext(ioDispatcher) {
                runCatching {
                    attestationServiceFactory().fetchCredential(
                        progress = { step ->
                            _downloadState.value = DownloadState.InProgress(step.message, step.index, O5KeyAttestationService.Progress.totalSteps)
                        },
                        requestToken = provideSetupToken
                    )
                }
            }
            result.onSuccess { data ->
                O5RegistrationData.install(data, O5RegistrationData.O5RegistrationSource.DOWNLOADED)
                secureO5RegistrationStorage.persistEntry(data, O5RegistrationData.O5RegistrationSource.DOWNLOADED)
                _downloadState.value = DownloadState.Success(data.controllerId)
                refreshInstalledCredentials()
            }.onFailure { e ->
                val recovery = (e as? O5KeyAttestationService.AttestationException)?.recoverySuggestion
                _downloadState.value = DownloadState.Failure(e.message ?: "Certificate download failed", recovery)
            }
        }
    }

    /**
     * Produces a hardware attestation sample without contacting any server, for the user to hand
     * to the key-manager operator so they can build and test their server-side checks. No pod or
     * credential state is touched.
     */
    fun buildTestAttestation() {
        _downloadState.value = DownloadState.InProgress("Building attestation sample…", 1, 1)
        viewModelScope.launch {
            val result = withContext(ioDispatcher) {
                runCatching { attestationServiceFactory().buildTestAttestation() }
            }
            result.onSuccess { att ->
                _downloadState.value = DownloadState.TestAttestationReady(att.toShareableText())
            }.onFailure { e ->
                val recovery = (e as? O5KeyAttestationService.AttestationException)?.recoverySuggestion
                _downloadState.value = DownloadState.Failure(e.message ?: "Could not build an attestation sample", recovery)
            }
        }
    }

    fun clearDownloadState() {
        _downloadState.value = DownloadState.Idle
    }

    fun onInputChanged(text: String) {
        _inputText.value = text
        // Clear any stale result once the user starts editing again.
        if (_importResult.value != ImportResult.None) {
            _importResult.value = ImportResult.None
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

        val controllerId = if (text.startsWith("{")) importJsonCredential(text) else importPackedCredential(text)
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
        refreshInstalledCredentials()
    }

    /**
     * Parses [text] as the `.o5keypair`-shaped JSON object OmnipodKit's own `toJSON()`
     * produces on iOS and installs it if valid. Returns the resulting controllerId, or
     * null if the text wasn't valid JSON or was missing a required field.
     */
    private fun importJsonCredential(text: String): Long? {
        val json = try {
            JSONObject(text)
        } catch (e: JSONException) {
            return null
        }
        val map = REQUIRED_JSON_KEYS.associateWith { key -> json.optString(key, null) }
        val data = O5RegistrationData.fromJsonMap(map) ?: return null
        O5RegistrationData.install(data, O5RegistrationData.O5RegistrationSource.IMPORTED)
        return data.controllerId
    }

    /**
     * Parses [text] as a packed `"controllerId|priv|pub|ica|tls"` string and installs it
     * if valid (see [O5RegistrationData.installPacked]). Returns the resulting
     * controllerId, or null if parsing/installation failed.
     */
    private fun importPackedCredential(text: String): Long? {
        val controllerId = parseControllerIdFromPacked(text)
        val ok = O5RegistrationData.installPacked(text)
        return controllerId.takeIf { ok }
    }

    /** Removes a credential from both the in-memory registry and persisted storage. */
    fun removeCredential(controllerId: Long) {
        O5RegistrationData.remove(controllerId)
        secureO5RegistrationStorage.removeEntry(controllerId)
        refreshInstalledCredentials()
    }

    private fun refreshInstalledCredentials() {
        _installedCredentials.value = O5RegistrationData.allValues.mapNotNull { data ->
            O5RegistrationData.source(data.controllerId)?.let { source ->
                InstalledCredentialRow(data.controllerId, source)
            }
        }
    }

    /**
     * Pulls just the controllerId out of a packed string, without fully parsing/validating
     * it - used so a failed [O5RegistrationData.installPacked] call can still be attributed
     * to a specific controllerId if the string was at least well-formed enough to read one.
     * Returns null for anything that doesn't even have a parseable leading controllerId field.
     */
    private fun parseControllerIdFromPacked(packed: String): Long? =
        packed.substringBefore("|").toLongOrNull()

    private companion object {
        /** Matches the keys OmnipodKit's `O5RegistrationData.toJSON()` produces on iOS. */
        val REQUIRED_JSON_KEYS = listOf("controllerId", "privateKey", "publicKey", "intermediateCA", "tlsCertificate")
    }
}
