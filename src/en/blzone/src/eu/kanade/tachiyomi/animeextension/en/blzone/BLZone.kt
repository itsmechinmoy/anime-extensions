package eu.kanade.tachiyomi.animeextension.en.blzone

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.filemoonextractor.FilemoonExtractor
import aniyomi.lib.mixdropextractor.MixDropExtractor
import aniyomi.lib.pixeldrainextractor.PixelDrainExtractor
import aniyomi.lib.playlistutils.PlaylistUtils
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import aniyomi.lib.vidguardextractor.VidGuardExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class BLZone :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "BLZone"
    override val baseUrl = "https://blzone.net"
    override val lang = "en"
    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    private val preferences by getPreferencesLazy()

    companion object {
        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Filemoon"
        private val SERVER_LIST = arrayOf("Filemoon", "StreamTape", "MixDrop", "VidGuard", "Voe", "PixelDrain")
    }

    // ---- FILTERS ----
    override fun getFilterList(): AnimeFilterList = AnimeFilterList(TypeFilter())

    private class TypeFilter :
        UriPartFilter(
            "Type",
            arrayOf(
                Pair("Both", ""),
                Pair("Anime", "anime"),
                Pair("Drama", "dorama"),
            ),
        )

    open class UriPartFilter(displayName: String, private val vals: Array<Pair<String, String>>) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
        fun isEmpty() = vals[state].second == ""
        fun isDefault() = state == 0
    }

    // ---- POPULAR ----
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/trending/", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = document.select("#dt-tvshows .item.tvshows, #dt-movies .item.tvshows, #dt-animes .item.tvshows, #dt-doramas .item.tvshows")
            .map { popularAnimeFromElement(it) }
        return AnimesPage(animeList, hasNextPage = false)
    }

    private fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val poster = element.selectFirst(".poster")
        val link = poster?.selectFirst("a")?.attr("href") ?: element.selectFirst("h3 a")?.attr("href")!!
        val img = poster?.selectFirst("img") ?: element.selectFirst("img")
        anime.title = img?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: element.selectFirst("h3 a")?.text()
            ?: "No title"
        anime.thumbnail_url = img?.let { getImageUrl(it) }
        anime.setUrlWithoutDomain(link)
        return anime
    }

    private fun getImageUrl(element: Element): String? = when {
        element.hasAttr("data-src") -> element.attr("abs:data-src")
        element.hasAttr("data-lazy-src") -> element.attr("abs:data-lazy-src")
        element.hasAttr("data-original") -> element.attr("abs:data-original")
        element.hasAttr("srcset") -> element.attr("abs:srcset").substringBefore(" ")
        else -> element.attr("abs:src")
    }

    // ---- LATEST ----
    override fun latestUpdatesRequest(page: Int): Request {
        val animePageUrl = if (page == 1) "$baseUrl/anime/" else "$baseUrl/anime/page/$page/"
        return GET(animePageUrl, headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = document.select(".items.full .item.tvshows, .items .item.tvshows")
            .map { latestAnimeFromElement(it) }.toMutableList()

        if (response.request.url.encodedPath.endsWith("/anime/")) {
            runCatching {
                client.newCall(GET("$baseUrl/dorama/", headers)).execute()
                    .use { resp ->
                        resp.asJsoup()
                            .select(".items.full .item.tvshows, .items .item.tvshows")
                            .map { latestAnimeFromElement(it) }
                            .let { animeList.addAll(it) }
                    }
            }
        }
        return AnimesPage(animeList, hasNextPage = hasNextPage(document))
    }

    private fun latestAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    private fun hasNextPage(document: Document): Boolean = document.selectFirst(".pagination span.current + a, .pagination a.inactive, .pagination .next:not(.disabled), .pagination a.arrow_pag") != null

    // ---- SEARCH ----
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val typeFilter = filters.filterIsInstance<TypeFilter>().firstOrNull()
        val url = baseUrl.toHttpUrl()
            .newBuilder().apply {
                if (typeFilter != null && !typeFilter.isDefault()) {
                    addPathSegment(typeFilter.toUriPart())
                    addPathSegment("")
                }
                if (page > 1) {
                    addPathSegment("page")
                    addPathSegment(page.toString())
                    addPathSegment("")
                }
                addQueryParameter("s", query.trim())
            }
            .build()
        return GET(url.toString(), headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = document.select(".search-page .result-item article, .result-item article, .items.full .item.tvshows")
            .map { searchAnimeFromElement(it) }
        return AnimesPage(animeList, hasNextPage = hasNextPage(document))
    }

    private fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val img = element.selectFirst(".thumbnail img, .image img, .poster img")
        val link = element.selectFirst(".thumbnail a, .image a, .title a, .poster a")?.attr("href")!!
        anime.title = img?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: element.selectFirst(".title a, h3 a")?.text()
            ?: "No title"
        anime.thumbnail_url = img?.let { getImageUrl(it) }
        anime.setUrlWithoutDomain(link)
        return anime
    }

    // ---- DETAILS ----
    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val anime = SAnime.create()
        val poster = document.selectFirst(".sheader .poster img")
        (document.selectFirst(".sheader .data h1")?.text() ?: poster?.attr("alt"))?.let {
            anime.title = it
        }
        anime.thumbnail_url = poster?.let { getImageUrl(it) }
        anime.genre = document.select(".sheader .sgeneros a").joinToString { it.text().trim() }
        val desc = document.selectFirst(".sbox .wp-content p")?.text()
            ?.takeIf { it.isNotBlank() }
        val altTitle = document.selectFirst(".custom_fields b.variante:contains(Original Title) + span.valor, .custom_fields b.variante:contains(Original Title) + span")?.text()
            ?.takeIf { it.isNotBlank() }
        anime.description = listOfNotNull(desc, altTitle)
            .joinToString("\n\n")
            .ifBlank { "No description available." }
        return anime
    }

    // ---- EPISODES ----
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodes = document.select("#episodes ul.episodios2 > li, #episodes ul.episodios > li, #seasons ul.episodios > li, ul.episodios2 > li, ul.episodios > li")
        return episodes.map { episodeFromElement(it) }.reversed()
    }

    private val episodeNumRegex = Regex("""Episode (\d+)""", RegexOption.IGNORE_CASE)

    private fun episodeFromElement(element: Element): SEpisode {
        val ep = SEpisode.create()
        val titleEl = element.selectFirst(".episodiotitle a")
        val link = titleEl?.attr("href")
            ?: element.selectFirst(".imagen a, a")?.attr("href")!!
        ep.setUrlWithoutDomain(link)
        ep.name = titleEl?.text()?.trim()
            ?: element.selectFirst(".numerando")?.text()?.trim()
            ?: "Episode"
        val episodeNum = episodeNumRegex.find(ep.name)?.groupValues?.getOrNull(1)
            ?: element.selectFirst(".numerando")?.text()?.filter { it.isDigit() }
        episodeNum?.toFloatOrNull()?.let { ep.episode_number = it }
        return ep
    }

    // ---- VIDEO EXTRACTORS ----
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val mixDropExtractor by lazy { MixDropExtractor(client) }
    private val vidGuardExtractor by lazy { VidGuardExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val pixelDrainExtractor by lazy { PixelDrainExtractor() }
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    // ---- VIDEO LIST PARSE ----
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val serverNames = document.select("#playeroptionsul li span.title").map { it.text().trim() }
        val serverBoxes = document.select(".dooplay_player .source-box").drop(1)

        return serverBoxes.mapIndexedNotNull { index, box ->
            val serverLabel = serverNames.getOrNull(index)?.ifBlank { null } ?: "Server ${index + 1}"
            val iframe = box.selectFirst("iframe.metaframe, iframe")
            val src = iframe?.attr("src")?.trim().orEmpty()
            if (src.isBlank()) return@mapIndexedNotNull null

            val videoUrl = if (src.contains("/diclaimer/?url=")) {
                runCatching {
                    URLDecoder.decode(src.substringAfter("/diclaimer/?url="), StandardCharsets.UTF_8.name())
                }.getOrDefault(src)
            } else {
                src
            }
            Video(videoUrl, serverLabel, videoUrl)
        }
    }

    // ---- GET VIDEO LIST ----
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val response = client.newCall(GET(baseUrl + episode.url, headers)).await()
        val videos = videoListParse(response)

        return coroutineScope {
            videos.map { video ->
                async(Dispatchers.IO) {
                    try {
                        serverVideoResolver(video.url, video.quality)
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }
    }

    private fun serverVideoResolver(url: String, serverName: String): List<Video> = when {
        url.contains("filemoon") -> filemoonExtractor.videosFromUrl(url, "FileMoon")
        url.contains("streamtape") -> streamtapeExtractor.videosFromUrl(url, "StreamTape")
        url.contains("mixdrop") -> mixDropExtractor.videosFromUrl(url, "MixDrop")
        url.contains("voe.sx") || (url.contains("/e/") && serverName.contains("voe", ignoreCase = true)) -> voeExtractor.videosFromUrl(url)
        url.contains("pixeldrain") || serverName.contains("pixel", ignoreCase = true) -> pixelDrainExtractor.videosFromUrl(url)
        url.contains("vgembed") || url.contains("byseqekaho") || url.contains("vidguard") || serverName.contains("byse", ignoreCase = true) || serverName.contains("vidguard", ignoreCase = true) -> vidGuardExtractor.videosFromUrl(url)
        url.contains(".m3u8") -> playlistUtils.extractFromHls(url, videoNameGen = { "$serverName: $it" })
        url.contains(".mp4") -> listOf(Video(url, "$serverName: MP4", url))
        else -> listOf(Video(url, serverName, url))
    }

    override fun List<Video>.sort(): List<Video> {
        val preferredServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        return this.sortedWith(
            compareByDescending { it.quality.contains(preferredServer, ignoreCase = true) },
        )
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred server"
            entries = SERVER_LIST
            entryValues = SERVER_LIST
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)
    }
}
