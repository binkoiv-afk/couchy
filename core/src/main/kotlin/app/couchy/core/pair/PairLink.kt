package app.couchy.core.pair

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * What the TV's QR code holds: where Couchy on the TV listens in the home network, a one-time
 * code that proves the phone saw this screen, and the TV's name to show on the phone.
 */
data class PairLink(val host: String, val port: Int, val code: String, val name: String) {
    fun toLink(): String =
        "couchy://pair?h=$host:$port&c=$code&n=" + URLEncoder.encode(name, "UTF-8")

    companion object {
        /** A fresh six-digit code; it lives only while the TV shows its QR. */
        fun newCode(random: kotlin.random.Random = kotlin.random.Random): String = (100_000 + random.nextInt(900_000)).toString()

        fun parse(text: String?): PairLink? {
            val t = text?.trim() ?: return null
            if (!t.startsWith("couchy://pair?")) return null
            val q = t.substringAfter('?').split('&').mapNotNull { p ->
                val k = p.substringBefore('=', "")
                if (k.isEmpty()) null else k to runCatching { URLDecoder.decode(p.substringAfter('='), "UTF-8") }.getOrDefault("")
            }.toMap()
            val address = q["h"] ?: return null
            val host = address.substringBeforeLast(':').takeIf { it.isNotBlank() } ?: return null
            val port = address.substringAfterLast(':', "").toIntOrNull() ?: return null
            val code = q["c"]?.takeIf { it.length in 4..12 && it.all(Char::isDigit) } ?: return null
            return PairLink(host, port, code, q["n"].orEmpty().ifBlank { "Приставка" })
        }
    }
}
