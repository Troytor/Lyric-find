package com.example.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url
import java.util.concurrent.TimeUnit

@JsonClass(generateAdapter = true)
data class ShazamGeoDto(
    val altitude: Int = 300,
    val latitude: Double = 45.0,
    val longitude: Double = 2.0,
)

@JsonClass(generateAdapter = true)
data class ShazamSignaturePayloadDto(
    val samplems: Int,
    val timestamp: Long,
    val uri: String,
)

@JsonClass(generateAdapter = true)
data class ShazamRequestDto(
    val geolocation: ShazamGeoDto = ShazamGeoDto(),
    val signature: ShazamSignaturePayloadDto,
    val timestamp: Long,
    val timezone: String = "America/Chicago",
)

@JsonClass(generateAdapter = true)
data class ShazamDiscoveryResponseDto(
    val matches: List<ShazamMatchDto> = emptyList(),
    val track: ShazamTrackDto? = null,
    val retryms: Long? = null,
)

@JsonClass(generateAdapter = true)
data class ShazamMatchDto(
    val id: String? = null,
    val offset: Double? = null,
    val timeskew: Double? = null,
    val frequencyskew: Double? = null,
)

@JsonClass(generateAdapter = true)
data class ShazamTrackDto(
    val key: String? = null,
    val title: String? = null,
    val subtitle: String? = null,
    val images: ShazamImagesDto? = null,
    val sections: List<ShazamSectionDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class ShazamImagesDto(
    val background: String? = null,
    val coverart: String? = null,
    val coverarthq: String? = null,
)

@JsonClass(generateAdapter = true)
data class ShazamSectionDto(
    val type: String? = null,
    val text: List<String>? = null,
    val metadata: List<ShazamMetadataItemDto>? = null,
)

@JsonClass(generateAdapter = true)
data class ShazamMetadataItemDto(
    val title: String? = null,
    val text: String? = null,
)

interface ShazamDiscoveryApi {
    @POST("discovery/v5/en-US/US/android/-/tag/{uuid1}/{uuid2}")
    suspend fun recognizeSignature(
        @Path("uuid1") uuid1: String,
        @Path("uuid2") uuid2: String,
        @Body body: ShazamRequestDto,
        @Query("sync") sync: Boolean = true,
        @Query("webv3") webv3: Boolean = true,
        @Query("sampling") sampling: Boolean = true,
        @Query("connected") connected: String = "",
        @Query("shazamapiversion") shazamApiVersion: String = "v3",
        @Query("sharehub") sharehub: Boolean = true,
        @Query("hubv5minorversion") hubMinorVersion: String = "v5.1",
        @Query("hidelb") hideLb: Boolean = true,
        @Query("video") video: String = "v3",
    ): ShazamDiscoveryResponseDto
}

@JsonClass(generateAdapter = true)
data class AudDResponseDto(
    val status: String? = null,
    val result: AudDResultDto? = null,
    val error: AudDErrorDto? = null,
)

@JsonClass(generateAdapter = true)
data class AudDResultDto(
    val artist: String? = null,
    val title: String? = null,
    val album: String? = null,
    val timecode: String? = null,
    @Json(name = "release_date") val releaseDate: String? = null,
)

@JsonClass(generateAdapter = true)
data class AudDErrorDto(
    @Json(name = "error_code") val errorCode: Int? = null,
    @Json(name = "error_message") val errorMessage: String? = null,
)

interface AudDApi {
    @Multipart
    @POST("/")
    suspend fun recognizeAudio(
        @Part("api_token") apiToken: RequestBody,
        @Part file: MultipartBody.Part,
    ): AudDResponseDto
}

@JsonClass(generateAdapter = true)
data class OEmbedResponseDto(
    val title: String? = null,
    @Json(name = "author_name") val authorName: String? = null,
    @Json(name = "thumbnail_url") val thumbnailUrl: String? = null,
)

interface OEmbedApi {
    @GET
    suspend fun fetchOEmbed(@Url endpointUrl: String): OEmbedResponseDto
}

@JsonClass(generateAdapter = true)
data class LrcLibRecord(
    val id: Long = 0L,
    val trackName: String? = null,
    val artistName: String? = null,
    val albumName: String? = null,
    val duration: Double? = null,
    val instrumental: Boolean = false,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
)

interface LrcLibApi {
    @GET("api/search")
    suspend fun searchByQuery(@Query("q") query: String): List<LrcLibRecord>

    @GET("api/search")
    suspend fun searchByFields(
        @Query("track_name") trackName: String,
        @Query("artist_name") artistName: String? = null,
    ): List<LrcLibRecord>
}

@JsonClass(generateAdapter = true)
data class ItunesSearchResponse(
    val resultCount: Int = 0,
    val results: List<ItunesTrackDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class ItunesTrackDto(
    val trackId: Long = 0L,
    val trackName: String? = null,
    val artistName: String? = null,
    val collectionName: String? = null,
    val artworkUrl100: String? = null,
    val previewUrl: String? = null,
    val trackTimeMillis: Long? = null,
)

interface ItunesSearchApi {
    @GET("search")
    suspend fun searchTracks(
        @Query("term") term: String,
        @Query("media") media: String = "music",
        @Query("entity") entity: String = "song",
        @Query("limit") limit: Int = 10,
    ): ItunesSearchResponse
}

@JsonClass(generateAdapter = true)
data class DeezerSearchResponse(
    val data: List<DeezerSearchItemDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class DeezerSearchItemDto(
    val id: Long = 0L,
    val title: String? = null,
    val duration: Int? = null,
    val preview: String? = null,
    val artist: DeezerArtistDto? = null,
    val album: DeezerAlbumDto? = null,
)

@JsonClass(generateAdapter = true)
data class DeezerArtistDto(
    val name: String? = null,
)

@JsonClass(generateAdapter = true)
data class DeezerAlbumDto(
    val title: String? = null,
    @Json(name = "cover_xl") val coverXl: String? = null,
    @Json(name = "cover_big") val coverBig: String? = null,
)

@JsonClass(generateAdapter = true)
data class DeezerTrackDetailDto(
    val id: Long = 0L,
    val title: String? = null,
    val duration: Int? = null,
    val bpm: Double? = null,
    val album: DeezerAlbumDto? = null,
)

interface DeezerApi {
    @GET("search")
    suspend fun searchTracks(
        @Query("q") query: String,
        @Query("limit") limit: Int = 5,
    ): DeezerSearchResponse

    @GET("track/{id}")
    suspend fun getTrackDetail(@Path("id") trackId: Long): DeezerTrackDetailDto
}

@JsonClass(generateAdapter = true)
data class GeniusPublicSearchResponse(
    val response: GeniusSectionsBody? = null,
)

@JsonClass(generateAdapter = true)
data class GeniusSectionsBody(
    val sections: List<GeniusSectionDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class GeniusSectionDto(
    val type: String? = null,
    val hits: List<GeniusHitDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class GeniusHitDto(
    val type: String? = null,
    val result: GeniusSongResultDto? = null,
)

@JsonClass(generateAdapter = true)
data class GeniusSongResultDto(
    val id: Long = 0L,
    val title: String? = null,
    @Json(name = "artist_names") val artistNames: String? = null,
    @Json(name = "song_art_image_url") val songArtImageUrl: String? = null,
    @Json(name = "primary_artist") val primaryArtist: GeniusArtistDto? = null,
)

@JsonClass(generateAdapter = true)
data class GeniusArtistDto(
    val name: String? = null,
)

interface GeniusPublicApi {
    @GET("api/search/lyric")
    suspend fun searchLyrics(
        @Query("q") query: String,
        @Query("per_page") perPage: Int = 8,
    ): GeniusPublicSearchResponse

    @GET("api/search/multi")
    suspend fun searchMulti(
        @Query("q") query: String,
        @Query("per_page") perPage: Int = 6,
    ): GeniusPublicSearchResponse
}

object MusicApiFactory {
    private val sharedClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(14, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request()
                    .newBuilder()
                    .header("User-Agent", "CadenceAndroid/1.2 (https://cadence.local)")
                    .header("Accept", "application/json")
                    .build()
                chain.proceed(request)
            }
            .build()
    }

    private val audDClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .readTimeout(24, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request()
                    .newBuilder()
                    .header("User-Agent", "CadenceAndroid/1.2")
                    .header("Accept", "application/json")
                    .build()
                chain.proceed(request)
            }
            .build()
    }

    private val webSearchClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request()
                    .newBuilder()
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
                    )
                    .header("Accept", "application/json, text/plain, */*")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .build()
                chain.proceed(request)
            }
            .build()
    }

    private val shazamClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(18, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request()
                    .newBuilder()
                    .header(
                        "User-Agent",
                        "Dalvik/2.1.0 (Linux; U; Android 14; Pixel 8 Build/UQ1A.240205.002)",
                    )
                    .header("Content-Language", "en_US")
                    .header("Accept", "application/json")
                    .build()
                chain.proceed(request)
            }
            .build()
    }

    val shazam: ShazamDiscoveryApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://amp.shazam.com/")
            .client(shazamClient)
            .addConverterFactory(MoshiConverterFactory.create())
            .build()
            .create(ShazamDiscoveryApi::class.java)
    }

    val audD: AudDApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.audd.io/")
            .client(audDClient)
            .addConverterFactory(MoshiConverterFactory.create())
            .build()
            .create(AudDApi::class.java)
    }

    val oEmbed: OEmbedApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://open.spotify.com/")
            .client(sharedClient)
            .addConverterFactory(MoshiConverterFactory.create())
            .build()
            .create(OEmbedApi::class.java)
    }

    val lrcLib: LrcLibApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://lrclib.net/")
            .client(sharedClient)
            .addConverterFactory(MoshiConverterFactory.create())
            .build()
            .create(LrcLibApi::class.java)
    }

    val itunes: ItunesSearchApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://itunes.apple.com/")
            .client(sharedClient)
            .addConverterFactory(MoshiConverterFactory.create())
            .build()
            .create(ItunesSearchApi::class.java)
    }

    val deezer: DeezerApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.deezer.com/")
            .client(sharedClient)
            .addConverterFactory(MoshiConverterFactory.create())
            .build()
            .create(DeezerApi::class.java)
    }

    val genius: GeniusPublicApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://genius.com/")
            .client(webSearchClient)
            .addConverterFactory(MoshiConverterFactory.create())
            .build()
            .create(GeniusPublicApi::class.java)
    }
}
