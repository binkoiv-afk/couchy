package app.couchy.ui.search

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.couchy.CouchyApp
import app.couchy.core.sources.SiteRef
import app.couchy.core.sources.Sites
import app.couchy.sources.SiteWindow
import app.couchy.ui.components.SecondaryButton
import java.io.ByteArrayInputStream

/** Headers the site's player sent for the video; the stream usually needs them. */
private val keptHeaders = setOf("referer", "origin", "user-agent")

/**
 * A site page with its own player. Ads and trackers get empty answers, pop-ups and
 * redirects to other sites are refused, and as soon as the player requests a video
 * stream, the window closes and [onVideo] gets the stream with its headers.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SiteWindowDialog(
    window: SiteWindow,
    userAgent: String,
    onVideo: (url: String, headers: Map<String, String>, pageUrl: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var web by remember { mutableStateOf<WebView?>(null) }
    val bypass = (LocalContext.current.applicationContext as CouchyApp).bypass
    // A blocked site needs the tunnel up before the page starts loading.
    val ready by produceState(false, window.url) {
        bypass.prepare(window.url)
        value = true
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false)) {
        BackHandler { web?.takeIf { it.canGoBack() }?.goBack() ?: onDismiss() }
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        listOfNotNull(window.site?.name, window.title.takeIf { it != window.site?.name }).joinToString(" · "),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (window.key == null) "Откройте фильм и нажмите ▶ в плеере сайта. Дальше Couchy покажет его в своём плеере, без рекламы."
                        else "Нажмите ▶ в плеере сайта, если он не начал сам. Дальше Couchy покажет видео в своём плеере, без рекламы.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SecondaryButton("Закрыть", Icons.Rounded.Close, onClick = onDismiss)
            }
            if (!ready) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
                return@Column
            }
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.javaScriptCanOpenWindowsAutomatically = false
                        settings.setSupportMultipleWindows(false)
                        settings.userAgentString = userAgent
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = Catcher(window.url, userAgent, onVideo)
                        loadUrl(window.url)
                        web = this
                    }
                },
                onRelease = { v ->
                    v.stopLoading()
                    v.loadUrl("about:blank")
                    v.destroy()
                },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
    }
}

private class Catcher(
    start: String,
    private val userAgent: String,
    private val onVideo: (String, Map<String, String>, String) -> Unit,
) : WebViewClient() {
    private val main = Handler(Looper.getMainLooper())
    private val startHost = SiteRef.hostOf(start)
    private val found = mutableListOf<Pair<String, Map<String, String>>>()
    private var page = start
    private var done = false

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url.toString()
        if (Sites.isAd(url)) return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
        if (Sites.isVideo(url)) {
            val headers = request.requestHeaders.filterKeys { it.lowercase() in keptHeaders }.toMutableMap()
            if (headers.keys.none { it.equals("user-agent", true) }) headers["User-Agent"] = userAgent
            CookieManager.getInstance().getCookie(url)?.let { headers["Cookie"] = it }
            main.post { take(url, headers) }
        }
        return null
    }

    private fun take(url: String, headers: Map<String, String>) {
        if (done) return
        found += url to headers
        // A player asks for several playlists at once; the master one arrives within a moment.
        if (found.size == 1) main.postDelayed({ finish() }, 1200)
    }

    private fun finish() {
        if (done) return
        done = true
        val best = Sites.preferred(found.map { it.first }) ?: return
        onVideo(best, found.first { it.first == best }.second, page)
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        if (!url.startsWith("http")) return true
        if (!request.isForMainFrame) return false
        // The page itself may move around its own site or to another known cinema; ad redirects may not.
        val host = SiteRef.hostOf(url)
        return !(host == startHost || SiteRef.siteOf(url) != null)
    }

    override fun onPageFinished(view: WebView, url: String?) {
        if (url != null && url.startsWith("http")) page = url
    }
}
