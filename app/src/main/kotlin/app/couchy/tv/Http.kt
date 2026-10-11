package app.couchy.tv

import app.couchy.BuildConfig
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/** Minimal HTTP for playlists and guides: follows redirects across http/https and unpacks gzip. */
object Http {
    private const val AGENT = "Couchy/${BuildConfig.VERSION_NAME} (Android)"

    fun <T> open(url: String, block: (InputStream) -> T): T {
        var current = url
        repeat(6) {
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", AGENT)
                setRequestProperty("Accept-Encoding", "gzip")
            }
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    current = URL(URL(current), connection.getHeaderField("Location") ?: throw IOException("Пустое перенаправление")).toString()
                    return@repeat
                }
                if (code != 200) throw IOException("Сервер ответил $code")
                val raw = BufferedInputStream(connection.inputStream, 64 * 1024)
                return raw.use { block(maybeGunzip(it)) }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Слишком много перенаправлений")
    }

    fun text(url: String): String = open(url) { it.bufferedReader(Charsets.UTF_8).readText() }

    /** Guides are often .xml.gz whatever the server says; the gzip magic bytes decide. */
    private fun maybeGunzip(input: BufferedInputStream): InputStream {
        input.mark(2)
        val a = input.read()
        val b = input.read()
        input.reset()
        return if (a == 0x1f && b == 0x8b) GZIPInputStream(input, 64 * 1024) else input
    }
}
