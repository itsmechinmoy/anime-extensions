package eu.kanade.tachiyomi.animeextension.en.anikage

import android.content.SharedPreferences
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import keiyoushi.utils.bodyString
import keiyoushi.utils.get
import okhttp3.Headers
import okhttp3.OkHttpClient

object Filters {

    private const val PREF_GENRES = "pref_dynamic_genres"
    private const val PREF_TAGS = "pref_dynamic_tags"
    private const val PREF_FORMATS = "pref_dynamic_formats"
    private const val PREF_STATUSES = "pref_dynamic_statuses"
    private const val PREF_SEASONS = "pref_dynamic_seasons"
    private const val PREF_SORTS = "pref_dynamic_sorts"

    private val APP_REGEX by lazy { Regex("""/_app/immutable/entry/(app\.[a-zA-Z0-9_\-]+\.js)|entry/(app\.[a-zA-Z0-9_\-]+\.js)""") }
    private val BROWSE_NODE_REGEX by lazy { Regex(""""/\(media\)/browse":\[(\d+)""") }
    private val BIFE_CHUNK_REGEX by lazy { Regex("""chunks/([a-zA-Z0-9_\-]*BIFE[a-zA-Z0-9_\-]*\.js)""") }
    private val CHUNK_IMPORT_REGEX by lazy { Regex("""from"\.\./chunks/([^"]+)"""") }

    private val ARRAY_REGEX by lazy { Regex("""\[([^\[\]]+)\]""") }
    private val LABEL_VALUE_REGEX by lazy { Regex("""label:[`"']([^`"']+)[`"'],value:[`"']([^`"']+)[`"']""") }
    private val GENRE_REGEX by lazy { Regex("""label:[`"']([^`"']+)[`"'],value:[`"']([^`"']+)[`"'],type:[`"']genres[`"']""") }
    private val TAG_REGEX by lazy { Regex("""label:[`"']([^`"']+)[`"'],value:[`"']([^`"']+)[`"'],type:[`"']tags[`"']""") }

    private val SORT_MAP = mapOf(
        "POPULARITY_DESC" to "popularity",
        "SCORE_DESC" to "score",
        "TRENDING_DESC" to "trending",
        "FAVOURITES_DESC" to "favourites",
        "START_DATE_DESC" to "year",
        "ID_DESC" to "updated",
        "TITLE_ENGLISH" to "title",
    )

    suspend fun fetchFilters(client: OkHttpClient, baseUrl: String, preferences: SharedPreferences) {
        try {
            val headers = Headers.Builder()
                .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .set("Referer", "$baseUrl/")
                .build()

            val html = client.get("$baseUrl/browse", headers).bodyString()
            val appFile = APP_REGEX.find(html)?.let { match ->
                match.groupValues.drop(1).firstOrNull { it.isNotBlank() }
            } ?: return

            val appJs = client.get("$baseUrl/_app/immutable/entry/$appFile", headers).bodyString()
            val browseNodeId = BROWSE_NODE_REGEX.find(appJs)?.groupValues?.get(1) ?: return
            val nodeFile = Regex("""nodes/$browseNodeId\.([a-zA-Z0-9_\-]+)\.js""").find(appJs)?.value ?: return

            val nodeJs = client.get("$baseUrl/_app/immutable/$nodeFile", headers).bodyString()
            var chunkName = BIFE_CHUNK_REGEX.find(nodeJs)?.groupValues?.get(1)
            if (chunkName == null) {
                val matches = CHUNK_IMPORT_REGEX.findAll(nodeJs)
                for (match in matches) {
                    val candidate = match.groupValues[1]
                    val content = client.get("$baseUrl/_app/immutable/chunks/$candidate", headers).bodyString()
                    if (content.contains("genres") && content.contains("tags")) {
                        chunkName = candidate
                        break
                    }
                }
            }
            if (chunkName == null) return

            val chunkJs = client.get("$baseUrl/_app/immutable/chunks/$chunkName", headers).bodyString()

            val genres = GENRE_REGEX.findAll(chunkJs).map { it.groupValues[1] }.distinct().toList()
            val tags = TAG_REGEX.findAll(chunkJs).map { it.groupValues[1] }.distinct().toList()

            var formats = emptyList<Pair<String, String>>()
            var seasons = emptyList<Pair<String, String>>()
            var statuses = emptyList<Pair<String, String>>()
            var sorts = emptyList<Pair<String, String>>()

            val arrays = ARRAY_REGEX.findAll(chunkJs)
            for (arr in arrays) {
                val items = LABEL_VALUE_REGEX.findAll(arr.groupValues[1]).map {
                    it.groupValues[1] to it.groupValues[2]
                }.toList()
                if (items.isEmpty()) continue
                val values = items.map { it.second }.toSet()

                if ("WINTER" in values && "SUMMER" in values) {
                    seasons = items
                } else if ("MOVIE" in values && "TV" in values) {
                    formats = items
                } else if ("FINISHED" in values && "RELEASING" in values) {
                    statuses = items
                } else if ("POPULARITY_DESC" in values) {
                    sorts = items.map { (label, value) -> label to (SORT_MAP[value] ?: value.lowercase()) }
                }
            }

            val editor = preferences.edit()
            if (genres.isNotEmpty()) editor.putString(PREF_GENRES, genres.joinToString("\n"))
            if (tags.isNotEmpty()) editor.putString(PREF_TAGS, tags.joinToString("\n"))
            if (formats.isNotEmpty()) editor.putString(PREF_FORMATS, encodePairs(formats))
            if (seasons.isNotEmpty()) editor.putString(PREF_SEASONS, encodePairs(seasons))
            if (statuses.isNotEmpty()) editor.putString(PREF_STATUSES, encodePairs(statuses))
            if (sorts.isNotEmpty()) editor.putString(PREF_SORTS, encodePairs(sorts))
            editor.apply()
        } catch (_: Exception) {
            // Keep using existing cached/defaults
        }
    }

    private fun encodePairs(pairs: List<Pair<String, String>>): String = pairs.joinToString("\n") { "${it.first}|${it.second}" }

    private fun decodePairs(raw: String?): List<Pair<String, String>> = raw?.split("\n")?.mapNotNull { line ->
        val parts = line.split("|")
        if (parts.size == 2) parts[0] to parts[1] else null
    }.orEmpty()

    open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    class SortFilter(vals: Array<Pair<String, String>> = DEFAULT_SORTS.toTypedArray()) : UriPartFilter("Sort by", vals)

    class FormatFilter(vals: Array<Pair<String, String>> = (listOf(Pair("Any", "")) + DEFAULT_FORMATS).toTypedArray()) : UriPartFilter("Format", vals)

    class StatusFilter(vals: Array<Pair<String, String>> = (listOf(Pair("Any", "")) + DEFAULT_STATUSES).toTypedArray()) : UriPartFilter("Status", vals)

    class SeasonFilter(vals: Array<Pair<String, String>> = (listOf(Pair("Any", "")) + DEFAULT_SEASONS).toTypedArray()) : UriPartFilter("Season", vals)

    class YearFilter : AnimeFilter.Text("Year")

    class GenreCheckBox(name: String) : AnimeFilter.CheckBox(name, false)
    class GenreFilter(genres: List<String> = DEFAULT_GENRES) : AnimeFilter.Group<GenreCheckBox>("Genres", genres.map { GenreCheckBox(it) }) {
        fun getIncluded(): List<String> = state.filter { it.state }.map { it.name }
    }

    class TagCheckBox(name: String) : AnimeFilter.CheckBox(name, false)
    class TagFilter(tags: List<String>) : AnimeFilter.Group<TagCheckBox>("Tags", tags.map { TagCheckBox(it) }) {
        fun getIncluded(): List<String> = state.filter { it.state }.map { it.name }
    }

    fun build(preferences: SharedPreferences? = null): AnimeFilterList {
        val cachedSorts = decodePairs(preferences?.getString(PREF_SORTS, null)).takeIf { it.isNotEmpty() }
            ?: DEFAULT_SORTS
        val cachedFormats = listOf(Pair("Any", "")) + (decodePairs(preferences?.getString(PREF_FORMATS, null)).takeIf { it.isNotEmpty() } ?: DEFAULT_FORMATS)
        val cachedStatuses = listOf(Pair("Any", "")) + (decodePairs(preferences?.getString(PREF_STATUSES, null)).takeIf { it.isNotEmpty() } ?: DEFAULT_STATUSES)
        val cachedSeasons = listOf(Pair("Any", "")) + (decodePairs(preferences?.getString(PREF_SEASONS, null)).takeIf { it.isNotEmpty() } ?: DEFAULT_SEASONS)

        val cachedGenres = preferences?.getString(PREF_GENRES, null)
            ?.split("\n")?.filter { it.isNotBlank() }
            ?.takeIf { it.isNotEmpty() } ?: DEFAULT_GENRES

        val cachedTags = preferences?.getString(PREF_TAGS, null)
            ?.split("\n")?.filter { it.isNotBlank() }
            .orEmpty()

        val filters = mutableListOf<AnimeFilter<*>>(
            AnimeFilter.Header("Filters can be combined with a text search"),
            SortFilter(cachedSorts.toTypedArray()),
            FormatFilter(cachedFormats.toTypedArray()),
            StatusFilter(cachedStatuses.toTypedArray()),
            SeasonFilter(cachedSeasons.toTypedArray()),
            YearFilter(),
            GenreFilter(cachedGenres),
        )

        if (cachedTags.isNotEmpty()) {
            filters.add(TagFilter(cachedTags))
        } else {
            filters.add(AnimeFilter.Header("Tags are loading... Reopen filters once loaded."))
        }

        return AnimeFilterList(filters)
    }

    private val DEFAULT_SORTS = listOf(
        Pair("Popularity", "popularity"),
        Pair("Score", "score"),
        Pair("Trending", "trending"),
        Pair("Favourites", "favourites"),
        Pair("Newest", "year"),
        Pair("Recently updated", "updated"),
        Pair("Title", "title"),
    )

    private val DEFAULT_FORMATS = listOf(
        Pair("TV", "TV"),
        Pair("TV Short", "TV_SHORT"),
        Pair("Movie", "MOVIE"),
        Pair("Special", "SPECIAL"),
        Pair("OVA", "OVA"),
        Pair("ONA", "ONA"),
        Pair("Music", "MUSIC"),
    )

    private val DEFAULT_STATUSES = listOf(
        Pair("Releasing", "RELEASING"),
        Pair("Finished", "FINISHED"),
        Pair("Not yet released", "NOT_YET_RELEASED"),
        Pair("Cancelled", "CANCELLED"),
    )

    private val DEFAULT_SEASONS = listOf(
        Pair("Winter", "WINTER"),
        Pair("Spring", "SPRING"),
        Pair("Summer", "SUMMER"),
        Pair("Fall", "FALL"),
    )

    private val DEFAULT_GENRES = listOf(
        "Action",
        "Adventure",
        "Comedy",
        "Drama",
        "Ecchi",
        "Fantasy",
        "Horror",
        "Mahou Shoujo",
        "Mecha",
        "Music",
        "Mystery",
        "Psychological",
        "Romance",
        "Sci-Fi",
        "Slice of Life",
        "Sports",
        "Supernatural",
        "Thriller",
    )
}
