package ai.droidcommand.app.ui.notes.studio

import ai.droidcommand.notes.studio.MermaidPreview
import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Markdown preview with live Mermaid diagrams. mermaid.js is bundled in assets (offline); the
 * WebView blocks network loads, file and content access, and every link navigation.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MermaidWebView(markdown: String, modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()
    val html = MermaidPreview.page(markdown, dark)
    AndroidView(
        modifier = modifier.testTag("mermaid_preview"),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.blockNetworkLoads = true
                settings.domStorageEnabled = false
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
                }
            }
        },
        update = { it.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "utf-8", null) },
    )
}
