package eu.kanade.tachiyomi.animeextension.en.kisskh

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.BuildConfig
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import keiyoushi.network.rateLimit
import keiyoushi.utils.LazyMutable
import keiyoushi.utils.UrlUtils
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSwitchPreference
import keiyoushi.utils.bodyString
import keiyoushi.utils.delegate
import keiyoushi.utils.get
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingMapNotNull
import keiyoushi.utils.parseAs
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

class KissKH :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "KissKH"

    override val lang = "en"

    override val supportsLatest = true

    override val client = network.client.newBuilder()
        .rateLimit(5)
        .build()

    private val preferences by getPreferencesLazy()

    override var baseUrl: String
        by preferences.delegate(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)

    private val hideUnaired: Boolean
        get() = preferences.getBoolean(PREF_HIDE_UNAIRED_KEY, PREF_HIDE_UNAIRED_DEFAULT)

    private var subDecryptor by LazyMutable { SubDecryptor(client, headers, baseUrl) }

    override val supportsRelatedAnimes = false

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET(browseUrl(page, order = 1))

    override fun popularAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    override suspend fun getPopularAnime(page: Int): AnimesPage = fetchDramaPage(page, order = 1)

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET(browseUrl(page, order = 2))

    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    override suspend fun getLatestUpdates(page: Int): AnimesPage = fetchDramaPage(page, order = 2)

    private fun browseUrl(page: Int, order: Int): String = "$baseUrl/api/DramaList/List?page=$page&type=0&sub=0&country=0&status=0&order=$order&pageSize=$PAGE_SIZE"

    private suspend fun fetchDramaPage(page: Int, order: Int): AnimesPage {
        val response = client.get(browseUrl(page, order))
        val dto = response.parseAs<DramaPageDto>()
        val hasNextPage = dto.data.size >= PAGE_SIZE
        val animeList = dto.data.mapNotNull { it.toSAnime() }
        return AnimesPage(animeList, hasNextPage)
    }

    // =============================== Search ===============================

    private fun searchUrl(query: String) = "$baseUrl/api/DramaList/Search".toHttpUrl().newBuilder()
        .addQueryParameter("q", query)
        .addQueryParameter("type", "0")
        .build()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET(searchUrl(query))

    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val response = client.get(searchUrl(query))
        val list = response.parseAs<List<DramaDto>>()
        val animeList = list.mapNotNull { it.toSAnime() }
        return AnimesPage(animeList, hasNextPage = false)
    }

    private fun DramaDto.toSAnime(): SAnime? {
        val dramaTitle = title ?: return null
        val dramaId = id ?: return null
        val titleURI = dramaTitle.replace(titleUriRegex, "-")
        return SAnime.create().apply {
            this.title = dramaTitle
            url = "/Drama/$titleURI?id=$dramaId"
            thumbnail_url = thumbnail
        }
    }

    // ============================== Details ===============================

    override fun getAnimeUrl(anime: SAnime): String = baseUrl + anime.url

    override fun animeDetailsRequest(anime: SAnime): Request {
        val id = anime.url.substringAfter("id=").substringBefore("&")
        return GET("$baseUrl/api/DramaList/Drama/$id?isq=false", headers)
    }

    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val id = anime.url.substringAfter("id=").substringBefore("&")
        val response = client.get("$baseUrl/api/DramaList/Drama/$id?isq=false", headers)
        val dto = response.parseAs<DramaDetailDto>()
        return SAnime.create().apply {
            dto.title?.let { title = it }
            status = parseStatus(dto.status)
            dto.description?.let { description = it }
            dto.thumbnail?.let { thumbnail_url = it }
        }
    }

    private fun parseStatus(status: String?) = when {
        status == null -> SAnime.UNKNOWN
        status.contains("Ongoing", ignoreCase = true) -> SAnime.ONGOING
        status.contains("Completed", ignoreCase = true) -> SAnime.COMPLETED
        else -> SAnime.UNKNOWN
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request = animeDetailsRequest(anime)

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val id = anime.url.substringAfter("id=").substringBefore("&")
        val response = client.get("$baseUrl/api/DramaList/Drama/$id?isq=false", headers)
        val dto = response.parseAs<DramaDetailDto>()
        val type = dto.type
        val episodesCount = dto.episodesCount ?: 1
        val isAiringOrUpcoming = dto.status?.let {
            it.contains("Ongoing", ignoreCase = true) || it.contains("Upcoming", ignoreCase = true)
        } == true

        val episodes = if (hideUnaired && isAiringOrUpcoming) {
            filterUnairedEpisodes(dto.episodes)
        } else {
            dto.episodes
        }

        return episodes.mapNotNull { ep ->
            val epId = ep.id?.toString() ?: return@mapNotNull null
            val number = ep.number?.toString()?.replace(".0", "") ?: "1"
            SEpisode.create().apply {
                url = epId
                ep.number?.let { episode_number = it }
                when {
                    type.isNullOrBlank() -> {
                        name = "Video $number"
                    }

                    (type.contains("Hollywood") && episodesCount == 1) || type.contains("Movie") -> {
                        name = "Movie"
                    }

                    type.contains("Anime") || type.contains("TVSeries") ||
                        (type.contains("Hollywood") && episodesCount > 1) -> {
                        name = "Episode $number"
                    }
                }
            }
        }
    }

    private suspend fun filterUnairedEpisodes(episodes: List<EpisodeDto>): List<EpisodeDto> {
        val latestEpId = episodes.firstOrNull()?.id?.toString() ?: return episodes
        val isUnaired = try {
            isEpisodeUnaired(latestEpId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        return if (isUnaired) episodes.drop(1) else episodes
    }

    private suspend fun isEpisodeUnaired(epId: String): Boolean {
        val kkey = requestVideoKey(epId)
        val url = "$baseUrl/api/DramaList/Episode/$epId.png?err=false&ts=&time=&kkey=$kkey"
        val videoDto = client.get(url, headers).parseAs<EpisodeVideoDto>()
        return isCountdownWidget(videoDto.video, videoDto.type)
    }

    private fun isCountdownWidget(videoUrl: String?, type: Int?): Boolean {
        if (type == TYPE_COUNTDOWN) return true
        if (videoUrl.isNullOrBlank()) return false
        return videoUrl.contains("tickcounter.com", ignoreCase = true) ||
            videoUrl.contains("/widget/countdown/", ignoreCase = true)
    }

    // =========================== Hosters & Videos ==========================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> = listOf(
        Hoster(
            hosterName = "KissKH",
            internalData = episode.url,
        ),
    )

    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val id = hoster.internalData
        val kkey = requestVideoKey(id)
        val url = "$baseUrl/api/DramaList/Episode/$id.png?err=false&ts=&time=&kkey=$kkey"
        val videoDto = client.get(url, headers).parseAs<EpisodeVideoDto>()

        if (isCountdownWidget(videoDto.video, videoDto.type)) {
            val countdown = getCountdownDetails(videoDto.video)
            val message = if (countdown != null) {
                "This episode has not aired yet ($countdown)"
            } else {
                "This episode has not aired yet (countdown timer active)"
            }
            throw Exception(message)
        }

        val videoUrl = videoDto.video?.takeIf(String::isNotBlank) ?: return emptyList()

        val subKey = requestSubKey(id)
        val subList = client.get("$baseUrl/api/Sub/$id?kkey=$subKey")
            .parseAs<List<SubtitleDto>>()
            .parallelCatchingMapNotNull { item ->
                val suburl = item.src?.takeIf(String::isNotBlank) ?: return@parallelCatchingMapNotNull null
                val lang = item.label?.takeIf(String::isNotBlank) ?: "Unknown"
                if (suburl.contains(".txt")) {
                    subDecryptor.getSubtitles(suburl, lang)
                } else {
                    Track(suburl, lang)
                }
            }

        return UrlUtils.fixUrl(videoUrl)?.let { fixedVideoUrl ->
            Video(
                videoUrl = fixedVideoUrl,
                videoTitle = "FirstParty",
                subtitleTracks = subList,
                headers = Headers.headersOf("referer", "$baseUrl/", "origin", baseUrl),
            ).let(::listOf)
        } ?: emptyList()
    }

    private suspend fun requestVideoKey(id: String): String {
        val url = "${BuildConfig.KISSKH_API}$id&version=2.8.10"
        return client.get(url, headers).parseAs<KeyDto>().key
    }

    private suspend fun requestSubKey(id: String): String {
        val url = "${BuildConfig.KISSKH_SUB_API}$id&version=2.8.10"
        return client.get(url, headers).parseAs<KeyDto>().key
    }

    private suspend fun getCountdownDetails(url: String?): String? = try {
        val widgetUrl = UrlUtils.fixUrl(url ?: return null) ?: return null
        val html = client.get(widgetUrl).bodyString()
        val match = COUNTDOWN_REGEX.find(html) ?: return null
        val (dateStr, tzStr) = match.destructured
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone(tzStr)
        }
        val target = format.parse(dateStr)?.time ?: return null
        val diff = target - System.currentTimeMillis()
        if (diff <= 0) {
            "airs soon"
        } else {
            val days = TimeUnit.MILLISECONDS.toDays(diff)
            val hours = TimeUnit.MILLISECONDS.toHours(diff) % 24
            val minutes = TimeUnit.MILLISECONDS.toMinutes(diff) % 60
            buildString {
                append("airs in ")
                if (days > 0) append("${days}d ")
                if (hours > 0 || days > 0) append("${hours}h ")
                append("${minutes}m")
            }.trim()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    // ============================= Preferences =============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_DOMAIN_KEY,
            title = "Preferred domain",
            entries = DOMAIN_ENTRIES,
            entryValues = DOMAIN_VALUES,
            default = PREF_DOMAIN_DEFAULT,
            summary = "%s",
        ) {
            baseUrl = it
            subDecryptor = SubDecryptor(client, headers, baseUrl)
        }

        screen.addSwitchPreference(
            key = PREF_HIDE_UNAIRED_KEY,
            title = "Hide unaired episodes",
            summary = "Hide upcoming episodes that only have a countdown timer",
            default = PREF_HIDE_UNAIRED_DEFAULT,
        )
    }

    private val titleUriRegex by lazy { Regex("[^a-zA-Z0-9]") }

    companion object {
        private const val PAGE_SIZE = 40
        private const val TYPE_COUNTDOWN = 2

        private const val PREF_DOMAIN_KEY = "preferred_domain"
        private val DOMAIN_ENTRIES = listOf(
            "kisskh.ovh",
            "kisskh.do",
            "kisskh.co",
            "kisskh.id",
            "kisskh.la",
            "kisskh.is",
        )
        private val DOMAIN_VALUES = DOMAIN_ENTRIES.map { "https://$it" }
        private val PREF_DOMAIN_DEFAULT = DOMAIN_VALUES[0]

        private const val PREF_HIDE_UNAIRED_KEY = "pref_hide_unaired_episodes"
        private const val PREF_HIDE_UNAIRED_DEFAULT = true

        private val COUNTDOWN_REGEX by lazy {
            Regex("""window\.countdown\("([^"]+)",\s*"[^"]*",\s*\d+,\s*"[^"]*",\s*"([^"]+)"""")
        }
    }
}
