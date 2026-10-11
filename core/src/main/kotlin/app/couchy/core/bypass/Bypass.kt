package app.couchy.core.bypass

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** When traffic goes through the bypass. */
enum class BypassMode(val label: String) { AUTO("Авто"), ALWAYS("Всегда"), OFF("Выключен") }

/** What carries it: a free Cloudflare WARP account, or the person's own WireGuard / AmneziaWG config. */
enum class BypassKind(val label: String) { WARP("Cloudflare WARP"), OWN("Свой конфиг") }

/** A registered WARP device: our keys plus what Cloudflare assigned. */
data class WarpAccount(
    val privateKey: String,
    val peerPublicKey: String,
    val addressV4: String,
    val addressV6: String?,
    /** Endpoint IP without port. */
    val endpointHost: String,
    val ports: List<Int>,
    val id: String?,
    val token: String?,
) {
    fun toJson(): String = JSONObject()
        .put("privateKey", privateKey).put("peer", peerPublicKey).put("v4", addressV4).put("v6", addressV6 ?: "")
        .put("host", endpointHost).put("ports", ports.joinToString(",")).put("id", id ?: "").put("token", token ?: "")
        .toString()

    companion object {
        fun fromJson(text: String): WarpAccount? = runCatching {
            val o = JSONObject(text)
            WarpAccount(
                o.getString("privateKey"), o.getString("peer"), o.getString("v4"), o.optString("v6").ifEmpty { null },
                o.getString("host"), o.getString("ports").split(',').mapNotNull { it.trim().toIntOrNull() },
                o.optString("id").ifEmpty { null }, o.optString("token").ifEmpty { null },
            )
        }.getOrNull()
    }
}

/** Cloudflare WARP: a free WireGuard tunnel, registered from the app like the official client does. */
object Warp {
    const val REG_URL = "https://api.cloudflareclient.com/v0a2158/reg"
    val regHeaders = mapOf("CF-Client-Version" to "a-6.30-2158", "User-Agent" to "okhttp/3.12.1", "Content-Type" to "application/json")
    private const val DEFAULT_ENDPOINT = "162.159.192.1"
    private val defaultPorts = listOf(2408, 500, 1701, 4500)

    fun regBody(publicKey: String, now: Date = Date()): String {
        val tos = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(now)
        return JSONObject().put("key", publicKey).put("install_id", "").put("fcm_token", "").put("tos", tos)
            .put("model", "PC").put("type", "Android").put("locale", "en_US").toString()
    }

    fun parseReg(json: String, privateKey: String): WarpAccount {
        val root = JSONObject(json)
        val cfg = root.getJSONObject("config")
        val peer = cfg.getJSONArray("peers").getJSONObject(0)
        val endpoint = peer.getJSONObject("endpoint")
        val addresses = cfg.getJSONObject("interface").getJSONObject("addresses")
        val host = endpoint.optString("v4").substringBeforeLast(':').ifEmpty { DEFAULT_ENDPOINT }
        val ports = endpoint.optJSONArray("ports")?.let { a -> (0 until a.length()).map { a.getInt(it) } }?.ifEmpty { null } ?: defaultPorts
        return WarpAccount(
            privateKey, peer.getString("public_key"), addresses.getString("v4"), addresses.optString("v6").ifEmpty { null },
            host, ports, root.optString("id").ifEmpty { null }, root.optString("token").ifEmpty { null },
        )
    }

    /**
     * The tunnel config. Junk packets before each handshake (AmneziaWG) hide it from
     * filters that drop plain WireGuard; WARP's servers ignore them.
     */
    fun config(account: WarpAccount, port: Int = account.ports.firstOrNull() ?: 2408, masked: Boolean = true): String = buildString {
        appendLine("[Interface]")
        appendLine("PrivateKey = ${account.privateKey}")
        appendLine("Address = ${account.addressV4}/32" + (account.addressV6?.let { ", $it/128" } ?: ""))
        appendLine("DNS = 1.1.1.1, 1.0.0.1")
        appendLine("MTU = 1280")
        if (masked) {
            appendLine("Jc = 4")
            appendLine("Jmin = 40")
            appendLine("Jmax = 70")
        }
        appendLine()
        appendLine("[Peer]")
        appendLine("PublicKey = ${account.peerPublicKey}")
        appendLine("AllowedIPs = 0.0.0.0/0, ::/0")
        appendLine("Endpoint = ${account.endpointHost}:$port")
        appendLine("PersistentKeepalive = 25")
    }
}

/** Configs the engine runs: the person's own, cleaned of what only wg-quick understands. */
object TunnelConfig {
    private val wgQuickOnly = setOf("table", "preup", "postup", "predown", "postdown", "saveconfig", "fwmark")
    private val ourSections = setOf("[socks5]", "[http]", "[tcpclienttunnel]", "[tcpservertunnel]", "[stdiotunnel]")

    /** Problems that make a pasted config unusable, in words for the person; null when it looks fine. */
    fun problem(text: String): String? {
        val lower = text.lowercase()
        return when {
            text.isBlank() -> "Вставьте текст конфига WireGuard или AmneziaWG."
            "[interface]" !in lower -> "В конфиге нет раздела [Interface]."
            "[peer]" !in lower -> "В конфиге нет раздела [Peer]."
            !Regex("(?im)^\\s*privatekey\\s*=").containsMatchIn(text) -> "В конфиге нет PrivateKey."
            !Regex("(?im)^\\s*endpoint\\s*=").containsMatchIn(text) -> "В конфиге нет Endpoint."
            else -> null
        }
    }

    /** The tunnel part of [text] with Couchy's local proxy added. */
    fun withProxy(text: String, httpPort: Int): String {
        val out = StringBuilder()
        var skip = false
        var inInterface = false
        text.replace("\r", "").lines().forEach { raw ->
            val line = raw.trim()
            if (line.startsWith("[")) {
                skip = line.lowercase() in ourSections
                inInterface = line.equals("[Interface]", ignoreCase = true)
                if (!skip) out.appendLine(line)
                return@forEach
            }
            if (skip) return@forEach
            val key = line.substringBefore('=').trim().lowercase()
            if (inInterface && (key in wgQuickOnly || key == "checkalive" || key == "checkaliveinterval")) return@forEach
            out.appendLine(line)
        }
        out.appendLine()
        out.appendLine("[http]")
        out.appendLine("BindAddress = 127.0.0.1:$httpPort")
        return out.toString()
    }
}

/** Which sites go through the bypass in [BypassMode.AUTO]. */
object BypassRules {
    /** Sites known to be blocked or to refuse Russian visitors. */
    val preset = setOf(
        "rezka.ag", "hdrezka.ag", "hdrezka.me", "hdrezka.co", "hdrezka.website", "rezka.cc",
        "torrentio.strem.fun", "imdb.com", "media-imdb.com", "rutracker.org", "nnmclub.to", "kinozal.tv",
        "filmix.gg", "zona.plus",
        "youtube.com", "googlevideo.com", "ytimg.com", "youtu.be", "thepiratebay.org", "1337x.to", "yts.mx",
        "archive.org", "instagram.com", "facebook.com", "x.com", "twitter.com", "twimg.com", "discord.com",
    )

    fun matches(host: String, hosts: Collection<String>): Boolean {
        val h = host.lowercase().trimEnd('.')
        return hosts.any { d -> h == d || h.endsWith(".$d") }
    }

    /** The part of a host worth remembering: example.com for cdn3.example.com, but co.uk-style pairs kept whole. */
    fun domainOf(host: String): String {
        val parts = host.lowercase().trimEnd('.').split('.')
        if (parts.size <= 2 || parts.all { it.all(Char::isDigit) }) return parts.joinToString(".")
        val second = parts[parts.size - 2]
        val keep = if (second.length <= 3 && second in setOf("co", "com", "net", "org", "gov", "edu", "ac")) 3 else 2
        return parts.takeLast(keep).joinToString(".")
    }

    private val blockPages = listOf("warning.rt.ru", "blocked.", "zapret", "rkn.gov.ru", "blocklist", "eais.rkn", "block.mts", "blacklist")

    /** A provider's "this site is blocked" page that a request was redirected to. */
    fun isBlockPage(url: String): Boolean {
        val u = url.lowercase()
        return blockPages.any { it in u }
    }

    /** Cloudflare's trace answer to a line for the person: where the traffic comes out. */
    fun traceSummary(trace: String): String? {
        val map = trace.lines().mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }.toMap()
        val loc = map["loc"] ?: return null
        val warp = map["warp"]?.let { it == "on" || it == "plus" } == true
        return "выход через $loc" + if (warp) ", WARP" else ""
    }

    /** Local addresses never go through the bypass. */
    fun isLocal(host: String): Boolean {
        val h = host.lowercase()
        return h == "localhost" || h.startsWith("127.") || h == "::1" || h == "[::1]" || h.startsWith("10.") || h.startsWith("192.168.") ||
            Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(h) || h.endsWith(".local")
    }
}
