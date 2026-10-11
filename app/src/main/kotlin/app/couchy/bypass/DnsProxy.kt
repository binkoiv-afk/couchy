package app.couchy.bypass

import android.util.Log
import app.couchy.core.bypass.Doh
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * The light tier of the bypass: a local HTTP proxy that looks names up over HTTPS instead of
 * through the provider's DNS, then connects to the site directly. It starts at once and needs
 * no server, so blocked sites open right away; the tunnel is for sites blocked deeper than DNS.
 */
class DnsProxy {
    private var server: ServerSocket? = null
    private val cache = ConcurrentHashMap<String, Pair<List<InetAddress>, Long>>()

    val running: Boolean get() = server?.isClosed == false

    fun start() {
        if (running) return
        val socket = runCatching { ServerSocket(PORT, 50, InetAddress.getByName("127.0.0.1")) }
            .onFailure { Log.w("Couchy", "DNS proxy did not start", it) }.getOrNull() ?: return
        server = socket
        Thread({
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                Thread({ handle(client) }, "dns-proxy").apply { isDaemon = true }.start()
            }
        }, "dns-proxy-accept").apply { isDaemon = true }.start()
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 30_000
            val input = client.getInputStream()
            val head = readHead(input) ?: return client.close()
            val lines = head.split("\r\n")
            val (host, port) = Doh.target(lines.first()) ?: return answer(client, "400 Bad Request")
            val site = Socket()
            try {
                site.connect(InetSocketAddress(resolve(host).first(), port), 10_000)
            } catch (e: IOException) {
                site.close()
                return answer(client, "502 Bad Gateway")
            }
            if (lines.first().startsWith("CONNECT", ignoreCase = true)) {
                client.getOutputStream().apply { write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray()); flush() }
            } else {
                // Plain http: the request goes on to the site in its usual form, one per connection.
                val rewritten = buildString {
                    append(Doh.originForm(lines.first())).append("\r\n")
                    lines.drop(1).filter { it.isNotEmpty() && !it.startsWith("Proxy-", ignoreCase = true) && !it.startsWith("Connection:", ignoreCase = true) }
                        .forEach { append(it).append("\r\n") }
                    append("Connection: close\r\n\r\n")
                }
                site.getOutputStream().apply { write(rewritten.toByteArray()); flush() }
            }
            client.soTimeout = 0
            site.soTimeout = 0
            val back = Thread({ pipe(site.getInputStream(), client.getOutputStream(), client, site) }, "dns-proxy-back").apply { isDaemon = true }
            back.start()
            pipe(input, site.getOutputStream(), client, site)
        } catch (e: Exception) {
            runCatching { client.close() }
        }
    }

    /** The real addresses of [host], from the HTTPS resolvers; cached for a while. */
    fun resolve(host: String): List<InetAddress> {
        if (host.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))) return listOf(InetAddress.getByName(host))
        cache[host]?.let { (addresses, until) -> if (until > System.currentTimeMillis()) return addresses }
        for (server in Doh.servers) {
            val found = runCatching {
                val c = URL(server + host).openConnection(Proxy.NO_PROXY) as HttpURLConnection
                try {
                    c.connectTimeout = 5_000
                    c.readTimeout = 5_000
                    c.setRequestProperty("Accept", "application/dns-json")
                    Doh.parse(c.inputStream.bufferedReader().use { it.readText() })
                } finally {
                    c.disconnect()
                }
            }.getOrNull().orEmpty()
            if (found.isNotEmpty()) {
                val addresses = found.map { InetAddress.getByName(it) }
                cache[host] = addresses to System.currentTimeMillis() + 10 * 60_000L
                return addresses
            }
        }
        // The resolvers are unreachable too: the system's answer is the last hope.
        return InetAddress.getAllByName(host).toList()
    }

    private fun readHead(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        var last4 = 0
        while (buffer.size() < 64_000) {
            val b = input.read()
            if (b < 0) return null
            buffer.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) return buffer.toString(Charsets.ISO_8859_1.name())
        }
        return null
    }

    private fun answer(client: Socket, status: String) {
        runCatching {
            client.getOutputStream().apply { write("HTTP/1.1 $status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); flush() }
        }
        runCatching { client.close() }
    }

    private fun pipe(from: InputStream, to: OutputStream, a: Socket, b: Socket) {
        val chunk = ByteArray(32 * 1024)
        try {
            while (true) {
                val n = from.read(chunk)
                if (n < 0) break
                to.write(chunk, 0, n)
                to.flush()
            }
        } catch (e: IOException) {
            // One side closed.
        } finally {
            runCatching { a.close() }
            runCatching { b.close() }
        }
    }

    companion object {
        const val PORT = 18092
    }
}
