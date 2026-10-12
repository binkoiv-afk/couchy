package app.couchy.cast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.provider.Settings
import android.util.Log
import app.couchy.core.cast.CastHttp
import app.couchy.core.cast.CastRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.ConcurrentLinkedQueue

/** Another Couchy in the home network. */
data class CastDevice(val name: String, val host: String, val port: Int)

/**
 * "Watch on TV". Every running Couchy listens on the home network and announces itself;
 * a phone finds the TV box by name and hands it what to play, with the position to start from.
 */
class Cast(private val context: Context) {
    private val nsd: NsdManager? = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private var server: ServerSocket? = null
    private var registration: NsdManager.RegistrationListener? = null
    @Volatile private var registeredName: String? = null

    private val _incoming = MutableSharedFlow<CastRequest>(extraBufferCapacity = 4)
    /** Requests from other devices, to be played here. */
    val incoming: SharedFlow<CastRequest> = _incoming.asSharedFlow()

    /** This device's name as other devices see it. */
    val deviceName: String by lazy {
        runCatching { Settings.Global.getString(context.contentResolver, "device_name") }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: runCatching { Settings.Secure.getString(context.contentResolver, "bluetooth_name") }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: listOf(Build.MANUFACTURER.replaceFirstChar { it.uppercase() }, Build.MODEL).distinct().joinToString(" ")
    }

    /** Starts listening and announcing; safe to call again. */
    fun start() {
        if (server != null) return
        val socket = runCatching { ServerSocket(CastHttp.PORT) }.getOrElse { runCatching { ServerSocket(0) }.getOrNull() } ?: return
        server = socket
        Thread({ accept(socket) }, "cast-server").apply { isDaemon = true }.start()
        announce(socket.localPort)
    }

    fun stop() {
        registration?.let { r -> runCatching { nsd?.unregisterService(r) } }
        registration = null
        runCatching { server?.close() }
        server = null
    }

    private fun accept(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            Thread({ handle(client) }, "cast-client").apply { isDaemon = true }.start()
        }
    }

    private fun handle(client: Socket) = client.use { c ->
        c.soTimeout = 5_000
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var request: CastHttp.Request? = null
        runCatching {
            while (buffer.size() < 256_000) {
                val n = c.getInputStream().read(chunk)
                if (n < 0) break
                buffer.write(chunk, 0, n)
                request = CastHttp.parse(buffer.toByteArray())
                if (request != null) break
            }
        }
        val r = request
        val answer = when {
            r == null -> CastHttp.response(400, "{}")
            r.method == "GET" && r.path == "/hello" -> CastHttp.response(200, "{\"name\":\"${deviceName.replace("\"", "")}\"}")
            // The phone remote: what plays here, and its buttons.
            r.method == "GET" && r.path == "/state" -> CastHttp.response(200, RemoteBridge.state?.toJson() ?: "{}")
            r.method == "POST" && r.path == "/control" -> {
                val command = app.couchy.core.cast.RemoteAction.parse(r.body)
                val handler = RemoteBridge.handler
                val say = RemoteBridge.say
                if (command?.first == app.couchy.core.cast.RemoteAction.SAY && say != null) {
                    val text = app.couchy.core.cast.RemoteAction.text(r.body)
                    android.os.Handler(android.os.Looper.getMainLooper()).post { say(text) }
                    CastHttp.response(200, "{}")
                } else if (command != null && handler != null) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post { handler(command.first, command.second) }
                    CastHttp.response(200, "{}")
                } else CastHttp.response(409, "{}")
            }
            r.method == "POST" && r.path == "/play" -> {
                val cast = CastRequest.fromJson(r.body)
                if (cast != null && _incoming.tryEmit(cast)) CastHttp.response(200, "{}") else CastHttp.response(400, "{}")
            }
            else -> CastHttp.response(404, "{}")
        }
        runCatching { c.getOutputStream().apply { write(answer); flush() } }
    }

    private fun announce(port: Int) {
        val manager = nsd ?: return
        val info = NsdServiceInfo().apply {
            serviceName = "Couchy · $deviceName"
            serviceType = CastHttp.SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) { registeredName = info.serviceName }
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) { Log.w("Couchy", "Cast announce failed $code") }
            override fun onServiceUnregistered(info: NsdServiceInfo) { registeredName = null }
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) = Unit
        }
        registration = listener
        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    /** Other Couchy devices in the network, updated as they appear and leave. */
    fun discover(): Flow<List<CastDevice>> = callbackFlow {
        val manager = nsd ?: run {
            trySend(emptyList())
            awaitClose {}
            return@callbackFlow
        }
        val found = linkedMapOf<String, CastDevice>()
        // Older Android resolves one service at a time.
        val queue = ConcurrentLinkedQueue<NsdServiceInfo>()
        var resolving = false
        fun next() {
            if (resolving) return
            val info = queue.poll() ?: return
            resolving = true
            @Suppress("DEPRECATION")
            manager.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, code: Int) {
                    resolving = false
                    next()
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    resolving = false
                    @Suppress("DEPRECATION")
                    val host = info.host?.hostAddress
                    if (host != null && info.serviceName != registeredName) {
                        found[info.serviceName] = CastDevice(info.serviceName.removePrefix("Couchy · "), host, info.port)
                        trySend(found.values.toList())
                    }
                    next()
                }
            })
        }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, code: Int) { trySend(emptyList()) }
            override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceName == registeredName) return
                synchronized(queue) { queue.add(info); next() }
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                if (found.remove(info.serviceName) != null) trySend(found.values.toList())
            }
        }
        trySend(emptyList())
        runCatching { manager.discoverServices(CastHttp.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
        awaitClose { runCatching { manager.stopServiceDiscovery(listener) } }
    }

    /** Hands [request] to [device]; throws with a message for the person when it does not answer. */
    suspend fun send(device: CastDevice, request: CastRequest) = withContext(Dispatchers.IO) {
        val c = URL("http://${device.host}:${device.port}/play").openConnection(Proxy.NO_PROXY) as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 5_000
            c.readTimeout = 5_000
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use { it.write(request.copy(from = deviceName).toJson().toByteArray()) }
            if (c.responseCode != 200) throw IOException("${device.name} не принял видео.")
        } catch (e: IOException) {
            throw IOException("${device.name} не отвечает. Проверьте, что Couchy на нём открыт.", e)
        } finally {
            c.disconnect()
        }
    }

    /** What [device] is playing; null when nothing or it does not answer. */
    suspend fun state(device: CastDevice): app.couchy.core.cast.RemoteState? = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL("http://${device.host}:${device.port}/state").openConnection(Proxy.NO_PROXY) as HttpURLConnection
            try {
                c.connectTimeout = 3_000
                c.readTimeout = 3_000
                app.couchy.core.cast.RemoteState.fromJson(c.inputStream.bufferedReader().use { it.readText() })
            } finally {
                c.disconnect()
            }
        }.getOrNull()
    }

    /** Presses a remote button on [device]; false when it did not take it. */
    suspend fun control(device: CastDevice, action: String, value: Long = 0, text: String = ""): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL("http://${device.host}:${device.port}/control").openConnection(Proxy.NO_PROXY) as HttpURLConnection
            try {
                c.requestMethod = "POST"
                c.doOutput = true
                c.connectTimeout = 3_000
                c.readTimeout = 3_000
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(app.couchy.core.cast.RemoteAction.json(action, value, text).toByteArray()) }
                c.responseCode == 200
            } finally {
                c.disconnect()
            }
        }.getOrDefault(false)
    }

    /** This device's address in the home network, to type on a phone that does not see it. */
    fun address(): String? = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is java.net.Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()?.let { ip -> if ((server?.localPort ?: CastHttp.PORT) == CastHttp.PORT) ip else "$ip:${server?.localPort}" }

    /** A device typed by address, for networks where devices cannot see each other's announcements. */
    fun manual(address: String): CastDevice? {
        val a = address.trim().removePrefix("http://").trimEnd('/')
        if (a.isEmpty()) return null
        val host = a.substringBefore(':')
        val port = a.substringAfter(':', "").toIntOrNull() ?: CastHttp.PORT
        return CastDevice(a, host, port)
    }
}

/** The player on this device, as the phone remote sees and presses it. Set while a video plays. */
object RemoteBridge {
    @Volatile var state: app.couchy.core.cast.RemoteState? = null
    @Volatile var handler: ((action: String, value: Long) -> Unit)? = null
    /** Spoken commands from the phone, as text. */
    @Volatile var say: ((String) -> Unit)? = null
}
