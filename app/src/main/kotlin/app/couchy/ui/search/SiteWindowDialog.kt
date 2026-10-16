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
import app.couchy.ui.components.PrimaryButton
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
    // A series needs the person to pick the episode in the site's player, so it stays in view.
    var hidden by remember { mutableStateOf(window.key != null && !window.series) }
    // Once the person asked to see the site, it is not hidden again behind their back.
    var shownByPerson by remember { mutableStateOf(false) }
    // The site is shown only when the person asks for it, never on its own.
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(web, hidden, attempt) {
        val view = web ?: return@LaunchedEffect
        if (!hidden) return@LaunchedEffect
        failed = false
        delay(2_500)
        repeat(10) {
            pressPlay(view)
            delay(2_500)
        }
        failed = true
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
                        if (window.series) "Выберите сезон и серию в плеере сайта и нажмите ▶. Дальше Couchy покажет видео в своём плеере, без рекламы."
                        else if (window.key == null) "Откройте фильм и нажмите ▶ в плеере сайта. Дальше Couchy покажет его в своём плеере, без рекламы."
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
                        webViewClient = Catcher(window.url, userAgent, onVideo, onBlocked, series = window.series, onPlayerPage = { if (!hidden && !shownByPerson && !window.series) hidden = true })
                        loadUrl(window.url)
                        web = this
                    }
                },
                onRelease = { v ->
                    v.stopLoading()
                    v.loadUrl("about:blank")
                    v.destroy()
                },
                update = { v ->
                    // Hidden for real, not only covered: a playing video draws on its own layer and could show through.
                    v.alpha = if (hidden) 0f else 1f
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (hidden) {
                Column(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                ) {
                    if (!failed) CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Text(
                        if (failed) "Видео не запустилось" else "Запускаю видео…",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        if (failed) "Плеер этого сайта не отдал видео. Выберите другой источник или откройте сайт и нажмите ▶ сами."
                        else "Couchy сам включает плеер сайта и забирает видео в свой плеер.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (failed) PrimaryButton("Другой источник", null, onClick = onDismiss)
                        if (failed) SecondaryButton("Ещё раз", null, onClick = { web?.reload(); attempt += 1 })
                        SecondaryButton("Показать сайт", null, onClick = { shownByPerson = true; hidden = false })
                    }
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
    private val start: String,
    private val userAgent: String,
    private val onVideo: (String, Map<String, String>, String) -> Unit,
    private val onBlocked: (WebView, String) -> Unit,
    /** A film page with a player opened, from the site's own search: Couchy takes over from here. */
    private val onPlayerPage: () -> Unit = {},
    /** A series: the site's player is opened on its own, full window, so its season and episode menus are in reach. */
    private val series: Boolean = false,
) : WebViewClient() {
    /** Hosts the window may move to: the site and the player it opened. */
    private val allowed = mutableSetOf<String>()
    private var playerOpened = false
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
        // A plain file first is often an advert before the film: wait longer for the stream.
        if (found.size == 1) main.postDelayed({ finish() }, if (Sites.isStream(url)) 1200 else 4000)
        else if (Sites.isStream(url) && found.none { Sites.isStream(it.first) && it.first != url }) main.postDelayed({ finish() }, 1200)
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
        return !(host == startHost || host in allowed || SiteRef.siteOf(url) != null)
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
        if (!request.isForMainFrame) return
        val blocked = error.errorCode in setOf(ERROR_HOST_LOOKUP, ERROR_CONNECT, ERROR_TIMEOUT, ERROR_FAILED_SSL_HANDSHAKE, ERROR_IO)
        if (blocked) onBlocked(view, request.url.toString())
    }

    /**
     * The biggest player frame on the page, opened as the page itself: a phone or a remote reaches
     * its episode list, and nothing of the site around it is left to tap by mistake.
     */
    private fun openPlayer(view: WebView, pageUrl: String) {
        view.evaluateJavascript(
            "(function(){var best='',area=0;document.querySelectorAll('iframe').forEach(function(f){var r=f.getBoundingClientRect(),a=r.width*r.height;" +
                "if(a>area&&r.width>240&&r.height>130&&/^https?:/.test(f.src)){area=a;best=f.src;}});return best;})()",
        ) { answer ->
            val src = answer?.trim('"')?.replace("\\u0026", "&")?.replace("\\/", "/").orEmpty()
            if (playerOpened || done || !src.startsWith("http")) return@evaluateJavascript
            playerOpened = true
            SiteRef.hostOf(src)?.let { allowed += it }
            view.loadUrl(src, mapOf("Referer" to pageUrl))
        }
    }

    override fun onPageFinished(view: WebView, url: String?) {
        if (url != null && url.startsWith("http")) page = url
        // Players often load a moment after the page: asked again a little later.
        if (series && !playerOpened && !done && url != null && url.startsWith("http")) {
            listOf(0L, 2_500L, 6_000L).forEach { wait -> main.postDelayed({ if (!playerOpened) openPlayer(view, url) }, wait) }
        }
        if (url == null || url.substringBefore('#') == start.substringBefore('#') || done) return
        // A page with a big player on it is a film page: hide the site and start the video.
        view.evaluateJavascript(
            "(function(){var e=document.querySelector('video,iframe[allowfullscreen],iframe[src*=player],iframe[src*=embed],[id*=player] iframe');if(!e)return false;var r=e.getBoundingClientRect();return r.width>200&&r.height>100;})()",
        ) { answer -> if (answer == "true") main.post { onPlayerPage() } }
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
