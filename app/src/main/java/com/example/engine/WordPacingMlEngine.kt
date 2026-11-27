package com.example.engine

import com.example.model.LyricLine
import com.example.model.TimingSource
import com.example.model.WordTiming
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

data class SynthesizedLyricsResult(
    val lines: List<LyricLine>,
    val timingSource: TimingSource,
    val confidence: Float,
)

private data class SyncToken(
    val text: String,
    val hasTrailingSpace: Boolean,
)

/**
 * Deterministic universal-language prosodic & musical beat-grid word-pacing engine.
 * Supports Latin & accented scripts (English, Spanish, French, Portuguese, German, Italian,
 * Turkish, Vietnamese, Indonesian, Tagalog), Japanese (Kanji/Hiragana/Katakana), Chinese (Hanzi),
 * Korean (Hangul), Thai/Lao/Khmer/Burmese, Arabic/Hebrew (RTL), Hindi/Devanagari & Indic scripts,
 * Cyrillic (Russian/Ukrainian), and Greek.
 */
class WordPacingMlEngine {

    private val unstressedLexicon = setOf(
        // English
        "a", "an", "the", "to", "of", "in", "on", "at", "by", "for", "and", "or",
        "but", "is", "are", "was", "were", "be", "it", "its", "my", "your", "our",
        "me", "us", "as", "if", "so", "do", "did", "with", "from", "that", "this",
        // Spanish / Portuguese / French / Italian / German common particles
        "el", "la", "los", "las", "de", "del", "en", "y", "que", "un", "una", "por", "con",
        "le", "les", "du", "des", "et", "au", "aux", "il", "di", "da", "der", "die", "das", "und",
    )

    private val latinVowelSet = setOf('a', 'e', 'i', 'o', 'u', 'y', 'ı', 'ø', 'æ', 'œ')
    private val cyrillicVowelSet = setOf('а', 'е', 'ё', 'и', 'о', 'у', 'ы', 'э', 'ю', 'я', 'і', 'ї', 'є')
    private val greekVowelSet = setOf('α', 'ε', 'η', 'ι', 'ο', 'υ', 'ω')
    private val sustainedConsonants = setOf('m', 'n', 'l', 'r', 's', 'z', 'f', 'v', 'h', 'ñ')

    // Small kana / prolonged sound mark attach to preceding Japanese character
    private val japaneseCombiningChars = setOf(
        'ぁ', 'ぃ', 'ぅ', 'ぇ', 'ぉ', 'っ', 'ゃ', 'ゅ', 'ょ', 'ゎ', 'ん',
        'ァ', 'ィ', 'ゥ', 'ェ', 'ォ', 'ッ', 'ャ', 'ュ', 'ョ', 'ヮ', 'ン', 'ー',
    )

    private val trailingPunctuationChars = setOf(
        ',', '.', '!', '?', ';', ':', ')', ']', '}', '"', '\'', '’', '”', '…',
        '，', '。', '！', '？', '、', '；', '：', '」', '』', '）', '》', '】', '～', '~',
        '،', '؛', '؟', '।',
    )

    private val leadingPunctuationChars = setOf(
        '(', '[', '{', '"', '\'', '‘', '“', '¡', '¿',
        '「', '『', '（', '《', '【',
    )

    fun synthesizeFromSyncedLrc(
        rawLrc: String,
        trackDurationMs: Long,
        bpm: Float?,
    ): SynthesizedLyricsResult {
        val parsed = LrcParser.parse(rawLrc)
        val rawLines = parsed.lines
        if (rawLines.isEmpty()) {
            return SynthesizedLyricsResult(emptyList(), TimingSource.PROSODIC_BEAT_ENGINE, 0f)
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
            val rawWindowMs = (nextStart - current.startMs).coerceAtLeast(450L)

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
            TimingSource.PROSODIC_BEAT_ENGINE
        }
        val confidence = when {
            parsed.hasEnhancedWordTags -> 0.98f
            bpm != null && bpm > 40f -> 0.92f
            else -> 0.86f
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
            val tokens = tokenizeUniversal(line)
            tokens.mapIndexed { idx, token ->
                computeWordProsodicWeight(token.text, idx == tokens.lastIndex).toDouble()
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
                        beatPeriodMs = beatPeriodMs,
                    ),
                )
            }
        }
    }

    internal fun predictWordTimings(
        text: String,
        lineStartMs: Long,
        lineEndMs: Long,
        beatPeriodMs: Float?,
    ): List<WordTiming> {
        val tokens = tokenizeUniversal(text)
        if (tokens.isEmpty()) return emptyList()

        val rawSpanMs = (lineEndMs - lineStartMs).coerceAtLeast(250L)
        val breathGapMs = when {
            rawSpanMs > 4_000L -> 280L
            rawSpanMs > 2_000L -> 160L
            else -> (rawSpanMs * 0.06f).roundToLong()
        }
        val vocalSpanMs = (rawSpanMs - breathGapMs).coerceAtLeast(200L)

        val weights = tokens.mapIndexed { idx, token ->
            computeWordProsodicWeight(token.text, isPhraseFinal = idx == tokens.lastIndex)
        }
        val pauseAfterWeights = tokens.mapIndexed { idx, token ->
            val t = token.text
            when {
                idx == tokens.lastIndex -> 0f
                t.endsWith(",") || t.endsWith(";") || t.endsWith("，") || t.endsWith("、") || t.endsWith("،") -> 0.40f
                t.endsWith("...") || t.endsWith("…") || t.endsWith("—") || t.endsWith("-") -> 0.48f
                t.endsWith("!") || t.endsWith("?") || t.endsWith(".") ||
                    t.endsWith("！") || t.endsWith("？") || t.endsWith("。") || t.endsWith("؟") -> 0.45f
                token.hasTrailingSpace -> 0.04f
                else -> 0.0f
            }
        }

        val totalUnits = (weights.sum() + pauseAfterWeights.sum()).coerceAtLeast(0.5f)
        val msPerUnit = vocalSpanMs / totalUnits
        val minWordMs = min(90L, vocalSpanMs / max(1, tokens.size)).coerceAtLeast(45L)

        val initial = mutableListOf<WordTiming>()
        var cursor = lineStartMs.toFloat()

        for (i in tokens.indices) {
            val token = tokens[i]
            val wordDur = max(minWordMs.toFloat(), weights[i] * msPerUnit)
            val start = cursor.roundToLong()
            val end = if (i == tokens.lastIndex) {
                (lineStartMs + vocalSpanMs).coerceAtLeast(start + 60L)
            } else {
                (cursor + wordDur).roundToLong().coerceAtMost(lineStartMs + vocalSpanMs)
            }
            val clean = stripDiacritics(token.text.lowercase()).filter { it.isLetter() }
            val stress = if (clean in unstressedLexicon) 0.45f else 1.0f

            initial += WordTiming(
                text = token.text,
                startMs = start,
                endMs = max(start + 55L, end),
                stressWeight = stress,
                isBeatAligned = false,
                hasTrailingSpace = if (i == tokens.lastIndex) false else token.hasTrailingSpace,
            )
            cursor += wordDur + (pauseAfterWeights[i] * msPerUnit)
        }

        // Only apply soft beat-grid pull when average token duration is wide enough (not dense CJK char stream)
        val avgTokenMs = vocalSpanMs / max(1, initial.size)
        return if (beatPeriodMs != null && initial.size >= 2 && avgTokenMs >= 240L) {
            applySoftBeatGridPull(initial, lineStartMs, lineStartMs + vocalSpanMs, beatPeriodMs)
        } else {
            initial
        }
    }

    /**
     * Universal script-aware tokenizer:
     * - Space-delimited languages (Latin, Cyrillic, Arabic, Hebrew, Hindi/Indic, Korean, Greek)
     *   preserve natural words with `hasTrailingSpace = true`.
     * - Unspaced scripts (Japanese, Chinese, Thai, Lao, Khmer, Burmese) segment into
     *   accurate sung syllable/mora clusters with `hasTrailingSpace = false` (except where spaces exist).
     * - Mixed-script lines (e.g., J-Pop / K-Pop mixing English + Japanese/Korean) handle each segment natively!
     */
    private fun tokenizeUniversal(text: String): List<SyncToken> {
        val rawWhitespaceTokens = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (rawWhitespaceTokens.isEmpty()) return emptyList()

        val result = mutableListOf<SyncToken>()
        rawWhitespaceTokens.forEachIndexed { segIdx, segment ->
            val isLastSegment = segIdx == rawWhitespaceTokens.lastIndex
            val subTokens = segmentSingleChunk(segment)
            subTokens.forEachIndexed { subIdx, subText ->
                val isLastSub = subIdx == subTokens.lastIndex
                result += SyncToken(
                    text = subText,
                    hasTrailingSpace = isLastSub && !isLastSegment,
                )
            }
        }
        return result
    }

    private fun segmentSingleChunk(segment: String): List<String> {
        if (segment.isEmpty()) return emptyList()

        val hasCjkOrKana = segment.any { isCjkIdeograph(it) || isHiragana(it) || isKatakana(it) }
        if (hasCjkOrKana) {
            return segmentCjkAndMixed(segment)
        }

        val hasThai = segment.any { isThaiChar(it) }
        if (hasThai) {
            return segmentThaiCluster(segment)
        }

        // Long unspaced Hangul strings (6+ syllables without spaces) split into 2-syllable rhythm beats
        val hangulCount = segment.count { isHangulSyllable(it) }
        if (hangulCount >= 6 && hangulCount == segment.count { it.isLetter() }) {
            return segmentUnspacedHangul(segment)
        }

        return listOf(segment)
    }

    /**
     * Segments Japanese / Chinese / mixed CJK+Latin chunks cleanly without splitting English words
     * or detaching small kana (ゃゅょっー) or punctuation (、。！？) from their host character.
     */
    private fun segmentCjkAndMixed(segment: String): List<String> {
        val tokens = mutableListOf<String>()
        var i = 0
        val n = segment.length
        var pendingPrefix = StringBuilder()

        while (i < n) {
            val ch = segment[i]

            if (ch in leadingPunctuationChars) {
                pendingPrefix.append(ch)
                i++
                continue
            }

            if (ch in trailingPunctuationChars) {
                if (tokens.isNotEmpty()) {
                    tokens[tokens.lastIndex] = tokens.last() + ch
                } else {
                    pendingPrefix.append(ch)
                }
                i++
                continue
            }

            if (isCjkIdeograph(ch) || isHiragana(ch) || isKatakana(ch)) {
                val unit = StringBuilder()
                if (pendingPrefix.isNotEmpty()) {
                    unit.append(pendingPrefix)
                    pendingPrefix = StringBuilder()
                }
                unit.append(ch)
                i++

                // Attach trailing Japanese combining mora / prolonged vowel mark (っ, ゃ, ゅ, ょ, ん, ー)
                while (i < n && segment[i] in japaneseCombiningChars) {
                    unit.append(segment[i])
                    i++
                }
                // Attach trailing CJK/ASCII punctuation to this syllable unit
                while (i < n && segment[i] in trailingPunctuationChars) {
                    unit.append(segment[i])
                    i++
                }
                tokens += unit.toString()
            } else {
                // Contiguous non-CJK run inside the same whitespace chunk (e.g., "LOVE" in "君をLOVE")
                val run = StringBuilder()
                if (pendingPrefix.isNotEmpty()) {
                    run.append(pendingPrefix)
                    pendingPrefix = StringBuilder()
                }
                while (i < n &&
                    !isCjkIdeograph(segment[i]) &&
                    !isHiragana(segment[i]) &&
                    !isKatakana(segment[i]) &&
                    segment[i] !in leadingPunctuationChars
                ) {
                    run.append(segment[i])
                    i++
                }
                tokens += run.toString()
            }
        }

        if (pendingPrefix.isNotEmpty()) {
            if (tokens.isNotEmpty()) {
                tokens[tokens.lastIndex] = tokens.last() + pendingPrefix.toString()
            } else {
                tokens += pendingPrefix.toString()
            }
        }
        return tokens.filter { it.isNotEmpty() }
    }

    /**
     * Segments Thai into orthographic syllable clusters:
     * optional leading vowel (เ แ โ ใ ไ) + base consonant + any above/below vowels & tone marks.
     */
    private fun segmentThaiCluster(segment: String): List<String> {
        val tokens = mutableListOf<String>()
        var i = 0
        val n = segment.length
        while (i < n) {
            val ch = segment[i]
            if (!isThaiChar(ch)) {
                val run = StringBuilder()
                while (i < n && !isThaiChar(segment[i])) {
                    run.append(segment[i])
                    i++
                }
                tokens += run.toString()
                continue
            }

            val cluster = StringBuilder()
            // Leading vowel attaches to the following consonant
            if (ch.code in 0x0E40..0x0E44) {
                cluster.append(ch)
                i++
                if (i < n && isThaiConsonant(segment[i])) {
                    cluster.append(segment[i])
                    i++
                }
            } else {
                cluster.append(ch)
                i++
            }
            // Attach combining marks (tone marks, upper/lower vowels) and trailing sara a/aa
            while (i < n && isThaiCombiningMark(segment[i])) {
                cluster.append(segment[i])
                i++
            }
            if (i < n && (segment[i] == '\u0E30' || segment[i] == '\u0E32' || segment[i] == '\u0E33')) {
                cluster.append(segment[i])
                i++
            }
            while (i < n && segment[i] in trailingPunctuationChars) {
                cluster.append(segment[i])
                i++
            }
            tokens += cluster.toString()
        }
        return tokens.filter { it.isNotEmpty() }
    }

    private fun segmentUnspacedHangul(segment: String): List<String> {
        val tokens = mutableListOf<String>()
        var i = 0
        while (i < segment.length) {
            val end = min(segment.length, i + 2)
            tokens += segment.substring(i, end)
            i = end
        }
        return tokens
    }

    private fun computeWordProsodicWeight(token: String, isPhraseFinal: Boolean): Float {
        val letters = token.filter { it.isLetter() || isThaiCombiningMark(it) || it == 'ー' }
        if (letters.isEmpty()) return 0.60f

        val syllables = estimateSyllables(token)
        val normalizedLatin = stripDiacritics(token.lowercase()).filter { it in 'a'..'z' }
        val consonantWeight = if (normalizedLatin.isNotEmpty()) {
            normalizedLatin.count { it !in latinVowelSet } * 0.055f
        } else {
            0.08f
        }
        val sustainBonus = if (normalizedLatin.isNotEmpty()) {
            normalizedLatin.count { it in sustainedConsonants } * 0.085f
        } else if (token.contains('ー') || token.contains('ん') || token.contains('ン')) {
            0.22f
        } else {
            0f
        }

        val base = (syllables * 0.88f) + consonantWeight + sustainBonus
        val functionFactor = if (normalizedLatin in unstressedLexicon && !isPhraseFinal) 0.72f else 1.0f
        val phraseFinalFactor = if (isPhraseFinal) 1.28f else 1.0f
        return (base * functionFactor * phraseFinalFactor).coerceIn(0.42f, 5.5f)
    }

    internal fun estimateSyllables(rawToken: String): Int {
        if (rawToken.isBlank()) return 1

        var cjkAndHangulSyllables = 0
        var thaiSyllables = 0
        var devanagariSyllables = 0
        var arabicHebrewLetters = 0
        var cyrillicVowels = 0
        var greekVowels = 0
        var hasCyrillic = false
        var hasGreek = false
        var prevCyrillicVowel = false
        var prevGreekVowel = false

        val latinBuf = StringBuilder()

        for (idx in rawToken.indices) {
            val ch = rawToken[idx]
            val cp = ch.code
            when {
                isHangulSyllable(ch) -> cjkAndHangulSyllables++
                isCjkIdeograph(ch) -> cjkAndHangulSyllables++
                isHiragana(ch) || isKatakana(ch) -> {
                    // Small palatalizing kana (ゃゅょ) share a mora; っ/ん/ー count slightly
                    if (ch !in setOf('ぁ', 'ぃ', 'ぅ', 'ぇ', 'ぉ', 'ゃ', 'ゅ', 'ょ', 'ゎ',
                            'ァ', 'ィ', 'ゥ', 'ェ', 'ォ', 'ャ', 'ュ', 'ョ', 'ヮ')
                    ) {
                        cjkAndHangulSyllables++
                    }
                }
                isThaiConsonant(ch) -> thaiSyllables++
                cp in 0x0904..0x0939 || cp in 0x0958..0x0961 -> {
                    // Devanagari vowel or consonant (skip consonant muted by trailing virama 0x094D)
                    val nextIsVirama = idx + 1 < rawToken.length && rawToken[idx + 1].code == 0x094D
                    if (!nextIsVirama) devanagariSyllables++
                }
                cp in 0x0621..0x064A || cp in 0x0671..0x06D3 || cp in 0x05D0..0x05EA -> {
                    arabicHebrewLetters++
                }
                cp in 0x0400..0x04FF -> {
                    hasCyrillic = true
                    val isV = ch.lowercaseChar() in cyrillicVowelSet
                    if (isV && !prevCyrillicVowel) cyrillicVowels++
                    prevCyrillicVowel = isV
                }
                cp in 0x0370..0x03FF -> {
                    hasGreek = true
                    val baseCh = stripDiacritics(ch.lowercase()).firstOrNull() ?: ch.lowercaseChar()
                    val isV = baseCh in greekVowelSet
                    if (isV && !prevGreekVowel) greekVowels++
                    prevGreekVowel = isV
                }
                ch.isLetter() -> latinBuf.append(ch)
            }
        }

        var total = cjkAndHangulSyllables
        if (thaiSyllables > 0) total += max(1, (thaiSyllables + 1) / 2)
        if (devanagariSyllables > 0) total += devanagariSyllables
        if (arabicHebrewLetters > 0) total += max(1, (arabicHebrewLetters + 1) / 2)
        if (hasCyrillic) total += cyrillicVowels.coerceAtLeast(1)
        if (hasGreek) total += greekVowels.coerceAtLeast(1)

        if (latinBuf.isNotEmpty()) {
            total += estimateLatinSyllables(latinBuf.toString())
        }

        return total.coerceAtLeast(1)
    }

    private fun estimateLatinSyllables(rawWord: String): Int {
        val word = stripDiacritics(rawWord.lowercase()).filter { it.isLetter() }
        if (word.length <= 2) return 1
        var count = 0
        var prevVowel = false
        for (ch in word) {
            val isVowel = ch in latinVowelSet
            if (isVowel && !prevVowel) count++
            prevVowel = isVowel
        }
        // Only apply English silent-e rule to purely ASCII words (avoid breaking Spanish/French/Italian/German)
        val isPureAscii = rawWord.all { it.code < 128 }
        if (isPureAscii && word.endsWith("e") && !word.endsWith("le") && count > 1) {
            count--
        }
        return count.coerceAtLeast(1)
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
            if (current.stressWeight < 0.7f) continue

            val relative = (current.startMs - lineStartMs).toFloat()
            val nearestSubdivision = (relative / eighthNoteMs).roundToLong() * eighthNoteMs
            val delta = nearestSubdivision - relative

            if (abs(delta) <= eighthNoteMs * 0.30f) {
                val pulledStart = (current.startMs + (delta * 0.45f)).roundToLong()
                val prev = adjusted[i - 1]
                val safeMin = prev.startMs + 85L
                val safeMax = current.endMs - 85L
                if (pulledStart in safeMin..safeMax) {
                    adjusted[i - 1] = prev.copy(endMs = min(prev.endMs, pulledStart))
                    adjusted[i] = current.copy(startMs = pulledStart, isBeatAligned = true)
                }
            }
        }

        for (i in adjusted.indices) {
            val nextStart = if (i + 1 < adjusted.size) adjusted[i + 1].startMs else lineVocalEndMs
            val w = adjusted[i]
            val clampedEnd = w.endMs.coerceIn(w.startMs + 55L, max(w.startMs + 55L, nextStart))
            adjusted[i] = w.copy(endMs = clampedEnd)
        }
        return adjusted
    }

    private fun estimateMaxVocalDuration(
        text: String,
        rawWindowMs: Long,
        beatPeriodMs: Float?,
    ): Long {
        val tokens = tokenizeUniversal(text)
        val totalSyllables = tokens.sumOf { estimateSyllables(it.text) }.coerceAtLeast(1)
        val msPerSyllable = if (beatPeriodMs != null) {
            (beatPeriodMs * 0.72f).coerceIn(260f, 580f)
        } else {
            420f
        }
        val naturalCap = ((totalSyllables * msPerSyllable) + 1_650f).roundToLong().coerceAtLeast(2_000L)
        return if (rawWindowMs > naturalCap + 4_200L) naturalCap else rawWindowMs
    }

    private fun normalizeEnhancedWords(
        words: List<WordTiming>,
        lineEndMs: Long,
    ): List<WordTiming> {
        if (words.isEmpty()) return emptyList()
        return words.mapIndexed { idx, w ->
            val end = if (idx == words.lastIndex) {
                max(w.startMs + 100L, min(w.endMs, lineEndMs))
            } else {
                max(w.startMs + 60L, min(w.endMs, words[idx + 1].startMs))
            }
            w.copy(endMs = end)
        }
    }

    private fun resolveBeatPeriodMs(bpm: Float?): Float? {
        if (bpm == null || bpm < 45f || bpm > 220f) return null
        return 60_000f / bpm
    }

    private fun stripDiacritics(input: String): String {
        val decomposed = Normalizer.normalize(input, Normalizer.Form.NFD)
        val out = StringBuilder(decomposed.length)
        for (ch in decomposed) {
            if (Character.getType(ch) != Character.NON_SPACING_MARK.toInt()) {
                out.append(ch)
            }
        }
        return out.toString()
    }

    private fun isCjkIdeograph(ch: Char): Boolean {
        val cp = ch.code
        return cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF || cp in 0xF900..0xFAFF
    }

    private fun isHiragana(ch: Char): Boolean = ch.code in 0x3040..0x309F
    private fun isKatakana(ch: Char): Boolean = ch.code in 0x30A0..0x30FF || ch == 'ー'
    private fun isHangulSyllable(ch: Char): Boolean = ch.code in 0xAC00..0xD7AF
    private fun isThaiChar(ch: Char): Boolean = ch.code in 0x0E00..0x0E7F
    private fun isThaiConsonant(ch: Char): Boolean = ch.code in 0x0E01..0x0E2E
    private fun isThaiCombiningMark(ch: Char): Boolean {
        val cp = ch.code
        return cp == 0x0E31 || cp in 0x0E34..0x0E3A || cp in 0x0E47..0x0E4E
    }
}
