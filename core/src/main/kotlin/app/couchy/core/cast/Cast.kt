package app.couchy.core.cast

import org.json.JSONObject

/** "Watch on TV": what one Couchy sends another in the home network. */
data class CastRequest(
    /** Library key: a link, a torrent file reference or an online source reference. */
    val link: String,
    val title: String,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** For streams that need them (IPTV), sent with a plain link. */
    val headers: Map<String, String> = emptyMap(),
    /** Name of the sending device, shown on the TV. */
    val from: String = "",
) {
    fun toJson(): String = JSONObject()
        .put("link", link).put("title", title).put("positionMs", positionMs).put("durationMs", durationMs)
        .put("headers", JSONObject(headers)).put("from", from)
        .toString()

    companion object {
        fun fromJson(text: String): CastRequest? = runCatching {
            val o = JSONObject(text)
            val h = o.optJSONObject("headers")
            CastRequest(
                link = o.getString("link").also { require(it.isNotBlank()) },
                title = o.optString("title"),
                positionMs = o.optLong("positionMs"),
                durationMs = o.optLong("durationMs"),
                headers = h?.keys()?.asSequence()?.associateWith { h.getString(it) }.orEmpty(),
                from = o.optString("from"),
            )
        }.getOrNull()

        /** Links that only mean something on the device they came from. */
        fun isLocalOnly(link: String): Boolean {
            val l = link.lowercase()
            return l.startsWith("content:") || l.startsWith("file:") || l.startsWith("/") ||
                Regex("^https?://(127\\.|localhost)").containsMatchIn(l)
        }
    }
}

/** The tiny HTTP the devices speak: one request line, headers, a JSON body. */
object CastHttp {
    const val SERVICE_TYPE = "_couchy._tcp."
    const val PORT = 18300

    data class Request(val method: String, val path: String, val body: String)

    /** Reads a request from raw bytes as far as they go; null while incomplete or malformed. */
    fun parse(raw: ByteArray): Request? {
        val text = String(raw, Charsets.UTF_8)
        val headEnd = text.indexOf("\r\n\r\n").takeIf { it >= 0 } ?: return null
        val lines = text.substring(0, headEnd).split("\r\n")
        val parts = lines.first().split(' ')
        if (parts.size < 2) return null
        val length = lines.drop(1).firstOrNull { it.startsWith("content-length:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
        val bodyStart = text.substring(0, headEnd + 4).toByteArray(Charsets.UTF_8).size
        if (raw.size - bodyStart < length) return null
        return Request(parts[0], parts[1], String(raw, bodyStart, length, Charsets.UTF_8))
    }

    fun response(code: Int, body: String): ByteArray {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = if (code == 200) "OK" else "Error"
        return ("HTTP/1.1 $code $reason\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n")
            .toByteArray() + bytes
    }
}

/** What the TV is playing, for the phone acting as its remote. */
data class RemoteState(
    val title: String,
    val positionMs: Long,
    val durationMs: Long,
    val playing: Boolean,
    val hasNext: Boolean,
) {
    fun toJson(): String = JSONObject()
        .put("title", title).put("position", positionMs).put("duration", durationMs)
        .put("playing", playing).put("next", hasNext).toString()

    companion object {
        fun fromJson(text: String): RemoteState? = runCatching {
            val o = JSONObject(text)
            if (!o.has("title")) return null
            RemoteState(o.getString("title"), o.optLong("position"), o.optLong("duration"), o.optBoolean("playing"), o.optBoolean("next"))
        }.getOrNull()
    }
}

/** Buttons of the phone remote, sent as POST /control {"action": …, "value": …}. */
object RemoteAction {
    const val TOGGLE = "toggle"
    const val SEEK_BY = "seekBy"
    const val SEEK_TO = "seekTo"
    const val NEXT = "next"
    const val STOP = "stop"
    const val VOLUME = "volume"
    /** A spoken command heard by the phone, carried as text: "включи субтитры". */
    const val SAY = "say"

    fun json(action: String, value: Long = 0, text: String = ""): String =
        JSONObject().put("action", action).put("value", value).put("text", text).toString()
    fun text(body: String): String = runCatching { JSONObject(body).optString("text") }.getOrDefault("")
    fun parse(text: String): Pair<String, Long>? = runCatching { JSONObject(text).let { it.getString("action") to it.optLong("value") } }.getOrNull()
}
