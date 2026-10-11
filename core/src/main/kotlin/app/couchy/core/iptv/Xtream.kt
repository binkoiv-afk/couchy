package app.couchy.core.iptv

/** Xtream Codes login: the server address and the account the provider gave. */
data class XtreamAccount(val server: String, val username: String, val password: String) {
    /** "http://host:port" without a trailing slash; "http://" is added when missing. */
    val base: String
        get() = server.trim().trimEnd('/').let { if ("://" in it) it else "http://$it" }

    fun api(action: String? = null): String =
        "$base/player_api.php?username=${enc(username)}&password=${enc(password)}" + (action?.let { "&action=$it" } ?: "")

    fun liveUrl(streamId: Long, extension: String = "ts"): String = "$base/live/${enc(username)}/${enc(password)}/$streamId.$extension"

    fun epgUrl(): String = "$base/xmltv.php?username=${enc(username)}&password=${enc(password)}"

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
}
