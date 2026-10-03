package eu.kanade.tachiyomi.animeextension.en.cinestream

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class CinemetaCatalogResponse(
    val metas: List<CinemetaMedia> = emptyList(),
    val hasMore: Boolean = true,
)

@Serializable
class CinemetaMedia(
    val id: String,
    val type: String,
    val name: String? = null,
    val poster: String? = null,
    val description: String? = null,
    val imdbRating: String? = null,
    val aliases: List<String>? = null,
)

@Serializable
class CinemetaMetaDetailResponse(
    val meta: CinemetaMetaDetail? = null,
)

@Serializable
class CinemetaMetaDetail(
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
class CinemetaVideo(
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
class HaglundIds(
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
class MediaPayload(
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
    val epImdbId: String? = null,
    val imdbSeason: Int? = null,
    val imdbEpisode: Int? = null,
    val isKitsu: Boolean = false,
    val anilistId: Int? = null,
    val malId: Int? = null,
    val kitsuId: String? = null,
)

@Serializable
class HosterPayload(
    val providerKey: String,
    val media: MediaPayload,
)

@Serializable
class TorrentioResponse(
    val streams: List<TorrentioStream> = emptyList(),
)

@Serializable
class TorrentioStream(
    val name: String? = null,
    val title: String? = null,
    val description: String? = null,
    val url: String? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
)

@Serializable
class AnimeToshoItemDto(
    val title: String? = null,
    @SerialName("magnet_uri") val magnetUri: String? = null,
    @SerialName("magnet_url") val magnetUrl: String? = null,
    @SerialName("torrent_url") val torrentUrl: String? = null,
)

@Serializable
class EncDecResponse(
    val result: EncDecResult? = null,
)

@Serializable
class EncDecSingleResultResponse(
    val result: String? = null,
)

@Serializable
class EncDecResult(
    val token: String? = null,
    val sources: List<EncDecSource>? = null,
    val subtitles: List<EncDecSubtitle>? = null,
)

@Serializable
class EncDecSource(
    val url: String? = null,
    val quality: String? = null,
    val server: String? = null,
)

@Serializable
class EncDecSubtitle(
    val url: String? = null,
    val language: String? = null,
)

@Serializable
class SeedResponse(
    val seed: String? = null,
)

@Serializable
class ReanimeResponse(
    val success: Boolean = false,
    val servers: List<ReanimeServer> = emptyList(),
)

@Serializable
class ReanimeServer(
    val dataType: String = "",
    val dataLink: String = "",
)

@Serializable
class Just4AnimeResponse(
    val data: Just4AnimeData? = null,
)

@Serializable
class Just4AnimeData(
    val servers: List<Just4AnimeServer> = emptyList(),
)

@Serializable
class Just4AnimeServer(
    val code: String? = null,
    val displayName: String? = null,
    val hasEpisode: Boolean = false,
    val types: List<String> = emptyList(),
)

@Serializable
class Just4AnimeSourcesResponse(
    val data: Just4AnimeSourceData? = null,
)

@Serializable
class Just4AnimeSourceData(
    val stream: Just4AnimeStream? = null,
)

@Serializable
class Just4AnimeStream(
    val multi: List<Just4AnimeTrack>? = null,
    val subtitles: List<Just4AnimeTrack>? = null,
)

@Serializable
class Just4AnimeTrack(
    val url: String? = null,
    val label: String? = null,
    val kind: String? = null,
)

@Serializable
class KisskhSearchItemDto(
    val id: Int? = null,
    val title: String? = null,
)

@Serializable
class KisskhDetailDto(
    val episodes: List<KisskhEpisodeDto> = emptyList(),
)

@Serializable
class KisskhEpisodeDto(
    val id: Int? = null,
    val number: Float? = null,
)

@Serializable
class KisskhVideoDto(
    @SerialName("Video") val video: String? = null,
)

@Serializable
class AniZipResponse(
    val titles: Map<String, String?>? = null,
    val episodes: Map<String, AniZipEpisode?>? = null,
    val episodeCount: Int? = null,
    val specialCount: Int? = null,
    val mappings: AniZipMappings? = null,
)

@Serializable
class AniZipEpisode(
    val episode: String? = null,
    val episodeNumber: Int? = null,
    val seasonNumber: Int? = null,
    val title: Map<String, String?>? = null,
    val overview: String? = null,
    val image: String? = null,
    val rating: String? = null,
)

@Serializable
class AniZipMappings(
    @SerialName("animeplanet_id") val animePlanetId: String? = null,
    @SerialName("kitsu_id") val kitsuId: Long? = null,
    @SerialName("mal_id") val myAnimeListId: Long? = null,
    val type: String? = null,
    @SerialName("anilist_id") val aniListId: Long? = null,
    @SerialName("anisearch_id") val aniSearchId: Long? = null,
    @SerialName("anidb_id") val aniDbId: Long? = null,
    @SerialName("thetvdb_id") val theTvDbId: Long? = null,
    @SerialName("imdb_id") val imdbId: String? = null,
    @SerialName("themoviedb_id") val theMovieDbId: String? = null,
)
