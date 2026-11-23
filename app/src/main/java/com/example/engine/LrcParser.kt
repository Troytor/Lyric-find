package com.example.engine

import com.example.model.WordTiming

data class RawTimedLine(
    val startMs: Long,
    val text: String,
    val enhancedWords: List<WordTiming> = emptyList(),
)

data class ParsedLrcDocument(
    val lines: List<RawTimedLine>,
    val hasEnhancedWordTags: Boolean,
)

object LrcParser {
    private val lineTagPattern = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")
    private val wordTagPattern = Regex("""<(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?>""")
    private val offsetPattern = Regex("""\[offset:\s*(-?\d+)]""", RegexOption.IGNORE_CASE)

    fun parse(rawLrc: String): ParsedLrcDocument {
        val offsetMs = offsetPattern.find(rawLrc)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
        val entries = mutableListOf<RawTimedLine>()
        var foundEnhanced = false

        rawLrc.lineSequence().forEach { rawLine ->
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) return@forEach

            val matches = lineTagPattern.findAll(trimmed).toList()
            if (matches.isEmpty()) return@forEach

            val bodyStart = matches.last().range.last + 1
            val rawBody = trimmed.substring(bodyStart).trim()

            matches.forEach { tag ->
                val timestamp = (parseTimestampMs(
                    minutes = tag.groupValues[1],
                    seconds = tag.groupValues[2],
                    fraction = tag.groupValues[3],
                ) + offsetMs).coerceAtLeast(0L)

                val wordMatches = wordTagPattern.findAll(rawBody).toList()
                if (wordMatches.isNotEmpty()) {
                    foundEnhanced = true
                    val extractedWords = extractEnhancedWords(rawBody, wordMatches, timestamp, offsetMs)
                    val cleanText = rawBody.replace(wordTagPattern, " ").replace(Regex("\\s+"), " ").trim()
                    if (cleanText.isNotEmpty()) {
                        entries += RawTimedLine(
                            startMs = timestamp,
                            text = cleanText,
                            enhancedWords = extractedWords,
                        )
                    }
                } else {
                    entries += RawTimedLine(
                        startMs = timestamp,
                        text = rawBody,
                        enhancedWords = emptyList(),
                    )
                }
            }
        }

        val sorted = entries.sortedBy { it.startMs }
        return ParsedLrcDocument(lines = sorted, hasEnhancedWordTags = foundEnhanced)
    }

    private fun extractEnhancedWords(
        body: String,
        matches: List<MatchResult>,
        lineStartMs: Long,
        offsetMs: Long,
    ): List<WordTiming> {
        val rawPieces = mutableListOf<Pair<Long, String>>()
        for (idx in matches.indices) {
            val current = matches[idx]
            val start = (parseTimestampMs(
                minutes = current.groupValues[1],
                seconds = current.groupValues[2],
                fraction = current.groupValues[3],
            ) + offsetMs).coerceAtLeast(lineStartMs)

            val textStart = current.range.last + 1
            val textEnd = if (idx + 1 < matches.size) matches[idx + 1].range.first else body.length
            val token = body.substring(textStart, textEnd).trim()
            if (token.isNotEmpty()) {
                rawPieces += start to token
            }
        }

        return rawPieces.mapIndexed { idx, (startMs, token) ->
            val endMs = if (idx + 1 < rawPieces.size) {
                rawPieces[idx + 1].first.coerceAtLeast(startMs + 80L)
            } else {
                startMs + 420L
            }
            WordTiming(
                text = token,
                startMs = startMs,
                endMs = endMs,
                stressWeight = 1f,
                isBeatAligned = true,
            )
        }
    }

    private fun parseTimestampMs(minutes: String, seconds: String, fraction: String): Long {
        val min = minutes.toLongOrNull() ?: 0L
        val sec = seconds.toLongOrNull() ?: 0L
        val fracMs = when (fraction.length) {
            0 -> 0L
            1 -> (fraction.toLongOrNull() ?: 0L) * 100L
            2 -> (fraction.toLongOrNull() ?: 0L) * 10L
            else -> fraction.take(3).toLongOrNull() ?: 0L
        }
        return (min * 60_000L) + (sec * 1_000L) + fracMs
    }
}
