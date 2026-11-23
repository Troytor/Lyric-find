package com.example.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

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

object MusicApiFactory {
    private val sharedClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request()
                    .newBuilder()
                    .header("User-Agent", "CadenceAndroid/1.0 (Live Lyrics Sync)")
                    .header("Accept", "application/json")
                    .build()
                chain.proceed(request)
            }
            .build()
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
}
