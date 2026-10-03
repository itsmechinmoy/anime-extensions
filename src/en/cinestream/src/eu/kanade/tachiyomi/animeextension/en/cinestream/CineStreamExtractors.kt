package eu.kanade.tachiyomi.animeextension.en.cinestream

import android.util.Base64
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import keiyoushi.utils.bodyString
import keiyoushi.utils.get
import keiyoushi.utils.parseAs
import keiyoushi.utils.post
import keiyoushi.utils.toJsonRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.cancellation.CancellationException

object CineStreamExtractors {

    class ProviderInfo(
        val key: String,
        val name: String,
        val isTorrent: Boolean = false,
        val isAnimeOnly: Boolean = false,
        val isAsianOnly: Boolean = false,
    )

    val BUILTIN_PROVIDERS = listOf(
        ProviderInfo("p_videasy", "Videasy"),
        ProviderInfo("p_hexa", "Hexa"),
        ProviderInfo("p_vidrock", "Vidrock"),
        ProviderInfo("p_vidfastpro", "VidFastPro"),
        ProviderInfo("p_peachify", "Peachify"),
        ProviderInfo("p_vidzee", "Vidzee"),
        ProviderInfo("p_vaplayer", "VaPlayer"),
        ProviderInfo("p_castle", "Castle"),
        ProviderInfo("p_reanime", "Re:ANIME", isAnimeOnly = true),
        ProviderInfo("p_just4anime", "Just4Anime", isAnimeOnly = true),
        ProviderInfo("p_anikoto", "Anikoto", isAnimeOnly = true),
        ProviderInfo("p_tokyoinsider", "TokyoInsider", isAnimeOnly = true),
        ProviderInfo("p_kisskh", "KissKH", isAsianOnly = true),
        ProviderInfo("p_torrentio", "Torrentio [Torrent]", isTorrent = true),
        ProviderInfo("p_torrentsdb", "TorrentsDB [Torrent]", isTorrent = true),
        ProviderInfo("p_animetosho", "AnimeTosho [Torrent]", isTorrent = true, isAnimeOnly = true),
    )

    suspend fun extractVideos(
        providerKey: String,
        media: MediaPayload,
        client: OkHttpClient,
        baseHeaders: Headers,
        playlistUtils: PlaylistUtils,
    ): List<Video> = withContext(Dispatchers.IO) {
        try {
            when (providerKey) {
                "p_torrentio" -> extractTorrentio("https://torrentio.strem.fun/limit=4", "Torrentio", media, client)
                "p_torrentsdb" -> extractTorrentio("https://torrentsdb.com/eyJsaW1pdCI6IjMiLCJkZWJyaWRvcHRpb25zIjpbIm5vZG93bmxvYWRsaW5rcyJdfQ==", "TorrentsDB", media, client)
                "p_animetosho" -> extractAnimeTosho(media, client)
                "p_videasy" -> extractVideasy(media, client, baseHeaders, playlistUtils)
                "p_hexa" -> extractHexa(media, client, baseHeaders, playlistUtils)
                "p_vidrock" -> extractVidrock(media, client, baseHeaders, playlistUtils)
                "p_vidfastpro" -> extractVidFastPro(media, client, playlistUtils)
                "p_peachify" -> extractPeachify(media, client, baseHeaders, playlistUtils)
                "p_vidzee" -> extractVidzee(media, client, playlistUtils)
                "p_vaplayer" -> extractVaPlayer(media, client, baseHeaders, playlistUtils)
                "p_castle" -> extractCastle(media, client, baseHeaders, playlistUtils)
                "p_tokyoinsider" -> extractTokyoInsider(media, client)
                "p_reanime" -> extractReanime(media, client, baseHeaders, playlistUtils)
                "p_just4anime" -> extractJust4Anime(media, client, baseHeaders, playlistUtils)
                "p_kisskh" -> extractKisskh(media, client, baseHeaders, playlistUtils)
                else -> emptyList()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ── Torrentio & TorrentsDB ──────────────────────────────────────────────────
    private suspend fun extractTorrentio(
        api: String,
        sourceName: String,
        media: MediaPayload,
        client: OkHttpClient,
    ): List<Video> {
        val streamPath = if (media.tvtype == "movie") {
            "movie/${media.imdbId ?: media.id}.json"
        } else if (media.isKitsu) {
            "series/kitsu:${media.kitsuId}:${media.episode ?: 1}.json"
        } else {
            "series/${media.imdbId ?: media.id}:${media.season ?: 1}:${media.episode ?: 1}.json"
        }

        val url = "$api/stream/$streamPath"
        val response = client.get(url).parseAs<TorrentioResponse>()
        return response.streams.mapNotNull { s ->
            val infoHash = s.infoHash ?: return@mapNotNull null
            val title = s.title ?: s.description ?: s.name ?: "Stream"
            val magnet = "magnet:?xt=urn:btih:$infoHash&dn=${URLEncoder.encode(media.title, "UTF-8")}"
            Video(
                videoUrl = magnet,
                videoTitle = "[$sourceName] " + title.lines().firstOrNull().orEmpty(),
            )
        }
    }

    // ── AnimeTosho ─────────────────────────────────────────────────────────────
    private suspend fun extractAnimeTosho(
        media: MediaPayload,
        client: OkHttpClient,
    ): List<Video> {
        val url = "https://feed.animetosho.net/json".toHttpUrl().newBuilder().apply {
            media.kitsuId?.let { addQueryParameter("kitsu_id", it) }
            media.malId?.let { addQueryParameter("mal_id", it.toString()) }
            media.episode?.let { addQueryParameter("ep", it.toString()) }
        }.build()

        val list = try {
            client.get(url.toString()).parseAs<List<AnimeToshoItemDto>>()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return emptyList()
        }

        return list.mapNotNull { item ->
            val title = item.title ?: return@mapNotNull null
            val magnet = item.magnetUri
                ?: item.magnetUrl
                ?: item.torrentUrl
                ?: return@mapNotNull null
            Video(
                videoUrl = magnet,
                videoTitle = "[AnimeTosho] $title",
            )
        }
    }

    // ── Videasy ────────────────────────────────────────────────────────────────
    private suspend fun extractVideasy(
        media: MediaPayload,
        client: OkHttpClient,
        headers: Headers,
        playlistUtils: PlaylistUtils,
    ): List<Video> {
        val tmdbId = media.tmdbId ?: return emptyList()
        val videasyHeaders = headers.newBuilder()
            .set("Origin", "https://player.videasy.to")
            .set("Referer", "https://player.videasy.to/")
            .build()

        val seedResp = client.get("https://api.speedracelight.com/seed?mediaId=$tmdbId", videasyHeaders)
        val seed = seedResp.parseAs<SeedResponse>().seed ?: return emptyList()

        val encTitle = URLEncoder.encode(URLEncoder.encode(media.title, "UTF-8"), "UTF-8")
        val isMovie = media.tvtype == "movie"
        val server = "myflixerzupcloud"

        val endpoint = if (isMovie) {
            "https://api.speedracelight.com/$server/sources-with-title?title=$encTitle&mediaType=movie&year=${media.year}&tmdbId=$tmdbId&imdbId=${media.imdbId.orEmpty()}&enc=2&seed=$seed"
        } else {
            "https://api.speedracelight.com/$server/sources-with-title?title=$encTitle&mediaType=tv&year=${media.year}&tmdbId=$tmdbId&episodeId=${media.episode ?: 1}&seasonId=${media.season ?: 1}&imdbId=${media.imdbId.orEmpty()}&enc=2&seed=$seed"
        }

        val encData = client.get(endpoint, videasyHeaders).bodyString()
        val decryptBody = buildJsonObject {
            put("text", encData)
            put("id", tmdbId)
            put("seed", seed)
        }.toJsonRequestBody()

        val decResp = client.post("https://enc-dec.app/api/dec-videasy", body = decryptBody).parseAs<EncDecResponse>()
        val result = decResp.result ?: return emptyList()

        val subtitles = result.subtitles.orEmpty().mapNotNull { sub ->
            val subUrl = sub.url ?: return@mapNotNull null
            Track(subUrl, sub.language ?: "Und")
        }

        return result.sources.orEmpty().flatMap { src ->
            val srcUrl = src.url ?: return@flatMap emptyList()
            val quality = src.quality ?: "Auto"
            if (srcUrl.contains(".m3u8")) {
                playlistUtils.extractFromHls(srcUrl, masterHeaders = videasyHeaders, videoHeaders = videasyHeaders, subtitleList = subtitles)
            } else {
                listOf(Video(videoUrl = srcUrl, videoTitle = "Videasy - $quality", headers = videasyHeaders, subtitleTracks = subtitles))
            }
        }
    }

    // ── Hexa ───────────────────────────────────────────────────────────────────
    private suspend fun extractHexa(
        media: MediaPayload,
        client: OkHttpClient,
        headers: Headers,
        playlistUtils: PlaylistUtils,
    ): List<Video> {
        val tmdbId = media.tmdbId ?: return emptyList()
        val url = if (media.tvtype == "movie") {
            "https://theemoviedb.hexa.su/api/tmdb/movie/$tmdbId/images"
        } else {
            "https://theemoviedb.hexa.su/api/tmdb/tv/$tmdbId/season/${media.season ?: 1}/episode/${media.episode ?: 1}/images"
        }

        val keyBytes = ByteArray(32)
        SecureRandom().nextBytes(keyBytes)
        val key = keyBytes.joinToString("") { "%02x".format(it) }

        val tokenResp = try {
            client.get("https://enc-dec.app/api/enc-hexa").parseAs<EncDecResponse>()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val token = tokenResp?.result?.token.orEmpty()

        val hexaHeaders = headers.newBuilder()
            .set("X-Api-Key", key)
            .set("X-Fingerprint-Lite", "e9136c41504646444")
            .set("Referer", "https://hexa.su/")
            .set("X-Cap-Token", token)
            .build()

        val encData = client.get(url, hexaHeaders).bodyString()
        val decryptBody = buildJsonObject {
            put("text", encData)
            put("key", key)
        }.toJsonRequestBody()

        val decResp = client.post("https://enc-dec.app/api/dec-hexa", body = decryptBody).parseAs<EncDecResponse>()
        return decResp.result?.sources.orEmpty().flatMap { src ->
            val srcUrl = src.url ?: return@flatMap emptyList()
            if (srcUrl.contains(".m3u8")) {
                playlistUtils.extractFromHls(srcUrl, masterHeaders = hexaHeaders, videoHeaders = hexaHeaders)
            } else {
                listOf(Video(videoUrl = srcUrl, videoTitle = "Hexa - ${src.quality ?: "Auto"}", headers = hexaHeaders))
            }
        }
    }

    // ── Vidrock ────────────────────────────────────────────────────────────────
    private suspend fun extractVidrock(
        media: MediaPayload,
        client: OkHttpClient,
        headers: Headers,
        playlistUtils: PlaylistUtils,
    ): List<Video> {
        val tmdbId = media.tmdbId ?: return emptyList()
        val type = if (media.tvtype == "movie") "movie" else "tv"
        val query = if (type == "movie") "$tmdbId" else "$tmdbId/${media.season ?: 1}/${media.episode ?: 1}"
        val apiUrl = "https://vidrock.ru/api/$type/$query/"

        val vidrockHeaders = headers.newBuilder()
            .set("Origin", "https://vidrock.ru")
            .set("Referer", "https://vidrock.ru/")
            .build()

        val obj = try {
            client.get(apiUrl, vidrockHeaders).parseAs<JsonObject>()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return emptyList()
        }

        val videos = mutableListOf<Video>()
        for ((serverName, serverElement) in obj) {
            val serverObj = runCatching { serverElement.jsonObject }.getOrNull() ?: continue
            val encUrl = serverObj["url"]?.jsonPrimitive?.content ?: continue
            if (encUrl.isBlank() || encUrl == "null" || encUrl == "error") continue

            val decryptedUrl = decryptVidrockUrl(encUrl) ?: continue
            val videoHeaders = headers.newBuilder()
                .set("Referer", "https://vidrock.ru/")
                .build()

            if (decryptedUrl.contains(".m3u8")) {
                videos += playlistUtils.extractFromHls(
                    decryptedUrl,
                    masterHeaders = videoHeaders,
                    videoHeaders = videoHeaders,
                    videoNameGen = { quality -> "Vidrock [$serverName] - $quality" },
                )
            } else {
                videos += Video(
                    videoUrl = decryptedUrl,
                    videoTitle = "Vidrock [$serverName]",
                    headers = videoHeaders,
                )
            }
        }
        return videos
    }

    private fun decryptVidrockUrl(encryptedPayload: String): String? {
        return runCatching {
            val aesKeyHex = "7f3e9c2a8b5d1f4e6a9c3b7d2e5f8a1c4b6d9e2f5a8c1b4d7e9f2a5c8b1d4e7f"
            val keyBytes = ByteArray(aesKeyHex.length / 2) { i ->
                aesKeyHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }

            var standardBase64 = encryptedPayload.replace("-", "+").replace("_", "/")
            val remainder = standardBase64.length % 4
            if (remainder != 0) {
                standardBase64 += "=".repeat(4 - remainder)
            }

            val encryptedData = Base64.decode(standardBase64, Base64.DEFAULT)
            if (encryptedData.size < 13) return null

            val nonce = encryptedData.copyOfRange(0, 12)
            val cipherTextWithTag = encryptedData.copyOfRange(12, encryptedData.size)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val keySpec = SecretKeySpec(keyBytes, "AES")
            val gcmSpec = GCMParameterSpec(128, nonce)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
            val decrypted = cipher.doFinal(cipherTextWithTag)
            String(decrypted, Charsets.UTF_8)
        }.getOrNull()
    }

    // ── VidFastPro ─────────────────────────────────────────────────────────────
    private suspend fun extractVidFastPro(
        media: MediaPayload,
        client: OkHttpClient,
        playlistUtils: PlaylistUtils,
    ): List<Video> {
        val tmdbId = media.tmdbId ?: return emptyList()
        val url = if (media.tvtype == "movie") {
            "https://vidfast.vc/movie/$tmdbId/"
        } else {
            "https://vidfast.vc/tv/$tmdbId/${media.season ?: 1}/${media.episode ?: 1}/"
        }
        val doc = Jsoup.parse(client.get(url).bodyString())
        val iframe = doc.selectFirst("iframe")?.attr("src") ?: return emptyList()
        return if (iframe.contains(".m3u8")) {
            playlistUtils.extractFromHls(iframe)
        } else {
            listOf(Video(videoUrl = iframe, videoTitle = "VidFastPro"))
        }
    }

    // ── Peachify ───────────────────────────────────────────────────────────────
    private suspend fun extractPeachify(
        media: MediaPayload,
        client: OkHttpClient,
        headers: Headers,
        playlistUtils: PlaylistUtils,
    ): List<Video> {
        val tmdbId = media.tmdbId ?: return emptyList()
        val peachHeaders = headers.newBuilder()
            .set("Origin", "https://peachify.top")
            .set("Referer", "https://peachify.top/")
            .build()

        val isMovie = media.tvtype == "movie"
        val servers = listOf("alpha", "delta")
        val videos = mutableListOf<Video>()

        for (srv in servers) {
            val url = if (isMovie) {
                "https://peachify.top/api/stream/$srv?id=$tmdbId"
            } else {
                "https://peachify.top/api/stream/$srv?id=$tmdbId&s=${media.season ?: 1}&e=${media.episode ?: 1}"
            }
            val text = try {
                client.get(url, peachHeaders).bodyString()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                continue
            }
            if (text.isBlank()) continue

            val decUrl = decryptPeachify(text) ?: continue
            if (decUrl.contains(".m3u8")) {
                videos += playlistUtils.extractFromHls(
                    decUrl,
                    masterHeaders = peachHeaders,
                    videoHeaders = peachHeaders,
                    videoNameGen = { q -> "Peachify [$srv] - $q" },
                )
            } else {
                videos += Video(videoUrl = decUrl, videoTitle = "Peachify [$srv]", headers = peachHeaders)
            }
        }
        return videos
    }

    private fun decryptPeachify(encrypted: String): String? = runCatching {
        val key = "peachifytopsecretkey2024".toByteArray(Charsets.UTF_8).copyOf(32)
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        val dec = cipher.doFinal(Base64.decode(encrypted.trim(), Base64.DEFAULT))
        String(dec, Charsets.UTF_8)
    }.getOrNull()

    // ── Vidzee ─────────────────────────────────────────────────────────────────
    private suspend fun extractVidzee(
        media: MediaPayload,
        client: OkHttpClient,
        playlistUtils: PlaylistUtils,
    ): List<Video> {
        val tmdbId = media.tmdbId ?: return emptyList()
        val url = if (media.tvtype == "movie") {
            "https://vidzee.org/movie/$tmdbId"
        } else {
            "https://vidzee.org/tv/$tmdbId/${media.season ?: 1}/${media.episode ?: 1}"
        }
        val doc = Jsoup.parse(client.get(url).bodyString())
        val iframe = doc.selectFirst("iframe")?.attr("src") ?: return emptyList()
        return if (iframe.contains(".m3u8")) {
            playlistUtils.extractFromHls(iframe)
        } else {
            listOf(Video(videoUrl = iframe, videoTitle = "Vidzee"))
        }
    }

    // ── VaPlayer ───────────────────────────────────────────────────────────────
    private suspend fun extractVaPlayer(
        media: MediaPayload,
        client: OkHttpClient,
        headers: Headers,
        playlistUtils: PlaylistUtils,
    ): List<Video> {
        val tmdbId = media.tmdbId ?: return emptyList()
        val vapHeaders = headers.newBuilder().set("Referer", "https://vaplayer.com/").build()
        val url = if (media.tvtype == "movie") {
            "https://vaplayer.com/movie/$tmdbId"
        } else {
            "https://vaplayer.com/tv/$tmdbId/${media.season ?: 1}/${media.episode ?: 1}"
        }
        val doc = Jsoup.parse(client.get(url, vapHeaders).bodyString())
        val src = doc.selectFirst("iframe")?.attr("src") ?: return emptyList()
        return if (src.contains(".m3u8")) {
            playlistUtils.extractFromHls(src, masterHeaders = vapHeaders, videoHeaders = vapHeaders)
        } else {
            listOf(Video(videoUrl = src, videoTitle = "VaPlayer", headers = vapHeaders))
        }
    }

    // ── Castle ─────────────────────────────────────────────────────────────────
    private suspend fun extractCastle(
        media: MediaPayload,
        client: OkHttpClient,
        headers: Headers,
        playlistUtils: PlaylistUtils,
    ): List<Video> {
        if (media.tvtype != "movie") return emptyList()
        val searchUrl = "https://api.hlowb.com/film-api/v1.1.0/movie/searchByKeyword?channel=IndiaA&clientType=1&keyword=${URLEncoder.encode(media.title, "UTF-8")}&lang=en-US&mode=1&packageName=com.external.castle&page=1&size=10"
        val castleHeaders = headers.newBuilder().set("Referer", "https://api.hlowb.com/").build()
        val searchResp = try {
            client.get(searchUrl, castleHeaders).parseAs<JsonObject>()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return emptyList()
        }
        val rows = searchResp["rows"]?.jsonArray ?: return emptyList()
        val firstId = rows.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.content ?: return emptyList()

        val videoUrl = "https://api.hlowb.com/film-api/v2.0.1/movie/getVideo2?clientType=1&packageName=com.external.castle&channel=IndiaA&lang=en-US"
        val body = buildJsonObject {
            put("mode", "1")
            put("clientType", "1")
            put("movieId", firstId)
            put("resolution", "2")
            put("packageName", "com.external.castle")
        }.toJsonRequestBody()

        val videoResp = try {
            client.post(videoUrl, headers = castleHeaders, body = body).parseAs<JsonObject>()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return emptyList()
        }
        val streamUrl = videoResp["videoUrl"]?.jsonPrimitive?.content ?: return emptyList()
        return if (streamUrl.contains(".m3u8")) {
            playlistUtils.extractFromHls(streamUrl, masterHeaders = castleHeaders, videoHeaders = castleHeaders)
        } else {
            listOf(Video(videoUrl = streamUrl, videoTitle = "Castle", headers = castleHeaders))
        }
    }

    // ── TokyoInsider ───────────────────────────────────────────────────────────
    private suspend fun extractTokyoInsider(
        media: MediaPayload,
        client: OkHttpClient,
    ): List<Video> {
        val title = media.title.trim()
        val firstChar = title.firstOrNull()?.uppercaseChar()?.toString() ?: return emptyList()
        val newTitle = title.lowercase().replace(" ", "_")
        val tvtype = if (media.tvtype == "movie") "_movie" else ""

        val doc = try {
            Jsoup.parse(client.get("https://www.tokyoinsider.com/anime/$firstChar/$newTitle$tvtype").bodyString())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return emptyList()
        }
        val episodeNum = media.episode ?: 1
        val epLink = doc.select("a").firstOrNull { it.text().contains("Episode $episodeNum", true) } ?: return emptyList()
        val epUrl = epLink.attr("href")

        val resDoc = try {
            Jsoup.parse(client.get("https://www.tokyoinsider.com$epUrl").bodyString())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return emptyList()
        }
        val downloadLinks = resDoc.select("a[href*=/download/]")
        return downloadLinks.mapNotNull { a ->
            val href = a.attr("href")
            val quality = a.text().trim()
            if (href.isNotBlank()) {
                Video(videoUrl = "https://www.tokyoinsider.com$href", videoTitle = "TokyoInsider - $quality")
            } else {
                null
            }
        }
    }

    // ── Re:ANIME ───────────────────────────────────────────────────────────────
    private suspend fun extractReanime(
        media: MediaPayload,
        client: OkHttpClient,
        headers: Headers,
        playlistUtils: PlaylistUtils,
    ): List<Video> {
        val anilistId = media.anilistId ?: return emptyList()
        val reHeaders = headers.newBuilder().set("Referer", "https://reanime.to/").build()
        val resp = client.get("https://reanime.to/api/flix/$anilistId/${media.episode ?: 1}", reHeaders).parseAs<ReanimeResponse>()
        if (!resp.success) return emptyList()

        return resp.servers.flatMap { server ->
            val link = server.dataLink
            if (link.contains(".m3u8")) {
                playlistUtils.extractFromHls(link, videoNameGen = { q -> "Re:ANIME [${server.dataType}] - $q" }, masterHeaders = reHeaders, videoHeaders = reHeaders)
            } else if (link.isNotEmpty()) {
                listOf(Video(videoUrl = link, videoTitle = "Re:ANIME [${server.dataType}]", headers = reHeaders))
            } else {
                emptyList()
            }
        }
    }

    // ── Just4Anime ─────────────────────────────────────────────────────────────
    private suspend fun extractJust4Anime(
        media: MediaPayload,
        client: OkHttpClient,
        headers: Headers,
        playlistUtils: PlaylistUtils,
    ): List<Video> {
        val anilistId = media.anilistId ?: return emptyList()
        val j4Headers = headers.newBuilder().set("Referer", "https://just4anime.online/").build()
        val resp = client.get("https://api.just4anime.online/api/v1/meta/availability/$anilistId/servers", j4Headers).parseAs<Just4AnimeResponse>()
        val servers = resp.data?.servers.orEmpty().filter { it.hasEpisode && !it.code.isNullOrBlank() }

        return servers.flatMap { server ->
            val code = server.code ?: return@flatMap emptyList()
            val type = server.types.firstOrNull() ?: "sub"
            val srcResp = try {
                client.get("https://api.just4anime.online/api/v1/meta/sources/$anilistId?provider=$code&num=${media.episode ?: 1}&type=$type", j4Headers)
                    .parseAs<Just4AnimeSourcesResponse>()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }

            val multi = srcResp?.data?.stream?.multi.orEmpty()
            val subs = srcResp?.data?.stream?.subtitles.orEmpty().mapNotNull { track ->
                val u = track.url ?: return@mapNotNull null
                Track(u, track.label ?: "Und")
            }

            multi.flatMap { trk ->
                val trkUrl = trk.url ?: return@flatMap emptyList()
                if (trkUrl.contains(".m3u8")) {
                    playlistUtils.extractFromHls(trkUrl, videoNameGen = { q -> "Just4Anime [${server.displayName ?: code}] - $q" }, masterHeaders = j4Headers, videoHeaders = j4Headers, subtitleList = subs)
                } else {
                    listOf(Video(videoUrl = trkUrl, videoTitle = "Just4Anime [${server.displayName ?: code}]", headers = j4Headers, subtitleTracks = subs))
                }
            }
        }
    }

    // ── KissKH ─────────────────────────────────────────────────────────────────
    private suspend fun extractKisskh(
        media: MediaPayload,
        client: OkHttpClient,
        headers: Headers,
        playlistUtils: PlaylistUtils,
    ): List<Video> {
        val kHeaders = headers.newBuilder()
            .set("Origin", "https://kisskh.ovh")
            .set("Referer", "https://kisskh.ovh/")
            .build()

        val searchUrl = "https://kisskh.ovh/api/DramaList/Search".toHttpUrl().newBuilder()
            .addQueryParameter("q", media.title)
            .addQueryParameter("type", "0")
            .build()

        val searchResp = try {
            client.get(searchUrl.toString(), kHeaders).parseAs<List<KisskhSearchItemDto>>()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return emptyList()
        }

        val firstId = searchResp.firstOrNull()?.id ?: return emptyList()

        val detailResp = try {
            client.get("https://kisskh.ovh/api/DramaList/Drama/$firstId?isq=false", kHeaders)
                .parseAs<KisskhDetailDto>()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return emptyList()
        }

        val epNumber = (media.episode ?: 1).toFloat()
        val ep = detailResp.episodes.firstOrNull { it.number == epNumber }
            ?: detailResp.episodes.firstOrNull()
            ?: return emptyList()
        val epId = ep.id ?: return emptyList()

        val keyResp = try {
            client.get("https://enc-dec.app/api/enc-kisskh?text=$epId&type=vid", kHeaders)
                .parseAs<EncDecSingleResultResponse>()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return emptyList()
        }
        val vidKey = keyResp.result ?: return emptyList()

        val srcResp = try {
            client.get("https://kisskh.ovh/api/DramaList/Episode/$epId.png?err=false&ts=&time=&kkey=$vidKey", kHeaders)
                .parseAs<KisskhVideoDto>()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return emptyList()
        }
        val videoLink = srcResp.video ?: return emptyList()

        return if (videoLink.contains(".m3u8")) {
            playlistUtils.extractFromHls(videoLink, masterHeaders = kHeaders, videoHeaders = kHeaders)
        } else {
            listOf(Video(videoUrl = videoLink, videoTitle = "KissKH", headers = kHeaders))
        }
    }
}
