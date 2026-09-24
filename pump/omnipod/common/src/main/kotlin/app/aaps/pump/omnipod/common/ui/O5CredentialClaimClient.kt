package app.aaps.pump.omnipod.common.ui

import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.pump.omnipod.common.R
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads one Omnipod 5 credential from a private token server (a small Cloudflare
 * Worker the builder runs - see the "o5-credential-server" project). The user pastes a
 * token they were given out of band; the app sends the token plus a stable device id, and
 * the server returns one credential string - the same text you could paste by hand.
 *
 * The server binds a token to the first device that uses it, so a leaked token cannot be
 * used on another phone. This talks only to the builder's own server; it never contacts
 * Insulet or any attestation service, and works on any phone.
 *
 * Uses plain [HttpURLConnection] so the module needs no extra network dependency.
 */
class O5CredentialClaimClient(private val rh: ResourceHelper) {

    /** Outcome of a claim. */
    sealed class ClaimResult {

        /** The server returned a credential string, ready to install. */
        data class Success(val credential: String) : ClaimResult()

        /** The server refused, or something went wrong; [message] is safe to show the user. */
        data class Failure(val message: String) : ClaimResult()
    }

    /**
     * Blocking network call - run it on a background dispatcher. POSTs `{token, deviceId}`
     * to `<serverUrl>/claim` and reads back `{"credential":"..."}` on success, or
     * `{"message":"..."}` with a non-2xx status on refusal.
     */
    fun claim(serverUrl: String, token: String, deviceId: String): ClaimResult {
        val body = JSONObject().put("token", token).put("deviceId", deviceId).toString()
        val connection = try {
            (URL("${serverUrl.trimEnd('/')}/claim").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
        } catch (e: Exception) {
            return ClaimResult.Failure(rh.gs(R.string.omnipod_common_o5_credential_error_unreachable))
        }

        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
            if (code in 200..299) {
                val credential = json?.optString("credential").orEmpty()
                if (credential.isBlank()) {
                    ClaimResult.Failure(rh.gs(R.string.omnipod_common_o5_credential_error_empty))
                } else {
                    ClaimResult.Success(credential)
                }
            } else {
                val message = json?.optString("message").orEmpty()
                ClaimResult.Failure(
                    message.ifBlank { rh.gs(R.string.omnipod_common_o5_credential_error_http, code) }
                )
            }
        } catch (e: Exception) {
            ClaimResult.Failure(rh.gs(R.string.omnipod_common_o5_credential_error_talking))
        } finally {
            connection.disconnect()
        }
    }

    companion object {

        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 15_000
    }
}
