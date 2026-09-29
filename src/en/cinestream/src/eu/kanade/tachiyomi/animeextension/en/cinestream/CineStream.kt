package eu.kanade.tachiyomi.animeextension.en.cinestream

import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import keiyoushi.network.rateLimit
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSwitchPreference
import keiyoushi.utils.get
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.net.URLEncoder

class CineStream :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "CineStream"
    override val lang = "en"
    override val baseUrl = "https://v3-cinemeta.strem.io"
    override val supportsLatest = true

    override val client: OkHttpClient = network.client.newBuilder()
        .rateLimit(5)
        .build()

    private val preferences: SharedPreferences by getPreferencesLazy()
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val preferredQuality: String
        get() = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT

    private val enableTorrents: Boolean
        get() = preferences.getBoolean(PREF_TORRENTS_KEY, PREF_TORRENTS_DEFAULT)

    private val preferredHoster: String
        get() = preferences.getString(PREF_HOSTER_KEY, PREF_HOSTER_DEFAULT) ?: PREF_HOSTER_DEFAULT

    // ============================== Popular ===============================

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val skip = (page - 1) * PAGE_SIZE
        val url = "https://cinemeta-catalogs.strem.io/top/catalog/movie/top/skip=$skip.json"
        val resp = client.get(url, headers).parseAs<CinemetaCatalogResponse>()
        val animeList = resp.metas.map { media ->
            SAnime.create().apply {
                this.url = "${media.type}/${media.id}"
                this.title = media.name ?: media.aliases?.firstOrNull() ?: media.id
                this.thumbnail_url = media.poster
            }
        }
        return AnimesPage(animeList, resp.hasMore)
    }

    override fun popularAnimeRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun popularAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    // ============================== Latest ================================

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val skip = (page - 1) * PAGE_SIZE
        val url = "https://anime-kitsu.strem.fun/catalog/anime/kitsu-anime-airing/skip=$skip.json"
        val resp = client.get(url, headers).parseAs<CinemetaCatalogResponse>()
        val animeList = resp.metas.map { media ->
            SAnime.create().apply {
                this.url = "anime/${media.id}"
                this.title = media.name ?: media.aliases?.firstOrNull() ?: media.id
                this.thumbnail_url = media.poster
            }
        }
        return AnimesPage(animeList, resp.hasMore)
    }

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException()

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
                    runCatching {
                        client.get(ep, headers).parseAs<CinemetaCatalogResponse>()
                    }.getOrNull()
                }
            }

            val responses = tasks.awaitAll().filterNotNull()
            val results = responses.map { it.metas }
            val hasMore = responses.any { it.metas.size >= PAGE_SIZE }

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

        val resp = client.get(url, headers).parseAs<CinemetaCatalogResponse>()
        val animeList = resp.metas.map { media ->
            SAnime.create().apply {
                this.url = "${media.type}/${media.id}"
                this.title = media.name ?: media.aliases?.firstOrNull() ?: media.id
                this.thumbnail_url = media.poster
            }
        }
        AnimesPage(animeList, resp.hasMore)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException()
    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    override fun getFilterList(): AnimeFilterList = CineStreamFilters.getFilterList()

    // ============================== Details ===============================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val parts = anime.url.split("/", limit = 2)
        val type = parts.firstOrNull() ?: "movie"
        val id = parts.getOrNull(1) ?: anime.url

        val isKitsu = id.startsWith("kitsu") || type == "anime"
        val metaUrl = if (isKitsu) {
            "https://anime-kitsu.strem.fun/meta/$type/${id.replace(":", "%3A")}.json"
        } else {
            "https://v3-cinemeta.strem.io/meta/$type/$id.json"
        }

        val metaResp = client.get(metaUrl, headers).parseAs<CinemetaMetaDetailResponse>()
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
        }
    }

    override fun getAnimeUrl(anime: SAnime): String {
        val parts = anime.url.split("/", limit = 2)
        val type = parts.firstOrNull() ?: "movie"
        val id = parts.getOrNull(1) ?: anime.url
        val metaHost = if (type == "anime" || id.startsWith("kitsu")) {
            "anime-kitsu.strem.fun"
        } else {
            "v3-cinemeta.strem.io"
        }
        return if (id.startsWith("tt")) "https://www.imdb.com/title/$id" else "https://$metaHost/meta/${anime.url}.json"
    }

    override fun animeDetailsRequest(anime: SAnime): Request = throw UnsupportedOperationException()
    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val parts = anime.url.split("/", limit = 2)
        val type = parts.firstOrNull() ?: "movie"
        val id = parts.getOrNull(1) ?: anime.url

        val isKitsu = id.startsWith("kitsu") || type == "anime"
        val kitsuId = if (isKitsu) id.substringAfter("kitsu:") else null

        val metaUrl = if (isKitsu) {
            "https://anime-kitsu.strem.fun/meta/$type/${id.replace(":", "%3A")}.json"
        } else {
            "https://v3-cinemeta.strem.io/meta/$type/$id.json"
        }

        val metaResp = client.get(metaUrl, headers).parseAs<CinemetaMetaDetailResponse>()
        val meta = metaResp.meta ?: return emptyList()

        val externalIds = if (isKitsu && kitsuId != null) {
            runCatching {
                client.get("https://arm.haglund.dev/api/v2/ids?source=kitsu&id=$kitsuId", headers).parseAs<HaglundIds>()
            }.getOrNull()
        } else {
            null
        }

        val title = meta.name ?: anime.title
        val tmdbId = meta.moviedbId ?: externalIds?.themoviedb
        val imdbId = if (isKitsu) externalIds?.imdb else meta.imdbId ?: id.takeIf { it.startsWith("tt") }
        val anilistId = externalIds?.anilist
        val malId = externalIds?.myanimelist
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
                    url = "$type/$id/0/1#${json.encodeToString(payload)}"
                },
            )
        }

        val videos = meta.videos.orEmpty().filter { it.season != 0 }
        return videos.map { ep ->
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
                imdbId = ep.imdbId ?: imdbId,
                imdbSeason = ep.imdbSeason ?: ep.season,
                imdbEpisode = ep.imdbEpisode ?: ep.episode,
                isKitsu = isKitsu,
                anilistId = anilistId,
                malId = malId,
                kitsuId = kitsuId,
            )
            SEpisode.create().apply {
                val epTitle = ep.name?.takeIf { it.isNotBlank() } ?: ep.title?.takeIf { it.isNotBlank() }
                name = if (epTitle != null) "S${ep.season}E${ep.episode} - $epTitle" else "Episode ${ep.episode}"
                episode_number = ep.episode.toFloat()
                summary = ep.overview
                url = "$type/$id/${ep.season}/${ep.episode}#${json.encodeToString(payload)}"
            }
        }.reversed()
    }

    override fun getEpisodeUrl(episode: SEpisode): String = baseUrl

    override fun episodeListRequest(anime: SAnime): Request = throw UnsupportedOperationException()
    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    override fun seasonListRequest(anime: SAnime): Request = throw UnsupportedOperationException()
    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    // ============================== Hosters ===============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val payloadJson = episode.url.substringAfter('#', "")
        if (payloadJson.isBlank()) return emptyList()

        val media = runCatching { json.decodeFromString<MediaPayload>(payloadJson) }.getOrNull()
            ?: return emptyList()

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
                internalData = json.encodeToString(hosterPayload),
            )
        }
    }

    override fun List<Hoster>.sortHosters(): List<Hoster> {
        val pref = preferredHoster
        return sortedWith(
            compareByDescending { it.hosterName.contains(pref, ignoreCase = true) },
        )
    }

    override fun hosterListRequest(episode: SEpisode): Request = throw UnsupportedOperationException()
    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()

    // =============================== Videos ===============================

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val hosterPayload = runCatching {
            json.decodeFromString<HosterPayload>(hoster.internalData)
        }.getOrNull() ?: return emptyList()

        val rawVideos = CineStreamExtractors.extractVideos(
            hosterPayload.providerKey,
            hosterPayload.media,
            client,
            headers,
            playlistUtils,
        )

        // Sort videos directly in getVideoList (as required by lib 16 and dev note)
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

    override fun videoListRequest(hoster: Hoster): Request = throw UnsupportedOperationException()
    override fun videoListParse(response: Response, hoster: Hoster): List<Video> = throw UnsupportedOperationException()

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
        private val PREF_HOSTER_ENTRIES = listOf("Videasy", "Hexa", "Vidrock", "VidFastPro", "Torrentio", "Re:ANIME")
        private val PREF_HOSTER_VALUES = listOf("Videasy", "Hexa", "Vidrock", "VidFastPro", "Torrentio", "Re:ANIME")
        private const val PREF_HOSTER_DEFAULT = "Videasy"
    }
}
