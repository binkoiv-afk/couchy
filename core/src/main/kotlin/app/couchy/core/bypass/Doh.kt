package app.couchy.core.bypass

import org.json.JSONObject

/**
 * Name lookups over HTTPS. Most blocks in Russia start with the provider's DNS refusing to
 * name the site; asking Cloudflare or Google over HTTPS gets the real address, and often
 * that is all a site needs to open.
 */
object Doh {
    /** Resolvers reached by address, so looking them up cannot be blocked too. */
    val servers = listOf(
        "https://1.1.1.1/dns-query?type=A&name=",
        "https://8.8.8.8/resolve?type=A&name=",
        "https://1.0.0.1/dns-query?type=A&name=",
    )

    /** IPv4 addresses from a JSON DNS answer (Cloudflare and Google share the format). */
    fun parse(json: String): List<String> {
        val answers = JSONObject(json).optJSONArray("Answer") ?: return emptyList()
        return (0 until answers.length()).mapNotNull { i ->
            val a = answers.optJSONObject(i) ?: return@mapNotNull null
            a.optString("data").takeIf { a.optInt("type") == 1 && ipv4.matches(it) }
        }
    }

    /** Host and port from a proxy request line: "CONNECT host:443 HTTP/1.1" or "GET http://host:8080/path HTTP/1.1". */
    fun target(requestLine: String): Pair<String, Int>? {
        val parts = requestLine.trim().split(' ')
        if (parts.size < 3) return null
        val (method, where) = parts
        if (method.equals("CONNECT", ignoreCase = true)) {
            val host = where.substringBeforeLast(':')
            val port = where.substringAfterLast(':', "443").toIntOrNull() ?: return null
            return host.removePrefix("[").removeSuffix("]") to port
        }
        if (!where.startsWith("http://", ignoreCase = true)) return null
        val authority = where.substring(7).substringBefore('/').substringBefore('?')
        val host = authority.substringBefore(':')
        val port = authority.substringAfter(':', "80").toIntOrNull() ?: return null
        return host to port
    }

    /** The request line rewritten for the site itself: "GET /path HTTP/1.1". */
    fun originForm(requestLine: String): String {
        val parts = requestLine.trim().split(' ')
        val rest = parts[1].substring(7)
        val path = rest.indexOfAny(charArrayOf('/', '?')).let { if (it < 0) "/" else rest.substring(it) }
        return parts[0] + " " + (if (path.startsWith("?")) "/$path" else path) + " " + parts[2]
    }

    private val ipv4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
}
