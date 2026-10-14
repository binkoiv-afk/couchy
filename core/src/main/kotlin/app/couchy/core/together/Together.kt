package app.couchy.core.together

import org.json.JSONObject
import kotlin.math.abs

/**
 * Watching together with friends elsewhere: every Couchy in a room sends what it plays, where and
 * whether it is paused, and the others follow. Messages go through a public relay under a topic
 * named after the room code, so only people who know the code see them.
 */
sealed class TogetherMessage {
    abstract val from: String

    /** What a device plays right now. */
    data class State(override val from: String, val key: String, val title: String, val positionMs: Long, val playing: Boolean) : TogetherMessage()
    data class Chat(override val from: String, val name: String, val text: String) : TogetherMessage()
    /** Someone came in: whoever plays something answers with a [State]. */
    data class Hello(override val from: String, val name: String) : TogetherMessage()
    data class Bye(override val from: String, val name: String) : TogetherMessage()

    fun encode(): String = when (this) {
        is State -> JSONObject().put("t", "state").put("f", from).put("k", key).put("n", title).put("p", positionMs).put("on", playing)
        is Chat -> JSONObject().put("t", "chat").put("f", from).put("n", name).put("x", text.take(500))
        is Hello -> JSONObject().put("t", "hello").put("f", from).put("n", name)
        is Bye -> JSONObject().put("t", "bye").put("f", from).put("n", name)
    }.toString()

    companion object {
        fun decode(text: String): TogetherMessage? = runCatching {
            val o = JSONObject(text)
            val from = o.getString("f")
            when (o.getString("t")) {
                "state" -> State(from, o.getString("k"), o.optString("n"), o.optLong("p"), o.optBoolean("on"))
                "chat" -> Chat(from, o.optString("n"), o.getString("x"))
                "hello" -> Hello(from, o.optString("n"))
                "bye" -> Bye(from, o.optString("n"))
                else -> null
            }
        }.getOrNull()
    }
}

/** What this device plays, as the player reports it every half second. */
data class LocalPlay(val key: String, val title: String, val positionMs: Long, val playing: Boolean, val at: Long)

object Together {
    /** Letters and digits that cannot be mixed up when read aloud or typed: no 0/O, 1/I/L. */
    private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    const val RELAY = "https://ntfy.sh"

    fun newCode(random: kotlin.random.Random = kotlin.random.Random): String = (1..8).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")

    /** "abcd-efgh", "ABCD EFGH" → "ABCDEFGH"; null when it is not a code. */
    fun normalize(input: String): String? {
        val c = input.uppercase().filter { it.isLetterOrDigit() }
        return c.takeIf { it.length == 8 && it.all { ch -> ch in ALPHABET } }
    }

    /** "ABCD-EFGH", for reading out. */
    fun pretty(code: String) = code.take(4) + "-" + code.drop(4)

    fun topic(code: String) = "couchy-together-" + code.lowercase()

    fun link(code: String) = "couchy://together?c=$code"

    /**
     * Whether the person here did something the others should follow: started another video,
     * paused or played, or jumped (the position is not where steady playback would have put it).
     */
    fun changed(before: LocalPlay?, now: LocalPlay): Boolean {
        if (before == null || before.key != now.key) return true
        if (before.playing != now.playing) return true
        val expected = before.positionMs + if (before.playing) now.at - before.at else 0
        return abs(now.positionMs - expected) > JUMP_MS
    }

    /** What to do here when a friend's [State] arrives. */
    sealed class Follow {
        data class Open(val key: String, val title: String, val positionMs: Long) : Follow()
        data class Seek(val positionMs: Long) : Follow()
        data class Play(val playing: Boolean) : Follow()
    }

    fun follow(here: LocalPlay?, there: TogetherMessage.State): List<Follow> {
        if (here == null || here.key != there.key) return listOf(Follow.Open(there.key, there.title, there.positionMs))
        val out = mutableListOf<Follow>()
        if (abs(here.positionMs - there.positionMs) > DRIFT_MS) out += Follow.Seek(there.positionMs)
        if (here.playing != there.playing) out += Follow.Play(there.playing)
        return out
    }

    private const val JUMP_MS = 2_500L
    private const val DRIFT_MS = 3_000L
}
