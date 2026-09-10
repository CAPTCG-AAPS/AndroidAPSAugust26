package app.aaps.pump.omnipod.common.ui

import app.aaps.shared.tests.AAPSLoggerTest
import com.google.common.truth.Truth.assertThat
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.mock
import java.io.IOException

/**
 * Covers the server-facing half of [O5KeyAttestationService] - status check, challenge,
 * claim, auth-token gating and error surfacing. Requests are served by a canned-response
 * OkHttp [Interceptor] rather than a real socket, so no MockWebServer dependency is needed
 * and there is no OkHttp-version clash.
 *
 * The hardware key-generation half ([O5KeyAttestationService.buildTestAttestation] and the
 * key step of `fetchCredential`) needs the Android Keystore, so it is exercised only in the
 * on-device instrumented tests. Here the flow is driven up to that point: the status/challenge
 * requests are pinned, and the key step then throws in a plain JVM, which is enough to check
 * request shapes and every server-error path this port must match iOS on.
 */
class O5KeyAttestationServiceTest {

    private val aapsLogger = AAPSLoggerTest()
    private val recorded = mutableListOf<Pair<String, String>>() // path to body

    /** Serves [responses] in order, one per request, recording each request's path and body. */
    private fun service(vararg responses: CannedResponse): O5KeyAttestationService {
        val queue = ArrayDeque(responses.toList())
        val interceptor = Interceptor { chain ->
            val request = chain.request()
            recorded.add(request.url.encodedPath to request.readBody())
            val canned = queue.removeFirstOrNull()
                ?: throw IOException("No canned response for ${request.url}")
            if (canned.ioError) throw IOException("simulated network failure")
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(canned.code)
                .message("canned")
                .body(canned.body.toResponseBody("application/json".toMediaType()))
                .build()
        }
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        return O5KeyAttestationService(mock(), aapsLogger, baseUrl = "https://example.invalid", client = client)
    }

    private data class CannedResponse(val code: Int, val body: String, val ioError: Boolean = false)

    private fun ok(json: JSONObject) = CannedResponse(200, json.toString())
    private fun http(code: Int, json: JSONObject) = CannedResponse(code, json.toString())
    private fun ioError() = CannedResponse(0, "", ioError = true)

    private fun Request.readBody(): String {
        val buffer = okio.Buffer()
        body?.writeTo(buffer)
        return buffer.readUtf8()
    }

    @Test
    fun `unavailable server surfaces its message and aborts before any key work`() {
        val service = service(ok(JSONObject().put("available", false).put("message", "Android is not supported yet")))

        val e = assertThrows<O5KeyAttestationService.AttestationException> { service.fetchCredential() }

        assertThat(e.userMessage).isEqualTo("Android is not supported yet")
        // Only the status endpoint should have been hit - no challenge, no key generation.
        assertThat(recorded).hasSize(1)
        assertThat(recorded[0].first).isEqualTo("/api/status/android")
        assertThat(JSONObject(recorded[0].second).getString("omnipodkit_api_version")).isNotEmpty()
    }

    @Test
    fun `a non-2xx status response raises with the server error text`() {
        val service = service(http(503, JSONObject().put("message", "maintenance")))

        val e = assertThrows<O5KeyAttestationService.AttestationException> { service.fetchCredential() }

        assertThat(e.userMessage).isEqualTo("maintenance")
        assertThat(e.httpStatusCode).isEqualTo(503)
    }

    @Test
    fun `an auth-gated server without a token cancels cleanly`() {
        val service = service(ok(JSONObject().put("available", false).put("authSupported", true)))

        val e = assertThrows<O5KeyAttestationService.AttestationException> { service.fetchCredential(requestToken = { null }) }

        assertThat(e.userMessage).isEqualTo("Setup cancelled.")
    }

    @Test
    fun `an available server fetches a challenge, then fails on hardware in a JVM`() {
        val service = service(
            ok(JSONObject().put("available", true)),
            ok(JSONObject().put("challenge", "Y2hhbGxlbmdl"))
        )

        assertThrows<Throwable> { service.fetchCredential() }

        assertThat(recorded.map { it.first })
            .containsExactly("/api/status/android", "/api/auth/android/challenge").inOrder()
    }

    @Test
    fun `a blank challenge is rejected`() {
        val service = service(
            ok(JSONObject().put("available", true)),
            ok(JSONObject().put("challenge", ""))
        )

        val e = assertThrows<O5KeyAttestationService.AttestationException> { service.fetchCredential() }

        assertThat(e.userMessage).contains("challenge")
    }

    @Test
    fun `a network failure is reported as unreachable, not as a crash`() {
        val service = service(ioError())

        val e = assertThrows<O5KeyAttestationService.AttestationException> { service.fetchCredential() }

        assertThat(e.userMessage).contains("key-management server")
    }
}
