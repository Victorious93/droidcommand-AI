package ai.droidcommand.app.ui.studio

import ai.droidcommand.notes.studio.MermaidHtml
import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Renders markdown, including ```mermaid diagrams, with the mermaid.js bundled in app assets. Network loads are
 * blocked in [WebViewClient.shouldInterceptRequest] and [WebView.getSettings.blockNetworkLoads]; the page also
 * carries a CSP. Links are not followed.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MermaidPreview(markdown: String, dark: Boolean, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.blockNetworkLoads = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.domStorageEnabled = false
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true

                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val url = request.url.toString()
                        return if (url.startsWith("file:///android_asset/")) null else WebResourceResponse("text/plain", "utf-8", null)
                    }
                }
            }
        },
        update = { web ->
            val html = MermaidHtml.page(markdown, dark)
            if (web.tag != html) {
                web.tag = html
                web.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "utf-8", null)
            }
        },
    )
}
