package app.aaps.pump.omnipod.common.ui

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Hosts the key manager's sign-in page and waits for it to hand back an Omnipod 5 certificate.
 *
 * The page signs the user in and then posts the certificate JSON through a message bridge
 * (`aapsKeymanagerBridge.postMessage(...)`), which arrives here via
 * [WebViewCompat.addWebMessageListener] and is passed to [onCredentialReceived]. The caller
 * installs it through the normal import path, so the same formats are accepted as a pasted or
 * file-imported certificate.
 *
 * [onError] reports a device whose WebView is too old to support the bridge. The certificate can
 * still be fetched in a normal browser and brought in with "Import from file" in that case.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun O5CredentialWebViewScreen(
    url: String,
    onCredentialReceived: (String) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentOnCredentialReceived by rememberUpdatedState(onCredentialReceived)
    val currentOnError by rememberUpdatedState(onError)

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // Keep navigation inside this view: the sign-in redirects through the identity
                // provider and back, and sending that to an external browser would lose the
                // bridge that returns the certificate.
                webViewClient = WebViewClient()

                if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                    currentOnError("This phone's WebView is too old to receive a certificate here - fetch it in a browser and use Import from file instead")
                    return@apply
                }

                WebViewCompat.addWebMessageListener(this, BRIDGE_NAME, ALLOWED_ORIGIN_RULES) { _, message, _, _, _ ->
                    if (message.type == WebMessageCompat.TYPE_STRING) {
                        message.data?.let { currentOnCredentialReceived(it) }
                    }
                }
                loadUrl(url)
            }
        }
    )
}

private const val BRIDGE_NAME = "aapsKeymanagerBridge"

/**
 * Only the key manager itself may hand a certificate to the app. The bridge is reachable by any
 * page loaded in this WebView, and sign-in redirects through a third-party identity provider, so
 * allowing every origin ("*") would let any page that the flow passes through - or is redirected
 * to - inject a certificate. Pin it to the one origin the certificate legitimately comes from.
 */
private val ALLOWED_ORIGIN_RULES = setOf("https://api.osaid-keymanager.org")
