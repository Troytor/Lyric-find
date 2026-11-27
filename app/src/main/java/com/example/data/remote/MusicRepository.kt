package com.example.data.remote

import android.util.Log
import com.example.engine.LrcParser
import com.example.engine.ShazamSignatureGenerator
import com.example.engine.WordPacingMlEngine
import com.example.model.DevicePlayingTrack
import com.example.model.LyricLine
import com.example.model.SearchLanguageOption
import com.example.model.SongCandidate
import com.example.model.SupportedSearchLanguages
import com.example.model.SyncedTrack
import com.example.model.TimingSource
import com.example.model.TrackMetadata
import com.example.model.TranslationLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.sqrt

private const val TAG = "MusicRepository"

sealed interface ShazamRecognitionOutcome {
    data class Matched(
        val candidate: SongCandidate,
        val sampleStartOffsetMs: Long,
    ) : ShazamRecognitionOutcome

    data class RetryNeeded(val retryMs: Long) : ShazamRecognitionOutcome
    data object NoMatch : ShazamRecognitionOutcome
    data class NetworkError(val message: String) : ShazamRecognitionOutcome
}

sealed interface AudioRecognitionOutcome {
    data class Matched(
        val candidate: SongCandidate,
        val timecodeMs: Long,
    ) : AudioRecognitionOutcome

    data class QuotaExceeded(val message: String) : AudioRecognitionOutcome
    data object NoMatch : AudioRecognitionOutcome
    data class NetworkError(val message: String) : AudioRecognitionOutcome
}

private data class SongLead(
    val title: String,
    val artist: String,
    val artworkUrl: String?,
    val origin: String,
    val baseConfidence: Float,
)

class MusicRepository(
    private val shazamApi: ShazamDiscoveryApi = MusicApiFactory.shazam,
    private val audDApi: AudDApi = MusicApiFactory.audD,
    private val oEmbedApi: OEmbedApi = MusicApiFactory.oEmbed,
    private val lrcLibApi: LrcLibApi = MusicApiFactory.lrcLib,
    private val itunesApi: ItunesSearchApi = MusicApiFactory.itunes,
    private val deezerApi: DeezerApi = MusicApiFactory.deezer,
    private val geniusApi: GeniusPublicApi = MusicApiFactory.genius,
    private val signatureGenerator: ShazamSignatureGenerator = ShazamSignatureGenerator(),
    private val translationService: LyricsTranslationService = LyricsTranslationService(),
    private val pacingEngine: WordPacingMlEngine = WordPacingMlEngine(),
) {

    private val translationCache = ConcurrentHashMap<String, TranslatedLyricsResult>()

    private val commonStopWords = setOf(
        "the", "and", "you", "your", "that", "this", "with", "for", "are", "was",
        "have", "not", "but", "all", "can", "just", "like", "know", "yeah", "ooh",
        "oh", "ah", "na", "la", "to", "in", "of", "on", "it", "is", "be", "my", "me",
    )

    /**
     * Free acoustic constellation fingerprinting against the open Shazam discovery v5 database.
     * No API key or paid quota required; returns exact song title, artist, album art, and sample start offset.
     */
    suspend fun identifySongFromShazamSignature(
        pcmSamples: ShortArray,
        tagUuid: String,
        sessionTimestampMs: Long,
    ): ShazamRecognitionOutcome {
        return try {
            val signature = withContext(Dispatchers.Default) {
                signatureGenerator.generateFromPcm16(pcmSamples)
            }
            if (signature.peakCount < 8) {
                return ShazamRecognitionOutcome.NoMatch
            }

            val requestUuid = UUID.randomUUID().toString().uppercase()
            val timezoneId = runCatching { TimeZone.getDefault().id }.getOrDefault("America/Chicago")
            val payload = ShazamRequestDto(
                signature = ShazamSignaturePayloadDto(
                    samplems = signature.sampleDurationMs,
                    timestamp = sessionTimestampMs,
                    uri = signature.dataUri,
                ),
                timestamp = sessionTimestampMs,
                timezone = timezoneId,
            )

            val response = shazamApi.recognizeSignature(
                uuid1 = tagUuid.uppercase(),
                uuid2 = requestUuid,
                body = payload,
            )

            val track = response.track
            val title = track?.title?.trim().orEmpty()
            val artist = track?.subtitle?.trim().orEmpty()
            if (title.isNotBlank() && artist.isNotBlank()) {
                val offsetSec = response.matches.firstOrNull()?.offset ?: 0.0
                val sampleStartOffsetMs = (offsetSec * 1_000.0).roundToLong().coerceAtLeast(0L)
                val albumName = track?.sections
                    ?.firstOrNull { it.type.equals("SONG", ignoreCase = true) }
                    ?.metadata
                    ?.firstOrNull { it.title.equals("Album", ignoreCase = true) }
                    ?.text
                    .orEmpty()
                val shazamPlainLyrics = track?.sections
                    ?.firstOrNull { it.type.equals("LYRICS", ignoreCase = true) }
                    ?.text
                    ?.joinToString("\n")
                    ?.takeIf { it.isNotBlank() }
                val artwork = track?.images?.coverarthq ?: track?.images?.coverart

                val candidate = SongCandidate(
                    metadata = TrackMetadata(
                        id = "shazam_${track?.key ?: "${normalizeText(title).hashCode()}"}",
                        title = title,
                        artist = artist,
                        album = albumName,
                        durationMs = 0L,
                        artworkUrl = artwork,
                        sourceLabel = "Shazam Acoustic DB",
                    ),
                    syncedLyricsRaw = null,
                    plainLyricsRaw = shazamPlainLyrics,
                    matchedLineStartMs = sampleStartOffsetMs,
                    matchScore = 1.0f,
                )
                return ShazamRecognitionOutcome.Matched(
                    candidate = candidate,
                    sampleStartOffsetMs = sampleStartOffsetMs,
                )
            }

            val retry = response.retryms
            if (retry != null && retry > 0L) {
                ShazamRecognitionOutcome.RetryNeeded(retry)
            } else {
                ShazamRecognitionOutcome.NoMatch
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Shazam acoustic lookup failed", e)
            ShazamRecognitionOutcome.NetworkError(e.localizedMessage ?: "Acoustic lookup failed")
        }
    }

    /**
     * Translates all vocal lyric lines of the active track with in-memory caching per (trackId, language).
     */
    suspend fun translateTrackLines(
        trackId: String,
        lines: List<LyricLine>,
        targetLanguage: TranslationLanguage,
    ): TranslatedLyricsResult? {
        val cacheKey = "${trackId}|${targetLanguage.code}"
        translationCache[cacheKey]?.let { return it }
        val result = translationService.translateLines(lines, targetLanguage)
        if (result != null && result.lineTranslations.isNotEmpty()) {
            translationCache[cacheKey] = result
        }
        return result
    }

    /**
     * Secondary acoustic audio fingerprinting via AudD API.
     * Sends a recorded 16 kHz mono PCM16 WAV clip and receives exact song title,
     * artist, album, and live playback timecode.
     */
    suspend fun identifySongFromAudioWav(
        wavBytes: ByteArray,
        apiToken: String,
    ): AudioRecognitionOutcome {
        return try {
            val effectiveToken = apiToken.trim().ifBlank { "test" }
            val tokenBody = effectiveToken.toRequestBody("text/plain".toMediaType())
            val wavBody = wavBytes.toRequestBody("audio/wav".toMediaType())
            val filePart = MultipartBody.Part.createFormData("file", "sample.wav", wavBody)

            val response = audDApi.recognizeAudio(
                apiToken = tokenBody,
                file = filePart,
            )

            if (response.status.equals("error", ignoreCase = true)) {
                val code = response.error?.errorCode ?: -1
                val msg = response.error?.errorMessage.orEmpty()
                if (code == 901 || code == 900 || msg.contains("limit", ignoreCase = true)) {
                    return AudioRecognitionOutcome.QuotaExceeded(
                        msg.ifBlank { "Shared AudD free recognition limit reached." },
                    )
                }
                return AudioRecognitionOutcome.NetworkError(
                    msg.ifBlank { "Recognition server error ($code)." },
                )
            }

            val hit = response.result
            val title = hit?.title?.trim().orEmpty()
            val artist = hit?.artist?.trim().orEmpty()
            if (title.isBlank() || artist.isBlank()) {
                return AudioRecognitionOutcome.NoMatch
            }

            val timecodeMs = parseTimecodeToMs(hit?.timecode)
            val candidate = SongCandidate(
                metadata = TrackMetadata(
                    id = "audd_${normalizeText(title).hashCode()}_${normalizeText(artist).hashCode()}",
                    title = title,
                    artist = artist,
                    album = hit?.album?.trim().orEmpty(),
                    durationMs = 0L,
                    artworkUrl = null,
                    sourceLabel = "AudD Audio Fingerprint",
                ),
                syncedLyricsRaw = null,
                plainLyricsRaw = null,
                matchedLineStartMs = timecodeMs,
                matchScore = 1.0f,
            )
            AudioRecognitionOutcome.Matched(candidate = candidate, timecodeMs = timecodeMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "AudD audio recognition failed", e)
            AudioRecognitionOutcome.NetworkError(e.localizedMessage ?: "Network request failed")
        }
    }

    /**
     * Resolves a track shared from another music app on the phone (Spotify, YouTube Music,
     * Apple Music, SoundCloud, etc.) via public oEmbed metadata or clean text extraction.
     */
    suspend fun resolveSharedTextOrUrl(sharedText: String): SongCandidate? {
        val raw = sharedText.trim()
        if (raw.isBlank()) return null

        val extractedUrl = Regex("https?://\\S+").find(raw)?.value?.trimEnd('.', ',', ')', ']')
        var queryFromOEmbed: String? = null
        var oEmbedArt: String? = null

        if (extractedUrl != null) {
            val encoded = URLEncoder.encode(extractedUrl, "UTF-8")
            val oEmbedUrl = when {
                extractedUrl.contains("spotify.com", ignoreCase = true) ->
                    "https://open.spotify.com/oembed?url=$encoded"
                extractedUrl.contains("youtube.com", ignoreCase = true) ||
                    extractedUrl.contains("youtu.be", ignoreCase = true) ->
                    "https://www.youtube.com/oembed?format=json&url=$encoded"
                extractedUrl.contains("soundcloud.com", ignoreCase = true) ->
                    "https://soundcloud.com/oembed?format=json&url=$encoded"
                else -> null
            }

            if (oEmbedUrl != null) {
                val dto = runCatching { oEmbedApi.fetchOEmbed(oEmbedUrl) }
                    .onFailure { if (it is CancellationException) throw it }
                    .getOrNull()
                if (dto != null && !dto.title.isNullOrBlank()) {
                    val cleanTitle = cleanSharedMediaTitle(dto.title)
                    val cleanAuthor = dto.authorName
                        ?.replace(Regex("\\s*-\\s*Topic$", RegexOption.IGNORE_CASE), "")
                        ?.replace(Regex("\\s*VEVO$", RegexOption.IGNORE_CASE), "")
                        ?.trim()
                        .orEmpty()
                    queryFromOEmbed = if (cleanAuthor.isNotBlank() &&
                        !cleanTitle.contains(cleanAuthor, ignoreCase = true)
                    ) {
                        "$cleanTitle $cleanAuthor"
                    } else {
                        cleanTitle
                    }
                    oEmbedArt = dto.thumbnailUrl
                }
            }
        }

        val cleanedTextQuery = queryFromOEmbed ?: extractQueryFromPlainShare(raw, extractedUrl)
        if (cleanedTextQuery.length < 2) return null

        val candidates = searchRealTracks(cleanedTextQuery)
        val best = candidates.firstOrNull { !it.syncedLyricsRaw.isNullOrBlank() }
            ?: candidates.firstOrNull()
        return best?.let { hit ->
            if (hit.metadata.artworkUrl == null && oEmbedArt != null) {
                hit.copy(metadata = hit.metadata.copy(artworkUrl = oEmbedArt))
            } else {
                hit
            }
        }
    }

    fun candidateFromDeviceTrack(deviceTrack: DevicePlayingTrack): SongCandidate {
        return SongCandidate(
            metadata = TrackMetadata(
                id = "device_${normalizeText(deviceTrack.title).hashCode()}_${normalizeText(deviceTrack.artist).hashCode()}",
                title = deviceTrack.title,
                artist = deviceTrack.artist,
                album = deviceTrack.album,
                durationMs = deviceTrack.durationMs,
                artworkUrl = null,
                sourceLabel = deviceTrack.sourceApp,
            ),
            syncedLyricsRaw = null,
            plainLyricsRaw = null,
            matchedLineStartMs = deviceTrack.estimatedCurrentPositionMs(),
            matchScore = 1.0f,
        )
    }

    /**
     * Universal multi-language internet song search combining LRCLIB synced lyrics,
     * regional iTunes storefronts, Deezer's international catalog, Genius lyric hits,
     * and automatic cross-script query expansion when a specific language is selected.
     */
    suspend fun searchRealTracks(
        query: String,
        language: SearchLanguageOption = SupportedSearchLanguages.all.first(),
    ): List<SongCandidate> = coroutineScope {
        val cleanQuery = query.trim()
        val hasNonAscii = cleanQuery.any { it.code > 127 }
        if (cleanQuery.isEmpty() || (!hasNonAscii && cleanQuery.length < 2)) {
            return@coroutineScope emptyList()
        }

        val lrcDeferred = async {
            runCatching { lrcLibApi.searchByQuery(cleanQuery) }
                .onFailure { if (it is CancellationException) throw it else Log.w(TAG, "LRCLIB search failed", it) }
                .getOrDefault(emptyList())
        }
        val itunesDeferred = async {
            runCatching {
                itunesApi.searchTracks(
                    term = cleanQuery,
                    limit = 15,
                    country = language.itunesCountry,
                    lang = language.itunesLang,
                ).results
            }.onFailure { if (it is CancellationException) throw it else Log.w(TAG, "iTunes search failed", it) }
                .getOrDefault(emptyList())
        }
        val deezerDeferred = async {
            runCatching { deezerApi.searchTracks(query = cleanQuery, limit = 10).data }
                .onFailure { if (it is CancellationException) throw it else Log.w(TAG, "Deezer search failed", it) }
                .getOrDefault(emptyList())
        }
        val geniusDeferred = async { fetchGeniusLeads(cleanQuery) }

        // If user chose a non-Latin language (e.g. Japanese/Korean/Arabic/Hindi) and typed Latin text,
        // also search the native-script equivalent in parallel for true universal discovery.
        val crossScriptDeferred = async {
            if (!language.isUniversal && language.requiresNonLatinScript && isPredominantlyLatin(cleanQuery)) {
                val nativeQuery = translationService.translateQueryText(cleanQuery, language.code)
                if (!nativeQuery.isNullOrBlank()) {
                    val nativeLrc = async {
                        runCatching { lrcLibApi.searchByQuery(nativeQuery) }
                            .onFailure { if (it is CancellationException) throw it }
                            .getOrDefault(emptyList())
                    }
                    val nativeItunes = async {
                        runCatching {
                            itunesApi.searchTracks(
                                term = nativeQuery,
                                limit = 10,
                                country = language.itunesCountry,
                                lang = language.itunesLang,
                            ).results
                        }.onFailure { if (it is CancellationException) throw it }
                            .getOrDefault(emptyList())
                    }
                    nativeLrc.await() to nativeItunes.await()
                } else {
                    emptyList<LrcLibRecord>() to emptyList<ItunesTrackDto>()
                }
            } else {
                emptyList<LrcLibRecord>() to emptyList<ItunesTrackDto>()
            }
        }

        val (nativeLrcRecords, nativeItunesTracks) = crossScriptDeferred.await()
        val lrcRecords = lrcDeferred.await() + nativeLrcRecords
        val itunesTracks = (nativeItunesTracks + itunesDeferred.await())
        val deezerTracks = deezerDeferred.await()
        val geniusLeads = geniusDeferred.await()

        val candidates = mutableListOf<SongCandidate>()
        val seenKeys = mutableSetOf<String>()

        val sortedLrc = lrcRecords
            .filter { !it.trackName.isNullOrBlank() && !it.artistName.isNullOrBlank() }
            .sortedWith(
                compareByDescending<LrcLibRecord> {
                    if (language.requiresNonLatinScript) {
                        matchesPreferredScript("${it.trackName} ${it.artistName}", language.code)
                    } else {
                        false
                    }
                }.thenByDescending { !it.syncedLyrics.isNullOrBlank() },
            )

        for (record in sortedLrc) {
            val title = record.trackName.orEmpty().trim()
            val artist = record.artistName.orEmpty().trim()
            val dedupKey = "${normalizeText(title)}|${normalizeText(artist)}"
            if (dedupKey == "|" || !seenKeys.add(dedupKey)) continue

            val matchedItunes = findMatchingItunesTrack(title, artist, itunesTracks)
            val artwork = matchedItunes?.artworkUrl100?.toHighResArtwork()
            val durationMs = ((record.duration ?: 0.0) * 1000.0).toLong()
                .takeIf { it > 0L } ?: (matchedItunes?.trackTimeMillis ?: 0L)

            candidates += SongCandidate(
                metadata = TrackMetadata(
                    id = "lrclib_${record.id}",
                    title = title,
                    artist = artist,
                    album = record.albumName?.takeIf { it.isNotBlank() }
                        ?: matchedItunes?.collectionName.orEmpty(),
                    durationMs = durationMs,
                    artworkUrl = artwork,
                    previewUrl = matchedItunes?.previewUrl,
                    sourceLabel = if (artwork != null) "LRCLIB · iTunes" else "LRCLIB",
                ),
                syncedLyricsRaw = record.syncedLyrics,
                plainLyricsRaw = record.plainLyrics,
            )
            if (candidates.size >= 14) break
        }

        // Regional iTunes storefront results (crucial for native-script Japanese, Korean, Hindi, Arabic, Latin catalogs)
        val sortedItunes = itunesTracks.sortedByDescending { track ->
            if (language.requiresNonLatinScript) {
                matchesPreferredScript("${track.trackName} ${track.artistName}", language.code)
            } else {
                false
            }
        }
        for (track in sortedItunes) {
            val title = track.trackName.orEmpty().trim()
            val artist = track.artistName.orEmpty().trim()
            if (title.isBlank() || artist.isBlank()) continue
            val dedupKey = "${normalizeText(title)}|${normalizeText(artist)}"
            if (dedupKey == "|" || !seenKeys.add(dedupKey)) continue

            candidates += SongCandidate(
                metadata = TrackMetadata(
                    id = "itunes_${track.trackId}",
                    title = title,
                    artist = artist,
                    album = track.collectionName.orEmpty(),
                    durationMs = track.trackTimeMillis ?: 0L,
                    artworkUrl = track.artworkUrl100?.toHighResArtwork(),
                    previewUrl = track.previewUrl,
                    sourceLabel = if (language.itunesCountry != null) {
                        "iTunes (${language.itunesCountry})"
                    } else {
                        "iTunes"
                    },
                ),
                syncedLyricsRaw = null,
                plainLyricsRaw = null,
            )
            if (candidates.size >= 20) break
        }

        // Deezer international catalog hits
        for (track in deezerTracks) {
            val title = track.title.orEmpty().trim()
            val artist = track.artist?.name.orEmpty().trim()
            if (title.isBlank() || artist.isBlank()) continue
            val dedupKey = "${normalizeText(title)}|${normalizeText(artist)}"
            if (dedupKey == "|" || !seenKeys.add(dedupKey)) continue

            candidates += SongCandidate(
                metadata = TrackMetadata(
                    id = "deezer_${track.id}",
                    title = title,
                    artist = artist,
                    album = track.album?.title.orEmpty(),
                    durationMs = (track.duration ?: 0) * 1_000L,
                    artworkUrl = track.album?.coverBig ?: track.album?.coverXl,
                    previewUrl = track.preview,
                    sourceLabel = "Deezer",
                ),
                syncedLyricsRaw = null,
                plainLyricsRaw = null,
            )
            if (candidates.size >= 24) break
        }

        for (lead in geniusLeads) {
            val dedupKey = "${normalizeText(lead.title)}|${normalizeText(lead.artist)}"
            if (dedupKey == "|" || !seenKeys.add(dedupKey)) continue
            val matchedItunes = findMatchingItunesTrack(lead.title, lead.artist, itunesTracks)
            candidates += SongCandidate(
                metadata = TrackMetadata(
                    id = "genius_${dedupKey.hashCode()}",
                    title = lead.title,
                    artist = lead.artist,
                    album = matchedItunes?.collectionName.orEmpty(),
                    durationMs = matchedItunes?.trackTimeMillis ?: 0L,
                    artworkUrl = matchedItunes?.artworkUrl100?.toHighResArtwork() ?: lead.artworkUrl,
                    previewUrl = matchedItunes?.previewUrl,
                    sourceLabel = "Genius Lyric Match",
                ),
                syncedLyricsRaw = null,
                plainLyricsRaw = null,
            )
            if (candidates.size >= 26) break
        }

        candidates
    }

    private fun isPredominantlyLatin(text: String): Boolean {
        val letters = text.filter { it.isLetter() }
        if (letters.isEmpty()) return false
        val asciiLetters = letters.count { it in 'a'..'z' || it in 'A'..'Z' }
        return asciiLetters.toFloat() / letters.length.toFloat() >= 0.7f
    }

    private fun matchesPreferredScript(text: String, langCode: String): Boolean = when (langCode) {
        "ja" -> text.any { ch ->
            val cp = ch.code
            cp in 0x3040..0x30FF || cp in 0x4E00..0x9FFF
        }
        "ko" -> text.any { ch -> ch.code in 0xAC00..0xD7AF || ch.code in 0x1100..0x11FF }
        "zh-CN" -> text.any { ch -> ch.code in 0x4E00..0x9FFF }
        "ar" -> text.any { ch -> ch.code in 0x0600..0x06FF }
        "hi" -> text.any { ch -> ch.code in 0x0900..0x097F }
        "ru" -> text.any { ch -> ch.code in 0x0400..0x04FF }
        "th" -> text.any { ch -> ch.code in 0x0E00..0x0E7F }
        else -> false
    }

    /**
     * Secondary vocal lyric matching fallback when acoustic fingerprinting returns no match.
     */
    suspend fun identifySongFromVocalTranscript(transcript: String): SongCandidate? = coroutineScope {
        val normalized = normalizeText(transcript)
        val tokens = normalized.split(" ").filter { it.length >= 2 }
        if (tokens.size < 2) return@coroutineScope null

        val recentSnippet = tokens.takeLast(minOf(tokens.size, 8)).joinToString(" ")
        val shorterSnippet = if (tokens.size >= 6) {
            tokens.takeLast(5).joinToString(" ")
        } else {
            recentSnippet
        }

        val geniusPrimaryDeferred = async { fetchGeniusLeads(recentSnippet) }
        val geniusShortDeferred = async {
            if (shorterSnippet != recentSnippet) fetchGeniusLeads(shorterSnippet) else emptyList()
        }
        val lrcDirectDeferred = async {
            runCatching { lrcLibApi.searchByQuery(recentSnippet) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrDefault(emptyList())
        }
        val itunesDirectDeferred = async {
            runCatching { itunesApi.searchTracks(term = recentSnippet, limit = 6).results }
                .onFailure { if (it is CancellationException) throw it }
                .getOrDefault(emptyList())
        }

        val leads = linkedMapOf<String, SongLead>()
        for (lead in geniusPrimaryDeferred.await() + geniusShortDeferred.await()) {
            val key = "${normalizeText(lead.title)}|${normalizeText(lead.artist)}"
            if (key !in leads) leads[key] = lead
        }
        for (item in itunesDirectDeferred.await()) {
            val t = item.trackName?.trim().orEmpty()
            val a = item.artistName?.trim().orEmpty()
            if (t.isNotBlank() && a.isNotBlank()) {
                val key = "${normalizeText(t)}|${normalizeText(a)}"
                if (key !in leads) {
                    leads[key] = SongLead(
                        title = t,
                        artist = a,
                        artworkUrl = item.artworkUrl100?.toHighResArtwork(),
                        origin = "iTunes",
                        baseConfidence = 0.08f,
                    )
                }
            }
        }

        val fetchedRecords = mutableListOf<Pair<LrcLibRecord, SongLead?>>()
        lrcDirectDeferred.await().forEach { rec ->
            if (!rec.trackName.isNullOrBlank() && !rec.artistName.isNullOrBlank()) {
                fetchedRecords += rec to null
            }
        }

        val topLeads = leads.values.take(6)
        val leadLrcResults = topLeads.map { lead ->
            async {
                val cleanTitle = stripTitleDecorations(lead.title)
                val cleanArtist = lead.artist.substringBefore(",").substringBefore("&").trim()
                val byFields = runCatching {
                    lrcLibApi.searchByFields(trackName = cleanTitle, artistName = cleanArtist)
                }.onFailure { if (it is CancellationException) throw it }.getOrDefault(emptyList())

                val bestRecord = byFields.firstOrNull { !it.syncedLyrics.isNullOrBlank() }
                    ?: byFields.firstOrNull { !it.plainLyrics.isNullOrBlank() }
                    ?: runCatching { lrcLibApi.searchByQuery("$cleanTitle $cleanArtist") }
                        .onFailure { if (it is CancellationException) throw it }
                        .getOrDefault(emptyList())
                        .firstOrNull { !it.syncedLyrics.isNullOrBlank() || !it.plainLyrics.isNullOrBlank() }

                bestRecord?.let { it to lead }
            }
        }.awaitAll().filterNotNull()

        fetchedRecords += leadLrcResults

        var bestCandidate: SongCandidate? = null
        var bestScore = 0f
        val seenIds = mutableSetOf<Long>()

        for ((record, lead) in fetchedRecords) {
            if (record.id != 0L && !seenIds.add(record.id)) continue
            val title = record.trackName?.trim().orEmpty()
            val artist = record.artistName?.trim().orEmpty()
            if (title.isBlank() || artist.isBlank()) continue

            val synced = record.syncedLyrics
            var lineScore = 0f
            var matchedStartMs: Long? = null

            if (!synced.isNullOrBlank()) {
                val parsed = LrcParser.parse(synced)
                val match = scoreAgainstRawLines(normalized, parsed.lines.map { it.startMs to it.text })
                if (match != null) {
                    lineScore = match.second + 0.10f
                    matchedStartMs = match.first
                }
            } else if (!record.plainLyrics.isNullOrBlank()) {
                val plainPairs = record.plainLyrics.lines().mapIndexed { i, line -> (i * 3_000L) to line }
                val match = scoreAgainstRawLines(normalized, plainPairs)
                if (match != null) {
                    lineScore = match.second * 0.80f
                }
            }

            val titleBoost = scoreLyricMatch(recentSnippet, title) * 0.12f
            val originBoost = lead?.baseConfidence ?: 0f
            val totalScore = (lineScore + titleBoost + originBoost).coerceAtMost(1.25f)

            if (totalScore > bestScore) {
                bestScore = totalScore
                bestCandidate = SongCandidate(
                    metadata = TrackMetadata(
                        id = "lrclib_${record.id}",
                        title = title,
                        artist = artist,
                        album = record.albumName.orEmpty().trim(),
                        durationMs = ((record.duration ?: 0.0) * 1000.0).toLong(),
                        artworkUrl = lead?.artworkUrl,
                        sourceLabel = if (lead != null) "Genius · LRCLIB" else "LRCLIB Vocal Match",
                    ),
                    syncedLyricsRaw = record.syncedLyrics,
                    plainLyricsRaw = record.plainLyrics,
                    matchedLineStartMs = matchedStartMs,
                    matchScore = totalScore,
                )
            }
        }

        if (bestCandidate != null && bestScore >= 0.44f) {
            bestCandidate
        } else {
            val fallbackLead = topLeads.firstOrNull { it.origin == "Genius Lyric" }
            if (fallbackLead != null && tokens.size >= 4) {
                SongCandidate(
                    metadata = TrackMetadata(
                        id = "genius_${fallbackLead.title.hashCode()}_${fallbackLead.artist.hashCode()}",
                        title = stripTitleDecorations(fallbackLead.title),
                        artist = fallbackLead.artist,
                        album = "",
                        durationMs = 0L,
                        artworkUrl = fallbackLead.artworkUrl,
                        sourceLabel = "Genius Lyric Match",
                    ),
                    syncedLyricsRaw = null,
                    plainLyricsRaw = null,
                    matchedLineStartMs = null,
                    matchScore = 0.50f,
                )
            } else {
                null
            }
        }
    }

    /**
     * Loads real synced lyrics (LRCLIB), high-res cover art (iTunes/Deezer), and real BPM (Deezer),
     * then runs WordPacingMlEngine to build word-by-word timed lyrics.
     */
    suspend fun loadFullSyncedTrack(
        candidate: SongCandidate,
        liveDetectedBpm: Float? = null,
    ): SyncedTrack = coroutineScope {
        val title = stripTitleDecorations(candidate.metadata.title)
        val artist = candidate.metadata.artist

        val lyricsDeferred = async {
            if (!candidate.syncedLyricsRaw.isNullOrBlank()) {
                candidate.syncedLyricsRaw to candidate.plainLyricsRaw
            } else {
                val primaryArtist = artist.substringBefore(",").substringBefore("&").trim()
                val baseTitle = title.substringBefore(" (").substringBefore(" - ").trim()
                val targetDurSec = candidate.metadata.durationMs / 1000.0

                val byFields = runCatching {
                    lrcLibApi.searchByFields(trackName = title, artistName = primaryArtist.ifBlank { null })
                }.onFailure { if (it is CancellationException) throw it }
                    .getOrDefault(emptyList())

                val byQuery = if (byFields.none { !it.syncedLyrics.isNullOrBlank() }) {
                    runCatching { lrcLibApi.searchByQuery("$title $primaryArtist".trim()) }
                        .onFailure { if (it is CancellationException) throw it }
                        .getOrDefault(emptyList())
                } else {
                    emptyList()
                }

                val byBaseTitle = if ((byFields + byQuery).none { !it.syncedLyrics.isNullOrBlank() } &&
                    baseTitle.isNotBlank() && baseTitle != title
                ) {
                    runCatching { lrcLibApi.searchByQuery("$baseTitle $primaryArtist".trim()) }
                        .onFailure { if (it is CancellationException) throw it }
                        .getOrDefault(emptyList())
                } else {
                    emptyList()
                }

                val combined = (byFields + byQuery + byBaseTitle).distinctBy { it.id }
                val syncedMatches = combined.filter { !it.syncedLyrics.isNullOrBlank() }
                val bestSynced = if (targetDurSec > 20.0 && syncedMatches.isNotEmpty()) {
                    syncedMatches.minByOrNull { rec ->
                        val recDur = rec.duration ?: targetDurSec
                        abs(recDur - targetDurSec)
                    }
                } else {
                    syncedMatches.firstOrNull()
                }

                val fetched = bestSynced ?: combined.firstOrNull { !it.plainLyrics.isNullOrBlank() }
                fetched?.syncedLyrics to (fetched?.plainLyrics ?: candidate.plainLyricsRaw)
            }
        }

        val itunesDeferred = async {
            if (candidate.metadata.artworkUrl != null) {
                null
            } else {
                runCatching { itunesApi.searchTracks("$title $artist".trim(), limit = 5).results }
                    .onFailure { if (it is CancellationException) throw it }
                    .getOrDefault(emptyList())
                    .let { findMatchingItunesTrack(title, artist, it) }
            }
        }

        val deezerDeferred = async {
            runCatching {
                val q = if (artist.isNotBlank()) "track:\"$title\" artist:\"$artist\"" else title
                val search = deezerApi.searchTracks(q, limit = 3)
                val firstId = search.data.firstOrNull()?.id
                    ?: deezerApi.searchTracks("$title $artist".trim(), limit = 3).data.firstOrNull()?.id
                if (firstId != null) deezerApi.getTrackDetail(firstId) else null
            }.onFailure { if (it is CancellationException) throw it }
                .getOrNull()
        }

        val (syncedRaw, plainRaw) = lyricsDeferred.await()
        val itunesMatch = itunesDeferred.await()
        val deezerDetail = deezerDeferred.await()

        val resolvedArtwork = candidate.metadata.artworkUrl
            ?: itunesMatch?.artworkUrl100?.toHighResArtwork()
            ?: deezerDetail?.album?.coverXl
            ?: deezerDetail?.album?.coverBig

        val deezerBpm = deezerDetail?.bpm?.toFloat()?.takeIf { it in 45f..210f }
        val resolvedBpm = deezerBpm ?: liveDetectedBpm

        val resolvedDurationMs = candidate.metadata.durationMs.takeIf { it > 0L }
            ?: itunesMatch?.trackTimeMillis
            ?: deezerDetail?.duration?.let { it * 1_000L }
            ?: 210_000L

        val sources = buildList {
            if (candidate.metadata.sourceLabel.contains("Shazam", ignoreCase = true)) {
                add("Shazam Acoustic DB")
            } else if (candidate.metadata.sourceLabel.contains("AudD", ignoreCase = true)) {
                add("AudD")
            } else if (candidate.metadata.sourceLabel in setOf("Spotify", "Apple Music", "Samsung Music", "Poweramp", "Amazon Music", "Phone Player")) {
                add(candidate.metadata.sourceLabel)
            }
            add("LRCLIB")
            if (resolvedArtwork != null) add("iTunes")
            if (deezerBpm != null) add("Deezer BPM")
        }.distinct().joinToString(" · ")

        val metadata = candidate.metadata.copy(
            artworkUrl = resolvedArtwork,
            bpm = resolvedBpm,
            durationMs = resolvedDurationMs,
            sourceLabel = sources,
        )

        val synthesized = when {
            !syncedRaw.isNullOrBlank() -> pacingEngine.synthesizeFromSyncedLrc(
                rawLrc = syncedRaw,
                trackDurationMs = resolvedDurationMs,
                bpm = resolvedBpm,
            )
            !plainRaw.isNullOrBlank() -> pacingEngine.synthesizeFromPlainLyrics(
                plainLyrics = plainRaw,
                trackDurationMs = resolvedDurationMs,
                bpm = resolvedBpm,
            )
            else -> null
        }

        SyncedTrack(
            metadata = metadata,
            lines = synthesized?.lines.orEmpty(),
            timingSource = synthesized?.timingSource ?: TimingSource.PROSODIC_BEAT_ENGINE,
            engineConfidence = synthesized?.confidence ?: 0f,
            isSyncedAvailable = !syncedRaw.isNullOrBlank(),
        )
    }

    /**
     * Deterministic phonetic & n-gram alignment to snap playback to a newly heard lyric phrase.
     */
    fun findBestRelockTimestamp(
        heardPhrase: String,
        lines: List<LyricLine>,
        currentPositionMs: Long,
        preferProximity: Boolean = true,
    ): Pair<Long, Float>? {
        val cleaned = normalizeText(heardPhrase)
        val heardTokens = cleaned.split(" ").filter { it.length >= 2 }
        val hasNonAscii = cleaned.any { it.code > 127 }
        if (heardTokens.size < 2 && !(hasNonAscii && cleaned.length >= 3)) return null

        val vocalLines = lines.filter { !it.isInstrumental && it.rawText.isNotBlank() }
        if (vocalLines.isEmpty()) return null

        var bestTimestampMs: Long? = null
        var bestScore = 0f

        for (i in vocalLines.indices) {
            val line = vocalLines[i]
            val distSec = abs(line.startMs - currentPositionMs) / 1000f
            val proximity = if (preferProximity) {
                (1f - (distSec / 45f)).coerceIn(0f, 1f)
            } else {
                0.5f
            }

            val singleScore = scoreLyricMatch(cleaned, line.rawText, proximity)
            if (singleScore > bestScore) {
                bestScore = singleScore
                bestTimestampMs = estimatePhraseEndTimestamp(heardTokens, line)
            }

            if (i + 1 < vocalLines.size) {
                val nextLine = vocalLines[i + 1]
                val combined = "${line.rawText} ${nextLine.rawText}"
                val combinedScore = scoreLyricMatch(cleaned, combined, proximity)
                if (combinedScore > bestScore) {
                    bestScore = combinedScore
                    bestTimestampMs = estimatePhraseEndTimestamp(heardTokens, nextLine)
                }
            }
        }

        return if (bestTimestampMs != null && bestScore >= 0.48f) {
            bestTimestampMs to bestScore
        } else {
            null
        }
    }

    fun normalizeText(raw: String): String =
        raw.lowercase()
            .replace("'", "")
            .replace("’", "")
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    fun phoneticKey(word: String): String {
        val w = normalizeText(word).replace(" ", "")
        if (w.length <= 2) return w
        val mapped = w
            .replace("ph", "f")
            .replace("ck", "k")
            .replace("qu", "k")
            .replace("gh", "")
            .replace("wh", "w")
            .replace("kn", "n")
            .replace("wr", "r")
            .replace('c', 'k')
            .replace('z', 's')
            .replace('v', 'f')
            .replace('d', 't')
            .replace('p', 'b')
            .replace('g', 'k')
        val out = StringBuilder()
        out.append(mapped.first())
        for (i in 1 until mapped.length) {
            val ch = mapped[i]
            if (ch !in "aeiouyh" && ch != out.last()) {
                out.append(ch)
            }
        }
        return out.toString()
    }

    fun scoreLyricMatch(
        heardRaw: String,
        candidateRaw: String,
        proximityBonus: Float = 0f,
    ): Float {
        val heard = normalizeText(heardRaw)
        val cand = normalizeText(candidateRaw)
        if (heard.length < 2 || cand.length < 2) return 0f
        if (heard == cand) return 1f

        val hWords = heard.split(" ").filter { it.length >= 2 }
        val cWords = cand.split(" ").filter { it.length >= 2 }

        // For unspaced scripts (Japanese, Chinese, Thai) use character n-gram overlap directly
        val compactHeard = heard.replace(" ", "")
        val compactCand = cand.replace(" ", "")
        if (hWords.size <= 1 || cWords.size <= 1) {
            val ngramScore = maxOf(
                charNgramCosine(compactHeard, compactCand, 2),
                charNgramCosine(compactHeard, compactCand, 3),
            )
            val containsBoost = if (compactHeard.length >= 3 && compactCand.contains(compactHeard)) 0.25f else 0f
            return (ngramScore * 0.90f + containsBoost + proximityBonus.coerceIn(0f, 1f) * 0.08f).coerceIn(0f, 1f)
        }

        val cSet = cWords.toSet()
        val cPhonSet = cWords.map { phoneticKey(it) }.toSet()

        var totalWeight = 0f
        var exactHit = 0f
        var phonHit = 0f
        for (w in hWords) {
            val weight = if (w in commonStopWords) 0.45f else 1.25f
            totalWeight += weight
            if (w in cSet) {
                exactHit += weight
                phonHit += weight
            } else if (phoneticKey(w) in cPhonSet) {
                phonHit += weight * 0.88f
            }
        }

        val exactRecall = (exactHit / totalWeight.coerceAtLeast(0.01f)).coerceIn(0f, 1f)
        val phonRecall = (phonHit / totalWeight.coerceAtLeast(0.01f)).coerceIn(0f, 1f)

        val hBigrams = hWords.zipWithNext { a, b -> "$a $b" }.toSet()
        val cBigrams = cWords.zipWithNext { a, b -> "$a $b" }.toSet()
        val bigramScore = if (hBigrams.isNotEmpty() && cBigrams.isNotEmpty()) {
            hBigrams.count { it in cBigrams }.toFloat() / hBigrams.size.toFloat()
        } else {
            exactRecall
        }

        val trigramScore = charNgramCosine(heard, cand, 3)
        val raw = (exactRecall * 0.36f) +
            (phonRecall * 0.28f) +
            (bigramScore * 0.16f) +
            (trigramScore * 0.14f) +
            (proximityBonus.coerceIn(0f, 1f) * 0.06f)
        return raw.coerceIn(0f, 1f)
    }

    private fun charNgramCosine(a: String, b: String, n: Int): Float {
        if (a.length < n || b.length < n) return 0f
        val mapA = HashMap<String, Int>()
        val mapB = HashMap<String, Int>()
        for (i in 0..a.length - n) {
            val g = a.substring(i, i + n)
            mapA[g] = (mapA[g] ?: 0) + 1
        }
        for (i in 0..b.length - n) {
            val g = b.substring(i, i + n)
            mapB[g] = (mapB[g] ?: 0) + 1
        }
        var dot = 0f
        var magA = 0f
        var magB = 0f
        for ((k, v) in mapA) {
            magA += v * v
            val bv = mapB[k]
            if (bv != null) dot += v * bv
        }
        for (v in mapB.values) magB += v * v
        val denom = sqrt(magA) * sqrt(magB)
        return if (denom > 0f) (dot / denom).coerceIn(0f, 1f) else 0f
    }

    private fun parseTimecodeToMs(timecode: String?): Long {
        if (timecode.isNullOrBlank()) return 0L
        val parts = timecode.trim().split(":").mapNotNull { it.toLongOrNull() }
        return when (parts.size) {
            2 -> (parts[0] * 60_000L) + (parts[1] * 1_000L)
            3 -> (parts[0] * 3_600_000L) + (parts[1] * 60_000L) + (parts[2] * 1_000L)
            else -> 0L
        }
    }

    private fun cleanSharedMediaTitle(rawTitle: String): String =
        rawTitle
            .replace(Regex("\\s*\\((official|music|lyric|audio|hd|4k)[^)]*\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\[(official|music|lyric|audio|hd|4k)[^]]*]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\|\\s*.*$"), "")
            .trim()

    private fun extractQueryFromPlainShare(raw: String, url: String?): String {
        val withoutUrl = if (url != null) raw.replace(url, " ") else raw
        return withoutUrl
            .replace(Regex("^(listen to|check out|playing|now playing)\\s+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+on\\s+(apple music|spotify|youtube music|amazon music|deezer|tidal).*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+by\\s+", RegexOption.IGNORE_CASE), " ")
            .replace(" - ", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun estimatePhraseEndTimestamp(heardTokens: List<String>, matchedLine: LyricLine): Long {
        if (matchedLine.words.isEmpty()) return matchedLine.endMs
        val lastHeardPhon = phoneticKey(heardTokens.last())
        val matchedWord = matchedLine.words.lastOrNull {
            phoneticKey(it.text) == lastHeardPhon
        }
        return matchedWord?.endMs ?: matchedLine.endMs
    }

    private suspend fun fetchGeniusLeads(query: String): List<SongLead> = coroutineScope {
        val lyricDeferred = async {
            runCatching { geniusApi.searchLyrics(query = query, perPage = 8) }
                .onFailure { if (it is CancellationException) throw it else Log.w(TAG, "Genius lyric search failed", it) }
                .getOrNull()
        }
        val multiDeferred = async {
            runCatching { geniusApi.searchMulti(query = query, perPage = 6) }
                .onFailure { if (it is CancellationException) throw it else Log.w(TAG, "Genius multi search failed", it) }
                .getOrNull()
        }

        val collected = mutableListOf<SongLead>()
        val lyricSections = lyricDeferred.await()?.response?.sections.orEmpty()
        for (section in lyricSections) {
            for (hit in section.hits) {
                val res = hit.result ?: continue
                val title = res.title?.trim().orEmpty()
                val artist = (res.primaryArtist?.name ?: res.artistNames)?.trim().orEmpty()
                if (title.isNotBlank() && artist.isNotBlank()) {
                    collected += SongLead(
                        title = title,
                        artist = artist,
                        artworkUrl = res.songArtImageUrl,
                        origin = "Genius Lyric",
                        baseConfidence = 0.16f,
                    )
                }
            }
        }

        val multiSections = multiDeferred.await()?.response?.sections.orEmpty()
        for (section in multiSections) {
            if (section.type != "lyric" && section.type != "song" && section.type != "top_hit") continue
            for (hit in section.hits) {
                if (hit.type != "song" && hit.type != null) continue
                val res = hit.result ?: continue
                val title = res.title?.trim().orEmpty()
                val artist = (res.primaryArtist?.name ?: res.artistNames)?.trim().orEmpty()
                if (title.isNotBlank() && artist.isNotBlank()) {
                    collected += SongLead(
                        title = title,
                        artist = artist,
                        artworkUrl = res.songArtImageUrl,
                        origin = if (section.type == "lyric") "Genius Lyric" else "Genius",
                        baseConfidence = if (section.type == "lyric") 0.14f else 0.10f,
                    )
                }
            }
        }
        collected
    }

    private fun scoreAgainstRawLines(
        heardNormalized: String,
        lines: List<Pair<Long, String>>,
    ): Pair<Long, Float>? {
        var bestTimestamp: Long? = null
        var bestScore = 0f

        for (i in lines.indices) {
            val lineText = lines[i].second
            if (lineText.isBlank()) continue

            val singleScore = scoreLyricMatch(heardNormalized, lineText)
            if (singleScore > bestScore) {
                bestScore = singleScore
                bestTimestamp = lines[i].first
            }

            if (i + 1 < lines.size) {
                val combined = "$lineText ${lines[i + 1].second}"
                val combinedScore = scoreLyricMatch(heardNormalized, combined)
                if (combinedScore > bestScore) {
                    bestScore = combinedScore
                    bestTimestamp = lines[i + 1].first
                }
            }
        }
        return bestTimestamp?.let { it to bestScore }
    }

    private fun findMatchingItunesTrack(
        title: String,
        artist: String,
        itunesTracks: List<ItunesTrackDto>,
    ): ItunesTrackDto? {
        val normTitle = normalizeText(stripTitleDecorations(title))
        val normArtist = normalizeText(artist)
        return itunesTracks.firstOrNull { dto ->
            val dt = normalizeText(dto.trackName.orEmpty())
            val da = normalizeText(dto.artistName.orEmpty())
            (dt == normTitle || dt.contains(normTitle) || normTitle.contains(dt)) &&
                (normArtist.isBlank() || da == normArtist || da.contains(normArtist) || normArtist.contains(da))
        } ?: itunesTracks.firstOrNull { dto ->
            normalizeText(dto.trackName.orEmpty()).contains(normTitle)
        }
    }

    private fun stripTitleDecorations(raw: String): String =
        raw.replace(Regex("\\s*\\((feat|ft|with)\\.[^)]*\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\[[^]]*]"), "")
            .trim()

    private fun String.toHighResArtwork(): String =
        replace("100x100bb", "600x600bb")
            .replace("100x100-", "600x600-")
}
