package app.couchy.sources

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Thrown when a site wants its visitor check passed in a browser window first. */
class ChallengeException(val url: String) : IOException("Сайт просит пройти проверку")

/**
 * HTTP for online sources. It shares cookies and the browser identity with the in-app
 * browser window, so once the person passes a site's check there, these requests are
 * the same visitor's.
 */
class Web(private val context: Context) {
    val userAgent: String by lazy {
        runCatching { WebSettings.getDefaultUserAgent(context) }.getOrNull()
            ?: "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
    }

    private val cookies: CookieManager? by lazy { runCatching { CookieManager.getInstance() }.getOrNull() }

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): String = request(url, null, headers)

    suspend fun post(url: String, form: Map<String, String>, headers: Map<String, String> = emptyMap()): String =
        request(url, form.entries.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }, headers + ("Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"))

    private suspend fun request(start: String, body: String?, headers: Map<String, String>): String = withContext(Dispatchers.IO) {
        var url = start
        repeat(6) {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 25_000
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8")
                cookies?.getCookie(url)?.let { setRequestProperty("Cookie", it) }
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
                if (body != null) {
                    requestMethod = "POST"
                    doOutput = true
                }
            }
            try {
                if (body != null) c.outputStream.use { it.write(body.toByteArray()) }
                val code = c.responseCode
                c.headerFields["Set-Cookie"]?.forEach { cookie -> cookies?.setCookie(url, cookie) }
                if (code in 300..399 && body == null) {
                    url = URL(URL(url), c.getHeaderField("Location") ?: throw IOException("Пустое перенаправление")).toString()
                    return@repeat
                }
                val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                // A check in answer to a background request is passed on the site's front page.
                if (app.couchy.core.sources.Rezka.isChallenge(text)) throw ChallengeException(if (body != null) URL(URL(start), "/").toString() else start)
                if (code >= 400) throw IOException("Сайт ответил $code")
                cookies?.flush()
                return@withContext text
            } finally {
                c.disconnect()
            }
        }
        throw IOException("Слишком много перенаправлений")
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
