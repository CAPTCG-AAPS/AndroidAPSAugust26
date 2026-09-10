package app.aaps.pump.omnipod.common.ui

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.pump.omnipod.common.bledriver.comm.pair.O5RegistrationData
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Android counterpart of OmnipodKit's iOS `O5AppAttestService`. Proves to the OSAID
 * key-manager that this app is running on genuine Android hardware, then downloads an
 * Omnipod 5 credential.
 *
 * The iOS app uses Apple App Attest; the equivalent guarantee on Android is
 * **hardware key attestation**: a key pair is generated inside the phone's secure
 * hardware (StrongBox, or the TEE as a fallback), and the hardware signs a certificate
 * chain - rooted in Google's attestation roots - that vouches the key really lives in
 * secure hardware and records the OS/boot state. The server's challenge is embedded in
 * that chain, so it cannot be replayed. See the accompanying server specification for the
 * checks the key-manager must perform on the chain.
 *
 * What this proves matches what iOS proves today: a real, uncompromised device. Like iOS,
 * it does not by itself stop one device requesting more than one credential - see
 * [app.aaps.pump.omnipod.common.bledriver.comm.pair.O5RegistrationData]'s notes. The
 * attestation key is only an identity proof for this download; it never touches pod
 * pairing, which continues to use the downloaded credential.
 *
 * This deliberately mirrors the iOS flow step for step so the server changes stay small.
 * The one ordering difference: on Android the server challenge must be fetched *before*
 * the key is generated, because the challenge is written into the key's attestation at
 * creation time (on iOS the key is generated first, then attested against the challenge).
 */
open class O5KeyAttestationService(
    private val context: Context,
    private val aapsLogger: AAPSLogger,
    private val baseUrl: String = OSAID_BASE_URL,
    private val client: OkHttpClient = defaultClient()
) {

    /** Ordered phases, mirroring iOS `O5KeyFetchProgress`, so the UI can drive a progress bar. */
    enum class Progress(val message: String) {
        CheckingServiceStatus("Checking server status…"),
        RequestingChallenge("Requesting server challenge…"),
        GeneratingHardwareKey("Generating hardware key…"),
        AttestingWithHardware("Attesting device hardware…"),
        DownloadingCertificate("Downloading certificate…");

        val index: Int get() = ordinal + 1
        companion object { val totalSteps: Int get() = entries.size }
    }

    /** A failure with a user-facing message, plus an optional recovery hint and HTTP status. */
    class AttestationException(
        val userMessage: String,
        val recoverySuggestion: String? = null,
        val httpStatusCode: Int? = null,
        cause: Throwable? = null
    ) : Exception(userMessage, cause)

    /** Outcome of the "build a test attestation" action - see [buildTestAttestation]. */
    data class TestAttestation(
        val packageName: String,
        val challenge: String,
        val securityLevel: String,
        val certificateChainPem: List<String>
    ) {
        /** Human-readable block the user can copy or share with the key-manager operator. */
        fun toShareableText(): String = buildString {
            appendLine("Omnipod 5 Android attestation sample")
            appendLine("package: $packageName")
            appendLine("challenge (base64url): $challenge")
            appendLine("key security level: $securityLevel")
            appendLine("certificate chain (leaf first), ${certificateChainPem.size} certs:")
            certificateChainPem.forEach { appendLine(it) }
        }
    }

    /**
     * Runs the full attestation + download flow. Blocking; call from a background
     * dispatcher. [progress] is invoked before each phase. A setup token, when the
     * server gates access behind one, is supplied by [requestToken] (return null to
     * cancel), matching the iOS token flow.
     */
    open fun fetchCredential(
        progress: (Progress) -> Unit = {},
        requestToken: () -> String? = { null }
    ): O5RegistrationData {
        progress(Progress.CheckingServiceStatus)
        val authToken = when (val status = checkServerStatus()) {
            ServerStatus.Available          -> null
            ServerStatus.AuthRequired       -> requestToken()
                ?: throw AttestationException("Setup cancelled.")

            is ServerStatus.Unavailable     -> throw AttestationException(status.message, httpStatusCode = status.statusCode)
        }

        progress(Progress.RequestingChallenge)
        val challenge = getChallenge(authToken)

        progress(Progress.GeneratingHardwareKey)
        val alias = "$KEY_ALIAS_PREFIX${System.currentTimeMillis()}"
        val attestation = try {
            generateAttestedKey(alias, challenge)
        } finally {
            // The key exists only to prove hardware for this one download; never reused.
            deleteKey(alias)
        }

        progress(Progress.AttestingWithHardware)
        // Nothing extra to compute locally: the certificate chain from key generation is the
        // attestation. Server verifies it. Phase kept for parity with the iOS progress list.

        progress(Progress.DownloadingCertificate)
        return claimKeypair(attestation, challenge, authToken)
    }

    /**
     * Generates a throwaway attested key and returns its chain for the user to hand to the
     * key-manager operator, without contacting any server. This is the artifact the
     * Nightscout team needs to build and test their server-side checks against a real
     * device. The chain carries the OS version, patch level and boot state, but no IMEI,
     * serial number or any per-user identifier (apps cannot request those in attestation).
     */
    open fun buildTestAttestation(): TestAttestation {
        val challengeBytes = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val challenge = base64Url(challengeBytes)
        val alias = "$KEY_ALIAS_PREFIX-test"
        return try {
            val chain = generateAttestedKey(alias, challenge)
            TestAttestation(
                packageName = context.packageName,
                challenge = challenge,
                securityLevel = chain.securityLevelName,
                certificateChainPem = chain.pemList()
            )
        } finally {
            deleteKey(alias)
        }
    }

    // -- attestation -----------------------------------------------------------------------

    /** The attested key's certificate chain plus a note of how strong the backing is. */
    private class Attestation(val chain: List<X509Certificate>, val strongBox: Boolean) {
        val securityLevelName get() = if (strongBox) "StrongBox" else "TEE"
        fun pemList(): List<String> = chain.map { toPem(it) }
        fun chainBase64(): List<String> = chain.map { Base64.getEncoder().encodeToString(it.encoded) }
    }

    /**
     * Creates an EC P-256 key in the Android Keystore whose attestation certificate embeds
     * [challenge]. Tries StrongBox first (a dedicated secure chip) and falls back to the TEE
     * if the device has no StrongBox. A software-only key is never produced: without secure
     * hardware the whole point - proving genuine hardware - is gone, so this throws instead.
     */
    private fun generateAttestedKey(alias: String, challenge: String): Attestation {
        val challengeBytes = base64UrlDecode(challenge)
        for (useStrongBox in listOf(true, false)) {
            try {
                val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setAttestationChallenge(challengeBytes)
                    .apply { if (useStrongBox) setIsStrongBoxBacked(true) }
                    .build()
                val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
                generator.initialize(spec)
                generator.generateKeyPair()

                val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                val chain = keyStore.getCertificateChain(alias)
                    ?.map { it as X509Certificate }
                    ?: throw AttestationException(
                        "This device did not return an attestation certificate.",
                        recoverySuggestion = "The device may not support hardware key attestation."
                    )
                if (chain.size < 2) {
                    throw AttestationException(
                        "This device did not provide a hardware attestation chain.",
                        recoverySuggestion = "Hardware key attestation is required to download a certificate."
                    )
                }
                aapsLogger.debug(LTag.PUMPBTCOMM, "O5 attestation key created (${if (useStrongBox) "StrongBox" else "TEE"}), chain length ${chain.size}")
                return Attestation(chain, useStrongBox)
            } catch (e: StrongBoxUnavailableException) {
                aapsLogger.debug(LTag.PUMPBTCOMM, "StrongBox unavailable, falling back to TEE")
                // loop continues with useStrongBox = false
            } catch (e: AttestationException) {
                throw e
            } catch (e: Exception) {
                if (!useStrongBox) {
                    throw AttestationException(
                        "Could not create a hardware-backed key on this device: ${e.message}",
                        recoverySuggestion = "Hardware key attestation is required to download a certificate.",
                        cause = e
                    )
                }
                aapsLogger.debug(LTag.PUMPBTCOMM, "StrongBox key generation failed (${e.message}), falling back to TEE")
            }
        }
        throw AttestationException("Could not create a hardware-backed key on this device.")
    }

    private fun deleteKey(alias: String) {
        try {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(alias)
        } catch (e: Exception) {
            aapsLogger.debug(LTag.PUMPBTCOMM, "Could not delete attestation key $alias: ${e.message}")
        }
    }

    // -- server calls ----------------------------------------------------------------------

    private sealed class ServerStatus {
        object Available : ServerStatus()
        object AuthRequired : ServerStatus()
        data class Unavailable(val message: String, val statusCode: Int?) : ServerStatus()
    }

    private fun checkServerStatus(): ServerStatus {
        val body = JSONObject().put("omnipodkit_api_version", OMNIPODKIT_API_VERSION)
        val (json, code) = postJson("$baseUrl/api/status/android", body, authToken = null)
            ?: throw AttestationException(
                "The key-management server is temporarily unavailable.",
                recoverySuggestion = "Please try again later."
            )
        val available = json.optBoolean("available", false)
        if (available) return ServerStatus.Available
        if (json.optBoolean("authSupported", false)) return ServerStatus.AuthRequired
        val message = json.optString("message").ifBlank { "The key-management server is temporarily unavailable." }
        return ServerStatus.Unavailable(message, code)
    }

    private fun getChallenge(authToken: String?): String {
        val (json, response) = postJson("$baseUrl/api/auth/android/challenge", JSONObject(), authToken)
            ?: throw AttestationException("Failed to get challenge from the server.")
        return json.optString("challenge").ifBlank {
            throw AttestationException("Server did not return a challenge.", httpStatusCode = response)
        }
    }

    private fun claimKeypair(attestation: Attestation, challenge: String, authToken: String?): O5RegistrationData {
        val body = JSONObject()
            .put("challenge", challenge)
            .put("app_id", context.packageName)
            .put("security_level", attestation.securityLevelName)
            .put("attestation_chain", JSONArray(attestation.chainBase64()))
        val (json, response) = postJson("$baseUrl/api/o5/keypair/android", body, authToken)
            ?: throw AttestationException("Failed to download the certificate.")

        val map = REQUIRED_JSON_KEYS.associateWith { key -> if (json.has(key)) json.optString(key) else null }
        return O5RegistrationData.fromJsonMap(map)
            ?: throw AttestationException(
                json.optString("message").ifBlank { "The server response was not a valid credential." },
                httpStatusCode = response
            )
    }

    /**
     * POSTs [body] as JSON and returns the parsed response object plus HTTP status. Non-2xx
     * responses raise an [AttestationException] carrying the server's `message`/`error` text
     * when present, so the user sees why the key-manager refused (e.g. "Android not yet
     * supported"). Returns null only when the body was 2xx but unparseable.
     */
    private fun postJson(url: String, body: JSONObject, authToken: String?): Pair<JSONObject, Int>? {
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .apply { authToken?.let { header("Authorization", "Bearer $it") } }
            .build()
        val (text, code) = try {
            client.newCall(request).execute().use { response ->
                (response.body?.string() ?: "") to response.code
            }
        } catch (e: Exception) {
            throw AttestationException(
                "Could not reach the key-management server.",
                recoverySuggestion = "Please check your Internet connection and try again.",
                cause = e
            )
        }
        val json = runCatching { JSONObject(text) }.getOrNull()
        if (code !in 200..299) {
            val message = json?.let { it.optString("message").ifBlank { it.optString("error") } }
                ?.takeIf { it.isNotBlank() } ?: "HTTP $code"
            throw AttestationException(message, httpStatusCode = code)
        }
        return json?.let { it to code }
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun base64UrlDecode(text: String): ByteArray =
        Base64.getUrlDecoder().decode(text)

    companion object {

        const val OSAID_BASE_URL = "https://api.osaid-keymanager.org"
        private const val OMNIPODKIT_API_VERSION = "1.1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS_PREFIX = "o5-attestation-"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        /** Same fields OmnipodKit's `o5keypair` JSON carries; see [O5RegistrationData.fromJsonMap]. */
        private val REQUIRED_JSON_KEYS = listOf("controllerId", "privateKey", "publicKey", "intermediateCA", "tlsCertificate")

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .callTimeout(30, TimeUnit.SECONDS)
            .build()

        private fun toPem(cert: X509Certificate): String {
            val b64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(cert.encoded)
            return "-----BEGIN CERTIFICATE-----\n$b64\n-----END CERTIFICATE-----"
        }

        /** SHA-256, exposed for tests that need to check challenge handling. */
        internal fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    }
}
