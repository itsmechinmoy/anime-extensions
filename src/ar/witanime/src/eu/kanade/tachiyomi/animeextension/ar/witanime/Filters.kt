package eu.kanade.tachiyomi.animeextension.ar.witanime

import android.content.SharedPreferences
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient

object Filters {

    private const val PREF_GENRES = "pref_dynamic_genres"
    private const val PREF_STATUSES = "pref_dynamic_statuses"
    private const val PREF_TYPES = "pref_dynamic_types"
    private const val PREF_SEASONS = "pref_dynamic_seasons"
    private const val PREF_SORTS = "pref_dynamic_sorts"
    private const val PREF_LAST_FETCH = "pref_dynamic_filters_last_fetch"
    private const val TTL = 86400000L // 24 hours

    suspend fun fetchFilters(client: OkHttpClient, baseUrl: String, preferences: SharedPreferences) {
        val lastFetch = preferences.getLong(PREF_LAST_FETCH, 0L)
        if (System.currentTimeMillis() - lastFetch < TTL && preferences.getString(PREF_GENRES, null) != null) {
            return
        }

        try {
            val headers = Headers.Builder()
                .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .set("Referer", "$baseUrl/")
                .build()

            val doc = client.newCall(GET("$baseUrl/browse", headers)).execute().asJsoup()

            // 1. Genres from checkboxes
            val genres = doc.select("input[type=checkbox]").mapNotNull { cb ->
                val value = cb.attr("value").trim()
                val name = cb.parent()?.text()?.trim() ?: value
                if (value.isNotBlank() && name.isNotBlank() && value != "on") name to value else null
            }.distinctBy { it.second }

            // 2. Statuses, Types, Seasons, Sorts from labels
            val statuses = mutableListOf<Pair<String, String>>()
            val types = mutableListOf<Pair<String, String>>()
            val seasons = mutableListOf<Pair<String, String>>()
            val sorts = mutableListOf<Pair<String, String>>()

            doc.select("label").forEach { label ->
                val inp = label.selectFirst("input") ?: return@forEach
                val value = inp.attr("value").trim()
                val name = label.text().trim()
                if (value.isBlank() || name.isBlank() || name == "الكل") return@forEach

                when {
                    value in listOf("ongoing", "completed", "upcoming") -> statuses.add(name to value)
                    value in listOf("TV", "TV Short", "Movie", "OVA", "ONA", "Special", "Music", "PV", "CM") -> types.add(name to value)
                    value in listOf("spring", "summer", "fall", "winter") -> seasons.add(name to value)
                    value in listOf("Top Rated", "Most Viewed", "A-Z", "Z-A") -> sorts.add(name to value)
                }
            }

            val editor = preferences.edit()
            if (genres.isNotEmpty()) editor.putString(PREF_GENRES, encodePairs(genres))
            if (statuses.isNotEmpty()) editor.putString(PREF_STATUSES, encodePairs(statuses.distinctBy { it.second }))
            if (types.isNotEmpty()) editor.putString(PREF_TYPES, encodePairs(types.distinctBy { it.second }))
            if (seasons.isNotEmpty()) editor.putString(PREF_SEASONS, encodePairs(seasons.distinctBy { it.second }))
            if (sorts.isNotEmpty()) editor.putString(PREF_SORTS, encodePairs(sorts.distinctBy { it.second }))
            editor.putLong(PREF_LAST_FETCH, System.currentTimeMillis())
            editor.apply()
        } catch (_: Exception) {
            // Keep using existing cached
        }
    }

    private fun encodePairs(pairs: List<Pair<String, String>>): String = pairs.joinToString("\n") { "${it.first}|${it.second}" }

    private fun decodePairs(raw: String?): List<Pair<String, String>> = raw?.split("\n")?.mapNotNull { line ->
        val parts = line.split("|")
        if (parts.size == 2) parts[0] to parts[1] else null
    }.orEmpty()

    open class UriPartFilter(
        name: String,
        private val vals: Array<Pair<String, String>>,
        defaultValue: Int = 0,
    ) : AnimeFilter.Select<String>(name, vals.map { it.first }.toTypedArray(), defaultValue) {
        fun toUriPart() = vals[state].second
    }

    class CheckBoxVal(name: String, state: Boolean = false) : AnimeFilter.CheckBox(name, state)

    class GenreFilter(genres: List<Pair<String, String>>) :
        AnimeFilter.Group<CheckBoxVal>("التصنيفات", genres.map { CheckBoxVal(it.first, false) }) {
        private val values = genres.map { it.second }
        fun getSelectedValues(): List<String> = state.mapIndexedNotNull { index, filter ->
            if (filter.state) values[index] else null
        }
    }

    class StatusFilter(vals: Array<Pair<String, String>>) : UriPartFilter("الحالة", vals)
    class TypeFilter(vals: Array<Pair<String, String>>) : UriPartFilter("النوع", vals)
    class SortFilter(vals: Array<Pair<String, String>>) : UriPartFilter("الترتيب", vals)
    class SeasonFilter(vals: Array<Pair<String, String>>) : UriPartFilter("الموسم", vals)
    class YearFilter : AnimeFilter.Text("السنة")

    fun build(preferences: SharedPreferences? = null): AnimeFilterList {
        val cachedGenres = decodePairs(preferences?.getString(PREF_GENRES, null))
        val cachedStatuses = decodePairs(preferences?.getString(PREF_STATUSES, null))
        val cachedTypes = decodePairs(preferences?.getString(PREF_TYPES, null))
        val cachedSeasons = decodePairs(preferences?.getString(PREF_SEASONS, null))
        val cachedSorts = decodePairs(preferences?.getString(PREF_SORTS, null))

        val filters = mutableListOf<AnimeFilter<*>>(
            AnimeFilter.Header("استخدم التصفية عند ترك حقل البحث فارغاً"),
        )

        if (cachedStatuses.isNotEmpty()) {
            filters.add(StatusFilter((listOf(Pair("الكل", "")) + cachedStatuses).toTypedArray()))
        }
        if (cachedTypes.isNotEmpty()) {
            filters.add(TypeFilter((listOf(Pair("الكل", "")) + cachedTypes).toTypedArray()))
        }
        if (cachedSorts.isNotEmpty()) {
            filters.add(SortFilter((listOf(Pair("الافتراضي", "")) + cachedSorts).toTypedArray()))
        }
        if (cachedSeasons.isNotEmpty()) {
            filters.add(SeasonFilter((listOf(Pair("الكل", "")) + cachedSeasons).toTypedArray()))
        }
        filters.add(YearFilter())
        if (cachedGenres.isNotEmpty()) {
            filters.add(GenreFilter(cachedGenres))
        }

        if (cachedGenres.isEmpty() && cachedStatuses.isEmpty()) {
            filters.add(AnimeFilter.Header("جاري تحميل التصنيفات... أعد فتح الفلاتر بعد لحظات."))
        }

        return AnimeFilterList(filters)
    }
}
