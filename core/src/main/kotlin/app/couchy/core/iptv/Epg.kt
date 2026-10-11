package app.couchy.core.iptv

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Small pure helpers for XMLTV programme guides; the XML itself is read on the device. */
object Epg {
    /** "20261011063000 +0300" (offset optional, UTC when missing) to epoch millis. */
    fun parseTime(text: String): Long? {
        val trimmed = text.trim()
        if (trimmed.length < 12) return null
        val digits = trimmed.take(14).padEnd(14, '0')
        val offset = trimmed.drop(14).trim().takeIf { it.matches(Regex("[+-]\\d{4}")) } ?: "+0000"
        val format = SimpleDateFormat("yyyyMMddHHmmssZ", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return format.parse(digits + offset, ParsePosition(0))?.time
    }

    /** Name used to match a playlist channel with a guide channel when there is no tvg-id. */
    fun normalize(name: String): String =
        name.lowercase()
            .replace(Regex("""\b(hd|fhd|uhd|4k|sd|orig|original|\+\d)\b"""), " ")
            .replace(Regex("""[^\p{L}\p{N}]+"""), "")
            .trim()
}
