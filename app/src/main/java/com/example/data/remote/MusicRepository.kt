package com.example.data.remote

import android.util.Log
import com.example.engine.LrcParser
import com.example.engine.WordPacingMlEngine
import com.example.model.LyricLine
import com.example.model.SongCandidate
import com.example.model.SyncedTrack
import com.example.model.TimingSource
import com.example.model.TrackMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.math.max

private const val TAG = "MusicRepository"

class MusicRepository(
    private val lrcLibApi: LrcLibApi = MusicApiFactory.lrcLib,
    private val itunesApi: ItunesSearchApi = MusicApiFactory.itunes,
    private val deezerApi: DeezerApi = MusicApiFactory.deezer,
    private val pacingEngine: WordPacingMlEngine = WordPacingMlEngine(),
) {

    /**
     * Live internet search merging LRCLIB synced lyrics with iTunes high-resolution artwork.
     */
    suspend fun searchRealTracks(query: String): List<SongCandidate> = coroutineScope {
        val cleanQuery = query.trim()
        if (cleanQuery.length < 2) return@coroutineScope emptyList()

        val lrcDeferred = async {
            runCatching { lrcLibApi.searchByQuery(cleanQuery) }
                .onFailure { if (it is CancellationException) throw it else Log.w(TAG, "LRCLIB search failed", it) }
                .getOrDefault(emptyList())
        }
        val itunesDeferred = async {
            runCatching { itunesApi.searchTracks(term = cleanQuery, limit = 12).results }
                .onFailure { if (it is CancellationException) throw it else Log.w(TAG, "iTunes search failed", it) }
                .getOrDefault(emptyList())
        }

        val lrcRecords = lrcDeferred.await()
        val itunesTracks = itunesDeferred.await()

        val candidates = mutableListOf<SongCandidate>()
        val seenKeys = mutableSetOf<String>()

        // Prioritize LRCLIB tracks that have synced lyrics, enriched with iTunes artwork
        val sortedLrc = lrcRecords
            .filter { !it.trackName.isNullOrBlank() && !it.artistName.isNullOrBlank() }
            .sortedByDescending { !it.syncedLyrics.isNullOrBlank() }

        for (record in sortedLrc) {
            val title = record.trackName.orEmpty().trim()
            val artist = record.artistName.orEmpty().trim()
            val dedupKey = "${normalizeText(title)}|${normalizeText(artist)}"
            if (!seenKeys.add(dedupKey)) continue

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
            if (candidates.size >= 15) break
        }

        // If LRCLIB returned few results, also include iTunes tracks so user can still tap and fetch lyrics by fields
        if (candidates.size < 6) {
            for (track in itunesTracks) {
                val title = track.trackName.orEmpty().trim()
                val artist = track.artistName.orEmpty().trim()
                if (title.isBlank() || artist.isBlank()) continue
                val dedupKey = "${normalizeText(title)}|${normalizeText(artist)}"
                if (!seenKeys.add(dedupKey)) continue

                candidates += SongCandidate(
                    metadata = TrackMetadata(
                        id = "itunes_${track.trackId}",
                        title = title,
                        artist = artist,
                        album = track.collectionName.orEmpty(),
                        durationMs = track.trackTimeMillis ?: 0L,
                        artworkUrl = track.artworkUrl100?.toHighResArtwork(),
                        previewUrl = track.previewUrl,
                        sourceLabel = "iTunes",
                    ),
                    syncedLyricsRaw = null,
                    plainLyricsRaw = null,
                )
                if (candidates.size >= 12) break
            }
        }

        candidates
    }

    /**
     * Identifies a real song from live spoken/sung vocal phrases captured by the microphone
     * by querying LRCLIB and aligning the phrase against real timestamped LRC lines.
     */
    suspend fun identifySongFromVocalTranscript(transcript: String): SongCandidate? = coroutineScope {
        val cleaned = normalizeText(transcript)
        val tokens = cleaned.split(" ").filter { it.length >= 2 }
        if (tokens.size < 2) return@coroutineScope null

        // Build up to 2 targeted lyric search phrases (full snippet + strongest 5-word window)
        val queries = linkedSetOf<String>()
        queries += tokens.takeLast(minOf(tokens.size, 8)).joinToString(" ")
        if (tokens.size >= 5) {
            queries += tokens.take(5).joinToString(" ")
        }

        val allRecords = mutableListOf<LrcLibRecord>()
        for (q in queries) {
            val results = runCatching { lrcLibApi.searchByQuery(q) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrDefault(emptyList())
            allRecords += results
            if (allRecords.any { !it.syncedLyrics.isNullOrBlank() }) break
        }

        val uniqueRecords = allRecords
            .filter { !it.trackName.isNullOrBlank() && !it.artistName.isNullOrBlank() }
            .distinctBy { it.id }

        if (uniqueRecords.isEmpty()) return@coroutineScope null

        var bestCandidate: SongCandidate? = null
        var bestScore = 0f

        for (record in uniqueRecords) {
            val synced = record.syncedLyrics
            var lineMatchScore = 0f
            var matchedStartMs: Long? = null

            if (!synced.isNullOrBlank()) {
                val parsed = LrcParser.parse(synced)
                val lineMatch = findBestMatchingRawLine(cleaned, parsed.lines.map { it.startMs to it.text })
                if (lineMatch != null) {
                    lineMatchScore = lineMatch.second + 0.15f // Bonus for synced LRC availability
                    matchedStartMs = lineMatch.first
                }
            } else if (!record.plainLyrics.isNullOrBlank()) {
                val plainLines = record.plainLyrics.lines().mapIndexed { idx, s -> (idx * 3_000L) to s }
                val lineMatch = findBestMatchingRawLine(cleaned, plainLines)
                if (lineMatch != null) {
                    lineMatchScore = lineMatch.second * 0.75f
                }
            }

            if (lineMatchScore > bestScore) {
                bestScore = lineMatchScore
                bestCandidate = SongCandidate(
                    metadata = TrackMetadata(
                        id = "lrclib_${record.id}",
                        title = record.trackName.orEmpty().trim(),
                        artist = record.artistName.orEmpty().trim(),
                        album = record.albumName.orEmpty().trim(),
                        durationMs = ((record.duration ?: 0.0) * 1000.0).toLong(),
                        artworkUrl = null,
                        sourceLabel = "LRCLIB Live Match",
                    ),
                    syncedLyricsRaw = record.syncedLyrics,
                    plainLyricsRaw = record.plainLyrics,
                    matchedLineStartMs = matchedStartMs,
                    matchScore = lineMatchScore,
                )
            }
        }

        // Require meaningful lyrical overlap so we never return a random false positive
        if (bestCandidate != null && bestScore >= 0.38f) {
            bestCandidate
        } else {
            null
        }
    }

    /**
     * Loads full high-res artwork (iTunes/Deezer), real track BPM (Deezer), and
     * runs the WordPacingMlEngine to produce word-by-word timed lyrics.
     */
    suspend fun loadFullSyncedTrack(
        candidate: SongCandidate,
        liveDetectedBpm: Float? = null,
    ): SyncedTrack = coroutineScope {
        val title = candidate.metadata.title
        val artist = candidate.metadata.artist

        val lyricsDeferred = async {
            if (!candidate.syncedLyricsRaw.isNullOrBlank() || !candidate.plainLyricsRaw.isNullOrBlank()) {
                candidate.syncedLyricsRaw to candidate.plainLyricsRaw
            } else {
                val fetched = runCatching {
                    lrcLibApi.searchByFields(trackName = title, artistName = artist)
                }.onFailure { if (it is CancellationException) throw it }
                    .getOrDefault(emptyList())
                    .firstOrNull { !it.syncedLyrics.isNullOrBlank() }
                    ?: runCatching { lrcLibApi.searchByQuery("$title $artist") }
                        .onFailure { if (it is CancellationException) throw it }
                        .getOrDefault(emptyList())
                        .firstOrNull { !it.syncedLyrics.isNullOrBlank() || !it.plainLyrics.isNullOrBlank() }
                fetched?.syncedLyrics to fetched?.plainLyrics
            }
        }

        val itunesDeferred = async {
            if (candidate.metadata.artworkUrl != null) {
                null
            } else {
                runCatching { itunesApi.searchTracks("$title $artist", limit = 5).results }
                    .onFailure { if (it is CancellationException) throw it }
                    .getOrDefault(emptyList())
                    .let { findMatchingItunesTrack(title, artist, it) }
            }
        }

        val deezerDeferred = async {
            runCatching {
                val search = deezerApi.searchTracks("track:\"$title\" artist:\"$artist\"", limit = 3)
                val firstId = search.data.firstOrNull()?.id
                    ?: deezerApi.searchTracks("$title $artist", limit = 3).data.firstOrNull()?.id
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
            add("LRCLIB")
            if (resolvedArtwork != null) add("iTunes")
            if (deezerBpm != null) add("Deezer BPM")
        }.joinToString(" · ")

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
            timingSource = synthesized?.timingSource ?: TimingSource.ML_PROSODIC_BEAT_ENGINE,
            engineConfidence = synthesized?.confidence ?: 0f,
            isSyncedAvailable = !syncedRaw.isNullOrBlank(),
        )
    }

    /**
     * Matches a newly heard vocal snippet against the active track's lyric lines
     * to re-synchronize the live playback clock mid-song.
     */
    fun findBestRelockTimestamp(
        heardPhrase: String,
        lines: List<LyricLine>,
        currentPositionMs: Long,
    ): Long? {
        val cleaned = normalizeText(heardPhrase)
        if (cleaned.split(" ").count { it.length >= 2 } < 2) return null

        val vocalLines = lines.filter { !it.isInstrumental && it.rawText.isNotBlank() }
        if (vocalLines.isEmpty()) return null

        var bestLine: LyricLine? = null
        var bestScore = 0f

        for (line in vocalLines) {
            val baseScore = computeLyricSimilarity(cleaned, normalizeText(line.rawText))
            // Mild proximity tie-breaker for repeated choruses
            val distanceSec = kotlin.math.abs(line.startMs - currentPositionMs) / 1000f
            val proximityBonus = if (distanceSec < 25f) 0.08f else 0f
            val total = baseScore + proximityBonus
            if (total > bestScore) {
                bestScore = total
                bestLine = line
            }
        }

        return if (bestLine != null && bestScore >= 0.42f) {
            // Offset forward slightly (~850ms) to compensate for speech recognition latency
            (bestLine.startMs + 850L).coerceAtMost(bestLine.endMs)
        } else {
            null
        }
    }

    private fun findBestMatchingRawLine(
        heardNormalized: String,
        lines: List<Pair<Long, String>>,
    ): Pair<Long, Float>? {
        var bestStart: Long? = null
        var bestScore = 0f

        for (i in lines.indices) {
            val singleNorm = normalizeText(lines[i].second)
            if (singleNorm.isBlank()) continue
            val singleScore = computeLyricSimilarity(heardNormalized, singleNorm)
            if (singleScore > bestScore) {
                bestScore = singleScore
                bestStart = lines[i].first
            }

            // Also evaluate two-line sliding window in case the sung phrase spans a line break
            if (i + 1 < lines.size) {
                val combined = "$singleNorm ${normalizeText(lines[i + 1].second)}"
                val combinedScore = computeLyricSimilarity(heardNormalized, combined)
                if (combinedScore > bestScore) {
                    bestScore = combinedScore
                    bestStart = lines[i].first
                }
            }
        }

        return bestStart?.let { it to bestScore }
    }

    private fun computeLyricSimilarity(query: String, candidateLine: String): Float {
        if (query.isBlank() || candidateLine.isBlank()) return 0f
        if (candidateLine.contains(query)) return 0.96f

        val qTokens = query.split(" ").filter { it.length >= 2 }
        val cTokens = candidateLine.split(" ").filter { it.length >= 2 }
        if (qTokens.isEmpty() || cTokens.isEmpty()) return 0f

        val cSet = cTokens.toSet()
        var exactMatches = 0
        for (t in qTokens) {
            if (t in cSet) exactMatches++
        }
        val recall = exactMatches.toFloat() / qTokens.size.toFloat()
        val precision = exactMatches.toFloat() / max(qTokens.size, cTokens.size).toFloat()

        // Ordered bigram match bonus
        var bigramMatches = 0
        if (qTokens.size >= 2 && cTokens.size >= 2) {
            val cBigrams = cTokens.zipWithNext().toSet()
            for (pair in qTokens.zipWithNext()) {
                if (pair in cBigrams) bigramMatches++
            }
        }
        val bigramRatio = if (qTokens.size >= 2) {
            bigramMatches.toFloat() / (qTokens.size - 1).toFloat()
        } else {
            0f
        }

        return (recall * 0.55f) + (precision * 0.20f) + (bigramRatio * 0.25f)
    }

    private fun findMatchingItunesTrack(
        title: String,
        artist: String,
        itunesTracks: List<ItunesTrackDto>,
    ): ItunesTrackDto? {
        val normTitle = normalizeText(title)
        val normArtist = normalizeText(artist)
        return itunesTracks.firstOrNull { dto ->
            val dt = normalizeText(dto.trackName.orEmpty())
            val da = normalizeText(dto.artistName.orEmpty())
            (dt == normTitle || dt.contains(normTitle) || normTitle.contains(dt)) &&
                (da == normArtist || da.contains(normArtist) || normArtist.contains(da))
        } ?: itunesTracks.firstOrNull { dto ->
            normalizeText(dto.trackName.orEmpty()).contains(normTitle)
        }
    }

    private fun String.toHighResArtwork(): String =
        replace("100x100bb", "600x600bb")
            .replace("100x100-", "600x600-")

    private fun normalizeText(input: String): String =
        input.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
