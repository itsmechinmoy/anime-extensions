package eu.kanade.tachiyomi.animeextension.en.av1encodes

import android.net.Uri
import android.util.Log
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import keiyoushi.network.get
import keiyoushi.utils.Source
import keiyoushi.utils.bodyString
import keiyoushi.utils.delegate
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parallelMapNotNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.useAsJsoup
import okhttp3.Dispatcher
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class AV1Encodes : Source() {

    override val name = "AV1Encodes"

    override val lang = "en"

    override val supportsLatest = true

    override var baseUrl: String
        by preferences.delegate(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)

    private val prefQuality: String
        by preferences.delegate(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)

    private val showTorrent: Boolean
        get() = preferences.getBoolean(PREF_SHOW_TORRENT_KEY, PREF_SHOW_TORRENT_DEFAULT)

    override val client: OkHttpClient = network.client.newBuilder()
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = 10 })
        .addInterceptor { chain ->
            val original = chain.request()
            val response = chain.proceed(original)
            if (response.code == 403) {
                response.close()
                runCatching {
                    chain.proceed(
                        original.newBuilder()
                            .url("$baseUrl/")
                            .get()
                            .build(),
                    ).close()
                }
                chain.proceed(original)
            } else {
                response
            }
        }
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // ============================== Popular ===============================

    override suspend fun getPopularAnime(page: Int): AnimesPage = if (page == 1) {
        val response = client.get(baseUrl)
        val animes = parseCardList(response.useAsJsoup()).animes
        AnimesPage(animes, true)
    } else {
        val response = client.get("$baseUrl/anime?page=${page - 1}")
        parseAnimeListPage(response.useAsJsoup())
    }

    // =============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): AnimesPage = if (page == 1) {
        val response = client.get(baseUrl)
        val animes = parseCardList(response.useAsJsoup()).animes
        AnimesPage(animes, true)
    } else {
        val response = client.get("$baseUrl/anime?page=${page - 1}")
        parseAnimeListPage(response.useAsJsoup())
    }

    // =============================== Search ===============================

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val url = if (query.isNotBlank()) {
            "$baseUrl/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("page", page.toString())
                .build()
        } else {
            var sortValue = ""
            var typeValue = ""
            var genreValue = ""
            filters.forEach { filter ->
                when (filter) {
                    is SortFilter -> sortValue = SORT_VALUES.getOrElse(filter.state) { "" }
                    is TypeFilter -> typeValue = TYPE_VALUES.getOrElse(filter.state) { "" }
                    is GenreFilter -> genreValue = GENRE_VALUES.getOrElse(filter.state) { "" }
                    else -> {}
                }
            }

            val builder = "$baseUrl/anime".toHttpUrl().newBuilder()
                .addQueryParameter("page", page.toString())
            if (genreValue.isNotBlank()) builder.addQueryParameter("genres", genreValue)
            if (sortValue.isNotBlank()) builder.addQueryParameter("sort", sortValue)
            if (typeValue.isNotBlank()) builder.addQueryParameter("type", typeValue)
            builder.build()
        }

        val response = client.get(url)
        val doc = response.useAsJsoup()
        return if (url.encodedPath == "/anime") {
            parseAnimeListPage(doc)
        } else {
            parseCardList(doc)
        }
    }

    private fun parseCardList(doc: Document): AnimesPage {
        var animes = doc.select(
            "a.anime-link, article.spotlight-slide, article.anime-card, #episodeGrid article, #latestCompletedList li, article[class*='card']",
        ).mapNotNull { el ->
            val a = if (el.tagName() == "a" && el.attr("href").contains("/anime/")) {
                el
            } else {
                el.selectFirst("a[href*='/anime/'], h3 > a, h4 > a") ?: return@mapNotNull null
            }
            val href = normalizePath(a.attr("href"))
            if (!href.startsWith("/anime/") || href == "/anime/") return@mapNotNull null
            val img = el.selectFirst("img") ?: a.selectFirst("img")
            val titleEl = el.selectFirst(".spotlight-title, h3, h4") ?: a.selectFirst("h3, h4") ?: a
            SAnime.create().apply {
                setUrlWithoutDomain(href)
                title = titleEl.text()
                thumbnail_url = extractImgUrl(img)
            }
        }.distinctBy { it.url }

        if (animes.isEmpty()) {
            val contentRoot = doc.selectFirst(
                "main, #main, #content, .content, [class*='anime-list'], [class*='anime-grid'], " +
                    "[class*='result'], [class*='listing'], [class*='airing'], section.animes",
            ) ?: doc
            animes = contentRoot.select("h3").mapNotNull { h3 ->
                val block = h3.parent() ?: return@mapNotNull null
                val a = block.selectFirst("a[href*='/anime/']")
                    ?: block.parent()?.selectFirst("a[href*='/anime/']")
                    ?: return@mapNotNull null
                val href = normalizePath(a.attr("href"))
                if (!href.startsWith("/anime/") || href == "/anime/") return@mapNotNull null
                val img = block.parent()?.selectFirst("img") ?: block.selectFirst("img")
                SAnime.create().apply {
                    setUrlWithoutDomain(href)
                    title = h3.text()
                    thumbnail_url = extractImgUrl(img)
                }
            }.distinctBy { it.url }
        }

        val hasNextPage = doc.selectFirst(
            "a.next-page, .pagination a[rel=next], .pagination .next:not(.disabled), " +
                "nav.pagination a:contains(Next), [aria-label=Next page]",
        ) != null
        return AnimesPage(animes, hasNextPage)
    }

    private suspend fun parseAnimeListPage(doc: Document): AnimesPage {
        val animes = doc.select("li > a[href*='/anime/'], a.anime-index-link").mapNotNull { a ->
            val href = normalizePath(a.attr("href"))
            if (!href.startsWith("/anime/") || href == "/anime/") return@mapNotNull null
            val titleText = a.text().ifBlank { return@mapNotNull null }
            SAnime.create().apply {
                setUrlWithoutDomain(href)
                title = titleText
            }
        }.distinctBy { it.url }

        val hasNextPage = doc.selectFirst(
            "a.next-page, a[rel=next], .pagination .next, a:contains(Next)",
        ) != null

        return AnimesPage(animes.fetchMissingCovers(), hasNextPage)
    }

    private suspend fun List<SAnime>.fetchMissingCovers(): List<SAnime> {
        return parallelMapNotNull { anime ->
            runCatching {
                if (anime.thumbnail_url != null) return@runCatching anime
                val doc = client.get(baseUrl + anime.url).useAsJsoup()
                val img = doc.selectFirst(
                    "img.anime-poster, img.poster, .anime-hero img, " +
                        "[class*='poster'] img, [class*='hero'] img, main img",
                )
                anime.thumbnail_url = extractImgUrl(img)
                    ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
                anime
            }.getOrElse { anime }
        }
    }

    // ============================== Details ===============================

    override fun getAnimeUrl(anime: SAnime): String = baseUrl + anime.url

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val animeUrl = if (anime.url.startsWith("http")) anime.url else baseUrl + anime.url
        val doc = client.get(animeUrl).useAsJsoup()
        return SAnime.create().apply {
            title = doc.selectFirst(
                ".anime-hero h1, h1.anime-title, [class*='anime-hero'] h1, [class*='detail'] h1, main h1, h1",
            )?.text() ?: anime.title

            val img = doc.selectFirst(
                "img.anime-poster, img.poster, .anime-hero img, [class*='poster'] img, [class*='hero'] img, main img",
            )
            thumbnail_url = extractImgUrl(img)
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: extractBg(
                    doc.selectFirst(
                        ".anime-poster, .poster, .anime-hero, [class*='poster'], [class*='hero']",
                    ) ?: doc,
                )

            description = doc.selectFirst(
                ".anime-synopsis, .synopsis, .description, [class*='synopsis'], [class*='description'], [class*='overview'], .desc",
            )?.text()
            genre = doc.select(
                ".genre-tag, .tag, a[href*='/genre/'], a[href*='/tag/'], [class*='genre'] a, link[rel='tag'][href*='/genre/']",
            ).map { el ->
                if (el.tagName() == "link") el.attr("href").substringAfterLast("/").replaceFirstChar { it.uppercase() } else el.text()
            }.distinct().filter { it.isNotBlank() }.joinToString().ifBlank {
                doc.selectFirst("p.anime-meta")?.text()?.substringAfter("Genre:")?.substringBefore("|")?.trim()
            }?.ifBlank { null }
            author = doc.selectFirst(".studio, .studio-name, [class*='studio']")?.text()
            status = if (doc.selectFirst("[class*='airing'], .status-airing, .airing-badge") != null) {
                SAnime.ONGOING
            } else {
                SAnime.COMPLETED
            }
        }
    }

    // ============================== Episodes ==============================

    override fun getEpisodeUrl(episode: SEpisode): String = baseUrl + episode.url

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val animeUrl = if (anime.url.startsWith("http")) anime.url else baseUrl + anime.url
        val doc = client.get(animeUrl).useAsJsoup()
        val slug = anime.url.trim('/').split("/").last { it.isNotBlank() }

        val seasons = doc.select(".season-tab[data-season], .season-option[data-season], [data-season]")
            .map { it.attr("data-season") }
            .filter { it.isNotBlank() }
            .distinct()
            .ifEmpty { listOf("1") }

        val resolutionCandidates = qualityCandidates(prefQuality)

        return seasons.sortedByDescending { it.toIntOrNull() ?: 0 }.parallelCatchingFlatMap { season ->
            var epHtml = ""
            var downloadLinks: List<Element> = emptyList()
            var selectedRes = resolutionCandidates.first()

            for (res in resolutionCandidates) {
                selectedRes = res
                val epPageUrl = "$baseUrl/episodes/$slug/$season/$res"
                val html = runCatching { client.get(epPageUrl).bodyString() }.getOrNull() ?: continue
                epHtml = html

                if (html.trim().startsWith("[")) {
                    val items = runCatching { html.parseAs<List<EpisodeItem>>() }.getOrNull()
                    if (!items.isNullOrEmpty()) {
                        return@parallelCatchingFlatMap items.sortedByDescending { it.num }.map { item ->
                            val filename = Uri.decode(item.href.substringAfterLast("/").substringBefore("?"))
                            SEpisode.create().apply {
                                setUrlWithoutDomain(item.href)
                                name = if (item.label.isNotBlank()) item.label else buildEpisodeLabel(filename, season)
                                episode_number = if (item.num > 0) item.num.toFloat() else parseEpisodeNumber(filename)
                            }
                        }
                    }
                }

                val parsed = Jsoup.parse(html)
                val links = parsed.select("a[href*='/download/']")
                if (links.isNotEmpty()) {
                    downloadLinks = links
                    break
                }
            }

            if (downloadLinks.isEmpty() && epHtml.isNotBlank()) {
                val filenames = extractFilenames(epHtml)
                if (filenames.isNotEmpty()) {
                    return@parallelCatchingFlatMap filenames.sortedByDescending { parseEpisodeNumber(it) }.map { filename ->
                        val encodedFilename = URLEncoder.encode(filename, "UTF-8").replace("+", "%20")
                        SEpisode.create().apply {
                            setUrlWithoutDomain("/download/$slug/$season/$selectedRes/$encodedFilename")
                            name = buildEpisodeLabel(filename, season)
                            episode_number = parseEpisodeNumber(filename)
                        }
                    }
                }
            }

            downloadLinks.sortedByDescending { link ->
                EPISODE_NUMBER_REGEX.find(link.attr("href"))?.groupValues?.get(1)?.toIntOrNull() ?: 0
            }.map { link ->
                val fullHref = link.attr("href")
                val filename = Uri.decode(fullHref.substringAfterLast("/").substringBefore("?"))
                SEpisode.create().apply {
                    setUrlWithoutDomain(fullHref)
                    name = buildEpisodeLabel(filename, season)
                    episode_number = parseEpisodeNumber(filename)
                }
            }
        }
    }

    // =============================== Hosters ===============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> = listOf(
        Hoster(
            hosterName = "AV1Encodes",
            internalData = episode.url,
        ),
    )

    // =============================== Videos ================================

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val episodeUrl = hoster.internalData
        if (episodeUrl.isBlank()) return emptyList()

        val encodedFilename = episodeUrl.substringBefore("?").substringAfterLast("/")
        val filename = Uri.decode(encodedFilename)

        var downloadPageUrl = baseUrl + episodeUrl
        var pageHtml = runCatching { client.get(downloadPageUrl).bodyString() }.getOrNull()

        if (pageHtml == null || !pageHtml.contains("anime-video-player")) {
            val pathParts = episodeUrl.substringBefore("?").trim('/').split("/")
            if (pathParts.size >= 5 && pathParts[0] == "download") {
                val slug = pathParts[1]
                val season = pathParts[2]
                val res = pathParts[3]
                val freshEpDoc = runCatching {
                    client.get("$baseUrl/episodes/$slug/$season/$res").useAsJsoup()
                }.getOrNull()
                val freshLink = freshEpDoc?.select("a[href*='/download/']")?.firstOrNull {
                    it.attr("href").contains(encodedFilename)
                }?.attr("href")
                if (!freshLink.isNullOrBlank()) {
                    downloadPageUrl = baseUrl + freshLink
                    pageHtml = runCatching { client.get(downloadPageUrl).bodyString() }.getOrNull()
                }
            }
        }

        if (pageHtml.isNullOrBlank()) {
            Log.e(TAG, "getVideoList: failed to load download page for $encodedFilename")
            return fallbackDirectUrl(episodeUrl, filename)
        }

        val videos = mutableListOf<Video>()

        val resLabel = RES_LABEL_REGEX.find(filename)?.groupValues?.get(1) ?: prefQuality
        val audioTag = AUDIO_TAG_REGEX.find(filename)?.groupValues?.get(1) ?: ""
        val audioSuffix = if (audioTag.isNotBlank()) " [$audioTag]" else ""
        val qualBase = "AV1 · $resLabel$audioSuffix"

        // 1. Check embedded player iframe (bypasses DDL captcha gate completely)
        val iframeSrc = Jsoup.parse(pageHtml).selectFirst("iframe#anime-video-player, iframe[src*='/r/']")?.attr("src")
        if (!iframeSrc.isNullOrBlank()) {
            val watchUrl = resolveRedirect(iframeSrc)
            if (watchUrl != null && watchUrl.contains("/watch/")) {
                val dashBase = watchUrl.replace("/watch/", "/dash/")
                val mpdUrl = "$dashBase/manifest.mpd"
                videos.add(Video(videoUrl = mpdUrl, videoTitle = "$qualBase · DASH"))
                videos.add(Video(videoUrl = watchUrl, videoTitle = "$qualBase · Stream"))
            }
        }

        // 2. Also query get_ddl if token is found
        val ddlToken = TOKEN_REGEX.find(pageHtml)?.groupValues?.get(1)
        if (ddlToken != null) {
            val ddlUrl = "$baseUrl/get_ddl/$encodedFilename"
            val ddl = runCatching {
                client.get(
                    ddlUrl,
                    headers.newBuilder()
                        .set("Accept", "application/json")
                        .set("Referer", downloadPageUrl)
                        .set("X-Ddl-Token", ddlToken)
                        .build(),
                ).parseAs<DdlResponse>()
            }.getOrNull()

            if (ddl != null && ddl.success) {
                val sizeLabel = ddl.fileSize?.let { " · $it" } ?: ""
                val qualLabel = "$qualBase$sizeLabel"

                val watchUrl = resolveRedirect(ddl.watchLink)
                if (watchUrl != null && watchUrl.contains("/watch/")) {
                    val dashBase = watchUrl.replace("/watch/", "/dash/")
                    val mpdUrl = "$dashBase/manifest.mpd"
                    if (videos.none { it.videoUrl == mpdUrl }) {
                        videos.add(Video(videoUrl = mpdUrl, videoTitle = "$qualLabel · DASH"))
                    }
                }

                val streamUrl = resolveRedirect(ddl.streamLink)
                if (streamUrl != null && streamUrl != watchUrl && videos.none { it.videoUrl == streamUrl }) {
                    videos.add(Video(videoUrl = streamUrl, videoTitle = "$qualLabel · Stream"))
                }

                val dlUrl = resolveRedirect(ddl.downloadLink)
                if (dlUrl != null && videos.none { it.videoUrl == dlUrl }) {
                    videos.add(Video(videoUrl = dlUrl, videoTitle = "$qualLabel · Direct DL"))
                }

                if (showTorrent && !ddl.torrentLink.isNullOrBlank()) {
                    val torrentUrl = resolveRedirect(ddl.torrentLink)
                    if (torrentUrl != null && videos.none { it.videoUrl == torrentUrl }) {
                        videos.add(Video(videoUrl = torrentUrl, videoTitle = "$qualLabel · Torrent"))
                    }
                }
            }
        }

        if (videos.isEmpty()) {
            Log.w(TAG, "getVideoList: no videos found from player or DDL, falling back")
            return fallbackDirectUrl(episodeUrl, filename)
        }

        return videos.sortByPreferredQuality(preferences).mapIndexed { index, video ->
            if (index == 0) video.copy(preferred = true) else video
        }
    }

    private suspend fun resolveRedirect(path: String?): String? {
        if (path.isNullOrBlank()) return null
        val url = if (path.startsWith("/")) "$baseUrl$path" else path
        return runCatching {
            client.get(url).use { resp ->
                resp.request.url.toString()
            }
        }.getOrNull()
    }

    private fun fallbackDirectUrl(episodeUrl: String, filename: String): List<Video> {
        val fullUrl = baseUrl + episodeUrl
        val resLabel = RES_LABEL_REGEX.find(filename)?.groupValues?.get(1) ?: prefQuality
        val audioTag = AUDIO_TAG_REGEX.find(filename)?.groupValues?.get(1) ?: ""
        val label = "AV1 · $resLabel${if (audioTag.isNotBlank()) " [$audioTag]" else ""} · Direct DL"
        return listOf(Video(videoUrl = fullUrl, videoTitle = label))
    }

    // =========================== Extraction Helpers ========================

    private fun extractFilenames(html: String): List<String> {
        val filenames = mutableSetOf<String>()
        val addDecoded = { fn: String ->
            val clean = Uri.decode(fn.trim())
            if (clean.isNotBlank() && !clean.contains("/")) filenames.add(clean)
        }
        Jsoup.parse(html).select("a[href*='/download/']").forEach {
            addDecoded(it.attr("href").substringAfterLast("/").substringBefore("?"))
        }
        FILENAME_REGEX.findAll(html).forEach { addDecoded(it.groupValues[1]) }
        return filenames.toList()
    }

    private fun buildEpisodeLabel(filename: String, season: String): String {
        val epMatch = EPISODE_NAME_REGEX.find(filename)
        return if (epMatch != null) {
            val e = epMatch.groupValues[1]
            val titlePart = epMatch.groupValues[2].trim()
            val audioTag = SUBDUB_REGEX.find(filename)?.groupValues?.get(1) ?: ""
            "Season $season Ep $e - $titlePart${if (audioTag.isNotBlank()) " [$audioTag]" else ""}"
        } else {
            val cleanName = filename.replace(QUALITY_REGEX, "").substringBeforeLast(".").trim()
            if (season != "1" && season.isNotBlank()) "Season $season - $cleanName" else cleanName
        }
    }

    private fun parseEpisodeNumber(filename: String): Float = EPISODE_S_NUMBER_REGEX.find(filename)?.groupValues?.get(1)?.toFloatOrNull() ?: 1f

    private fun extractImgUrl(img: Element?): String? {
        if (img == null) return null
        val src = img.attr("abs:data-src").ifBlank { img.attr("abs:data-lazy-src") }.ifBlank { img.attr("abs:src") }
        return src.takeIf { it.isNotBlank() }
    }

    private fun extractBg(el: Element): String? {
        val style = el.attr("style")
        if (!style.contains("background", ignoreCase = true)) return null
        val match = BACKGROUND_URL_REGEX.find(style) ?: return null
        val url = match.groupValues[1].ifBlank { return null }
        return if (url.startsWith("http")) url else "$baseUrl/${url.removePrefix("/")}"
    }

    private fun qualityCandidates(pref: String): List<String> {
        val list = mutableListOf(pref)
        listOf("1920 x 1080", "1280 x 720", "854 x 480", "640 x 360").forEach {
            if (it !in list) list.add(it)
        }
        return list.map { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
    }

    private fun normalizePath(href: String): String {
        val value = href.trim()
        if (value.startsWith("/")) return value
        if (!value.startsWith("http", ignoreCase = true)) return value

        return runCatching {
            val url = value.toHttpUrl()
            val host = url.host
            val allowed = host == baseUrl.toHttpUrl().host ||
                host.endsWith("av1encodes.com") ||
                host.endsWith("animealpha.cc") ||
                host.endsWith("av1please.com")
            if (allowed) {
                buildString {
                    append(url.encodedPath)
                    url.encodedQuery?.let { append('?').append(it) }
                }
            } else {
                ""
            }
        }.getOrDefault("")
    }

    // =============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        SortFilter(),
        TypeFilter(),
        GenreFilter(),
    )

    // ============================= Preferences ============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        buildPreferenceScreen(screen)
    }

    // ============================== Constants ==============================

    companion object {
        private const val TAG = "AV1Encodes"

        private val RES_LABEL_REGEX = Regex("""\[(\d+p)]""")
        private val AUDIO_TAG_REGEX = Regex("""\[(Dual|Sub|Dub|Tri|Multi)]""", RegexOption.IGNORE_CASE)
        private val EPISODE_NUMBER_REGEX = Regex("""E(\d+)""", RegexOption.IGNORE_CASE)
        private val EPISODE_S_NUMBER_REGEX = Regex("""\[(?:S\d+-)?E(\d+)]""")
        private val EPISODE_NAME_REGEX = Regex("""\[(?:S\d+-)?E(\d+)]\s*(.+?)\s*\[""")
        private val SUBDUB_REGEX = Regex("""\[(Dual|Sub|Dub|English Dub)]""", RegexOption.IGNORE_CASE)
        private val QUALITY_REGEX = Regex("""\[\d{3,4}p].*""")
        private val FILENAME_REGEX = Regex("""([a-zA-Z0-9_ \-\[\]().%]+?\.(?:mkv|mp4))""", RegexOption.IGNORE_CASE)
        private val TOKEN_REGEX = Regex("""['"](A{4,}[A-Za-z0-9_\-]{10,})['"]""")
        private val BACKGROUND_URL_REGEX = Regex("""url\(['"](.*?)['"]\)""")
    }
}
