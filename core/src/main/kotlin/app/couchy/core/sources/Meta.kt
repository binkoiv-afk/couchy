package app.couchy.core.sources

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

/**
 * What Couchy knows about a title from a film database rather than from a site that plays it:
 * TMDB in Russian when there is a key, Cinemeta (IMDb data, in English) without one.
 */
data class MetaInfo(
    val name: String? = null,
    val original: String? = null,
    val year: Int? = null,
    val description: String? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    val rating: String? = null,
    val imdbId: String? = null,
    val tmdbId: Int? = null,
    val details: TitleDetails = TitleDetails(),
    /** YouTube id of a trailer. */
    val trailer: String? = null,
    /** In Russian (TMDB), so it goes before what the sites say. */
    val russian: Boolean = false,
)

/** A genre every source understands, under its own name in each. */
data class Genre(val slug: String, val name: String, val cinemeta: String?, val tmdbMovie: Int?, val tmdbTv: Int?)

object Genres {
    val all = listOf(
        Genre("comedy", "Комедии", "Comedy", 35, 35),
        Genre("action", "Боевики", "Action", 28, 10759),
        Genre("fiction", "Фантастика", "Sci-Fi", 878, 10765),
        Genre("thriller", "Триллеры", "Thriller", 53, 9648),
        Genre("drama", "Драмы", "Drama", 18, 18),
        Genre("horror", "Ужасы", "Horror", 27, null),
        Genre("detective", "Детективы", "Mystery", 9648, 9648),
        Genre("adventures", "Приключения", "Adventure", 12, 10759),
        Genre("crime", "Криминал", "Crime", 80, 80),
        Genre("melodrama", "Мелодрамы", "Romance", 10749, null),
        Genre("fantasy", "Фэнтези", "Fantasy", 14, 10765),
        Genre("family", "Семейные", "Family", 10751, 10751),
        Genre("kids", "Детские", "Family", 10751, 10762),
        Genre("historical", "Исторические", "History", 36, null),
        Genre("military", "Военные", "War", 10752, 10768),
        Genre("biographical", "Биографические", "Biography", 36, null),
        Genre("documentary", "Документальные", "Documentary", 99, 99),
        Genre("sport", "Спортивные", "Sport", null, null),
        Genre("western", "Вестерны", "Western", 37, 37),
        Genre("musical", "Музыкальные", "Music", 10402, null),
    )

    fun bySlug(slug: String?) = all.firstOrNull { it.slug == slug }

    /** A source's own genre name ("Sci-Fi", "Science Fiction", "Фантастика") as a catalogue genre. */
    fun find(name: String): Genre? {
        val n = name.lowercase()
        return all.firstOrNull { it.name.lowercase().take(5) == n.take(5) || it.cinemeta?.lowercase() == n } ?: when {
            "science" in n || "фантаст" in n -> bySlug("fiction")
            "мульт" in n || "animation" in n -> null
            "war" in n || "воен" in n -> bySlug("military")
            "romance" in n || "мелодр" in n -> bySlug("melodrama")
            "mystery" in n || "детект" in n -> bySlug("detective")
            "семей" in n -> bySlug("family")
            "истор" in n -> bySlug("historical")
            "докум" in n -> bySlug("documentary")
            "приключ" in n -> bySlug("adventures")
            else -> null
        }
    }
}

/** Countries of the catalogue by their codes; the names are Russian, as in the filter. */
object Countries {
    val codes = linkedMapOf(
        "США" to "US", "Россия" to "RU", "СССР" to "SU", "Великобритания" to "GB", "Франция" to "FR", "Германия" to "DE",
        "Италия" to "IT", "Испания" to "ES", "Канада" to "CA", "Япония" to "JP", "Корея Южная" to "KR", "Китай" to "CN",
        "Индия" to "IN", "Турция" to "TR", "Австралия" to "AU", "Украина" to "UA", "Беларусь" to "BY", "Казахстан" to "KZ",
    )

    fun code(name: String?) = name?.let { codes[it] }

    /** "US" to "США": the filter's own names first, then the system's Russian names. */
    fun russian(code: String): String =
        codes.entries.firstOrNull { it.value == code }?.key ?: Locale("", code).getDisplayCountry(Locale("ru")).ifBlank { code }

    fun english(name: String): String? = code(name)?.let { Locale("", it).getDisplayCountry(Locale.ENGLISH) }
}

/** Link addresses that stay inside Couchy: a person, a genre or a country to open as a list. */
object MetaLinks {
    fun tmdbPerson(id: Int) = "tmdb:person:$id"
    fun person(name: String) = "name:$name"
    fun genre(slug: String) = "genre:$slug"
    fun country(name: String) = "country:$name"

    fun tmdbPersonId(url: String) = url.removePrefix("tmdb:person:").takeIf { url.startsWith("tmdb:person:") }?.toIntOrNull()
    fun personName(url: String) = url.removePrefix("name:").takeIf { url.startsWith("name:") }
}

object Cinemeta {
    const val BASE = "https://v3-cinemeta.strem.io"

    fun metaUrl(imdbId: String, kind: TitleKind) = "$BASE/meta/${if (kind == TitleKind.SERIES) "series" else "movie"}/$imdbId.json"

    fun searchUrl(query: String, kind: TitleKind) =
        "$BASE/catalog/${if (kind == TitleKind.SERIES) "series" else "movie"}/top/search=${enc(query)}.json"

    /**
     * One page of a catalogue: popular ("top"), best rated, or a year's. The address takes one
     * extra at a time, so whatever it cannot narrow is narrowed by [keep].
     */
    fun catalogUrl(f: CatalogFilter, page: Int): String {
        val type = if (f.type == CatalogType.FILMS || f.type == CatalogType.CARTOONS) "movie" else "series"
        val genre = Genres.bySlug(f.genre)?.cinemeta ?: if (f.type == CatalogType.CARTOONS || f.type == CatalogType.ANIME) "Animation" else null
        val (catalog, extra) = when {
            f.year != null -> "year" to "genre=${f.year}"
            f.sort == CatalogSort.LAST -> "year" to "genre=${java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)}"
            f.sort == CatalogSort.BEST -> "imdbRating" to genre?.let { "genre=${enc(it)}" }
            else -> "top" to genre?.let { "genre=${enc(it)}" }
        }
        val skip = if (page > 1) "skip=${(page - 1) * PAGE}" else null
        val extras = listOfNotNull(extra, skip).joinToString("&")
        return "$BASE/catalog/$type/$catalog" + (if (extras.isEmpty()) "" else "/$extras") + ".json"
    }

    const val PAGE = 50

    fun keep(f: CatalogFilter, t: Title, genres: List<String>, countries: String?): Boolean {
        val wanted = Genres.bySlug(f.genre)?.cinemeta
        if (wanted != null && genres.isNotEmpty() && wanted !in genres) return false
        if ((f.type == CatalogType.CARTOONS || f.type == CatalogType.ANIME) && genres.isNotEmpty() && "Animation" !in genres) return false
        if (f.type == CatalogType.FILMS && "Animation" in genres && f.genre == null) return false
        if (f.year != null && t.year != null && t.year != f.year) return false
        val country = f.country?.let(Countries::english)
        if (country != null && countries != null && country !in countries) return false
        return true
    }

    fun parseCatalog(json: String, kind: TitleKind, f: CatalogFilter? = null): List<Title> {
        val list = JSONObject(json).optJSONArray("metas") ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            val o = list.getJSONObject(i)
            val t = title(o, kind) ?: return@mapNotNull null
            if (f != null && !keep(f, t, strings(o.optJSONArray("genres") ?: o.optJSONArray("genre")), o.optString("country").ifBlank { null })) null else t
        }
    }

    private fun title(o: JSONObject, kind: TitleKind): Title? {
        val id = o.optString("imdb_id").ifBlank { o.optString("id") }.takeIf { it.startsWith("tt") } ?: return null
        val name = o.optString("name").ifBlank { return null }
        return Title(
            name = name,
            original = name,
            year = year(o),
            kind = if (o.optString("type") == "series") TitleKind.SERIES else if (o.optString("type") == "movie") TitleKind.MOVIE else kind,
            poster = o.optString("poster").ifBlank { null },
            backdrop = o.optString("background").ifBlank { null },
            imdbId = id,
            tmdbId = o.optInt("moviedb_id").takeIf { it > 0 },
            info = listOfNotNull(year(o)?.toString(), o.optString("country").substringBefore(',').ifBlank { null }).joinToString(", ").ifBlank { null },
            rating = o.optString("imdbRating").toDoubleOrNull()?.takeIf { it > 0 }?.let { "IMDb $it" },
        )
    }

    fun parseMeta(json: String): MetaInfo? {
        val o = JSONObject(json).optJSONObject("meta") ?: return null
        val genres = strings(o.optJSONArray("genres") ?: o.optJSONArray("genre"))
        return MetaInfo(
            name = o.optString("name").ifBlank { null },
            original = o.optString("name").ifBlank { null },
            year = year(o),
            description = o.optString("description").ifBlank { null },
            poster = o.optString("poster").ifBlank { null },
            backdrop = o.optString("background").ifBlank { null },
            rating = o.optString("imdbRating").toDoubleOrNull()?.takeIf { it > 0 }?.let { "IMDb $it" },
            imdbId = o.optString("imdb_id").ifBlank { o.optString("id") }.takeIf { it.startsWith("tt") },
            tmdbId = o.optInt("moviedb_id").takeIf { it > 0 },
            trailer = o.optJSONArray("trailers")?.optJSONObject(0)?.optString("source")?.ifBlank { null },
            details = TitleDetails(
                released = o.optString("released").take(10).ifBlank { null }?.let(::russianDate),
                countries = o.optString("country").split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { name ->
                    val code = Locale.getISOCountries().firstOrNull { Locale("", it).getDisplayCountry(Locale.ENGLISH) == name }
                    val ru = code?.let(Countries::russian) ?: name
                    Link(ru, MetaLinks.country(ru))
                },
                genres = genres.mapNotNull { g -> Genres.find(g)?.let { Link(it.name, MetaLinks.genre(it.slug)) } }.distinctBy { it.url },
                directors = strings(o.optJSONArray("director")).map { Link(it, MetaLinks.person(it)) },
                actors = strings(o.optJSONArray("cast")).map { Link(it, MetaLinks.person(it)) },
                duration = o.optString("runtime").replace("min", "мин.").ifBlank { null },
            ),
        )
    }

    private fun year(o: JSONObject): Int? =
        Regex("(\\d{4})").find(o.optString("releaseInfo").ifBlank { o.optString("year") })?.groupValues?.get(1)?.toIntOrNull()

    private fun strings(a: JSONArray?): List<String> = a?.let { (0 until it.length()).map { i -> it.optString(i) }.filter { s -> s.isNotBlank() } }.orEmpty()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}

private val months = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря")

/** "2024-03-01" to "1 марта 2024". */
fun russianDate(iso: String): String? {
    val m = Regex("(\\d{4})-(\\d{2})-(\\d{2})").find(iso) ?: return null
    val (y, mo, d) = m.destructured
    return "${d.toInt()} ${months.getOrNull(mo.toInt() - 1) ?: return null} $y"
}

/** TMDB with the person's own free key: Russian names, descriptions, cast with photos, recommendations. */
object Tmdb {
    const val API = "https://api.themoviedb.org/3"
    private const val IMG = "https://image.tmdb.org/t/p"

    /** A v4 read token goes in a header, a v3 key in the address. */
    fun request(key: String, path: String, params: Map<String, String> = emptyMap()): Pair<String, Map<String, String>> {
        val bearer = key.startsWith("eyJ")
        val all = params + ("language" to "ru-RU") + (if (bearer) emptyMap() else mapOf("api_key" to key))
        val url = API + path + "?" + all.entries.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }
        return url to (if (bearer) mapOf("Authorization" to "Bearer $key") else emptyMap())
    }

    fun detailsPath(id: Int, kind: TitleKind) = (if (kind == TitleKind.SERIES) "/tv/" else "/movie/") + id
    const val APPEND = "credits,recommendations,external_ids,release_dates,content_ratings,videos"

    fun discover(f: CatalogFilter, page: Int): Pair<String, Map<String, String>> {
        val tv = f.type == CatalogType.SERIES || f.type == CatalogType.ANIME
        val genre = Genres.bySlug(f.genre)?.let { if (tv) it.tmdbTv else it.tmdbMovie }
        val genres = listOfNotNull(genre, if (f.type == CatalogType.CARTOONS || f.type == CatalogType.ANIME) 16 else null).distinct()
        val p = buildMap {
            put("page", "$page")
            put(
                "sort_by",
                when (f.sort) {
                    CatalogSort.BEST -> "vote_average.desc"
                    CatalogSort.LAST -> if (tv) "first_air_date.desc" else "primary_release_date.desc"
                    else -> "popularity.desc"
                },
            )
            if (f.sort == CatalogSort.BEST) put("vote_count.gte", "300")
            // Newest among those already out, with some votes, rather than announcements.
            if (f.sort == CatalogSort.LAST) {
                put("vote_count.gte", "20")
                put(if (tv) "first_air_date.lte" else "primary_release_date.lte", java.time.LocalDate.now().toString())
            }
            if (genres.isNotEmpty()) put("with_genres", genres.joinToString(","))
            if (f.type == CatalogType.FILMS && genre != 16) put("without_genres", "16")
            if (f.type == CatalogType.ANIME) put("with_original_language", "ja")
            f.year?.let { put(if (tv) "first_air_date_year" else "primary_release_year", "$it") }
            Countries.code(f.country)?.let { put("with_origin_country", it) }
        }
        return "/discover/${if (tv) "tv" else "movie"}" to p
    }

    fun image(path: String?, size: String): String? = path?.takeIf { it.isNotBlank() && it != "null" }?.let { "$IMG/$size$it" }

    /** A list of results: search, discover, recommendations, a person's films. */
    fun parseList(json: String, kind: TitleKind? = null): List<Title> {
        val o = JSONObject(json)
        val list = o.optJSONArray("results") ?: o.optJSONArray("cast") ?: return emptyList()
        return items(list, kind)
    }

    /** A person's films and series, as actor and as director, most popular first. */
    fun parseCredits(json: String): List<Title> {
        val o = JSONObject(json)
        val cast = o.optJSONArray("cast")?.let { items(it, null) }.orEmpty()
        val crew = o.optJSONArray("crew")?.let { a ->
            items(JSONArray((0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("job") == "Director" }), null)
        }.orEmpty()
        return (cast + crew).distinctBy { it.key }
    }

    private fun items(list: JSONArray, kind: TitleKind?): List<Title> =
        (0 until list.length()).map { list.getJSONObject(it) }
            .filter { it.optString("media_type", "").let { t -> t.isEmpty() || t == "movie" || t == "tv" } }
            .sortedByDescending { if (it.has("popularity")) it.optDouble("popularity") else 0.0 }
            .mapNotNull { item(it, kind) }
            .distinctBy { it.key }

    private fun item(o: JSONObject, kind: TitleKind?): Title? {
        val tv = o.optString("media_type") == "tv" || (o.optString("media_type").isEmpty() && (kind == TitleKind.SERIES || o.has("first_air_date")))
        val name = (if (tv) o.optString("name") else o.optString("title")).ifBlank { return null }
        val original = (if (tv) o.optString("original_name") else o.optString("original_title")).ifBlank { null }
        val year = (if (tv) o.optString("first_air_date") else o.optString("release_date")).take(4).toIntOrNull()
        return Title(
            name = name,
            original = original,
            altNames = listOfNotNull(original),
            year = year,
            kind = if (tv) TitleKind.SERIES else TitleKind.MOVIE,
            poster = image(o.optString("poster_path"), "w342"),
            backdrop = image(o.optString("backdrop_path"), "w1280"),
            tmdbId = o.optInt("id").takeIf { it > 0 },
            info = year?.toString(),
            rating = o.optDouble("vote_average", 0.0).takeIf { it > 0 && o.optInt("vote_count") > 20 }?.let { "TMDB " + String.format(Locale.US, "%.1f", it) },
        )
    }

    fun parseDetails(json: String, kind: TitleKind): MetaInfo {
        val o = JSONObject(json)
        val tv = kind == TitleKind.SERIES
        val credits = o.optJSONObject("credits")
        val cast = credits?.optJSONArray("cast")?.let { a -> (0 until minOf(a.length(), 20)).map { a.getJSONObject(it) } }.orEmpty()
        val crew = credits?.optJSONArray("crew")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
        val directors = if (tv) o.optJSONArray("created_by")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
        else crew.filter { it.optString("job") == "Director" }
        val countries = o.optJSONArray("production_countries")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("iso_3166_1") } }
            ?.ifEmpty { null } ?: o.optJSONArray("origin_country")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        val runtime = if (tv) o.optJSONArray("episode_run_time")?.optInt(0)?.takeIf { it > 0 } else o.optInt("runtime").takeIf { it > 0 }
        val date = if (tv) o.optString("first_air_date") else o.optString("release_date")
        val name = (if (tv) o.optString("name") else o.optString("title")).ifBlank { null }
        val original = (if (tv) o.optString("original_name") else o.optString("original_title")).ifBlank { null }
        val trailer = o.optJSONObject("videos")?.optJSONArray("results")?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("site") == "YouTube" && it.optString("type") == "Trailer" }?.optString("key")
        }
        return MetaInfo(
            name = name,
            original = original,
            year = date.take(4).toIntOrNull(),
            description = o.optString("overview").ifBlank { null },
            poster = image(o.optString("poster_path"), "w500"),
            backdrop = image(o.optString("backdrop_path"), "w1280"),
            rating = o.optDouble("vote_average", 0.0).takeIf { it > 0 && o.optInt("vote_count") > 20 }?.let { "TMDB " + String.format(Locale.US, "%.1f", it) },
            imdbId = (o.optString("imdb_id").ifBlank { null } ?: o.optJSONObject("external_ids")?.optString("imdb_id"))?.takeIf { it.startsWith("tt") },
            tmdbId = o.optInt("id").takeIf { it > 0 },
            trailer = trailer,
            russian = true,
            details = TitleDetails(
                slogan = o.optString("tagline").ifBlank { null },
                released = russianDate(date),
                countries = countries.filter { it.isNotBlank() }.map { Countries.russian(it) }.map { Link(it, MetaLinks.country(it)) },
                genres = o.optJSONArray("genres")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("name") } }.orEmpty()
                    .mapNotNull { g -> Genres.find(g)?.let { Link(it.name, MetaLinks.genre(it.slug)) } ?: Link(g.replaceFirstChar { it.uppercase() }, "") }
                    .filter { it.url.isNotEmpty() }.distinctBy { it.url },
                directors = directors.map { Link(it.optString("name"), MetaLinks.tmdbPerson(it.optInt("id"))) },
                actors = cast.map { Link(it.optString("name"), MetaLinks.tmdbPerson(it.optInt("id"))) },
                photos = (directors + cast).associate { MetaLinks.tmdbPerson(it.optInt("id")) to image(it.optString("profile_path"), "w185") }
                    .filterValues { it != null }.mapValues { it.value!! },
                age = age(o, tv),
                duration = runtime?.let { "$it мин." },
                similar = o.optJSONObject("recommendations")?.optJSONArray("results")?.let { items(it, kind) }.orEmpty().take(20),
            ),
        )
    }

    /** The Russian age rating, or the American one. */
    private fun age(o: JSONObject, tv: Boolean): String? {
        val list = (if (tv) o.optJSONObject("content_ratings") else o.optJSONObject("release_dates"))?.optJSONArray("results") ?: return null
        val byCountry = (0 until list.length()).map { list.getJSONObject(it) }.associateBy { it.optString("iso_3166_1") }
        for (c in listOf("RU", "US")) {
            val r = byCountry[c] ?: continue
            val rating = if (tv) r.optString("rating") else r.optJSONArray("release_dates")?.let { a ->
                (0 until a.length()).map { a.getJSONObject(it).optString("certification") }.firstOrNull { it.isNotBlank() }
            }
            if (!rating.isNullOrBlank()) return if (c == "RU") rating.removeSuffix("+") + "+" else rating
        }
        return null
    }

    /** "/find" by IMDb id: the TMDB id of a film or series. */
    fun parseFind(json: String): Pair<Int, TitleKind>? {
        val o = JSONObject(json)
        o.optJSONArray("movie_results")?.optJSONObject(0)?.optInt("id")?.takeIf { it > 0 }?.let { return it to TitleKind.MOVIE }
        o.optJSONArray("tv_results")?.optJSONObject(0)?.optInt("id")?.takeIf { it > 0 }?.let { return it to TitleKind.SERIES }
        return null
    }

    fun parsePersonSearch(json: String): Int? = JSONObject(json).optJSONArray("results")?.optJSONObject(0)?.optInt("id")?.takeIf { it > 0 }
}
