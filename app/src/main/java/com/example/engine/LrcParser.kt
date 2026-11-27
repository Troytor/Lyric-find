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
    // Supports both ASCII [mm:ss.xx] and full-width CJK ［mm:ss.xx］ brackets
    private val lineTagPattern = Regex("""[\[［](\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?[\]］]""")
    private val wordTagPattern = Regex("""<(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?>""")
    private val offsetPattern = Regex("""[\[［]offset:\s*(-?\d+)[\]］]""", RegexOption.IGNORE_CASE)

    fun parse(rawLrc: String): ParsedLrcDocument {
        val offsetMs = offsetPattern.find(rawLrc)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
        val entries = mutableListOf<RawTimedLine>()
        var foundEnhanced = false

        rawLrc.lineSequence().forEach { rawLine ->
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) return@forEach

            val matches = lineTagPattern.findAll(trimmed).toList()
            if (matches.isEmpty()) return@forEach

            // Check if timestamps appear inline between lyrics (e.g. [00:10.00]word1 [00:10.50]word2)
            val leadingMatches = mutableListOf<MatchResult>()
            var cursor = 0
            for (m in matches) {
                val between = trimmed.substring(cursor, m.range.first)
                if (between.isBlank()) {
                    leadingMatches += m
                    cursor = m.range.last + 1
                } else {
                    break
                }
            }

            if (leadingMatches.isEmpty()) return@forEach

            // If there are additional bracket tags AFTER lyrics start, treat them as inline word tags
            val inlineBracketMatches = matches.drop(leadingMatches.size)
            val rawBody = trimmed.substring(cursor).trim()

            leadingMatches.forEach { tag ->
                val timestamp = (parseTimestampMs(
                    minutes = tag.groupValues[1],
                    seconds = tag.groupValues[2],
                    fraction = tag.groupValues[3],
                ) + offsetMs).coerceAtLeast(0L)

                val angleWordMatches = wordTagPattern.findAll(rawBody).toList()
                val wordMatches = when {
                    angleWordMatches.isNotEmpty() -> angleWordMatches
                    inlineBracketMatches.isNotEmpty() -> lineTagPattern.findAll(rawBody).toList()
                    else -> emptyList()
                }

                if (wordMatches.isNotEmpty()) {
                    val extractedWords = extractEnhancedWords(
                        body = rawBody,
                        matches = wordMatches,
                        lineStartMs = timestamp,
                        offsetMs = offsetMs,
                    )
                    val cleanText = buildCleanTextFromWords(extractedWords, rawBody)
                    if (cleanText.isNotEmpty()) {
                        if (extractedWords.isNotEmpty()) foundEnhanced = true
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
        data class RawPiece(
            val startMs: Long,
            val text: String,
            val hasTrailingSpace: Boolean,
        )

        val pieces = mutableListOf<RawPiece>()
        var explicitClosingEndMs: Long? = null

        // Preserve any lyric text appearing BEFORE the first inline word tag
        val prefixRaw = body.substring(0, matches.first().range.first)
        val prefixTrimmed = prefixRaw.trim()
        if (prefixTrimmed.isNotEmpty()) {
            pieces += RawPiece(
                startMs = lineStartMs,
                text = prefixTrimmed,
                hasTrailingSpace = prefixRaw.last().isWhitespace(),
            )
        }

        for (idx in matches.indices) {
            val current = matches[idx]
            val start = (parseTimestampMs(
                minutes = current.groupValues[1],
                seconds = current.groupValues[2],
                fraction = current.groupValues[3],
            ) + offsetMs).coerceAtLeast(lineStartMs)

            val textStart = current.range.last + 1
            val textEnd = if (idx + 1 < matches.size) matches[idx + 1].range.first else body.length
            val rawSegment = body.substring(textStart, textEnd)
            val token = rawSegment.trim()

            if (token.isNotEmpty()) {
                val trailingSpace = rawSegment.isNotEmpty() && rawSegment.last().isWhitespace()
                pieces += RawPiece(
                    startMs = start,
                    text = token,
                    hasTrailingSpace = trailingSpace,
                )
            } else if (idx == matches.lastIndex && pieces.isNotEmpty()) {
                // Trailing end-of-line timestamp tag like <00:15.40>
                explicitClosingEndMs = start
            }
        }

        return pieces.mapIndexed { idx, piece ->
            val endMs = when {
                idx + 1 < pieces.size -> pieces[idx + 1].startMs.coerceAtLeast(piece.startMs + 60L)
                explicitClosingEndMs != null -> explicitClosingEndMs.coerceAtLeast(piece.startMs + 80L)
                else -> piece.startMs + 420L
            }
            WordTiming(
                text = piece.text,
                startMs = piece.startMs,
                endMs = endMs,
                stressWeight = 1f,
                isBeatAligned = true,
                hasTrailingSpace = if (idx == pieces.lastIndex) false else piece.hasTrailingSpace,
            )
        }
    }

    private fun buildCleanTextFromWords(words: List<WordTiming>, fallbackRaw: String): String {
        if (words.isEmpty()) {
            return fallbackRaw.replace(wordTagPattern, "")
                .replace(lineTagPattern, "")
                .replace(Regex("\\s+"), " ")
                .trim()
        }
        val sb = StringBuilder()
        words.forEachIndexed { idx, word ->
            sb.append(word.text)
            if (idx < words.lastIndex && word.hasTrailingSpace) {
                sb.append(' ')
            }
        }
        return sb.toString().trim()
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
