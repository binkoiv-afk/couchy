package app.couchy.core.sources

/** «Что посмотреть?»: a mood, how much time there is, and three good titles to choose from. */
enum class PickMood(val label: String, val movie: String?, val tv: String?, val slug: String?) {
    LAUGH("Посмеяться", "35", "35", "comedy"),
    THRILL("Пощекотать нервы", "53|27", "9648|80", "thriller"),
    WOW("Чтобы захватило", "12|878|14", "10759|10765", "fiction"),
    FEEL("Растрогаться", "18|10749", "18", "drama"),
    PUZZLE("Распутать загадку", "9648|80", "9648|80", "detective"),
    FAMILY("Всей семьёй", "10751", "10751", "family"),
    ANY("Что угодно", null, null, null),
}

enum class PickLength(val label: String, val series: Boolean, val maxMinutes: Int?) {
    SHORT("До 1,5 часа", false, 100),
    MOVIE("Фильм на вечер", false, null),
    SERIES("Сериал", true, null),
}

object Pick {
    /** Pages of well-liked titles to draw from; a random one each time keeps the picks fresh. */
    const val PAGES = 10
    const val COUNT = 3

    /** TMDB's discover for the choice: popular and well rated, no cartoons unless for the family. */
    fun tmdb(mood: PickMood, length: PickLength, page: Int): Pair<String, Map<String, String>> {
        val tv = length.series
        val p = buildMap {
            put("page", "$page")
            put("sort_by", "popularity.desc")
            put("vote_average.gte", "6.7")
            put("vote_count.gte", if (tv) "250" else "600")
            (if (tv) mood.tv else mood.movie)?.let { put("with_genres", it) }
            if (mood != PickMood.FAMILY) put("without_genres", if (tv) "16,10762,10764,10767" else "16,99")
            length.maxMinutes?.let { put("with_runtime.lte", "$it"); put("with_runtime.gte", "70") }
        }
        return "/discover/${if (tv) "tv" else "movie"}" to p
    }

    /** The same choice for the catalogue used without a TMDB key: the best of the genre. */
    fun filter(mood: PickMood, length: PickLength) = CatalogFilter(
        type = if (length.series) CatalogType.SERIES else CatalogType.FILMS,
        sort = CatalogSort.BEST,
        genre = mood.slug,
    )

    /** Three titles not seen yet and not shown in the last round, if there are enough. */
    fun choose(pool: List<Title>, skip: Set<String>, random: kotlin.random.Random = kotlin.random.Random): List<Title> {
        val fresh = pool.distinctBy { it.key }.filter { it.poster != null && it.key !in skip }
        val from = if (fresh.size >= COUNT) fresh else pool.distinctBy { it.key }
        return from.shuffled(random).take(COUNT)
    }
}
