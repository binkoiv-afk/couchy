package app.couchy.torrent

import android.content.Context
import android.util.Log
import app.couchy.data.Prefs
import app.couchy.data.TorrEngineMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Finds a working TorrServer: the built-in one (a TorrServer binary shipped as a native
 * library and started on demand on port 8090) or the external TorrServe from settings.
 * A TorrServe app already running on this device answers on the same port and is reused.
 */
class TorrEngine(private val context: Context) {
    private val lock = Mutex()
    private var process: Process? = null

    private val binary: File get() = File(context.applicationInfo.nativeLibraryDir, "libtorrserver.so")

    /** False on processors the shipped binary does not support (x86 emulators). */
    val builtInAvailable: Boolean get() = binary.exists()

    suspend fun server(prefs: Prefs): TorrServer = when (prefs.torrEngine) {
        TorrEngineMode.REMOTE -> {
            val address = normalize(prefs.torrAddress)
                ?: throw IOException("Укажите адрес TorrServe в настройках, например 192.168.1.20:8090.")
            val server = TorrServer(address)
            try {
                server.echo()
            } catch (e: Exception) {
                throw IOException("TorrServe по адресу $address не отвечает. Проверьте, что он запущен и устройства в одной сети.")
            }
            server
        }
        TorrEngineMode.BUILT_IN -> builtIn()
    }

    /** Checks an address typed in settings and returns the TorrServer version it reports. */
    suspend fun check(address: String): String {
        val base = normalize(address) ?: throw IOException("Адрес пустой")
        return TorrServer(base).echo().ifBlank { "TorrServer" }
    }

    private suspend fun builtIn(): TorrServer = lock.withLock {
        val server = TorrServer(LOCAL)
        if (alive(server)) return server
        if (!builtInAvailable) {
            throw IOException("Встроенный движок не работает на этом процессоре. Укажите в настройках адрес TorrServe в сети.")
        }
        withContext(Dispatchers.IO) { start() }
        repeat(40) {
            delay(250)
            if (alive(server)) return server
            if (process?.isAliveCompat() == false) throw IOException("Встроенный движок не запустился. Попробуйте ещё раз или укажите TorrServe в сети.")
        }
        throw IOException("Встроенный движок не отвечает. Попробуйте ещё раз.")
    }

    private fun start() {
        val dir = File(context.filesDir, "torrserver").apply { mkdirs() }
        val log = File(dir, "torrserver.log")
        if (log.length() > 2_000_000) log.delete()
        process?.destroy()
        process = ProcessBuilder(binary.absolutePath, "-p", PORT.toString(), "-d", dir.absolutePath, "-l", log.absolutePath)
            .directory(dir)
            .redirectErrorStream(true)
            .start()
            .also { p ->
                // The pipe must be drained or the engine stalls once its buffer fills.
                Thread({ runCatching { p.inputStream.copyTo(NullStream) } }, "torrserver-out").apply { isDaemon = true }.start()
            }
        Log.i("Couchy", "TorrServer started")
    }

    /** Stops the built-in engine when Couchy closes, so it does not use the network in the background. */
    fun stop() {
        val p = process ?: return
        process = null
        Thread {
            runCatching { java.net.URL("$LOCAL/shutdown").openConnection().apply { connectTimeout = 1_000; readTimeout = 1_000 }.getInputStream().close() }
            runCatching { Thread.sleep(500); p.destroy() }
        }.start()
    }

    private suspend fun alive(server: TorrServer) = runCatching { server.echo().isNotBlank() }.getOrDefault(false)

    private fun Process.isAliveCompat(): Boolean = try {
        exitValue(); false
    } catch (e: IllegalThreadStateException) {
        true
    }

    private object NullStream : java.io.OutputStream() {
        override fun write(b: Int) = Unit
        override fun write(b: ByteArray, off: Int, len: Int) = Unit
    }

    companion object {
        const val PORT = 8090
        const val LOCAL = "http://127.0.0.1:$PORT"

        /** "192.168.1.20" or "192.168.1.20:8090" or a full URL, to "http://host:port". */
        fun normalize(address: String): String? {
            val text = address.trim().trimEnd('/').ifEmpty { return null }
            val withScheme = if ("://" in text) text else "http://$text"
            val hostPart = withScheme.substringAfter("://").substringBefore('/')
            return if (':' in hostPart) withScheme else withScheme.replaceFirst(hostPart, "$hostPart:$PORT")
        }
    }
}
