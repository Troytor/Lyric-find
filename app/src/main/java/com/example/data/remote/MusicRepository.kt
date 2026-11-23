package com.example.data.remote

import android.util.Log
import com.example.engine.LocalCadenceNeuralNet
import com.example.engine.LrcParser
import com.example.engine.WordPacingMlEngine
import com.example.model.LyricLine
import com.example.model.SongCandidate
import com.example.model.SyncedTrack
import com.example.model.TimingSource
import com.example.model.TrackMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.math.abs

private const val TAG = "MusicRepository"

private data class SongLead(
    val title: String,
    val artist: String,
    val artworkUrl: String?,
    val origin: String,
    val baseConfidence: Float,
)

class MusicRepository(
    private val lrcLibApi: LrcLibApi = MusicApiFactory.lrcLib,
    private val itunesApi: ItunesSearchApi = MusicApiFactory.itunes,
    private val deezerApi: DeezerApi = MusicApiFactory.deezer,
    private val geniusApi: GeniusPublicApi = MusicApiFactory.genius,
    private val neuralNet: LocalCadenceNeuralNet = LocalCadenceNeuralNet(),
    private val pacingEngine: WordPacingMlEngine = WordPacingMlEngine(neuralNet),
) {

    /**
     * Live internet search combining LRCLIB synced lyrics, iTunes artwork, and Genius full-text lyric matches.
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
        val geniusDeferred = async {
            fetchGeniusLeads(cleanQuery)
        }

        val lrcRecords = lrcDeferred.await()
        val itunesTracks = itunesDeferred.await()
        val geniusLeads = geniusDeferred.await()

        val candidates = mutableListOf<SongCandidate>()
        val seenKeys = mutableSetOf<String>()

        val sortedLrc = lrcRecords
            .filter { !it.trackName.isNullOrBlank() && !it.artistName.isNullOrBlank() }
            .sortedByDescending { !it.syncedLyrics.isNullOrBlank() }

        for (record in sortedLrc) {
            val title = record.trackName.orEmpty().trim()
            val artist = record.artistName.orEmpty().trim()
            val dedupKey = "${neuralNet.normalizeLyrics(title)}|${neuralNet.normalizeLyrics(artist)}"
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
            if (candidates.size >= 14) break
        }

        // Include Genius full-text lyric hits (essential when user types a lyric line into Search!)
        for (lead in geniusLeads) {
            val dedupKey = "${neuralNet.normalizeLyrics(lead.title)}|${neuralNet.normalizeLyrics(lead.artist)}"
            if (!seenKeys.add(dedupKey)) continue
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
            if (candidates.size >= 16) break
        }

        if (candidates.size < 8) {
            for (track in itunesTracks) {
                val title = track.trackName.orEmpty().trim()
                val artist = track.artistName.orEmpty().trim()
                if (title.isBlank() || artist.isBlank()) continue
                val dedupKey = "${neuralNet.normalizeLyrics(title)}|${neuralNet.normalizeLyrics(artist)}"
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
                if (candidates.size >= 14) break
            }
        }

        candidates
    }

    /**
     * Robust multi-provider song identification from live microphone vocal phrases:
     * 1. Searches full-text lyrics on Genius + metadata on iTunes & LRCLIB in parallel.
     * 2. Fetches timestamped LRC lyrics from LRCLIB for top candidate songs.
     * 3. Runs CadenceNet-Nano phonetic-lexical alignment across every line & 2-line window
     *    to pick the exact real song and its current live timestamp.
     */
    suspend fun identifySongFromVocalTranscript(transcript: String): SongCandidate? = coroutineScope {
        val normalized = neuralNet.normalizeLyrics(transcript)
        val tokens = normalized.split(" ").filter { it.length >= 2 }
        if (tokens.size < 2) return@coroutineScope null

        // Build focused search snippets (most recent 8 words + most distinctive 5-word window)
        val recentSnippet = tokens.takeLast(minOf(tokens.size, 8)).joinToString(" ")
        val shorterSnippet = if (tokens.size >= 6) {
            tokens.takeLast(5).joinToString(" ")
        } else {
            recentSnippet
        }

        // Stage 1: Discover song candidates from full-text lyrics (Genius) + iTunes + LRCLIB in parallel
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
            val key = "${neuralNet.normalizeLyrics(lead.title)}|${neuralNet.normalizeLyrics(lead.artist)}"
            if (key !in leads) leads[key] = lead
        }
        for (item in itunesDirectDeferred.await()) {
            val t = item.trackName?.trim().orEmpty()
            val a = item.artistName?.trim().orEmpty()
            if (t.isNotBlank() && a.isNotBlank()) {
                val key = "${neuralNet.normalizeLyrics(t)}|${neuralNet.normalizeLyrics(a)}"
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

        // Stage 2: Fetch full synced LRC records from LRCLIB for the top 6 leads in parallel
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

        // Stage 3: Score every candidate's real timestamped lyrics with CadenceNet-Nano
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

            // Title similarity bonus if the user sang or spoke the title hook
            val titleBoost = neuralNet.scoreLyricAlignment(recentSnippet, title) * 0.12f
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
                        sourceLabel = if (lead != null) "Genius · LRCLIB" else "LRCLIB Live Match",
                    ),
                    syncedLyricsRaw = record.syncedLyrics,
                    plainLyricsRaw = record.plainLyrics,
                    matchedLineStartMs = matchedStartMs,
                    matchScore = totalScore,
                )
            }
        }

        // If Genius found a strong full-text lyric hit but LRCLIB missed exact artist punctuation, return top Genius lead
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
     * Loads full high-res artwork (iTunes/Deezer), real track BPM (Deezer), and
     * runs CadenceNet-Nano + WordPacingMlEngine to produce word-by-word timed lyrics.
     */
    suspend fun loadFullSyncedTrack(
        candidate: SongCandidate,
        liveDetectedBpm: Float? = null,
    ): SyncedTrack = coroutineScope {
        val title = stripTitleDecorations(candidate.metadata.title)
        val artist = candidate.metadata.artist

        val lyricsDeferred = async {
            if (!candidate.syncedLyricsRaw.isNullOrBlank() || !candidate.plainLyricsRaw.isNullOrBlank()) {
                candidate.syncedLyricsRaw to candidate.plainLyricsRaw
            } else {
                val primaryArtist = artist.substringBefore(",").substringBefore("&").trim()
                val fetched = runCatching {
                    lrcLibApi.searchByFields(trackName = title, artistName = primaryArtist)
                }.onFailure { if (it is CancellationException) throw it }
                    .getOrDefault(emptyList())
                    .firstOrNull { !it.syncedLyrics.isNullOrBlank() }
                    ?: runCatching { lrcLibApi.searchByQuery("$title $primaryArtist") }
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
            timingSource = synthesized?.timingSource ?: TimingSource.LOCAL_NEURAL_CADENCE_ENGINE,
            engineConfidence = synthesized?.confidence ?: 0f,
            isSyncedAvailable = !syncedRaw.isNullOrBlank(),
        )
    }

    /**
     * Matches a newly heard vocal snippet against the active track's lyric lines using
     * CadenceNet-Nano (single-line and 2-line sliding windows with phonetic similarity)
     * to snap playback to the real live position in the song.
     */
    fun findBestRelockTimestamp(
        heardPhrase: String,
        lines: List<LyricLine>,
        currentPositionMs: Long,
        preferProximity: Boolean = true,
    ): Pair<Long, Float>? {
        val cleaned = neuralNet.normalizeLyrics(heardPhrase)
        val heardTokens = cleaned.split(" ").filter { it.length >= 2 }
        if (heardTokens.size < 2) return null

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

            val singleScore = neuralNet.scoreLyricAlignment(cleaned, line.rawText, proximity)
            if (singleScore > bestScore) {
                bestScore = singleScore
                // Estimate which word within the line corresponds to the end of the heard phrase
                bestTimestampMs = estimatePhraseEndTimestamp(heardTokens, line)
            }

            // Two-line sliding window in case the microphone captured across a line break
            if (i + 1 < vocalLines.size) {
                val nextLine = vocalLines[i + 1]
                val combined = "${line.rawText} ${nextLine.rawText}"
                val combinedScore = neuralNet.scoreLyricAlignment(cleaned, combined, proximity)
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

    private fun estimatePhraseEndTimestamp(heardTokens: List<String>, matchedLine: LyricLine): Long {
        if (matchedLine.words.isEmpty()) return matchedLine.endMs
        val lastHeardPhon = neuralNet.phoneticSkeleton(heardTokens.last())
        val matchedWord = matchedLine.words.lastOrNull {
            neuralNet.phoneticSkeleton(it.text) == lastHeardPhon
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

            val singleScore = neuralNet.scoreLyricAlignment(heardNormalized, lineText)
            if (singleScore > bestScore) {
                bestScore = singleScore
                bestTimestamp = lines[i].first
            }

            if (i + 1 < lines.size) {
                val combined = "$lineText ${lines[i + 1].second}"
                val combinedScore = neuralNet.scoreLyricAlignment(heardNormalized, combined)
                if (combinedScore > bestScore) {
                    bestScore = combinedScore
                    // When matching across 2 lines, the end of the phrase is near line i+1 start
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
        val normTitle = neuralNet.normalizeLyrics(stripTitleDecorations(title))
        val normArtist = neuralNet.normalizeLyrics(artist)
        return itunesTracks.firstOrNull { dto ->
            val dt = neuralNet.normalizeLyrics(dto.trackName.orEmpty())
            val da = neuralNet.normalizeLyrics(dto.artistName.orEmpty())
            (dt == normTitle || dt.contains(normTitle) || normTitle.contains(dt)) &&
                (da == normArtist || da.contains(normArtist) || normArtist.contains(da))
        } ?: itunesTracks.firstOrNull { dto ->
            neuralNet.normalizeLyrics(dto.trackName.orEmpty()).contains(normTitle)
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
