package eu.kanade.tachiyomi.animeextension.en.cinestream

import androidx.preference.PreferenceScreen
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import keiyoushi.network.rateLimit
import keiyoushi.utils.Source
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSwitchPreference
import keiyoushi.utils.delegate
import keiyoushi.utils.get
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.Headers
import okhttp3.OkHttpClient
import java.net.URLEncoder

class CineStream : Source() {

    override val name = "CineStream"
    override val lang = "en"
    override val baseUrl = "https://v3-cinemeta.strem.io"
    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36")

    override val client: OkHttpClient = network.client.newBuilder()
        .rateLimit(5)
        .build()

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private val preferredQuality: String by preferences.delegate(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)
    private val enableTorrents: Boolean by preferences.delegate(PREF_TORRENTS_KEY, PREF_TORRENTS_DEFAULT)
    private val preferredHoster: String by preferences.delegate(PREF_HOSTER_KEY, PREF_HOSTER_DEFAULT)

    // ============================== Popular ===============================

    override suspend fun getPopularAnime(page: Int): AnimesPage = coroutineScope {
        val skip = (page - 1) * PAGE_SIZE
        val movieDeferred = async {
            try {
                client.get("https://cinemeta-catalogs.strem.io/top/catalog/movie/top/skip=$skip.json")
                    .parseAs<CinemetaCatalogResponse>()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
        val seriesDeferred = async {
            try {
                client.get("https://cinemeta-catalogs.strem.io/top/catalog/series/top/skip=$skip.json")
                    .parseAs<CinemetaCatalogResponse>()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
        val animeDeferred = async {
            try {
                client.get("https://anime-kitsu.strem.fun/catalog/anime/kitsu-anime-popular/skip=$skip.json")
                    .parseAs<CinemetaCatalogResponse>()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }

        val movieResp = movieDeferred.await()
        val seriesResp = seriesDeferred.await()
        val animeResp = animeDeferred.await()

        val allMetas = (movieResp?.metas.orEmpty() + seriesResp?.metas.orEmpty() + animeResp?.metas.orEmpty())
            .distinctBy { it.id }

        val animeList = allMetas.map { media ->
            SAnime.create().apply {
                this.url = "${media.type}/${media.id}"
                this.title = media.name ?: media.aliases?.firstOrNull() ?: media.id
                this.thumbnail_url = media.poster
            }
        }
        val hasMore = (movieResp?.hasMore == true) || (seriesResp?.hasMore == true) || (animeResp?.hasMore == true)
        AnimesPage(animeList, hasMore)
    }

    // ============================== Latest ================================

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val skip = (page - 1) * PAGE_SIZE
        val url = "https://anime-kitsu.strem.fun/catalog/anime/kitsu-anime-airing/skip=$skip.json"
        val resp = client.get(url).parseAs<CinemetaCatalogResponse>()
        val animeList = resp.metas.map { media ->
            SAnime.create().apply {
                this.url = "anime/${media.id}"
                this.title = media.name ?: media.aliases?.firstOrNull() ?: media.id
                this.thumbnail_url = media.poster
            }
        }
        return AnimesPage(animeList, resp.hasMore)
    }

    // ============================== Search ================================

    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage = coroutineScope {
        if (query.isNotBlank()) {
            val skip = (page - 1) * PAGE_SIZE
            val encQuery = URLEncoder.encode(query.trim(), "UTF-8")
            val endpoints = listOf(
                "https://v3-cinemeta.strem.io/catalog/movie/top/skip=$skip&search=$encQuery.json",
                "https://v3-cinemeta.strem.io/catalog/series/top/skip=$skip&search=$encQuery.json",
                "https://anime-kitsu.strem.fun/catalog/anime/kitsu-anime-airing/skip=$skip&search=$encQuery.json",
            )

            val tasks = endpoints.map { ep ->
                async {
                    try {
                        client.get(ep).parseAs<CinemetaCatalogResponse>()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                }
            }

            val responses = tasks.awaitAll().filterNotNull()
            val results = responses.map { it.metas }
            val hasMore = responses.any { it.hasMore || it.metas.size >= PAGE_SIZE }

            val interleaved = buildList {
                val maxSize = results.maxOfOrNull { it.size } ?: 0
                for (i in 0 until maxSize) {
                    for (list in results) {
                        if (i < list.size) add(list[i])
                    }
                }
            }

            val animeList = interleaved.distinctBy { it.id }.map { media ->
                SAnime.create().apply {
                    this.url = "${media.type}/${media.id}"
                    this.title = media.name ?: media.aliases?.firstOrNull() ?: media.id
                    this.thumbnail_url = media.poster
                }
            }
            return@coroutineScope AnimesPage(animeList, hasMore)
        }

        // Browse / Filter query
        val filterList = filters.ifEmpty { getFilterList() }
        val catalogFilter = filterList.filterIsInstance<CineStreamFilters.CatalogFilter>().firstOrNull()
        val selected = catalogFilter?.selected ?: CineStreamFilters.CATALOGS.first()
        val skip = (page - 1) * PAGE_SIZE

        val url = buildString {
            append(selected.baseUrl)
            append("/skip=$skip")
            if (selected.genre != null) {
                append("&genre=${selected.genre}")
            }
            append(".json")
        }

        val resp = client.get(url).parseAs<CinemetaCatalogResponse>()
        val animeList = resp.metas.map { media ->
            SAnime.create().apply {
                this.url = "${media.type}/${media.id}"
                this.title = media.name ?: media.aliases?.firstOrNull() ?: media.id
                this.thumbnail_url = media.poster
            }
        }
        AnimesPage(animeList, resp.hasMore)
    }

    override fun getFilterList(): AnimeFilterList = CineStreamFilters.getFilterList()

    // ============================== Details ===============================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val parts = anime.url.split("/", limit = 3)
        val type = parts.firstOrNull() ?: "movie"
        val id = parts.getOrNull(1) ?: anime.url

        val isKitsu = id.startsWith("kitsu") || type == "anime"
        val metaUrl = if (isKitsu) {
            "https://anime-kitsu.strem.fun/meta/$type/${id.replace(":", "%3A")}.json"
        } else {
            "https://v3-cinemeta.strem.io/meta/$type/$id.json"
        }

        val metaResp = client.get(metaUrl).parseAs<CinemetaMetaDetailResponse>()
        val meta = metaResp.meta ?: return anime

        val genres = meta.genres ?: meta.genre
        val desc = meta.description
        val year = meta.year ?: meta.releaseInfo

        return anime.apply {
            title = meta.name ?: anime.title
            thumbnail_url = meta.poster ?: anime.thumbnail_url
            description = buildString {
                if (!year.isNullOrBlank()) append("Year: $year\n\n")
                if (!desc.isNullOrBlank()) append(desc)
            }
            genre = genres?.joinToString(", ")
            status = when {
                meta.status?.equals("ended", true) == true || meta.status?.equals("completed", true) == true -> SAnime.COMPLETED
                else -> SAnime.ONGOING
            }
            fetch_type = if (type == "movie") FetchType.Episodes else FetchType.Seasons
        }
    }

    override fun getAnimeUrl(anime: SAnime): String {
        val parts = anime.url.split("/", limit = 3)
        val type = parts.firstOrNull() ?: "movie"
        val id = parts.getOrNull(1) ?: anime.url
        val metaHost = if (type == "anime" || id.startsWith("kitsu")) {
            "anime-kitsu.strem.fun"
        } else {
            "v3-cinemeta.strem.io"
        }
        return if (id.startsWith("tt")) "https://www.imdb.com/title/$id" else "https://$metaHost/meta/${anime.url}.json"
    }

    // ============================== Seasons ===============================

    override suspend fun getSeasonList(anime: SAnime): List<SAnime> {
        val parts = anime.url.split("/", limit = 3)
        val type = parts.firstOrNull() ?: "series"
        val id = parts.getOrNull(1) ?: anime.url

        val isKitsu = id.startsWith("kitsu") || type == "anime"
        val metaUrl = if (isKitsu) {
            "https://anime-kitsu.strem.fun/meta/$type/${id.replace(":", "%3A")}.json"
        } else {
            "https://v3-cinemeta.strem.io/meta/$type/$id.json"
        }

        val metaResp = client.get(metaUrl).parseAs<CinemetaMetaDetailResponse>()
        val meta = metaResp.meta ?: return emptyList()
        val videos = meta.videos.orEmpty().filter { it.season != 0 }
        val seasons = videos.map { it.season }.distinct().sorted()

        if (seasons.isEmpty()) return emptyList()

        return seasons.map { seasonNum ->
            SAnime.create().apply {
                title = "${meta.name ?: anime.title} Season $seasonNum"
                url = "$type/$id/$seasonNum"
                thumbnail_url = meta.poster ?: anime.thumbnail_url
                genre = anime.genre
                description = anime.description
                status = anime.status
                season_number = seasonNum.toDouble()
                fetch_type = FetchType.Episodes
            }
        }
    }

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val parts = anime.url.split("/", limit = 3)
        val type = parts.firstOrNull() ?: "movie"
        val id = parts.getOrNull(1) ?: anime.url
        val filterSeason = parts.getOrNull(2)?.toIntOrNull()

        val isKitsu = id.startsWith("kitsu") || type == "anime"
        val kitsuId = if (isKitsu) id.substringAfter("kitsu:") else null

        val metaUrl = if (isKitsu) {
            "https://anime-kitsu.strem.fun/meta/$type/${id.replace(":", "%3A")}.json"
        } else {
            "https://v3-cinemeta.strem.io/meta/$type/$id.json"
        }

        val metaResp = client.get(metaUrl).parseAs<CinemetaMetaDetailResponse>()
        val meta = metaResp.meta ?: return emptyList()

        val aniZipResp = if (isKitsu && kitsuId != null) {
            try {
                client.get("https://api.ani.zip/mappings?kitsu_id=$kitsuId").parseAs<AniZipResponse>()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }

        val externalIds = if (isKitsu && kitsuId != null && aniZipResp?.mappings?.theMovieDbId == null) {
            try {
                client.get("https://arm.haglund.dev/api/v2/ids?source=kitsu&id=$kitsuId").parseAs<HaglundIds>()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }

        val title = meta.name ?: anime.title
        val tmdbId = meta.moviedbId
            ?: aniZipResp?.mappings?.theMovieDbId?.toIntOrNull()
            ?: externalIds?.themoviedb
        val imdbId = if (isKitsu) {
            aniZipResp?.mappings?.imdbId ?: externalIds?.imdb ?: meta.imdbId
        } else {
            meta.imdbId ?: id.takeIf { it.startsWith("tt") }
        }
        val anilistId = aniZipResp?.mappings?.aniListId?.toInt() ?: externalIds?.anilist
        val malId = aniZipResp?.mappings?.myAnimeListId?.toInt() ?: externalIds?.myanimelist
        val country = meta.country.orEmpty()
        val genres = meta.genres ?: meta.genre ?: emptyList()

        val isCartoon = genres.any { it.contains("Animation", true) }
        val isAnime = isKitsu || ((country.contains("Japan", true) || country.contains("China", true)) && isCartoon)
        val isBollywood = country.contains("India", true)
        val isAsian = (country.contains("Korea", true) || country.contains("China", true)) && !isAnime

        if (type == "movie") {
            val payload = MediaPayload(
                title = title,
                id = id,
                tmdbId = tmdbId,
                tvtype = type,
                year = meta.year ?: meta.releaseInfo,
                season = null,
                episode = null,
                isAnime = isAnime,
                isBollywood = isBollywood,
                isAsian = isAsian,
                isCartoon = isCartoon,
                imdbId = imdbId,
                isKitsu = isKitsu,
                anilistId = anilistId,
                malId = malId,
                kitsuId = kitsuId,
            )
            return listOf(
                SEpisode.create().apply {
                    name = "Movie"
                    episode_number = 1F
                    url = "$type/$id/0/1#${payload.toJsonString()}"
                },
            )
        }

        val rawVideos = meta.videos.orEmpty().filter { it.season != 0 }
        val videos = if (filterSeason != null) {
            rawVideos.filter { it.season == filterSeason }
        } else {
            rawVideos
        }

        return videos.map { ep ->
            val epKey = ep.episode.toString()
            val aniZipEp = aniZipResp?.episodes?.get(epKey)
            val epTitle = aniZipEp?.title?.get("en")
                ?: aniZipEp?.title?.get("x-jat")
                ?: ep.name?.takeIf { it.isNotBlank() }
                ?: ep.title?.takeIf { it.isNotBlank() }
            val epOverview = aniZipEp?.overview ?: ep.overview

            val payload = MediaPayload(
                title = title,
                id = id,
                tmdbId = tmdbId,
                tvtype = type,
                year = meta.year ?: meta.releaseInfo,
                season = ep.season,
                episode = ep.episode,
                firstAired = ep.firstAired ?: ep.released,
                isAnime = isAnime,
                isBollywood = isBollywood,
                isAsian = isAsian,
                isCartoon = isCartoon,
                imdbId = imdbId,
                epImdbId = ep.imdbId,
                imdbSeason = ep.imdbSeason ?: ep.season,
                imdbEpisode = ep.imdbEpisode ?: ep.episode,
                isKitsu = isKitsu,
                anilistId = anilistId,
                malId = malId,
                kitsuId = kitsuId,
            )
            SEpisode.create().apply {
                name = if (epTitle != null) "S${ep.season}E${ep.episode} - $epTitle" else "Episode ${ep.episode}"
                episode_number = ep.episode.toFloat()
                summary = epOverview
                url = "$type/$id/${ep.season}/${ep.episode}#${payload.toJsonString()}"
            }
        }.reversed()
    }

    override fun getEpisodeUrl(episode: SEpisode): String = baseUrl

    // ============================== Hosters ===============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val payloadJson = episode.url.substringAfter('#', "")
        if (payloadJson.isBlank()) return emptyList()

        val media = try {
            payloadJson.parseAs<MediaPayload>()
        } catch (_: Exception) {
            return emptyList()
        }

        val providers = CineStreamExtractors.BUILTIN_PROVIDERS.filter { p ->
            if (p.isTorrent && !enableTorrents) return@filter false
            if (p.isAnimeOnly && !media.isAnime) return@filter false
            if (p.isAsianOnly && !media.isAsian) return@filter false
            true
        }

        return providers.map { p ->
            val hosterPayload = HosterPayload(p.key, media)
            Hoster(
                hosterName = p.name,
                internalData = hosterPayload.toJsonString(),
            )
        }
    }

    override fun List<Hoster>.sortHosters(): List<Hoster> {
        val pref = preferredHoster
        return sortedWith(
            compareByDescending { it.hosterName.contains(pref, ignoreCase = true) },
        )
    }

    // =============================== Videos ===============================

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val hosterPayload = try {
            hoster.internalData.parseAs<HosterPayload>()
        } catch (_: Exception) {
            return emptyList()
        }

        val rawVideos = CineStreamExtractors.extractVideos(
            hosterPayload.providerKey,
            hosterPayload.media,
            client,
            headers,
            playlistUtils,
        )

        // Sort videos directly in getVideoList (as required by Lib 16)
        val prefQual = preferredQuality
        val qualityRank = listOf("2160p", "4k", "1080p", "720p", "480p", "360p")

        val sorted = rawVideos.sortedWith(
            compareByDescending<Video> { it.videoTitle.replace("4k", "2160", ignoreCase = true).contains(prefQual, ignoreCase = true) }
                .thenBy { v ->
                    val idx = qualityRank.indexOfFirst { v.videoTitle.contains(it, ignoreCase = true) }
                    if (idx == -1) qualityRank.size else idx
                },
        )

        return sorted.mapIndexed { index, video ->
            video.copy(preferred = index == 0)
        }
    }

    // ============================ Preferences =============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred Quality",
            entries = PREF_QUALITY_ENTRIES,
            entryValues = PREF_QUALITY_VALUES,
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )
        screen.addSwitchPreference(
            key = PREF_TORRENTS_KEY,
            title = "Enable Torrent Providers",
            summary = "Show Torrentio, TorrentsDB, and AnimeTosho in hoster list",
            default = PREF_TORRENTS_DEFAULT,
        )
        screen.addListPreference(
            key = PREF_HOSTER_KEY,
            title = "Preferred Hoster",
            entries = PREF_HOSTER_ENTRIES,
            entryValues = PREF_HOSTER_VALUES,
            default = PREF_HOSTER_DEFAULT,
            summary = "%s",
        )
    }

    companion object {
        private const val PAGE_SIZE = 20

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private val PREF_QUALITY_ENTRIES = listOf("2160p (4K)", "1080p", "720p", "480p", "360p")
        private val PREF_QUALITY_VALUES = listOf("2160", "1080", "720", "480", "360")
        private const val PREF_QUALITY_DEFAULT = "1080"

        private const val PREF_TORRENTS_KEY = "pref_enable_torrents"
        private const val PREF_TORRENTS_DEFAULT = true

        private const val PREF_HOSTER_KEY = "preferred_hoster"
        private val PREF_HOSTER_ENTRIES = CineStreamExtractors.BUILTIN_PROVIDERS.map { it.name }
        private val PREF_HOSTER_VALUES = CineStreamExtractors.BUILTIN_PROVIDERS.map { it.name }
        private const val PREF_HOSTER_DEFAULT = "Vidrock"
    }
}
