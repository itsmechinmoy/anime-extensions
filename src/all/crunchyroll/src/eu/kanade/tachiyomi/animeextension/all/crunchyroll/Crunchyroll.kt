package eu.kanade.tachiyomi.animeextension.all.crunchyroll

import android.content.SharedPreferences
import android.text.InputType
import android.util.Log
import androidx.preference.PreferenceScreen
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
import eu.kanade.tachiyomi.network.POST
import keiyoushi.utils.addEditTextPreference
import keiyoushi.utils.addListPreference
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingMapNotNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString
import keiyoushi.utils.tryParse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Crunchyroll, using the user's own account.
 *
 * Streams are Widevine-protected DASH. The manifest and license endpoint are passed
 * to the player through [Video.internalData]; the device CDM does the key exchange.
 */
class Crunchyroll :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Crunchyroll"

    override val baseUrl = "https://www.crunchyroll.com"

    override val lang = "all"

    override val supportsLatest = true

    private val preferences: SharedPreferences by getPreferencesLazy()

    init {
        // The app looks for a stored sub/dub value to decide whether to show its
        // switch, and a list preference does not persist its default until changed.
        if (!preferences.contains(PREF_AUDIO_TYPE)) {
            preferences.edit().putString(PREF_AUDIO_TYPE, PREF_AUDIO_TYPE_DEFAULT).apply()
        }
    }

    // ============================== Config ================================

    private val basicToken get() = preferences.getString(PREF_BASIC, "")!!.trim()
    private val etpRt get() = preferences.getString(PREF_ETP_RT, "")!!.trim()
    private val email get() = preferences.getString(PREF_EMAIL, "")!!.trim()
    private val password get() = preferences.getString(PREF_PASSWORD, "")!!.trim()
    private val contentLocale get() = preferences.getString(PREF_LOCALE, PREF_LOCALE_DEFAULT)!!
    private val dubLocale get() = preferences.getString(PREF_AUDIO, PREF_AUDIO_DEFAULT)!!

    // Dubs are sibling seasons of one series, so the app's sub/dub switch flips
    // PREF_AUDIO_TYPE instead of picking a different entry.
    private val audioLocale: String
        get() {
            val dubbed = preferences.getString(PREF_AUDIO_TYPE, PREF_AUDIO_TYPE_DEFAULT) == AUDIO_DUB
            if (!dubbed) return ORIGINAL_LOCALE
            return dubLocale.takeUnless { it == ORIGINAL_LOCALE } ?: FALLBACK_DUB_LOCALE
        }
    private val subtitleLocale get() = preferences.getString(PREF_SUB, PREF_SUB_DEFAULT)!!

    private val deviceId: String by lazy {
        preferences.getString(PREF_DEVICE_ID, null)?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString().also {
                preferences.edit().putString(PREF_DEVICE_ID, it).apply()
            }
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("Origin", baseUrl)
        .set("Referer", "$baseUrl/")

    // =============================== Auth =================================

    @Volatile private var accessToken: String? = null

    @Volatile private var tokenExpiresAt = 0L

    @Volatile private var workingGrant: String? = null

    // No cookie jar: BridgeInterceptor sets the Cookie header with header(), which
    // replaces the etp_rt cookie set here and makes the exchange fail with
    // invalid_request.
    private val authClient = network.client.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .build()

    override val client = network.client.newBuilder()
        .addInterceptor(::authIntercept)
        .build()

    private fun authIntercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.encodedPath.endsWith("/auth/v1/token")) {
            return chain.proceed(request)
        }

        val response = chain.proceed(request.withToken(token()))
        if (response.code != 401) return response

        // Token rejected early (revoked profile switch, clock skew) - force one retry.
        response.close()
        return chain.proceed(request.withToken(token(force = true)))
    }

    private fun okhttp3.Request.withToken(token: String) = newBuilder().header("Authorization", "Bearer $token").build()

    @Synchronized
    private fun token(force: Boolean = false): String {
        val cached = accessToken
        if (!force && cached != null && System.currentTimeMillis() < tokenExpiresAt) {
            return cached
        }

        if (basicToken.isEmpty()) {
            throw IOException("Set the Basic auth token in the extension settings")
        }

        // A web credential only accepts the cookie exchange, a mobile/TV one only
        // the password grant, and the user cannot tell which they pasted.
        val grants = buildList {
            if (email.isNotEmpty() && password.isNotEmpty()) add(GRANT_PASSWORD)
            if (etpRt.isNotEmpty()) add(GRANT_COOKIE)
        }
            // Tokens last 300s; retrying a hopeless grant that often gets a 429.
            .sortedByDescending { it == workingGrant }

        if (grants.isEmpty()) {
            throw IOException("Set either the etp_rt cookie, or email + password, in the extension settings")
        }

        var lastError: String? = null

        for (grant in grants) {
            val form = FormBody.Builder().apply {
                add("scope", "offline_access")
                add("device_id", deviceId)
                add("device_name", DEVICE_NAME)
                add("device_type", DEVICE_TYPE)
                add("grant_type", grant)
                if (grant == GRANT_PASSWORD) {
                    add("username", email)
                    add("password", password)
                }
            }.build()

            val authHeaders = headersBuilder()
                .set("Authorization", "Basic $basicToken")
                .apply { if (grant == GRANT_COOKIE) set("Cookie", "etp_rt=$etpRt") }
                .build()

            val response = authClient.newCall(
                POST("$baseUrl/auth/v1/token", authHeaders, form),
            ).execute()

            if (!response.isSuccessful) {
                lastError = runCatching { response.parseAs<ErrorDto>().error }.getOrNull()
                    ?: "HTTP ${response.code}"
                Log.w(LOG_TAG, "token: grant '$grant' rejected ($lastError)")
                response.close()
                continue
            }

            val dto = response.parseAs<TokenDto>()
            workingGrant = grant
            accessToken = dto.accessToken
            tokenExpiresAt = System.currentTimeMillis() + (dto.expiresIn - 30).coerceAtLeast(30) * 1000L
            return dto.accessToken
        }

        throw IOException(
            when (lastError) {
                "unsupported_grant_type" ->
                    "Login rejected: this Basic token does not support the credentials given. " +
                        "A web token needs the etp_rt cookie; email + password needs a mobile/TV token."
                "invalid_grant" -> "Login rejected: the etp_rt cookie or password is no longer valid."
                else -> "Login failed: $lastError"
            },
        )
    }

    // ============================== Popular ===============================

    override suspend fun getPopularAnime(page: Int): AnimesPage = browse(page, "popularity")

    override fun popularAnimeRequest(page: Int) = throw UnsupportedOperationException()
    override fun popularAnimeParse(response: Response) = throw UnsupportedOperationException()

    // =============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): AnimesPage = browse(page, "newly_added")

    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response) = throw UnsupportedOperationException()

    private suspend fun browse(page: Int, sortBy: String): AnimesPage {
        val url = "$baseUrl/content/v2/discover/browse".toHttpUrl().newBuilder()
            .addQueryParameter("n", PAGE_SIZE.toString())
            .addQueryParameter("start", ((page - 1) * PAGE_SIZE).toString())
            .addQueryParameter("sort_by", sortBy)
            .addQueryParameter("type", "series")
            .addQueryParameter("locale", contentLocale)
            .build()

        val data = client.newCall(GET(url, headers)).execute().parseAs<BrowseResponseDto>()
        return AnimesPage(
            data.data.map { it.toSAnime() },
            page * PAGE_SIZE < data.total,
        )
    }

    // =============================== Search ===============================

    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage {
        if (query.isBlank()) return browse(page, "popularity")

        val url = "$baseUrl/content/v2/discover/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("n", PAGE_SIZE.toString())
            .addQueryParameter("start", ((page - 1) * PAGE_SIZE).toString())
            .addQueryParameter("type", "series")
            .addQueryParameter("locale", contentLocale)
            .build()

        val data = client.newCall(GET(url, headers)).execute().parseAs<SearchResponseDto>()
        val items = data.data.firstOrNull { it.type == "series" }?.items.orEmpty()

        // Crunchyroll keeps sequels as seasons of one series while trackers list them
        // as separate titles, so a multi-season series is offered one entry per season
        // - otherwise both titles map here and their episode numbers collide.
        val entries = items.parallelCatchingMapNotNull { item ->
            if (item.seasonCount > 1) seasonEntries(item) else listOf(item.toSAnime())
        }.flatten()

        return AnimesPage(entries, items.size >= PAGE_SIZE)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) = throw UnsupportedOperationException()
    override fun searchAnimeParse(response: Response) = throw UnsupportedOperationException()

    private fun seasonEntries(item: ContentItemDto): List<SAnime> {
        val seasonsUrl = "$baseUrl/content/v2/cms/series/${item.id}/seasons?locale=$contentLocale"
        val seasons = runCatching {
            client.newCall(GET(seasonsUrl, headers)).execute().parseAs<SeasonsResponseDto>().data
        }.getOrNull().orEmpty().distinctBy { it.seasonNumber to it.seasonDisplayNumber }

        if (seasons.size < 2) return listOf(item.toSAnime())

        val series = item.toSAnime()
        return seasons.sortedBy { it.seasonNumber }.map { season ->
            SAnime.create().apply {
                url = "$SEASON_PREFIX${season.guidFor(audioLocale)}:${item.id}"
                title = season.cleanTitle.ifEmpty { "${series.title} S${season.seasonNumber}" }
                thumbnail_url = season.thumbnail ?: series.thumbnail_url
                description = series.description
                genre = series.genre
                status = series.status
                initialized = true
            }
        }
    }

    // =========================== Anime Details ============================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val url = "$baseUrl/content/v2/cms/series/${anime.seriesId}?locale=$contentLocale"
        val data = client.newCall(GET(url, headers)).execute().parseAs<BrowseResponseDto>()
        val series = data.data.firstOrNull()?.toSAnime() ?: return anime
        // A season entry has its own title and artwork; only the blurb is shared.
        if (anime.seasonId == null) return series
        return anime.apply {
            description = series.description
            genre = series.genre
            status = series.status
            initialized = true
        }
    }

    override fun animeDetailsRequest(anime: SAnime) = throw UnsupportedOperationException()
    override fun animeDetailsParse(response: Response) = throw UnsupportedOperationException()

    override fun getAnimeUrl(anime: SAnime) = "$baseUrl/series/${anime.seriesId}"

    /** Season entries are stored as `season:<seasonId>:<seriesId>`. */
    private val SAnime.seasonId: String?
        get() = url.takeIf { it.startsWith(SEASON_PREFIX) }
            ?.removePrefix(SEASON_PREFIX)?.substringBefore(':')

    private val SAnime.seriesId: String
        get() = if (url.startsWith(SEASON_PREFIX)) url.substringAfterLast(':') else url

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        // A season entry maps 1:1 onto a tracker season, so its episodes keep the
        // numbering the tracker expects and need no season label.
        anime.seasonId?.let { seasonId ->
            val url = "$baseUrl/content/v2/cms/seasons/$seasonId/episodes?locale=$contentLocale"
            return client.newCall(GET(url, headers)).execute()
                .parseAs<EpisodesResponseDto>().data
                .map { it.toSEpisode("", dateFormat.tryParse(it.date), audioLocale) }
                .reversed()
        }

        val seasonsUrl = "$baseUrl/content/v2/cms/series/${anime.url}/seasons?locale=$contentLocale"
        val seasons = client.newCall(GET(seasonsUrl, headers)).execute()
            .parseAs<SeasonsResponseDto>().data

        // A series can list the same season once per audio locale.
        val wanted = seasons.distinctBy { it.seasonNumber to it.seasonDisplayNumber }

        val bySeason = wanted.sortedBy { it.seasonNumber }
            .parallelCatchingMapNotNull { season ->
                val seasonId = season.guidFor(audioLocale)
                val url = "$baseUrl/content/v2/cms/seasons/$seasonId/episodes?locale=$contentLocale"
                season to client.newCall(GET(url, headers)).execute()
                    .parseAs<EpisodesResponseDto>().data
            }
            .sortedBy { (season, _) -> season.seasonNumber }

        // Every season restarts at episode 1, so numbering them as they come makes
        // season 2 collide with season 1 and the app keeps only one of each pair.
        // Offsetting by the episodes already listed keeps them distinct and ordered.
        var offset = 0f
        return bySeason.flatMap { (season, episodes) ->
            val label = if (bySeason.size > 1) {
                "S${season.seasonDisplayNumber.ifEmpty { season.seasonNumber.toString() }}"
            } else {
                ""
            }
            val numbered = episodes.map {
                it.toSEpisode(label, dateFormat.tryParse(it.date), audioLocale, offset)
            }
            offset += episodes.size
            numbered
        }.reversed()
    }

    override fun episodeListRequest(anime: SAnime) = throw UnsupportedOperationException()
    override fun episodeListParse(response: Response) = throw UnsupportedOperationException()
    override fun seasonListRequest(anime: SAnime) = throw UnsupportedOperationException()
    override fun seasonListParse(response: Response) = throw UnsupportedOperationException()

    override fun getEpisodeUrl(episode: SEpisode) = "$baseUrl/watch/${episode.url}"

    // ============================== Hosters ===============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        // No playback-stopped hook exists, so this is the only reliable place to
        // hand back the previous slot before taking another.
        releasePreviousStream()

        val playUrl = "$PLAY_HOST/v1/${episode.url}/$PLAY_PROFILE/play"
        val response = client.newCall(GET(playUrl, headers)).execute()

        if (!response.isSuccessful) {
            val body = runCatching { response.peekBody(2048).string() }.getOrDefault("")
            Log.e(LOG_TAG, "play: ${episode.url} -> HTTP ${response.code} $body")
            val detail = runCatching { response.parseAs<ErrorDto>() }.getOrNull()
            throw IOException(
                when {
                    detail?.error?.contains("subscription", true) == true ->
                        "Your subscription does not cover this episode"
                    detail?.error?.contains("Rejected", true) == true ->
                        "Playback rejected - too many active streams, or the episode is unavailable in your region"
                    detail?.error != null -> detail.error
                    else -> "Could not start playback (HTTP ${response.code})"
                },
            )
        }

        val play = response.parseAs<PlayResponseDto>()
        play.token?.let {
            lastStream = episode.url to it
            startKeepAlive(episode.url, it, play.session)
        }

        val offline = play.drm?.let { offlinePayload(episode.url) }

        val drmPayload = play.drm?.let { drm ->
            // The license proxy needs all three; any two of them still return 403.
            DrmPayload(
                drmScheme = drm.name,
                licenseUrl = drm.drmUrl,
                licenseHeaders = buildMap {
                    put("Authorization", "Bearer ${token()}")
                    put("x-cr-content-id", episode.url)
                    play.token?.let { put("x-cr-video-token", it) }
                },
                offline = offline,
            ).toJsonString()
        }.orEmpty()

        if (play.drm != null && play.token == null) {
            Log.w(LOG_TAG, "play response had DRM but no stream token - license will 403")
        }

        val title = buildString {
            append("Crunchyroll")
            if (play.audioLocale.isNotEmpty()) append(" · ${localeName(play.audioLocale)}")
            if (play.drm != null) append(" · DRM")
        }

        return listOf(
            Hoster(
                hosterName = title,
                internalData = play.url,
                videoList = buildVideoList(play, drmPayload),
            ),
        )
    }

    override fun hosterListRequest(episode: SEpisode) = throw UnsupportedOperationException()
    override fun hosterListParse(response: Response) = throw UnsupportedOperationException()

    // ============================ Video Links =============================

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        return hoster.videoList ?: emptyList()
    }

    override fun videoListRequest(hoster: Hoster) = throw UnsupportedOperationException()
    override fun videoListParse(response: Response, hoster: Hoster) = throw UnsupportedOperationException()

    private fun buildVideoList(play: PlayResponseDto, drmPayload: String): List<Video> {
        val videoHeaders = headersBuilder()
            .set("Authorization", "Bearer ${token()}")
            .build()

        // A locale can appear in both maps, so merging them as maps would drop the
        // dialogue track in favour of the CC one.
        val subtitles = buildList {
            play.subtitles.forEach { (locale, sub) -> add(Track(sub.url, localeName(locale))) }
            play.captions.forEach { (locale, sub) -> add(Track(sub.url, "${localeName(locale)} (CC)")) }
        }.sortedWith(compareByDescending { it.lang.startsWith(localeName(subtitleLocale)) })

        val title = buildString {
            append("Crunchyroll")
            if (play.audioLocale.isNotEmpty()) append(" · ${localeName(play.audioLocale)}")
            if (play.drm != null) append(" · DRM")
        }

        return listOf(
            Video(
                videoUrl = play.url,
                videoTitle = title,
                headers = videoHeaders,
                subtitleTracks = subtitles,
                internalData = drmPayload,
            ),
        )
    }

    // Downloads come from their own profile: 720p only, no renewal session, and a
    // license endpoint that mints persistent licenses. Signed URLs last ~6h.
    // Never throws - a failure here must not stop playback.
    private fun offlinePayload(episodeId: String): OfflinePayload? = runCatching {
        val url = "$PLAY_HOST/v1/$episodeId/$DOWNLOAD_PROFILE/download"
        val response = client.newCall(GET(url, headers)).execute()
        if (!response.isSuccessful) {
            response.close()
            return null
        }
        val download = response.parseAs<PlayResponseDto>()
        val drm = download.drm ?: return null
        OfflinePayload(
            manifestUrl = download.url,
            headers = mapOf("Authorization" to "Bearer ${token()}"),
            licenseUrl = drm.drmUrl,
            licenseHeaders = buildMap {
                put("Authorization", "Bearer ${token()}")
                put("x-cr-content-id", episodeId)
                download.token?.let { put("x-cr-video-token", it) }
            },
        )
    }.getOrElse {
        Log.w(LOG_TAG, "offline: could not prepare download for $episodeId: ${it.message}")
        null
    }

    @Volatile private var lastStream: Pair<String, String>? = null

    private val streamScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var keepAliveJob: Job? = null

    // A stream token expires ~renewSeconds after it is issued unless pinged, which
    // drops playback a few minutes in.
    private fun startKeepAlive(id: String, streamToken: String, session: SessionDto?) {
        keepAliveJob?.cancel()
        val renew = (session?.renewSeconds ?: 270).coerceAtLeast(60)
        val interval = (renew - 30).coerceAtLeast(30) * 1000L
        val deadline = System.currentTimeMillis() + SESSION_MAX_SECONDS * 1000L
        keepAliveJob = streamScope.launch {
            var failures = 0
            while (isActive && System.currentTimeMillis() < deadline) {
                delay(interval)
                if (lastStream?.second != streamToken) return@launch
                val code = runCatching {
                    val request = Request.Builder()
                        .url("$PLAY_HOST/v1/token/$id/$streamToken/active")
                        .headers(headers)
                        .patch(ByteArray(0).toRequestBody())
                        .build()
                    client.newCall(request).execute().use { it.code }
                }.getOrElse {
                    Log.w(LOG_TAG, "keep-alive $id failed: ${it.message}")
                    return@getOrElse -1
                }
                // The token is gone; further pings are pointless.
                if (code == 404 || code == 410) return@launch
                if (code in 200..299) {
                    failures = 0
                } else if (++failures >= MAX_KEEPALIVE_FAILURES) {
                    // A session is dropped after ~370s without contact, so the slot is
                    // gone anyway - playback stopped, or the device went offline.
                    return@launch
                }
            }
        }
    }

    private fun releasePreviousStream() {
        keepAliveJob?.cancel()
        keepAliveJob = null
        val (id, streamToken) = lastStream ?: return
        lastStream = null
        runCatching {
            // Must be PATCH; a POST is silently ignored and leaks the slot until it
            // times out, after which play returns 420 TOO_MANY_ACTIVE_STREAMS.
            val request = Request.Builder()
                .url("$PLAY_HOST/v1/token/$id/$streamToken/inactive")
                .headers(headers)
                .patch(ByteArray(0).toRequestBody())
                .build()
            client.newCall(request).execute().use {
            }
        }.onFailure { Log.w(LOG_TAG, "release stream token $id failed: ${it.message}") }
    }

    // ============================= Utilities ==============================

    private fun localeName(locale: String) = LOCALES[locale] ?: locale

    // ============================ Preferences =============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addEditTextPreference(
            key = PREF_BASIC,
            default = "",
            title = "Basic auth token",
            summary = "Required. From your browser: DevTools → Network → the auth/v1/token request → " +
                "Authorization header → the part after \"Basic \".",
            dialogMessage = "Paste only the base64 blob, without the leading \"Basic \".",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            restartRequired = true,
        )

        screen.addEditTextPreference(
            key = PREF_ETP_RT,
            default = "",
            title = "etp_rt cookie",
            summary = "Used with a web Basic token. DevTools → Storage → Cookies → crunchyroll.com → etp_rt. " +
                "Re-authenticates on its own; only needs replacing if you log out in the browser.",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            restartRequired = true,
        )

        screen.addEditTextPreference(
            key = PREF_EMAIL,
            default = "",
            title = "Email (optional)",
            summary = "Only works with a mobile/TV Basic token. Leave blank to use the etp_rt cookie.",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            restartRequired = true,
        )

        screen.addEditTextPreference(
            key = PREF_PASSWORD,
            default = "",
            title = "Password (optional)",
            summary = "Stored on-device. Only used with a mobile/TV Basic token.",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            restartRequired = true,
        )

        screen.addListPreference(
            key = PREF_AUDIO_TYPE,
            default = PREF_AUDIO_TYPE_DEFAULT,
            title = "Audio",
            summary = "%s",
            entries = listOf("Subbed (original audio)", "Dubbed"),
            entryValues = listOf(AUDIO_SUB, AUDIO_DUB),
        )

        screen.addListPreference(
            key = PREF_AUDIO,
            default = PREF_AUDIO_DEFAULT,
            title = "Dub language",
            summary = "Used when Audio is set to Dubbed. %s",
            entries = LOCALES.values.toList(),
            entryValues = LOCALES.keys.toList(),
        )

        screen.addListPreference(
            key = PREF_SUB,
            default = PREF_SUB_DEFAULT,
            title = "Preferred subtitle language",
            summary = "%s",
            entries = LOCALES.values.toList(),
            entryValues = LOCALES.keys.toList(),
        )

        screen.addListPreference(
            key = PREF_LOCALE,
            default = PREF_LOCALE_DEFAULT,
            title = "Metadata language",
            summary = "%s",
            entries = LOCALES.values.toList(),
            entryValues = LOCALES.keys.toList(),
        )
    }

    companion object {
        private const val LOG_TAG = "Crunchyroll"

        /** Needs a mobile/TV client credential. */
        private const val GRANT_PASSWORD = "password"

        /** The only grant a web client credential accepts. */
        private const val GRANT_COOKIE = "etp_rt_cookie"

        private const val PAGE_SIZE = 36

        private const val PLAY_HOST = "https://cr-play-service.prd.crunchyrollsvc.com"

        // The service allows six hours, but with no playback-stopped hook a shorter
        // cap bounds how long a finished episode can hold a slot.
        private const val SESSION_MAX_SECONDS = 10800L

        /** Consecutive renewal failures before the session is presumed dead. */
        private const val MAX_KEEPALIVE_FAILURES = 2

        // A web token only works on `web` paths; android/console answer "Outdated
        // Token". This profile also serves the full 1080p ladder.
        private const val PLAY_PROFILE = "web/firefox"

        // The only download profile that mints Widevine licenses; switch returns OMA.
        private const val DOWNLOAD_PROFILE = "android/phone"

        private const val SEASON_PREFIX = "season:"

        private const val DEVICE_NAME = "Firefox on Windows"
        private const val DEVICE_TYPE = "Firefox on Windows"

        private const val PREF_BASIC = "cr_basic_token"
        private const val PREF_ETP_RT = "cr_etp_rt"
        private const val PREF_EMAIL = "cr_email"
        private const val PREF_PASSWORD = "cr_password"
        private const val PREF_DEVICE_ID = "cr_device_id"

        private const val PREF_AUDIO_TYPE = "cr_audio_type"

        // Must stay bare "sub"/"dub"; the app matches on that exactly.
        private const val AUDIO_SUB = "sub"
        private const val AUDIO_DUB = "dub"
        private const val PREF_AUDIO_TYPE_DEFAULT = AUDIO_SUB
        private const val ORIGINAL_LOCALE = "ja-JP"
        private const val FALLBACK_DUB_LOCALE = "en-US"

        private const val PREF_AUDIO = "cr_audio_locale"
        private const val PREF_AUDIO_DEFAULT = FALLBACK_DUB_LOCALE
        private const val PREF_SUB = "cr_subtitle_locale"
        private const val PREF_SUB_DEFAULT = "en-US"
        private const val PREF_LOCALE = "cr_content_locale"
        private const val PREF_LOCALE_DEFAULT = "en-US"

        private val dateFormat by lazy {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
        }

        private val LOCALES = linkedMapOf(
            "ja-JP" to "Japanese",
            "en-US" to "English",
            "en-IN" to "English (India)",
            "es-419" to "Spanish (Latin America)",
            "es-ES" to "Spanish (Spain)",
            "pt-BR" to "Portuguese (Brazil)",
            "pt-PT" to "Portuguese (Portugal)",
            "fr-FR" to "French",
            "de-DE" to "German",
            "it-IT" to "Italian",
            "ru-RU" to "Russian",
            "ar-SA" to "Arabic",
            "hi-IN" to "Hindi",
            "ta-IN" to "Tamil",
            "te-IN" to "Telugu",
            "ms-MY" to "Malay",
            "id-ID" to "Indonesian",
            "th-TH" to "Thai",
            "vi-VN" to "Vietnamese",
            "ko-KR" to "Korean",
            "zh-CN" to "Chinese (Mainland)",
            "zh-HK" to "Chinese (Hong Kong)",
            "zh-TW" to "Chinese (Taiwan)",
            "pl-PL" to "Polish",
            "ca-ES" to "Catalan",
        )
    }
}

// Serialised into Video.internalData; ignored by players without DRM support.
@Serializable
class DrmPayload(
    val drmScheme: String,
    val licenseUrl: String,
    val licenseHeaders: Map<String, String>,
    val offline: OfflinePayload? = null,
)

@Serializable
class OfflinePayload(
    val manifestUrl: String,
    // Sent with manifest and segment requests; the manifest 401s without them.
    val headers: Map<String, String>,
    val licenseUrl: String,
    val licenseHeaders: Map<String, String>,
)
