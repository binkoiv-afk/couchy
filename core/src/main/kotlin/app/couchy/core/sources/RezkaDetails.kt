package app.couchy.core.sources

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** A name on the site with its page: a person, a genre, a country. */
data class Link(val name: String, val url: String)

/** Everything a title page says about the film besides how to play it. */
data class TitleDetails(
    val slogan: String? = null,
    val released: String? = null,
    val countries: List<Link> = emptyList(),
    val genres: List<Link> = emptyList(),
    val directors: List<Link> = emptyList(),
    val actors: List<Link> = emptyList(),
    val age: String? = null,
    val duration: String? = null,
    /** "Смотрите также": the site's own picks of similar titles. */
    val similar: List<Title> = emptyList(),
    /** Other parts of the franchise, in the site's order. */
    val parts: List<Title> = emptyList(),
    /** Photos of people by their link address. */
    val photos: Map<String, String> = emptyMap(),
) {
    val isEmpty: Boolean get() = this == TitleDetails()

    /** Each part from here, or from [other] where this has none. */
    fun or(other: TitleDetails) = TitleDetails(
        slogan = slogan ?: other.slogan,
        released = released ?: other.released,
        countries = countries.ifEmpty { other.countries },
        genres = genres.ifEmpty { other.genres },
        directors = directors.ifEmpty { other.directors },
        actors = actors.ifEmpty { other.actors },
        age = age ?: other.age,
        duration = duration ?: other.duration,
        similar = similar.ifEmpty { other.similar },
        parts = parts.ifEmpty { other.parts },
        photos = other.photos + photos,
    )
}

object RezkaDetails {
    /** Reads the info table, the cast and the side lists of a title page. */
    fun parse(html: String, self: String? = null): TitleDetails {
        val table = html.substringAfter("class=\"b-post__info\"", "").substringBefore("</table>")
        val rows = Regex("<tr[^>]*>(.*?)</tr>", RegexOption.DOT_MATCHES_ALL).findAll(table).map { it.groupValues[1] }.toList()
        fun row(vararg labels: String): String? = rows.firstOrNull { r ->
            val label = Regex("<h2>([^<]+)</h2>").find(r)?.groupValues?.get(1)?.trim() ?: return@firstOrNull false
            labels.any { label.startsWith(it, ignoreCase = true) }
        }?.let { r -> r.substringAfter("</h2>").substringAfter(":") }
        val people = { r: String? -> links(r).filter { "/person/" in it.url } }
        val selfPath = self?.let(::path)
        return TitleDetails(
            slogan = row("Слоган")?.let(::text)?.trim('«', '»', '"', ' ')?.ifBlank { null }?.takeIf { it != "-" },
            released = row("Дата выхода")?.let(::text)?.ifBlank { null },
            countries = links(row("Страна")),
            genres = links(row("Жанр")),
            directors = people(row("Режиссер", "Режиссёр")),
            actors = people(row("В ролях")),
            age = row("Возраст")?.let(::text)?.let { Regex("\\d+\\+").find(it)?.value },
            duration = row("Время")?.let(::text)?.ifBlank { null },
            similar = section(html, "b-sidelist").filter { path(it.rezkaUrl ?: "") != selfPath },
            parts = section(html, "b-post__partcontent").filter { path(it.rezkaUrl ?: "") != selfPath },
        )
    }

    /** A person's filmography, or any catalogue page: the usual grid of posters. */
    fun parseListing(html: String): List<Title> =
        Rezka.parseSearch(html).ifEmpty { loose(html.substringAfter("b-content__main", html)) }

    private fun section(html: String, marker: String): List<Title> {
        val start = html.indexOf("class=\"$marker")
        if (start < 0) return emptyList()
        // The section ends where the next known part of the page begins.
        val end = stops.filter { marker !in it }.map { html.indexOf(it, start + marker.length + 7) }.filter { it > 0 }.minOrNull() ?: html.length
        val chunk = html.substring(start, minOf(end, start + 60_000))
        return Rezka.parseSearch(chunk).ifEmpty { loose(chunk) }.distinctBy { it.rezkaUrl }
    }

    private val stops = listOf(
        "class=\"b-sidelist", "class=\"b-post__partcontent", "class=\"b-post__social", "class=\"b-post__qa",
        "class=\"b-comment", "id=\"comments", "class=\"b-footer", "id=\"footer",
    )

    /** Title links in any markup: the page address, the picture and the name in the link or its alt. */
    fun loose(chunk: String): List<Title> {
        val found = LinkedHashMap<String, Title>()
        Regex("""<a[^>]*href="(https?://[^"]+/\d+-[^"/]+\.html)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL).findAll(chunk).forEach { m ->
            val url = m.groupValues[1]
            val inner = m.groupValues[2]
            val poster = Regex("""<img[^>]*src="([^"]+)"""").find(inner)?.groupValues?.get(1)
            val name = text(inner).ifBlank { Regex("""alt="([^"]+)"""").find(inner)?.groupValues?.get(1)?.let(Rezka::unescape).orEmpty() }
            val old = found[url]
            found[url] = Title(
                name = old?.name?.takeIf { it.isNotBlank() } ?: name,
                year = Regex("-(\\d{4})\\.html").find(url)?.groupValues?.get(1)?.toIntOrNull(),
                kind = if ("/films/" in url) TitleKind.MOVIE else TitleKind.SERIES,
                poster = old?.poster ?: poster,
                rezkaUrl = url,
            )
        }
        return found.values.filter { it.name.isNotBlank() }
    }

    private fun links(cell: String?): List<Link> {
        cell ?: return emptyList()
        return Regex("""<a[^>]*href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL).findAll(cell).map { m ->
            Link(text(m.groupValues[2]), m.groupValues[1])
        }.filter { it.name.isNotBlank() }.distinctBy { it.url }.toList()
    }

    private fun text(html: String): String =
        Rezka.unescape(html.replace(Regex("<[^>]+>"), " ")).replace(Regex("\\s+"), " ").trim()

    /** "https://rezka.ag/person/1-x/" and "https://hdrezka.me/person/1-x/" are the same page. */
    fun path(url: String): String = "/" + url.substringAfter("://").substringAfter('/', "").substringBefore('#')
}

enum class CatalogType(val label: String, val slug: String) {
    FILMS("Фильмы", "films"), SERIES("Сериалы", "series"), CARTOONS("Мультфильмы", "cartoons"), ANIME("Аниме", "animation"),
}

enum class CatalogSort(val label: String, val filter: String?) {
    POPULAR("Популярные", "popular"), WATCHING("Сейчас смотрят", "watching"), LAST("Новые", "last"), BEST("Лучшие", null),
}

/** What the catalogue shows: one kind of title, sorted one way, narrowed by genre, year and country. */
data class CatalogFilter(
    val type: CatalogType = CatalogType.FILMS,
    val sort: CatalogSort = CatalogSort.POPULAR,
    /** Genre slug as in the site's addresses, see [RezkaCatalog.genres]. */
    val genre: String? = null,
    val year: Int? = null,
    val country: String? = null,
    /** The genre's name when it is not one of [RezkaCatalog.genres]. */
    val genreLabel: String? = null,
)

/** HDrezka's catalogue addresses, and the narrowing the addresses cannot do. */
object RezkaCatalog {
    val genres: List<Pair<String, String>> = listOf(
        "comedy" to "Комедии", "action" to "Боевики", "fiction" to "Фантастика", "thriller" to "Триллеры",
        "drama" to "Драмы", "horror" to "Ужасы", "detective" to "Детективы", "adventures" to "Приключения",
        "crime" to "Криминал", "melodrama" to "Мелодрамы", "fantasy" to "Фэнтези", "family" to "Семейные",
        "kids" to "Детские", "historical" to "Исторические", "military" to "Военные", "biographical" to "Биографические",
        "documentary" to "Документальные", "sport" to "Спортивные", "western" to "Вестерны", "musical" to "Мюзиклы",
        "arthouse" to "Артхаус", "cognitive" to "Познавательные", "standup" to "Стендап", "short" to "Короткометражные",
        "russian" to "Русские", "foreign" to "Зарубежные",
    )

    val countries: List<String> = listOf(
        "США", "Россия", "СССР", "Великобритания", "Франция", "Германия", "Италия", "Испания", "Канада",
        "Япония", "Корея Южная", "Китай", "Индия", "Турция", "Австралия", "Украина", "Беларусь", "Казахстан",
    )

    fun genreName(slug: String?): String? = Genres.bySlug(slug)?.name ?: genres.firstOrNull { it.first == slug }?.second

    /** A genre link from a title page, "https://rezka.ag/films/fiction/", as a catalogue filter. */
    fun fromGenreLink(link: Link): CatalogFilter? {
        val parts = RezkaDetails.path(link.url).substringBefore('?').trim('/').split('/')
        val type = CatalogType.entries.firstOrNull { it.slug == parts.getOrNull(0) } ?: return null
        return CatalogFilter(type, genre = parts.getOrNull(1)?.takeIf { it.isNotBlank() }, genreLabel = link.name)
    }

    /**
     * The address of one page: the site sorts and narrows by genre in the address, and by year
     * only among the best ones; a country has its own list of every kind of title.
     */
    fun path(f: CatalogFilter, page: Int = 1): String {
        val more = if (page > 1) "page/$page/" else ""
        if (f.country != null) {
            return "/country/" + URLEncoder.encode(f.country, "UTF-8").replace("+", "%20") + "/" + more
        }
        if (f.sort == CatalogSort.BEST || f.year != null) {
            return "/${f.type.slug}/best/" + (f.genre?.let { "$it/" } ?: "") + (f.year?.let { "$it/" } ?: "") + more
        }
        return "/${f.type.slug}/" + (f.genre?.let { "$it/" } ?: "") + more + "?filter=" + (f.sort.filter ?: "popular")
    }

    /** What the address could not narrow: kind, year and genre on a country's list. */
    fun keep(f: CatalogFilter, t: Title): Boolean {
        if (f.country == null) return true
        val url = t.rezkaUrl ?: return false
        if ("/${f.type.slug}/" !in url) return false
        if (f.year != null && t.year != f.year) return false
        val genre = genreName(f.genre)
        if (genre != null && genre.take(5).lowercase() !in (t.info ?: "").lowercase() && "/${f.genre}/" !in url) return false
        return true
    }

    /** "Фантастика · фильмы · 2023 · США", for the screen's title. */
    fun describe(f: CatalogFilter): String =
        listOfNotNull(genreName(f.genre) ?: f.genreLabel?.takeIf { f.genre != null } ?: f.type.label, if (f.genre != null) f.type.label.lowercase() else null, f.year?.toString(), f.country)
            .joinToString(" · ")
}

/** Search results kept on the device, so a past search opens at once. */
object TitleJson {
    fun encode(list: List<Title>): JSONArray = JSONArray().apply {
        list.forEach { t ->
            put(
                JSONObject()
                    .put("name", t.name).put("original", t.original).put("alt", JSONArray(t.altNames))
                    .put("year", t.year ?: 0).put("kind", t.kind.name).put("poster", t.poster)
                    .put("imdb", t.imdbId).put("rezka", t.rezkaUrl).put("info", t.info).put("rating", t.rating)
                    .put("backdrop", t.backdrop).put("tmdb", t.tmdbId ?: 0),
            )
        }
    }

    fun decode(a: JSONArray): List<Title> = (0 until a.length()).mapNotNull { i ->
        val o = a.optJSONObject(i) ?: return@mapNotNull null
        fun s(k: String) = o.optString(k).takeIf { o.has(k) && !o.isNull(k) && it.isNotEmpty() }
        Title(
            name = s("name") ?: return@mapNotNull null,
            original = s("original"),
            altNames = o.optJSONArray("alt")?.let { alt -> (0 until alt.length()).map { alt.getString(it) } }.orEmpty(),
            year = o.optInt("year").takeIf { it > 0 },
            kind = runCatching { TitleKind.valueOf(o.optString("kind")) }.getOrDefault(TitleKind.MOVIE),
            poster = s("poster"),
            imdbId = s("imdb"),
            rezkaUrl = s("rezka"),
            info = s("info"),
            rating = s("rating"),
            backdrop = s("backdrop"),
            tmdbId = o.optInt("tmdb").takeIf { it > 0 },
        )
    }
}
