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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import app.couchy.bypass.Bypass
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    val scope = rememberCoroutineScope()
    var tries by remember { mutableStateOf(0) }
    // The provider refused the page: learn the site, bring up what the bypass can and load it again.
    val onBlocked: (WebView, String) -> Unit = { view, url ->
        val host = Bypass.hostOf(url)
        if (tries >= 2 || host == null) {
            view.loadDataWithBaseURL(null, blockedPage(host ?: url), "text/html", "utf-8", null)
        } else {
            tries += 1
            scope.launch {
                if (bypass.canLearn(host)) bypass.learn(host) else runCatching { bypass.ensure() }
                // The browser picks up the new route a moment later.
                delay(700)
                view.loadUrl(url)
            }
        }
    }
    // A blocked site needs the tunnel up before the page starts loading.
    val ready by produceState(false, window.url) {
        bypass.prepare(window.url)
        value = true
    }
    // A film page plays without being seen: Couchy presses ▶ in the site's player itself and only
    // shows the site when that does not start the video.
    var hidden by remember { mutableStateOf(window.key != null) }
    LaunchedEffect(web, hidden) {
        val view = web ?: return@LaunchedEffect
        if (!hidden) return@LaunchedEffect
        delay(2_500)
        repeat(6) {
            pressPlay(view)
            delay(2_500)
        }
        hidden = false
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
            Box(Modifier.fillMaxWidth().weight(1f)) {
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
                        webViewClient = Catcher(window.url, userAgent, onVideo, onBlocked)
                        loadUrl(window.url)
                        web = this
                    }
                },
                onRelease = { v ->
                    v.stopLoading()
                    v.loadUrl("about:blank")
                    v.destroy()
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (hidden) {
                Column(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Text("Запускаю видео…", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                    Text(
                        "Couchy сам включает плеер сайта и забирает видео в свой плеер.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SecondaryButton("Показать сайт", null) { hidden = false }
                }
            }
            }
        }
    }
}

/**
 * Finds the biggest player on the page, scrolls to it and taps its middle, the way a person would.
 * A real tap also reaches players inside frames from other sites, which scripts cannot.
 */
private fun pressPlay(view: WebView) {
    val script = """
        (function(){
          var best=null,area=0;
          document.querySelectorAll('video,iframe,[id*=player],[class*=player]').forEach(function(e){
            var r=e.getBoundingClientRect(),a=r.width*r.height;
            if(a>area&&r.width>120&&r.height>80){area=a;best=e;}
          });
          var v=document.querySelector('video'); if(v&&v.play){try{v.play();}catch(x){}}
          if(!best)return '';
          best.scrollIntoView({block:'center'});
          var r=best.getBoundingClientRect();
          return (r.left+r.width/2)+','+(r.top+r.height/2);
        })()
    """.trimIndent()
    view.evaluateJavascript(script) { answer ->
        val point = answer?.trim('"')?.split(',')?.mapNotNull { it.toFloatOrNull() }
        if (point == null || point.size != 2) return@evaluateJavascript
        val density = view.resources.displayMetrics.density
        val x = point[0] * density
        val y = point[1] * density
        if (x <= 0 || y <= 0 || x > view.width || y > view.height) return@evaluateJavascript
        val time = android.os.SystemClock.uptimeMillis()
        listOf(android.view.MotionEvent.ACTION_DOWN to time, android.view.MotionEvent.ACTION_UP to time + 60).forEach { (action, at) ->
            val event = android.view.MotionEvent.obtain(time, at, action, x, y, 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            view.dispatchTouchEvent(event)
            event.recycle()
        }
    }
}

private class Catcher(
    start: String,
    private val userAgent: String,
    private val onVideo: (String, Map<String, String>, String) -> Unit,
    private val onBlocked: (WebView, String) -> Unit,
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

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
        if (!request.isForMainFrame) return
        val blocked = error.errorCode in setOf(ERROR_HOST_LOOKUP, ERROR_CONNECT, ERROR_TIMEOUT, ERROR_FAILED_SSL_HANDSHAKE, ERROR_IO)
        if (blocked) onBlocked(view, request.url.toString())
    }

    override fun onPageFinished(view: WebView, url: String?) {
        if (url != null && url.startsWith("http")) page = url
    }
}

/** Shown in place of the browser's white error page, in Couchy's colours. */
private fun blockedPage(host: String) = """
    <html><head><meta name="viewport" content="width=device-width, initial-scale=1"></head>
    <body style="background:#0D0E12;color:#F3F3F6;font-family:sans-serif;padding:28px;line-height:1.5">
    <h2 style="margin-top:40px">Сайт не открылся</h2>
    <p style="color:#A4A8B4">$host блокирует провайдер, а обход не помог. Закройте окно и выберите другой источник,
    или включите свой конфиг обхода в настройках.</p></body></html>
""".trimIndent()
