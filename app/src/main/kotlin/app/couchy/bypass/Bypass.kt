package app.couchy.bypass

import android.content.Context
import android.os.Looper
import android.util.Log
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import app.couchy.core.bypass.BypassKind
import app.couchy.core.bypass.BypassMode
import app.couchy.core.bypass.BypassRules
import app.couchy.core.bypass.TunnelConfig
import app.couchy.core.bypass.Warp
import app.couchy.core.bypass.WarpAccount
import app.couchy.core.bypass.X25519
import app.couchy.data.Prefs
import app.couchy.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.URL
import java.util.concurrent.Executor

sealed interface BypassStatus {
    /** Not running: nothing has needed it yet, or it is switched off. */
    data object Idle : BypassStatus
    data object Starting : BypassStatus
    data class Ready(val via: String) : BypassStatus
    data class Failed(val message: String) : BypassStatus
}

/**
 * The built-in block bypass. A WireGuard / AmneziaWG engine (shipped as a native library)
 * runs only while some site needs it and serves a local HTTP proxy. Every connection in
 * the app asks [selector] which way to go: blocked sites through the tunnel, the rest
 * directly. In Auto mode Couchy learns blocked sites by itself.
 */
class Bypass(private val context: Context, private val settings: Settings) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var process: Process? = null
    @Volatile private var prefs = Prefs()
    /** The config the running engine was started with; a change in settings restarts it. */
    private var runningConfig: String? = null
    private var portIndex = 0

    private val _status = MutableStateFlow<BypassStatus>(BypassStatus.Idle)
    val status: StateFlow<BypassStatus> = _status.asStateFlow()

    private val binary: File get() = File(context.applicationInfo.nativeLibraryDir, "libwireproxy.so")
    /** Xray, for servers shared as vless://, vmess://, trojan:// or ss:// links. */
    private val xray: File get() = File(context.applicationInfo.nativeLibraryDir, "libxray.so")
    val available: Boolean get() = binary.exists()
    val ready: Boolean get() = _status.value is BypassStatus.Ready

    private val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", HTTP_PORT))

    /** The light tier, always on unless the bypass is off: names looked up over HTTPS. */
    private val dns = DnsProxy()
    private val dnsProxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", DnsProxy.PORT))

    /** When the tunnel last failed: it is not retried for a while, so nothing waits on it again and again. */
    @Volatile private var failedAt = 0L
    @Volatile private var failure: String? = null

    fun install() {
        ProxySelector.setDefault(selector)
        scope.launch {
            settings.prefs.collect { p ->
                val old = prefs
                prefs = p
                if (p.bypassMode == BypassMode.OFF) dns.stop() else dns.start()
                if (old.bypassKind != p.bypassKind || old.bypassConfig != p.bypassConfig) failedAt = 0
                when {
                    p.bypassMode == BypassMode.OFF -> stop()
                    process != null && (old.bypassKind != p.bypassKind || old.bypassConfig != p.bypassConfig) -> {
                        stop()
                        if (p.bypassMode == BypassMode.ALWAYS) runCatching { ensure() }
                    }
                }
                applyWebView()
            }
        }
    }

    /** Video servers that played badly through the tunnel: straight to them until Couchy restarts. */
    private val direct = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Sends [hosts] past the tunnel; for video that stalls through WARP but plays directly. */
    fun goDirect(hosts: Collection<String>) {
        hosts.forEach { h -> direct += BypassRules.domainOf(h) }
        Log.i("Couchy", "Bypass: direct for $hosts")
    }

    fun undoDirect(hosts: Collection<String>) { hosts.forEach { h -> direct -= BypassRules.domainOf(h) } }

    /** Whether [host] goes through the tunnel or the DNS tier right now. */
    fun tunnels(host: String?): Boolean = wants(host) && ((ready && process?.isAliveCompat() == true) || dns.running)

    /** Whether a request to [host] should go through the tunnel under the current settings. */
    fun wants(host: String?): Boolean {
        if (host == null || BypassRules.isLocal(host) || !available) return false
        if (direct.isNotEmpty() && BypassRules.domainOf(host) in direct) return false
        return when (prefs.bypassMode) {
            BypassMode.OFF -> false
            BypassMode.ALWAYS -> true
            BypassMode.AUTO -> BypassRules.matches(host, BypassRules.preset) || BypassRules.matches(host, prefs.bypassHosts)
        }
    }

    /** True when Auto mode may try a failed site again through the tunnel. */
    fun canLearn(host: String?): Boolean =
        host != null && available && prefs.bypassMode == BypassMode.AUTO && !BypassRules.isLocal(host) && !wants(host)

    /** Remembers that [host] is blocked; later requests to its site go through the tunnel. */
    suspend fun learn(host: String) {
        val domain = BypassRules.domainOf(host)
        Log.i("Couchy", "Bypass learned $domain")
        prefs = prefs.copy(bypassHosts = prefs.bypassHosts + domain)
        settings.addBypassHost(domain)
        applyWebView()
    }

    /** Starts the tunnel if [url]'s site needs it, so the request that follows can use it. */
    fun prepare(url: String) {
        // Blocked sites go through the DNS tier at once; the tunnel comes up in the background and takes over.
        if (wants(hostOf(url)) && !ready && !backingOff()) scope.launch { runCatching { ensure() } }
    }

    private fun backingOff() = failedAt > 0 && System.currentTimeMillis() - failedAt < BACKOFF_MS

    /** Starts the engine (registering a WARP device first if needed) and waits until the tunnel carries traffic. */
    suspend fun ensure(force: Boolean = false): Unit = lock.withLock {
        if (ready && process?.isAliveCompat() == true) return
        if (!available) throw IOException("Обход не работает на этом процессоре.")
        if (!force && backingOff()) throw IOException(failure ?: "Туннель не подключился")
        _status.value = BypassStatus.Starting
        try {
            val p = prefs
            val tries = if (p.bypassKind == BypassKind.WARP) WARP_PORT_TRIES else 1
            var last: Exception? = null
            repeat(tries) {
                val config = withContext(Dispatchers.IO) { config(p) }
                try {
                    start(config)
                    if (waitReady()) {
                        failedAt = 0
                        _status.value = BypassStatus.Ready(if (p.bypassKind == BypassKind.WARP) "WARP" else "свой сервер")
                        applyWebView()
                        return
                    }
                    last = IOException("Туннель не отвечает")
                } catch (e: IOException) {
                    last = e
                }
                // Filters often drop one WARP port but not another.
                portIndex++
            }
            throw last ?: IOException("Туннель не отвечает")
        } catch (e: Exception) {
            stopProcess()
            val message = when (prefs.bypassKind) {
                BypassKind.WARP -> "WARP не подключился. Возможно, его блокирует провайдер; попробуйте свой конфиг."
                BypassKind.OWN -> e.message?.takeIf { it != "Туннель не отвечает" } ?: "Свой сервер не подключился. Проверьте конфиг или ссылку и сам сервер."
            }
            failedAt = System.currentTimeMillis()
            failure = message
            _status.value = BypassStatus.Failed(message)
            applyWebView()
            throw IOException(message, e)
        }
    }

    /** Checks the tunnel end to end and says where traffic comes out. */
    suspend fun check(): String {
        try {
            ensure(force = true)
        } catch (e: IOException) {
            if (!dns.running) throw e
            throw IOException(
                (e.message ?: "Туннель не подключился.") + " Работает лёгкий обход: сайты, закрытые только через DNS, откроются.",
                e,
            )
        }
        val text = withContext(Dispatchers.IO) {
            val c = URL("https://www.cloudflare.com/cdn-cgi/trace").openConnection(proxy) as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 10_000
            try {
                c.inputStream.bufferedReader().use { it.readText() }
            } finally {
                c.disconnect()
            }
        }
        return "Работает: " + (BypassRules.traceSummary(text) ?: "ответ получен")
    }

    /** Stops the engine when Couchy closes or the bypass is switched off. */
    fun stop() {
        stopProcess()
        _status.value = BypassStatus.Idle
        applyWebView()
    }

    private fun stopProcess() {
        process?.destroy()
        process = null
        runningConfig = null
    }

    private suspend fun config(p: Prefs): String = when (p.bypassKind) {
        BypassKind.WARP -> {
            val account = WarpAccount.fromJson(p.warpAccount) ?: register()
            val port = account.ports.getOrElse(portIndex % account.ports.size.coerceAtLeast(1)) { 2408 }
            TunnelConfig.withProxy(Warp.config(account, port), HTTP_PORT)
        }
        BypassKind.OWN -> {
            TunnelConfig.problem(p.bypassConfig)?.let { throw IOException(it) }
            if (app.couchy.core.bypass.ProxyLink.isLink(p.bypassConfig)) {
                if (!xray.exists()) throw IOException("Ссылки vless и другие пока не работают на этом процессоре.")
                // Like WireGuard's endpoint, the server's name is looked up here: the engine cannot on Android.
                val host = app.couchy.core.bypass.ProxyLink.host(p.bypassConfig)
                val ip = host?.takeIf { h -> !h.all { it.isDigit() || it == '.' } }?.let { h ->
                    runCatching { InetAddress.getAllByName(h).first { it is java.net.Inet4Address }.hostAddress }.getOrNull()
                        ?: throw IOException("Не нашёл адрес сервера $h")
                }
                app.couchy.core.bypass.ProxyLink.config(p.bypassConfig, HTTP_PORT, ip)
            } else {
                TunnelConfig.withProxy(resolveEndpoint(p.bypassConfig), HTTP_PORT)
            }
        }
    }

    /** A new WARP device, made like the official app does: our key pair, Cloudflare's addresses. */
    private suspend fun register(): WarpAccount = withContext(Dispatchers.IO) {
        val key = X25519.privateKey()
        val c = (URL(Warp.REG_URL).openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 15_000
            Warp.regHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            c.outputStream.use { it.write(Warp.regBody(X25519.publicKey(key)).toByteArray()) }
            if (c.responseCode !in 200..299) throw IOException("Cloudflare ответил ${c.responseCode}")
            val account = Warp.parseReg(c.inputStream.bufferedReader().use { it.readText() }, key)
            settings.setWarpAccount(account.toJson())
            prefs = prefs.copy(warpAccount = account.toJson())
            account
        } catch (e: IOException) {
            throw IOException("Не удалось получить ключи WARP: ${e.message}", e)
        } finally {
            c.disconnect()
        }
    }

    /** The engine cannot look up names on Android, so the server's name is turned into its address here. */
    private fun resolveEndpoint(text: String): String = Regex("(?im)^(\\s*Endpoint\\s*=\\s*)([^:\\s\\[]+):(\\d+)").replace(text) { m ->
        val host = m.groupValues[2]
        val ip = if (host.all { it.isDigit() || it == '.' }) host
        else runCatching { InetAddress.getAllByName(host).first { it is java.net.Inet4Address }.hostAddress }.getOrNull()
            ?: throw IOException("Не нашёл адрес сервера $host")
        m.groupValues[1] + ip + ":" + m.groupValues[3]
    }

    private fun start(config: String) {
        if (runningConfig == config && process?.isAliveCompat() == true) return
        stopProcess()
        val dir = File(context.filesDir, "bypass").apply { mkdirs() }
        // A JSON config is Xray's; the rest is WireGuard's.
        val json = config.trimStart().startsWith("{")
        val file = File(dir, if (json) "tunnel.json" else "tunnel.conf").apply { writeText(config) }
        val command = if (json) listOf(xray.absolutePath, "run", "-c", file.absolutePath)
        else listOf(binary.absolutePath, "-c", file.absolutePath, "-i", "127.0.0.1:$INFO_PORT")
        process = ProcessBuilder(command)
            .directory(dir)
            .redirectErrorStream(true)
            .start()
            .also { p ->
                // Keeps the last lines of the engine's output for the log; the pipe must be drained anyway.
                Thread({
                    runCatching {
                        p.inputStream.bufferedReader().forEachLine { Log.d("CouchyBypass", it) }
                    }
                }, "bypass-out").apply { isDaemon = true }.start()
            }
        runningConfig = config
        Log.i("Couchy", "Bypass engine started")
    }

    /** Ready once a small request goes through the tunnel and back. */
    private suspend fun waitReady(): Boolean {
        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            delay(700)
            if (process?.isAliveCompat() != true) return false
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val c = URL("http://cp.cloudflare.com/generate_204").openConnection(proxy) as HttpURLConnection
                    c.connectTimeout = 3_000
                    c.readTimeout = 4_000
                    try {
                        c.responseCode in 200..399
                    } finally {
                        c.disconnect()
                    }
                }.getOrDefault(false)
            }
            if (ok) return true
        }
        return false
    }

    /** Browser windows: only blocked sites go through the tunnel when the WebView can do that, otherwise everything does. */
    private fun applyWebView() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) return
        val p = prefs
        scope.launch(Dispatchers.Main) {
            runCatching {
                val controller = ProxyController.getInstance()
                if (p.bypassMode == BypassMode.OFF || (!ready && !dns.running)) {
                    controller.clearProxyOverride(Direct, {})
                    return@runCatching
                }
                val port = if (ready) HTTP_PORT else DnsProxy.PORT
                val builder = ProxyConfig.Builder().addProxyRule("http://127.0.0.1:$port").bypassSimpleHostnames()
                if (p.bypassMode == BypassMode.AUTO) {
                    if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE_REVERSE_BYPASS)) {
                        controller.clearProxyOverride(Direct, {})
                        return@runCatching
                    }
                    builder.setReverseBypassEnabled(true)
                    (BypassRules.preset + p.bypassHosts).forEach { d -> builder.addBypassRule(d); builder.addBypassRule("*.$d") }
                } else {
                    listOf("localhost", "127.0.0.1", "10.*", "192.168.*").forEach(builder::addBypassRule)
                }
                controller.setProxyOverride(builder.build(), Direct, {})
            }.onFailure { Log.w("Couchy", "WebView proxy", it) }
        }
    }

    /** Which way each connection goes; asked by HttpURLConnection, OkHttp and the player. */
    val selector = object : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> {
            val host = uri?.host
            if (!wants(host)) return listOf(Proxy.NO_PROXY)
            // Nothing waits here: the tunnel when it is up, the DNS tier until then.
            return when {
                ready && process?.isAliveCompat() == true -> listOf(proxy)
                dns.running -> listOf(dnsProxy)
                else -> listOf(Proxy.NO_PROXY)
            }
        }

        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
            Log.w("Couchy", "Bypass connect failed for ${uri?.host}: ${ioe?.message}")
        }
    }

    private object Direct : Executor {
        override fun execute(command: Runnable) = command.run()
    }

    private fun Process.isAliveCompat(): Boolean = try {
        exitValue(); false
    } catch (e: IllegalThreadStateException) {
        true
    }

    companion object {
        const val HTTP_PORT = 18090
        const val INFO_PORT = 18091
        private const val READY_TIMEOUT_MS = 12_000L
        private const val WARP_PORT_TRIES = 3
        private const val BACKOFF_MS = 3 * 60_000L

        fun hostOf(url: String): String? = runCatching { URI(url).host }.getOrNull()

        /** Errors that look like a provider blocking the site rather than the site being down. */
        fun looksBlocked(e: Throwable): Boolean = e is java.net.UnknownHostException || e is java.net.ConnectException ||
            e is java.net.SocketTimeoutException || e is javax.net.ssl.SSLException ||
            (e is java.net.SocketException && e.message?.contains("reset", ignoreCase = true) == true) ||
            // The DNS tier could not reach the site either.
            e.message?.contains("Unable to tunnel", ignoreCase = true) == true
    }
}
