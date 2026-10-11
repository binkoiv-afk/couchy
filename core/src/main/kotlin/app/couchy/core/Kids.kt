package app.couchy.core

/** Kids mode: only what a parent allowed, and leaving it needs the PIN. */
object Kids {
    private val kidsGroup = Regex("(?iu)дет|мульт|kid|child|cartoon|junior|disney|nick")

    /** TV groups shown in kids mode. */
    fun isKidsGroup(group: String?): Boolean = group != null && kidsGroup.containsMatchIn(group)

    /** A PIN is four digits. */
    fun validPin(pin: String): Boolean = pin.length == 4 && pin.all(Char::isDigit)
}
