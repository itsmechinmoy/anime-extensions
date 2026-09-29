package eu.kanade.tachiyomi.animeextension.en.cinestream

import android.util.Base64
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import keiyoushi.utils.get
import keiyoushi.utils.parseAs
import keiyoushi.utils.post
import keiyoushi.utils.toJsonRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
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
        runCatching {
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
        }.getOrDefault(emptyList())
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

        val array = runCatching { client.get(url.toString()).parseAs<JsonArray>() }.getOrNull() ?: return emptyList()
        return array.mapNotNull { item ->
            val obj = item.jsonObject
            val title = obj["title"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val magnet = obj["magnet_uri"]?.jsonPrimitive?.content
                ?: obj["magnet_url"]?.jsonPrimitive?.content
                ?: obj["torrent_url"]?.jsonPrimitive?.content
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

        val encData = client.get(endpoint, videasyHeaders).body.string()
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

        val tokenResp = client.get("https://enc-dec.app/api/enc-hexa").parseAs<JsonObject>()
        val token = tokenResp["result"]?.jsonObject?.get("token")?.jsonPrimitive?.content ?: ""

        val hexaHeaders = headers.newBuilder()
            .set("X-Api-Key", key)
            .set("X-Fingerprint-Lite", "e9136c41504646444")
            .set("Referer", "https://hexa.su/")
            .set("X-Cap-Token", token)
            .build()

        val encData = client.get(url, hexaHeaders).body.string()
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

        val obj = runCatching {
            client.get(apiUrl, vidrockHeaders).parseAs<JsonObject>()
        }.getOrNull() ?: return emptyList()

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
        val doc = Jsoup.parse(client.get(url).body.string())
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

        val servers = listOf("multi", "hr", "holly", "air", "moviebox")
        val videos = mutableListOf<Video>()

        for (server in servers) {
            val url = if (media.tvtype == "movie") {
                "https://x.eat-peach.sbs/$server/movie/$tmdbId"
            } else {
                "https://x.eat-peach.sbs/$server/tv/$tmdbId/${media.season ?: 1}/${media.episode ?: 1}"
            }
            val text = runCatching { client.get(url, peachHeaders).body.string() }.getOrNull() ?: continue
            val encrypt = runCatching { text.parseAs<JsonObject>()["data"]?.jsonPrimitive?.content }.getOrNull() ?: continue
            if (encrypt.isNullOrBlank()) continue

            val decrypted = decryptPeachifyUrl(encrypt) ?: continue
            val decryptedObj = runCatching { decrypted.parseAs<JsonObject>() }.getOrNull() ?: continue
            val streamUrl = decryptedObj["url"]?.jsonPrimitive?.content ?: continue
            if (streamUrl.contains(".m3u8")) {
                videos += playlistUtils.extractFromHls(
                    streamUrl,
                    masterHeaders = peachHeaders,
                    videoHeaders = peachHeaders,
                    videoNameGen = { q -> "Peachify [$server] - $q" },
                )
            } else {
                videos += Video(videoUrl = streamUrl, videoTitle = "Peachify [$server]", headers = peachHeaders)
            }
        }
        return videos
    }

    private fun decryptPeachifyUrl(encrypt: String): String? {
        return runCatching {
            val parts = encrypt.split(".")
            if (parts.size < 3) return null

            fun b64Decode(s: String): ByteArray {
                var padded = s.replace('-', '+').replace('_', '/')
                val rem = padded.length % 4
                if (rem != 0) padded += "=".repeat(4 - rem)
                return Base64.decode(padded, Base64.DEFAULT)
            }

            val iv = b64Decode(parts[0])
            val cipherData = b64Decode(parts[1]) + b64Decode(parts[2])

            val keyHex = "a8f2a1b5e9c470814f6b2c3a5d8e7f9c1a2b3c4d5e3f7a8b8cad1e2d0a4d5c5d"
            val keyBytes = ByteArray(keyHex.length / 2) { i ->
                keyHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                GCMParameterSpec(128, iv),
            )
            String(cipher.doFinal(cipherData), Charsets.UTF_8)
        }.getOrNull()
    }

    // ── Vidzee ─────────────────────────────────────────────────────────────────
    private suspend fun extractVidzee(
        media: MediaPayload,
        client: OkHttpClient,
        playlistUtils: PlaylistUtils,
    ): List<Video> {
        val tmdbId = media.tmdbId ?: return emptyList()
        val url = if (media.tvtype == "movie") {
            "https://player.vidzee.wtf/movie/$tmdbId"
        } else {
            "https://player.vidzee.wtf/tv/$tmdbId/${media.season ?: 1}/${media.episode ?: 1}"
        }
        val doc = Jsoup.parse(client.get(url).body.string())
        val stream = doc.selectFirst("source")?.attr("src") ?: return emptyList()
        return if (stream.contains(".m3u8")) {
            playlistUtils.extractFromHls(stream)
        } else {
            listOf(Video(videoUrl = stream, videoTitle = "Vidzee"))
        }
    }

    // ── VaPlayer ───────────────────────────────────────────────────────────────
    private suspend fun extractVaPlayer(
        media: MediaPayload,
        client: OkHttpClient,
        headers: Headers,
        playlistUtils: PlaylistUtils,
    ): List<Video> {
        val id = media.imdbId ?: media.id
        val url = if (media.tvtype == "movie") {
            "https://streamdata.vaplayer.ru/movie/$id"
        } else {
            "https://streamdata.vaplayer.ru/tv/$id/${media.season ?: 1}/${media.episode ?: 1}"
        }
        val vaHeaders = headers.newBuilder().set("Referer", "https://nextgencloudfabric.com/").build()
        val obj = runCatching { client.get(url, vaHeaders).parseAs<JsonObject>() }.getOrNull() ?: return emptyList()
        val streamUrl = obj["url"]?.jsonPrimitive?.content ?: return emptyList()
        return if (streamUrl.contains(".m3u8")) {
            playlistUtils.extractFromHls(streamUrl, masterHeaders = vaHeaders, videoHeaders = vaHeaders)
        } else {
            listOf(Video(videoUrl = streamUrl, videoTitle = "VaPlayer", headers = vaHeaders))
        }
    }

    // ── Castle ─────────────────────────────────────────────────────────────────
    private suspend fun extractCastle(
        media: MediaPayload,
        client: OkHttpClient,
        headers: Headers,
        playlistUtils: PlaylistUtils,
    ): List<Video> {
        val searchUrl = "https://api.hlowb.com/film-api/v1.1.0/movie/searchByKeyword?channel=IndiaA&clientType=1&keyword=${URLEncoder.encode(media.title, "UTF-8")}&lang=en-US&mode=1&packageName=com.external.castle&page=1&size=10"
        val castleHeaders = headers.newBuilder().set("Referer", "https://api.hlowb.com/").build()
        val searchResp = runCatching { client.get(searchUrl, castleHeaders).parseAs<JsonObject>() }.getOrNull() ?: return emptyList()
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

        val videoResp = runCatching { client.post(videoUrl, headers = castleHeaders, body = body).parseAs<JsonObject>() }.getOrNull() ?: return emptyList()
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
        val tvtype = if (media.tvtype == "movie") "_(Movie)" else "_(TV)"
        val firstChar = media.title.firstOrNull()?.uppercaseChar()?.toString() ?: "0"
        val newTitle = media.title.replace(" ", "_")
        val doc = Jsoup.parse(client.get("https://www.tokyoinsider.com/anime/$firstChar/$newTitle$tvtype").body.string())

        val selector = if (media.episode != null) "a.download-link:matches((?i)(episode ${media.episode}\\b))" else "a.download-link"
        val aTag = doc.selectFirst(selector) ?: doc.selectFirst("a.download-link") ?: return emptyList()
        val epUrl = aTag.attr("href")
        val resDoc = Jsoup.parse(client.get("https://www.tokyoinsider.com$epUrl").body.string())
        return resDoc.select("div.c_h2 > div > a").map {
            val name = it.text()
            val dlUrl = it.attr("href")
            Video(videoUrl = dlUrl, videoTitle = "TokyoInsider - $name")
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
            val srcResp = runCatching {
                client.get("https://api.just4anime.online/api/v1/meta/sources/$anilistId?provider=$code&num=${media.episode ?: 1}&type=$type", j4Headers)
                    .parseAs<Just4AnimeSourcesResponse>()
            }.getOrNull()

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
        val kHeaders = headers.newBuilder().set("Referer", "https://kisskh.nl/").build()
        val searchResp = runCatching {
            client.get("https://kisskh.nl/api/DramaList/Search?q=${URLEncoder.encode(media.title, "UTF-8")}&type=0", kHeaders).parseAs<JsonArray>()
        }.getOrNull() ?: return emptyList()
        val firstId = searchResp.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.content ?: return emptyList()

        val detailResp = client.get("https://kisskh.nl/api/DramaList/Drama/$firstId?isq=false", kHeaders).parseAs<JsonObject>()
        val eps = detailResp["episodes"]?.jsonArray ?: return emptyList()

        val epNumber = media.episode ?: 1
        val epObj = eps.firstOrNull { it.jsonObject["number"]?.jsonPrimitive?.content?.toIntOrNull() == epNumber }
            ?: eps.firstOrNull() ?: return emptyList()
        val epId = epObj.jsonObject["id"]?.jsonPrimitive?.content ?: return emptyList()

        val keyResp = client.get("https://enc-dec.app/api/enc-kisskh?text=$epId&type=vid", kHeaders).parseAs<JsonObject>()
        val vidKey = keyResp["result"]?.jsonPrimitive?.content ?: return emptyList()

        val srcResp = client.get("https://kisskh.nl/api/DramaList/Episode/$epId.png?err=false&ts=&time=&kkey=$vidKey", kHeaders).parseAs<JsonObject>()
        val videoLink = srcResp["video"]?.jsonPrimitive?.content ?: return emptyList()

        return if (videoLink.contains(".m3u8")) {
            playlistUtils.extractFromHls(videoLink, masterHeaders = kHeaders, videoHeaders = kHeaders)
        } else {
            listOf(Video(videoUrl = videoLink, videoTitle = "KissKH", headers = kHeaders))
        }
    }
}
