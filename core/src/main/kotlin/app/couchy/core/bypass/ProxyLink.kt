package app.couchy.core.bypass

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.util.Base64

/**
 * A proxy server shared as a link (vless://, vmess://, trojan://, ss://), turned into a config
 * for the Xray engine with Couchy's local HTTP proxy in front of it.
 */
object ProxyLink {
    private val schemes = listOf("vless://", "vmess://", "trojan://", "ss://")

    fun isLink(text: String) = schemes.any { text.trim().startsWith(it, ignoreCase = true) }

    /** The server's host name, to be looked up on the device before the engine starts. */
    fun host(text: String): String? = runCatching { outbound(text.trim()).let { server(it).getString("address") } }.getOrNull()

    /** Problems in words for the person; null when the link can be used. */
    fun problem(text: String): String? {
        val t = text.trim()
        if (!isLink(t)) return "Это не ссылка vless://, vmess://, trojan:// или ss://."
        return runCatching { outbound(t); null }.getOrElse { "Не получилось прочитать ссылку: ${it.message ?: "формат не тот"}" }
    }

    /** The Xray config: HTTP proxy on 127.0.0.1:[httpPort], everything through the server; [ip] replaces the server's name. */
    fun config(text: String, httpPort: Int, ip: String? = null): String {
        val out = outbound(text.trim())
        if (ip != null) server(out).put("address", ip)
        return JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("dns", JSONObject().put("servers", JSONArray().put("1.1.1.1").put("8.8.8.8")))
            .put("inbounds", JSONArray().put(JSONObject().put("listen", "127.0.0.1").put("port", httpPort).put("protocol", "http").put("tag", "in")))
            .put("outbounds", JSONArray().put(out.put("tag", "proxy")))
            .toString(2)
    }

    /** The server object inside an outbound, whatever the protocol keeps it in. */
    private fun server(out: JSONObject): JSONObject {
        val s = out.getJSONObject("settings")
        return (s.optJSONArray("vnext") ?: s.getJSONArray("servers")).getJSONObject(0)
    }

    private fun outbound(link: String): JSONObject = when {
        link.startsWith("vmess://", true) -> vmess(link)
        link.startsWith("ss://", true) -> shadowsocks(link)
        else -> uriBased(link)
    }

    private fun uriBased(link: String): JSONObject {
        val uri = URI(link.substringBefore('#').replace(" ", "%20"))
        val protocol = uri.scheme.lowercase()
        val q = query(uri.rawQuery)
        val user = URLDecoder.decode(uri.rawUserInfo ?: throw IllegalArgumentException("нет ключа"), "UTF-8")
        val host = uri.host ?: throw IllegalArgumentException("нет адреса сервера")
        val port = uri.port.takeIf { it > 0 } ?: 443
        val settings = when (protocol) {
            "vless" -> JSONObject().put("vnext", JSONArray().put(JSONObject().put("address", host).put("port", port).put("users", JSONArray().put(
                JSONObject().put("id", user).put("encryption", q["encryption"] ?: "none").apply { q["flow"]?.takeIf { it.isNotEmpty() }?.let { put("flow", it) } },
            ))))
            "trojan" -> JSONObject().put("servers", JSONArray().put(JSONObject().put("address", host).put("port", port).put("password", user)))
            else -> throw IllegalArgumentException("протокол $protocol не поддерживается")
        }
        return JSONObject().put("protocol", protocol).put("settings", settings)
            .put("streamSettings", stream(q, host, defaultSecurity = if (protocol == "trojan") "tls" else "none"))
    }

    private fun vmess(link: String): JSONObject {
        val o = JSONObject(String(decode(link.substringAfter("://").substringBefore('#'))))
        val host = o.getString("add")
        val port = o.optString("port").toIntOrNull() ?: o.optInt("port", 443)
        val q = mapOf(
            "type" to o.optString("net", "tcp"),
            "security" to o.optString("tls").ifEmpty { "none" },
            "sni" to o.optString("sni"),
            "host" to o.optString("host"),
            "path" to o.optString("path"),
            "serviceName" to o.optString("path"),
            "fp" to o.optString("fp"),
        ).filterValues { it.isNotEmpty() }
        val settings = JSONObject().put("vnext", JSONArray().put(JSONObject().put("address", host).put("port", port).put("users", JSONArray().put(
            JSONObject().put("id", o.getString("id")).put("alterId", o.optString("aid").toIntOrNull() ?: 0).put("security", o.optString("scy").ifEmpty { "auto" }),
        ))))
        return JSONObject().put("protocol", "vmess").put("settings", settings).put("streamSettings", stream(q, host, "none"))
    }

    private fun shadowsocks(link: String): JSONObject {
        val body = link.substringAfter("://").substringBefore('#')
        // ss://base64(method:password)@host:port or ss://base64(method:password@host:port)
        val (credentials, address) = if ('@' in body) {
            val cred = body.substringBeforeLast('@')
            val plain = runCatching { String(decode(cred)) }.getOrNull()?.takeIf { ':' in it } ?: URLDecoder.decode(cred, "UTF-8")
            plain to body.substringAfterLast('@').substringBefore('?').substringBefore('/')
        } else {
            val all = String(decode(body.substringBefore('?')))
            all.substringBeforeLast('@') to all.substringAfterLast('@')
        }
        val method = credentials.substringBefore(':')
        val password = credentials.substringAfter(':')
        val host = address.substringBeforeLast(':').trim('[', ']')
        val port = address.substringAfterLast(':').toInt()
        val settings = JSONObject().put("servers", JSONArray().put(JSONObject().put("address", host).put("port", port).put("method", method).put("password", password)))
        return JSONObject().put("protocol", "shadowsocks").put("settings", settings)
    }

    private fun stream(q: Map<String, String>, host: String, defaultSecurity: String): JSONObject {
        val network = (q["type"] ?: "tcp").let { if (it == "http") "h2" else it }
        val security = q["security"]?.takeIf { it.isNotEmpty() } ?: defaultSecurity
        val s = JSONObject().put("network", network).put("security", security)
        val sni = q["sni"] ?: q["peer"] ?: q["host"]?.takeIf { network == "ws" } ?: host
        when (security) {
            "tls" -> s.put("tlsSettings", JSONObject().put("serverName", sni).put("fingerprint", q["fp"] ?: "chrome").apply {
                q["alpn"]?.let { put("alpn", JSONArray(it.split(','))) }
                if (q["allowInsecure"] == "1" || q["insecure"] == "1") put("allowInsecure", true)
            })
            "reality" -> s.put("realitySettings", JSONObject()
                .put("serverName", sni)
                .put("fingerprint", q["fp"] ?: "chrome")
                .put("publicKey", q["pbk"] ?: throw IllegalArgumentException("нет pbk для REALITY"))
                .put("shortId", q["sid"].orEmpty())
                .put("spiderX", q["spx"].orEmpty()))
        }
        when (network) {
            "ws" -> s.put("wsSettings", JSONObject().put("path", q["path"] ?: "/").apply { q["host"]?.let { put("headers", JSONObject().put("Host", it)) } })
            "grpc" -> s.put("grpcSettings", JSONObject().put("serviceName", q["serviceName"].orEmpty()).put("multiMode", q["mode"] == "multi"))
            "httpupgrade" -> s.put("httpupgradeSettings", JSONObject().put("path", q["path"] ?: "/").apply { q["host"]?.let { put("host", it) } })
            "xhttp", "splithttp" -> s.put("xhttpSettings", JSONObject().put("path", q["path"] ?: "/").apply { q["host"]?.let { put("host", it) }; q["mode"]?.let { put("mode", it) } })
            "h2" -> s.put("httpSettings", JSONObject().put("path", q["path"] ?: "/").apply { q["host"]?.let { put("host", JSONArray(it.split(','))) } })
        }
        return s
    }

    private fun query(raw: String?): Map<String, String> = raw.orEmpty().split('&').filter { '=' in it }
        .associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }

    private fun decode(text: String): ByteArray {
        val t = text.trim().replace('-', '+').replace('_', '/')
        return Base64.getDecoder().decode(t + "=".repeat((4 - t.length % 4) % 4))
    }
}
