package eu.kanade.tachiyomi.animeextension.en.cinestream

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object CineStreamFilters {

    val CATALOGS = listOf(
        CatalogOption("Top Movies", "https://cinemeta-catalogs.strem.io/top/catalog/movie/top"),
        CatalogOption("Top Series", "https://cinemeta-catalogs.strem.io/top/catalog/series/top"),
        CatalogOption("Airing Anime", "https://anime-kitsu.strem.fun/catalog/anime/kitsu-anime-airing"),
        CatalogOption("Trending Anime", "https://anime-kitsu.strem.fun/catalog/anime/kitsu-anime-trending"),
        CatalogOption("Top Action Movies", "https://cinemeta-catalogs.strem.io/top/catalog/movie/top", "Action"),
        CatalogOption("Top Action Series", "https://cinemeta-catalogs.strem.io/top/catalog/series/top", "Action"),
        CatalogOption("Top Comedy Movies", "https://cinemeta-catalogs.strem.io/top/catalog/movie/top", "Comedy"),
        CatalogOption("Top Comedy Series", "https://cinemeta-catalogs.strem.io/top/catalog/series/top", "Comedy"),
        CatalogOption("Top Romance Movies", "https://cinemeta-catalogs.strem.io/top/catalog/movie/top", "Romance"),
        CatalogOption("Top Romance Series", "https://cinemeta-catalogs.strem.io/top/catalog/series/top", "Romance"),
        CatalogOption("Top Horror Movies", "https://cinemeta-catalogs.strem.io/top/catalog/movie/top", "Horror"),
        CatalogOption("Top Horror Series", "https://cinemeta-catalogs.strem.io/top/catalog/series/top", "Horror"),
        CatalogOption("Top Thriller Movies", "https://cinemeta-catalogs.strem.io/top/catalog/movie/top", "Thriller"),
        CatalogOption("Top Thriller Series", "https://cinemeta-catalogs.strem.io/top/catalog/series/top", "Thriller"),
        CatalogOption("Top Sci-Fi Movies", "https://cinemeta-catalogs.strem.io/top/catalog/movie/top", "Sci-Fi"),
        CatalogOption("Top Sci-Fi Series", "https://cinemeta-catalogs.strem.io/top/catalog/series/top", "Sci-Fi"),
        CatalogOption("Top Fantasy Movies", "https://cinemeta-catalogs.strem.io/top/catalog/movie/top", "Fantasy"),
        CatalogOption("Top Fantasy Series", "https://cinemeta-catalogs.strem.io/top/catalog/series/top", "Fantasy"),
        CatalogOption("Top Mystery Movies", "https://cinemeta-catalogs.strem.io/top/catalog/movie/top", "Mystery"),
        CatalogOption("Top Mystery Series", "https://cinemeta-catalogs.strem.io/top/catalog/series/top", "Mystery"),
        CatalogOption("Top Crime Movies", "https://cinemeta-catalogs.strem.io/top/catalog/movie/top", "Crime"),
        CatalogOption("Top Crime Series", "https://cinemeta-catalogs.strem.io/top/catalog/series/top", "Crime"),
    )

    class CatalogOption(
        val name: String,
        val baseUrl: String,
        val genre: String? = null,
    )

    class CatalogFilter :
        AnimeFilter.Select<String>(
            "Catalog",
            CATALOGS.map { it.name }.toTypedArray(),
            0,
        ) {
        val selected: CatalogOption
            get() = CATALOGS[state]
    }

    fun getFilterList(): AnimeFilterList = AnimeFilterList(
        CatalogFilter(),
    )
}
