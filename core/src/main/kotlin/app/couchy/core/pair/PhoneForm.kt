package app.couchy.core.pair

import java.net.URLDecoder

/**
 * Typing on a TV with a remote is slow: the TV shows a QR, the phone's browser opens this small
 * page from the TV itself, and what is typed there lands in the TV's fields.
 */
object PhoneForm {
    data class Field(val label: String, val secret: Boolean = false, val multiline: Boolean = false, val value: String = "")

    fun page(title: String, fields: List<Field>, code: String): String {
        val inputs = fields.mapIndexed { i, f ->
            // What is secret stays on the TV: the phone gets an empty field to fill.
            val v = if (f.secret) "" else escape(f.value)
            val input = if (f.multiline) "<textarea name=\"f$i\" rows=\"6\">$v</textarea>"
            else "<input name=\"f$i\" value=\"$v\" type=\"${if (f.secret) "password" else "text"}\" autocapitalize=\"off\" autocorrect=\"off\"${if (i == 0) " autofocus" else ""}>"
            "<label>${escape(f.label)}$input</label>"
        }.joinToString("")
        return shell(
            escape(title),
            "<form method=\"post\" action=\"/type\"><input type=\"hidden\" name=\"c\" value=\"${escape(code)}\">$inputs" +
                "<button type=\"submit\">Отправить на ТВ</button></form>",
        )
    }

    fun done(): String = shell("Готово", "<p>Текст уже на экране ТВ. Эту страницу можно закрыть.</p>")

    fun expired(): String = shell("Код устарел", "<p>Откройте ввод с телефона на ТВ ещё раз и отсканируйте новый QR.</p>")

    /** The form's body: the code and the fields in order. */
    fun parse(body: String): Pair<String, List<String>> {
        val values = body.split('&').filter { '=' in it }.mapNotNull { pair ->
            val (k, v) = pair.split('=', limit = 2)
            runCatching { URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8").replace("\r\n", "\n") }.getOrNull()
        }.toMap()
        val fields = generateSequence(0) { it + 1 }.map { values["f$it"] }.takeWhile { it != null }.map { it!! }.toList()
        return values["c"].orEmpty() to fields
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun shell(title: String, body: String) =
        "<!doctype html><html lang=\"ru\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<title>$title</title><style>" +
            "body{margin:0;padding:24px 18px;background:#0e0f13;color:#f2f3f5;font:16px/1.4 system-ui,sans-serif}" +
            "h1{font-size:24px;margin:0 0 18px}label{display:block;margin:0 0 14px;color:#a3a7b1;font-size:14px}" +
            "input,textarea{display:block;box-sizing:border-box;width:100%;margin-top:6px;padding:14px;border-radius:14px;border:1px solid #2b2e36;background:#181a20;color:#fff;font-size:17px}" +
            "button{width:100%;padding:16px;border:0;border-radius:14px;background:#ff7a3d;color:#000;font-weight:700;font-size:17px;margin-top:6px}" +
            "</style></head><body><h1>$title</h1>$body</body></html>"
}
