package eu.kanade.tachiyomi.animeextension.en.cinestream

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CinemetaCatalogResponse(
    val metas: List<CinemetaMedia> = emptyList(),
    val hasMore: Boolean = true,
)

@Serializable
data class CinemetaMedia(
    val id: String,
    val type: String,
    val name: String? = null,
    val poster: String? = null,
    val description: String? = null,
    val imdbRating: String? = null,
    val aliases: List<String>? = null,
)

@Serializable
data class CinemetaMetaDetailResponse(
    val meta: CinemetaMetaDetail? = null,
)

@Serializable
data class CinemetaMetaDetail(
    val id: String? = null,
    @SerialName("imdb_id") val imdbId: String? = null,
    val type: String? = null,
    val name: String? = null,
    val description: String? = null,
    val poster: String? = null,
    val background: String? = null,
    @SerialName("moviedb_id") val moviedbId: Int? = null,
    val year: String? = null,
    val releaseInfo: String? = null,
    val country: String? = null,
    val genres: List<String>? = null,
    val genre: List<String>? = null,
    val imdbRating: String? = null,
    val status: String? = null,
    val videos: List<CinemetaVideo>? = null,
    val aliases: List<String>? = null,
)

@Serializable
data class CinemetaVideo(
    val id: String? = null,
    val name: String? = null,
    val title: String? = null,
    val season: Int = 0,
    val episode: Int = 0,
    val rating: String? = null,
    val released: String? = null,
    val firstAired: String? = null,
    val overview: String? = null,
    val thumbnail: String? = null,
    @SerialName("moviedb_id") val moviedbId: Int? = null,
    @SerialName("imdb_id") val imdbId: String? = null,
    val imdbSeason: Int? = null,
    val imdbEpisode: Int? = null,
)

@Serializable
data class HaglundIds(
    val anilist: Int? = null,
    val imdb: String? = null,
    val kitsu: Int? = null,
    val myanimelist: Int? = null,
    val simkl: Int? = null,
    val themoviedb: Int? = null,
    val thetvdb: Int? = null,
    @SerialName("thetvdb-season") val thetvdbSeason: Int? = null,
)

@Serializable
data class MediaPayload(
    val title: String,
    val id: String,
    val tmdbId: Int? = null,
    val tvtype: String,
    val year: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val firstAired: String? = null,
    val isAnime: Boolean = false,
    val isBollywood: Boolean = false,
    val isAsian: Boolean = false,
    val isCartoon: Boolean = false,
    val imdbId: String? = null,
    val imdbSeason: Int? = null,
    val imdbEpisode: Int? = null,
    val isKitsu: Boolean = false,
    val anilistId: Int? = null,
    val malId: Int? = null,
    val kitsuId: String? = null,
)

@Serializable
data class HosterPayload(
    val providerKey: String,
    val media: MediaPayload,
)

@Serializable
data class TorrentioResponse(
    val streams: List<TorrentioStream> = emptyList(),
)

@Serializable
data class TorrentioStream(
    val name: String? = null,
    val title: String? = null,
    val description: String? = null,
    val url: String? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
)

@Serializable
data class EncDecResponse(
    val result: EncDecResult? = null,
)

@Serializable
data class EncDecResult(
    val token: String? = null,
    val sources: List<EncDecSource>? = null,
    val subtitles: List<EncDecSubtitle>? = null,
)

@Serializable
data class EncDecSource(
    val url: String? = null,
    val quality: String? = null,
    val server: String? = null,
)

@Serializable
data class EncDecSubtitle(
    val url: String? = null,
    val language: String? = null,
)

@Serializable
data class SeedResponse(
    val seed: String? = null,
)

@Serializable
data class ReanimeResponse(
    val success: Boolean = false,
    val servers: List<ReanimeServer> = emptyList(),
)

@Serializable
data class ReanimeServer(
    val dataType: String = "",
    val dataLink: String = "",
)

@Serializable
data class Just4AnimeResponse(
    val data: Just4AnimeData? = null,
)

@Serializable
data class Just4AnimeData(
    val servers: List<Just4AnimeServer> = emptyList(),
)

@Serializable
data class Just4AnimeServer(
    val code: String? = null,
    val displayName: String? = null,
    val hasEpisode: Boolean = false,
    val types: List<String> = emptyList(),
)

@Serializable
data class Just4AnimeSourcesResponse(
    val data: Just4AnimeSourceData? = null,
)

@Serializable
data class Just4AnimeSourceData(
    val stream: Just4AnimeStream? = null,
)

@Serializable
data class Just4AnimeStream(
    val multi: List<Just4AnimeTrack>? = null,
    val subtitles: List<Just4AnimeTrack>? = null,
)

@Serializable
data class Just4AnimeTrack(
    val url: String? = null,
    val label: String? = null,
    val kind: String? = null,
)
