package app.couchy.core

/** Orders for the library grid. */
enum class SortOrder(val label: String) {
    RECENTLY_WATCHED("Недавно смотрел"),
    RECENTLY_ADDED("Недавно добавлены"),
    TITLE("По названию"),
    PROGRESS("По прогрессу"),
}

/** The bits of a library entry sorting needs, so the rule is testable without Android. */
interface Sortable {
    val title: String
    val addedAt: Long
    val lastWatchedAt: Long
    val positionMs: Long
    val durationMs: Long
}

object LibrarySort {
    fun <T : Sortable> sort(items: List<T>, order: SortOrder): List<T> = when (order) {
        SortOrder.RECENTLY_WATCHED -> items.sortedWith(
            compareByDescending<T> { it.lastWatchedAt }.thenByDescending { it.addedAt },
        )
        SortOrder.RECENTLY_ADDED -> items.sortedByDescending { it.addedAt }
        SortOrder.TITLE -> items.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        // Started-but-unfinished first, closest to the end on top; untouched and finished after.
        SortOrder.PROGRESS -> items.sortedWith(
            compareBy<T> { rank(it) }.thenByDescending { Progress.fraction(it.positionMs, it.durationMs) },
        )
    }

    private fun rank(item: Sortable): Int = when {
        Progress.isWatched(item.positionMs, item.durationMs) -> 2
        item.positionMs >= Progress.MIN_RESUME_MS -> 0
        else -> 1
    }

    /** Titles that are started and not finished, most recent first: the "continue watching" row. */
    fun <T : Sortable> continueWatching(items: List<T>): List<T> =
        items.filter { it.positionMs >= Progress.MIN_RESUME_MS && !Progress.isWatched(it.positionMs, it.durationMs) }
            .sortedByDescending { it.lastWatchedAt }
}
