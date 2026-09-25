package app.aaps.pump.omnipod.common.ui

import android.annotation.SuppressLint
import android.os.Message
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
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
 * Sign-in goes through a third-party identity provider, which opens its own window, so popups are
 * supported and routed back into this view - a WebView drops them silently otherwise, which looks
 * like the page opening and closing again with nothing happening.
 *
 * [onLog] records what the page does; the WebView writes nothing to the AAPS log by itself, so
 * without it a sign-in that fails leaves no trace. [onError] reports a device whose WebView cannot
 * support the bridge at all; the certificate can still be fetched in a browser and brought in with
 * "Import from file".
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun O5CredentialWebViewScreen(
    url: String,
    onCredentialReceived: (String) -> Unit,
    onError: (String) -> Unit,
    onLog: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentOnCredentialReceived by rememberUpdatedState(onCredentialReceived)
    val currentOnError by rememberUpdatedState(onError)
    val currentOnLog by rememberUpdatedState(onLog)

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // The identity provider opens its sign-in in a new window.
                settings.setSupportMultipleWindows(true)

                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, pageUrl: String?, favicon: android.graphics.Bitmap?) {
                        currentOnLog("loading $pageUrl")
                    }

                    override fun onPageFinished(view: WebView?, pageUrl: String?) {
                        currentOnLog("loaded $pageUrl")
                    }

                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                        if (request?.isForMainFrame == true) {
                            currentOnLog("failed to load ${request.url}")
                        }
                    }
                }

                webChromeClient = object : WebChromeClient() {
                    /**
                     * Routes a popup back into this same view. The sign-in window must keep the
                     * bridge, and a second WebView would not have it.
                     */
                    override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                        val relay = WebView(view.context)
                        relay.webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(relayView: WebView?, request: WebResourceRequest?): Boolean {
                                request?.url?.let {
                                    currentOnLog("popup -> $it")
                                    view.loadUrl(it.toString())
                                }
                                relay.destroy()
                                return true
                            }
                        }
                        transport.webView = relay
                        resultMsg.sendToTarget()
                        return true
                    }

                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        consoleMessage?.let { currentOnLog("page says: ${it.message()}") }
                        return true
                    }
                }

                // If the page ends in a file download rather than posting over the bridge, the
                // WebView would ignore it silently. Record it so the reason is visible.
                setDownloadListener { downloadUrl, _, _, mimeType, _ ->
                    currentOnLog("page tried to download $downloadUrl ($mimeType) instead of posting the certificate")
                }

                if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                    currentOnError("This phone's WebView is too old to receive a certificate here - fetch it in a browser and use Import from file instead")
                    return@apply
                }

                WebViewCompat.addWebMessageListener(this, BRIDGE_NAME, ALLOWED_ORIGIN_RULES) { _, message, sourceOrigin, _, _ ->
                    currentOnLog("bridge message from $sourceOrigin")
                    if (message.type == WebMessageCompat.TYPE_STRING) {
                        message.data?.let { currentOnCredentialReceived(it) }
                    }
                }
                currentOnLog("opening $url")
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
