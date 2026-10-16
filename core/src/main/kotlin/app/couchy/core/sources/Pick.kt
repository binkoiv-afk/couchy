package app.couchy.core.sources

/** «Что посмотреть?»: a mood, how much time there is, and three good titles to choose from. */
enum class PickMood(val label: String, val movie: String?, val tv: String?, val slug: String?) {
    LAUGH("Посмеяться", "35", "35", "comedy"),
    THRILL("Пощекотать нервы", "53|27", "9648|80", "thriller"),
    SCARY("Страшно", "27", "9648|10765", "horror"),
    ACTION("Экшен", "28", "10759", "action"),
    WOW("Чтобы захватило", "12|878|14", "10759|10765", "fiction"),
    FEEL("Растрогаться", "18|10749", "18", "drama"),
    LOVE("Про любовь", "10749", "10766|18", "melodrama"),
    PUZZLE("Распутать загадку", "9648|80", "9648|80", "detective"),
    HISTORY("История и война", "36|10752", "10768", "historical"),
    REAL("По реальным событиям", "99|36", "99", "documentary"),
    CARTOON("Мультфильм", "16", "16", null),
    FAMILY("Всей семьёй", "10751", "10751", "family"),
    ANY("Что угодно", null, null, null),
}

enum class PickLength(val label: String, val series: Boolean, val maxMinutes: Int?, val minMinutes: Int? = null) {
    SHORT("До 1,5 часа", false, 100),
    MOVIE("Фильм на вечер", false, null),
    LONG("Большое кино", false, null, 140),
    SERIES("Сериал", true, null),
}

/** When it came out. */
enum class PickEra(val label: String, val from: Int?, val to: Int?) {
    ANY("Любые годы", null, null),
    NEW("Новинки", 0, null),
    Y2010("2010-е и позже", 2010, null),
    Y2000("2000-е", 2000, 2009),
    Y1990("90-е", 1990, 1999),
    OLD("Классика", null, 1989),
}

/** Where it was made, as TMDB's origin countries. */
enum class PickOrigin(val label: String, val countries: String?, val language: String? = null) {
    ANY("Откуда угодно", null),
    OURS("Наше", "RU|SU"),
    US("США", "US"),
    EUROPE("Европа", "GB|FR|DE|IT|ES|SE|DK|NO|PL"),
    ASIA("Корея и Япония", "KR|JP"),
    INDIA("Индия", "IN"),
}

/** How well liked, by TMDB's score. */
enum class PickScore(val label: String, val min: Double) {
    GOOD("От 6,5", 6.5),
    STRONG("От 7", 7.0),
    GREAT("От 7,5", 7.5),
    BEST("Только шедевры", 8.0),
}

/** Everything the person set in «Что посмотреть?». */
data class PickOptions(
    val mood: PickMood = PickMood.ANY,
    val length: PickLength = PickLength.MOVIE,
    val era: PickEra = PickEra.ANY,
    val origin: PickOrigin = PickOrigin.ANY,
    val score: PickScore = PickScore.STRONG,
    /** Hidden gems: well liked but not what everyone has already seen. */
    val lesserKnown: Boolean = false,
    /** Only from the person's own «Смотреть позже». */
    val fromLater: Boolean = false,
    /** Lean on what the person loved and scored high. */
    val taste: Boolean = true,
)

object Pick {
    /** Pages of well-liked titles to draw from; a random one each time keeps the picks fresh. */
    const val PAGES = 10
    const val COUNT = 3

    /** TMDB's discover for the choice: popular and well rated, no cartoons unless asked for. */
    fun tmdb(o: PickOptions, page: Int, thisYear: Int = java.time.LocalDate.now().year): Pair<String, Map<String, String>> {
        val tv = o.length.series
        val mood = o.mood
        val p = buildMap {
            put("page", "$page")
            put("sort_by", if (o.lesserKnown) "vote_average.desc" else "popularity.desc")
            put("vote_average.gte", "${o.score.min}")
            val votes = when {
                o.lesserKnown -> if (tv) 80 else 150
                o.era == PickEra.NEW -> if (tv) 60 else 150
                o.origin != PickOrigin.ANY && o.origin != PickOrigin.US -> if (tv) 60 else 150
                else -> if (tv) 250 else 600
            }
            put("vote_count.gte", "$votes")
            // Popular titles are left to the usual picks; hidden gems stay under the radar.
            if (o.lesserKnown) put("vote_count.lte", if (tv) "1500" else "4000")
            (if (tv) mood.tv else mood.movie)?.let { put("with_genres", it) }
            val cartoons = mood == PickMood.FAMILY || mood == PickMood.CARTOON
            if (!cartoons) put("without_genres", if (tv) "16,10762,10764,10767" else "16,99".takeIf { mood != PickMood.REAL } ?: "16")
            o.length.maxMinutes?.let { put("with_runtime.lte", "$it"); put("with_runtime.gte", "70") }
            o.length.minMinutes?.let { put("with_runtime.gte", "$it") }
            val date = if (tv) "first_air_date" else "primary_release_date"
            val from = if (o.era == PickEra.NEW) thisYear - 1 else o.era.from
            from?.let { put("$date.gte", "$it-01-01") }
            o.era.to?.let { put("$date.lte", "$it-12-31") }
            o.origin.countries?.let { put("with_origin_country", it) }
        }
        return "/discover/${if (tv) "tv" else "movie"}" to p
    }

    /** The same choice for the catalogue used without a TMDB key: the best of the genre. */
    fun filter(o: PickOptions) = CatalogFilter(
        type = when {
            o.mood == PickMood.CARTOON -> CatalogType.CARTOONS
            o.length.series -> CatalogType.SERIES
            else -> CatalogType.FILMS
        },
        sort = if (o.era == PickEra.NEW) CatalogSort.LAST else CatalogSort.BEST,
        genre = o.mood.slug,
        country = when (o.origin) {
            PickOrigin.OURS -> "Россия"
            PickOrigin.US -> "США"
            PickOrigin.INDIA -> "Индия"
            else -> null
        },
    )

    /** Whether a title from another list (later, the person's taste) fits what was asked. */
    fun fits(t: Title, o: PickOptions, thisYear: Int = java.time.LocalDate.now().year): Boolean {
        if ((t.kind == TitleKind.SERIES) != o.length.series) return false
        val year = t.year ?: return o.era == PickEra.ANY
        val from = if (o.era == PickEra.NEW) thisYear - 1 else o.era.from
        if (from != null && year < from) return false
        if (o.era.to != null && year > o.era.to) return false
        return true
    }

    /**
     * Three titles not seen yet and not shown in the last round, if there are enough. Up to two
     * come from [liked] (what the person's own taste suggests) when those fit.
     */
    fun choose(pool: List<Title>, skip: Set<String>, random: kotlin.random.Random = kotlin.random.Random, liked: List<Title> = emptyList()): List<Title> {
        val fromTaste = liked.distinctBy { it.key }.filter { it.poster != null && it.key !in skip }.shuffled(random).take(2)
        val fresh = pool.distinctBy { it.key }.filter { it.poster != null && it.key !in skip && fromTaste.none { f -> f.key == it.key } }
        val from = if (fresh.size + fromTaste.size >= COUNT) fresh else pool.distinctBy { it.key }.filter { fromTaste.none { f -> f.key == it.key } }
        return (fromTaste + from.shuffled(random).take(COUNT - fromTaste.size)).shuffled(random)
    }
}
