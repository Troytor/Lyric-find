package com.example.engine

import com.example.model.LyricLine
import com.example.model.TimingSource
import com.example.model.WordTiming
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

data class CloudVocalProfile(
    val isAvailable: Boolean,
    val statusNote: String,
)

interface CloudAiPacingRepository {
    fun getCloudStatus(): CloudVocalProfile
}

class LocalOnDevicePacingRepository(
    private val neuralNet: LocalCadenceNeuralNet = LocalCadenceNeuralNet(),
) : CloudAiPacingRepository {
    override fun getCloudStatus(): CloudVocalProfile = CloudVocalProfile(
        isAvailable = true,
        statusNote = "${neuralNet.modelName} (${neuralNet.parameterCount} params) running locally on-device for vocal VAD, beat lock, and word pacing.",
    )
}

data class SynthesizedLyricsResult(
    val lines: List<LyricLine>,
    val timingSource: TimingSource,
    val confidence: Float,
)

/**
 * Local AI word-pacing & beat-alignment engine backed by CadenceNet-Nano.
 * Transforms standard line-level LRC timestamps into smooth word-by-word fill timings.
 */
class WordPacingMlEngine(
    private val neuralNet: LocalCadenceNeuralNet = LocalCadenceNeuralNet(),
) {

    private val unstressedLexicon = setOf(
        "a", "an", "the", "to", "of", "in", "on", "at", "by", "for", "and", "or",
        "but", "is", "are", "was", "were", "be", "it", "its", "my", "your", "our",
        "me", "us", "as", "if", "so", "do", "did", "with", "from", "that", "this",
    )

    private val vowelSet = setOf('a', 'e', 'i', 'o', 'u', 'y')
    private val sustainedConsonants = setOf('m', 'n', 'l', 'r', 's', 'z', 'f', 'v', 'h')
    private val plosiveConsonants = setOf('p', 'b', 't', 'd', 'k', 'g')

    fun synthesizeFromSyncedLrc(
        rawLrc: String,
        trackDurationMs: Long,
        bpm: Float?,
    ): SynthesizedLyricsResult {
        val parsed = LrcParser.parse(rawLrc)
        val rawLines = parsed.lines
        if (rawLines.isEmpty()) {
            return SynthesizedLyricsResult(emptyList(), TimingSource.LOCAL_NEURAL_CADENCE_ENGINE, 0f)
        }

        val effectiveDuration = max(
            trackDurationMs,
            rawLines.last().startMs + 6_000L,
        )
        val beatPeriodMs = resolveBeatPeriodMs(bpm)
        val output = mutableListOf<LyricLine>()

        val firstStart = rawLines.first().startMs
        if (firstStart >= 4_500L) {
            output += LyricLine(
                index = 0,
                startMs = 0L,
                endMs = firstStart,
                rawText = "",
                words = emptyList(),
                isInstrumental = true,
            )
        }

        for (i in rawLines.indices) {
            val current = rawLines[i]
            val nextStart = if (i + 1 < rawLines.size) rawLines[i + 1].startMs else effectiveDuration
            val rawWindowMs = (nextStart - current.startMs).coerceAtLeast(500L)

            if (current.text.isBlank()) {
                if (rawWindowMs >= 4_000L) {
                    output += LyricLine(
                        index = output.size,
                        startMs = current.startMs,
                        endMs = nextStart,
                        rawText = "",
                        words = emptyList(),
                        isInstrumental = true,
                    )
                }
                continue
            }

            val maxActiveSingWindow = estimateMaxVocalDuration(current.text, rawWindowMs, beatPeriodMs)
            val lineEndMs = (current.startMs + maxActiveSingWindow).coerceAtMost(nextStart)

            val timedWords = if (current.enhancedWords.isNotEmpty()) {
                normalizeEnhancedWords(current.enhancedWords, lineEndMs)
            } else {
                predictWordTimings(
                    text = current.text,
                    lineStartMs = current.startMs,
                    lineEndMs = lineEndMs,
                    bpm = bpm,
                    beatPeriodMs = beatPeriodMs,
                )
            }

            output += LyricLine(
                index = output.size,
                startMs = current.startMs,
                endMs = lineEndMs,
                rawText = current.text,
                words = timedWords,
                isInstrumental = false,
            )

            val gapAfterLine = nextStart - lineEndMs
            if (gapAfterLine >= 6_500L && i + 1 < rawLines.size) {
                output += LyricLine(
                    index = output.size,
                    startMs = lineEndMs,
                    endMs = nextStart,
                    rawText = "",
                    words = emptyList(),
                    isInstrumental = true,
                )
            }
        }

        val source = if (parsed.hasEnhancedWordTags) {
            TimingSource.ENHANCED_LRC
        } else {
            TimingSource.LOCAL_NEURAL_CADENCE_ENGINE
        }
        val confidence = when {
            parsed.hasEnhancedWordTags -> 0.98f
            bpm != null && bpm > 40f -> 0.93f
            else -> 0.87f
        }
        return SynthesizedLyricsResult(output, source, confidence)
    }

    fun synthesizeFromPlainLyrics(
        plainLyrics: String,
        trackDurationMs: Long,
        bpm: Float?,
    ): SynthesizedLyricsResult {
        val textLines = plainLyrics.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (textLines.isEmpty()) {
            return SynthesizedLyricsResult(emptyList(), TimingSource.PLAIN_ESTIMATED, 0f)
        }

        val totalMs = if (trackDurationMs > 15_000L) trackDurationMs else textLines.size * 3_200L
        val introMs = min(8_000L, (totalMs * 0.06f).roundToLong())
        val usableMs = (totalMs - introMs - 4_000L).coerceAtLeast(textLines.size * 1_500L)
        val beatPeriodMs = resolveBeatPeriodMs(bpm)

        val lineWeights = textLines.map { line ->
            val words = line.split(Regex("\\s+")).filter { it.isNotBlank() }
            words.mapIndexed { idx, w ->
                scoreWordWithNeuralNet(w, idx == 0, idx == words.lastIndex, bpm).durationWeight.toDouble()
            }.sum().coerceAtLeast(1.5)
        }
        val weightSum = lineWeights.sum().coerceAtLeast(1.0)

        val resultLines = mutableListOf<LyricLine>()
        var cursorMs = introMs
        textLines.forEachIndexed { idx, lineText ->
            val span = ((lineWeights[idx] / weightSum) * usableMs).roundToLong().coerceAtLeast(900L)
            val endMs = cursorMs + span
            val words = predictWordTimings(
                text = lineText,
                lineStartMs = cursorMs,
                lineEndMs = (endMs - 140L).coerceAtLeast(cursorMs + 500L),
                bpm = bpm,
                beatPeriodMs = beatPeriodMs,
            )
            resultLines += LyricLine(
                index = idx,
                startMs = cursorMs,
                endMs = endMs,
                rawText = lineText,
                words = words,
                isInstrumental = false,
            )
            cursorMs = endMs
        }

        return SynthesizedLyricsResult(resultLines, TimingSource.PLAIN_ESTIMATED, 0.62f)
    }

    fun refineWithLiveBpm(lines: List<LyricLine>, liveBpm: Float): List<LyricLine> {
        val beatPeriodMs = resolveBeatPeriodMs(liveBpm) ?: return lines
        return lines.map { line ->
            if (line.isInstrumental || line.rawText.isBlank()) {
                line
            } else {
                line.copy(
                    words = predictWordTimings(
                        text = line.rawText,
                        lineStartMs = line.startMs,
                        lineEndMs = line.endMs,
                        bpm = liveBpm,
                        beatPeriodMs = beatPeriodMs,
                    ),
                )
            }
        }
    }

    private fun predictWordTimings(
        text: String,
        lineStartMs: Long,
        lineEndMs: Long,
        bpm: Float?,
        beatPeriodMs: Float?,
    ): List<WordTiming> {
        val tokens = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return emptyList()

        val rawSpanMs = (lineEndMs - lineStartMs).coerceAtLeast(250L)
        val breathGapMs = when {
            rawSpanMs > 4_000L -> 340L
            rawSpanMs > 2_000L -> 200L
            else -> (rawSpanMs * 0.08f).roundToLong()
        }
        val vocalSpanMs = (rawSpanMs - breathGapMs).coerceAtLeast(200L)

        // Run local CadenceNet-Nano word-pacing head on every token
        val neuralOutputs = tokens.mapIndexed { idx, token ->
            scoreWordWithNeuralNet(
                token = token,
                isLineStart = idx == 0,
                isPhraseFinal = idx == tokens.lastIndex,
                bpm = bpm,
            )
        }

        val pauseAfterWeights = tokens.mapIndexed { idx, token ->
            when {
                idx == tokens.lastIndex -> 0f
                token.endsWith(",") || token.endsWith(";") -> 0.42f
                token.endsWith("...") || token.endsWith("—") || token.endsWith("-") -> 0.52f
                token.endsWith("!") || token.endsWith("?") || token.endsWith(".") -> 0.48f
                else -> 0.04f
            }
        }

        val totalUnits = (neuralOutputs.sumOf { it.durationWeight.toDouble() }.toFloat() + pauseAfterWeights.sum())
            .coerceAtLeast(0.5f)
        val msPerUnit = vocalSpanMs / totalUnits
        val minWordMs = min(115L, vocalSpanMs / max(1, tokens.size))

        val initial = mutableListOf<WordTiming>()
        var cursor = lineStartMs.toFloat()

        for (i in tokens.indices) {
            val prediction = neuralOutputs[i]
            val wordDur = max(minWordMs.toFloat(), prediction.durationWeight * msPerUnit)
            val start = cursor.roundToLong()
            val end = (cursor + wordDur).roundToLong().coerceAtMost(lineStartMs + vocalSpanMs)

            initial += WordTiming(
                text = tokens[i],
                startMs = start,
                endMs = max(start + 70L, end),
                stressWeight = prediction.beatAttraction,
                isBeatAligned = false,
            )
            cursor += wordDur + (pauseAfterWeights[i] * msPerUnit)
        }

        return if (beatPeriodMs != null && initial.size >= 2) {
            applySoftBeatGridPull(initial, lineStartMs, lineStartMs + vocalSpanMs, beatPeriodMs)
        } else {
            initial
        }
    }

    private fun scoreWordWithNeuralNet(
        token: String,
        isLineStart: Boolean,
        isPhraseFinal: Boolean,
        bpm: Float?,
    ): WordNeuralOutput {
        val letters = token.lowercase().filter { it.isLetter() }
        val len = letters.length.coerceAtLeast(1)
        val syllables = if (letters.isEmpty()) 1 else estimateSyllables(letters)
        val vowelCount = letters.count { it in vowelSet }
        val sustainCount = letters.count { it in sustainedConsonants }
        val plosiveCount = letters.count { it in plosiveConsonants }
        val isFunc = if (letters in unstressedLexicon) 1f else 0f
        val hasPause = if (token.endsWith(",") || token.endsWith(".") || token.endsWith("!") || token.endsWith("?")) 1f else 0f
        val normTempo = ((bpm ?: 110f) / 140f).coerceIn(0.4f, 1.5f)

        val features = WordFeatureVector(
            normSyllables = (syllables / 3f).coerceIn(0.2f, 2.2f),
            vowelRatio = vowelCount.toFloat() / len.toFloat(),
            sustainRatio = sustainCount.toFloat() / len.toFloat(),
            plosiveRatio = plosiveCount.toFloat() / len.toFloat(),
            normLength = (len / 7f).coerceIn(0.15f, 2.0f),
            isFunctionWord = isFunc,
            isLineStart = if (isLineStart) 1f else 0f,
            isPhraseFinal = if (isPhraseFinal) 1f else 0f,
            hasPausePunctuation = hasPause,
            normTempo = normTempo,
        )
        return neuralNet.inferWordPacing(features)
    }

    private fun applySoftBeatGridPull(
        words: List<WordTiming>,
        lineStartMs: Long,
        lineVocalEndMs: Long,
        beatPeriodMs: Float,
    ): List<WordTiming> {
        val eighthNoteMs = beatPeriodMs / 2f
        val adjusted = words.toMutableList()

        for (i in 1 until adjusted.size) {
            val current = adjusted[i]
            if (current.stressWeight < 0.45f) continue

            val relative = (current.startMs - lineStartMs).toFloat()
            val nearestSubdivision = (relative / eighthNoteMs).roundToLong() * eighthNoteMs
            val delta = nearestSubdivision - relative

            if (abs(delta) <= eighthNoteMs * 0.30f) {
                val pullFactor = (0.28f + current.stressWeight * 0.32f).coerceIn(0.25f, 0.58f)
                val pulledStart = (current.startMs + (delta * pullFactor)).roundToLong()
                val prev = adjusted[i - 1]
                val safeMin = prev.startMs + 90L
                val safeMax = current.endMs - 90L
                if (pulledStart in safeMin..safeMax) {
                    adjusted[i - 1] = prev.copy(endMs = min(prev.endMs, pulledStart))
                    adjusted[i] = current.copy(startMs = pulledStart, isBeatAligned = true)
                }
            }
        }

        for (i in adjusted.indices) {
            val nextStart = if (i + 1 < adjusted.size) adjusted[i + 1].startMs else lineVocalEndMs
            val w = adjusted[i]
            val clampedEnd = w.endMs.coerceIn(w.startMs + 65L, max(w.startMs + 65L, nextStart))
            adjusted[i] = w.copy(endMs = clampedEnd)
        }
        return adjusted
    }

    private fun estimateSyllables(word: String): Int {
        if (word.length <= 2) return 1
        var count = 0
        var prevVowel = false
        for (ch in word) {
            val isVowel = ch in vowelSet
            if (isVowel && !prevVowel) count++
            prevVowel = isVowel
        }
        if (word.endsWith("e") && !word.endsWith("le") && count > 1) count--
        return count.coerceAtLeast(1)
    }

    private fun estimateMaxVocalDuration(
        text: String,
        rawWindowMs: Long,
        beatPeriodMs: Float?,
    ): Long {
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        val totalSyllables = words.sumOf { estimateSyllables(it.lowercase().filter(Char::isLetter)) }
        val msPerSyllable = if (beatPeriodMs != null) {
            (beatPeriodMs * 0.68f).coerceIn(240f, 560f)
        } else {
            390f
        }
        val naturalCap = ((totalSyllables * msPerSyllable) + 1_350f).roundToLong().coerceAtLeast(1_600L)
        return if (rawWindowMs > naturalCap + 3_500L) naturalCap else rawWindowMs
    }

    private fun normalizeEnhancedWords(
        words: List<WordTiming>,
        lineEndMs: Long,
    ): List<WordTiming> {
        if (words.isEmpty()) return emptyList()
        return words.mapIndexed { idx, w ->
            val end = if (idx == words.lastIndex) {
                max(w.startMs + 120L, min(w.endMs, lineEndMs))
            } else {
                max(w.startMs + 80L, min(w.endMs, words[idx + 1].startMs))
            }
            w.copy(endMs = end)
        }
    }

    private fun resolveBeatPeriodMs(bpm: Float?): Float? {
        if (bpm == null || bpm < 45f || bpm > 220f) return null
        return 60_000f / bpm
    }
}
