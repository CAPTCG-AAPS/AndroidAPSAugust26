package app.aaps.pump.omnipod.omnipod5.ui

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import app.aaps.pump.omnipod.omnipod5.bledriver.comm.pair.O5RegistrationData
import app.aaps.pump.omnipod.omnipod5.bledriver.pod.security.SecureO5RegistrationStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    private val secureO5RegistrationStorage: SecureO5RegistrationStorage
) : ViewModel() {

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText

    private val _importResult = MutableStateFlow<ImportResult>(ImportResult.None)
    val importResult: StateFlow<ImportResult> = _importResult

    private val _installedCredentials = MutableStateFlow<List<InstalledCredentialRow>>(emptyList())
    val installedCredentials: StateFlow<List<InstalledCredentialRow>> = _installedCredentials

    init {
        refreshInstalledCredentials()
    }

    fun onInputChanged(text: String) {
        _inputText.value = text
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
