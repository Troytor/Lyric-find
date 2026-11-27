package com.example

import com.example.engine.LrcParser
import com.example.engine.WordPacingMlEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WordSyncMultilingualTest {

    private val engine = WordPacingMlEngine()

    @Test
    fun japaneseUnspacedLineProducesMultiTokenSyncWithoutTruncation() {
        val lrc = """
            [00:10.00]君の知らない物語
            [00:15.00]夜に駆けるよ
        """.trimIndent()

        val result = engine.synthesizeFromSyncedLrc(lrc, trackDurationMs = 180_000L, bpm = 128f)
        val firstVocal = result.lines.first { !it.isInstrumental }

        assertTrue("Expected multiple Japanese mora/character sync tokens", firstVocal.words.size >= 6)
        assertTrue("Japanese line should not be truncated early", firstVocal.endMs >= 14_500L)
        assertFalse("Unspaced Japanese tokens should not inject artificial spaces", firstVocal.words.first().hasTrailingSpace)
        assertMonotonicTimings(firstVocal.words.map { it.startMs to it.endMs })
    }

    @Test
    fun chineseAndThaiUnspacedLinesSegmentIntoSyllableTokens() {
        val lrc = """
            [00:05.00]你问我爱你有多深，我爱你有几分。
            [00:11.00]เธอคือความรักที่ฉันตามหา
        """.trimIndent()

        val result = engine.synthesizeFromSyncedLrc(lrc, trackDurationMs = 180_000L, bpm = 100f)
        val vocalLines = result.lines.filter { !it.isInstrumental }
        assertEquals(2, vocalLines.size)

        val chinese = vocalLines[0]
        assertTrue("Chinese line should split into per-character tokens", chinese.words.size >= 12)
        assertFalse("Chinese tokens should not add spaces", chinese.words.first().hasTrailingSpace)
        assertMonotonicTimings(chinese.words.map { it.startMs to it.endMs })

        val thai = vocalLines[1]
        assertTrue("Thai line should split into syllable clusters", thai.words.size >= 5)
        assertFalse("Thai clusters should not add spaces", thai.words.first().hasTrailingSpace)
        assertMonotonicTimings(thai.words.map { it.startMs to it.endMs })
    }

    @Test
    fun koreanArabicHindiRussianSpanishPreserveWordsAndSyllables() {
        val lrc = """
            [00:02.00]보고 싶다 이렇게 말하니까 더 보고 싶다
            [00:07.00]حبيبي يا نور العين يا ساكن خيالي
            [00:12.00]तुम ही हो अब तुम ही हो ज़िंदगी अब तुम ही हो
            [00:17.00]Миллион алых роз из окна видишь ты
            [00:22.00]Despacito quiero respirar tu cuello despacito
        """.trimIndent()

        val result = engine.synthesizeFromSyncedLrc(lrc, trackDurationMs = 200_000L, bpm = 110f)
        val vocalLines = result.lines.filter { !it.isInstrumental }
        assertEquals(5, vocalLines.size)

        vocalLines.forEach { line ->
            assertTrue("Line '${line.rawText}' should have multiple word tokens", line.words.size >= 5)
            assertTrue("Space-delimited words should preserve trailing space", line.words.first().hasTrailingSpace)
            assertFalse("Last word in line should not have trailing space", line.words.last().hasTrailingSpace)
            assertMonotonicTimings(line.words.map { it.startMs to it.endMs })
        }
    }

    @Test
    fun enhancedLrcPreservesPrefixTextClosingTimestampAndFullWidthBrackets() {
        val lrc = """
            ［00:10.00］Hello <00:10.50>world <00:11.20>
        """.trimIndent()

        val parsed = LrcParser.parse(lrc)
        assertTrue(parsed.hasEnhancedWordTags)
        assertEquals(1, parsed.lines.size)

        val words = parsed.lines.first().enhancedWords
        assertEquals(2, words.size)
        assertEquals("Hello", words[0].text)
        assertEquals(10_000L, words[0].startMs)
        assertEquals(10_500L, words[0].endMs)
        assertEquals("world", words[1].text)
        assertEquals(10_500L, words[1].startMs)
        assertEquals(11_200L, words[1].endMs)
    }

    private fun assertMonotonicTimings(spans: List<Pair<Long, Long>>) {
        for (i in spans.indices) {
            val (start, end) = spans[i]
            assertTrue("Word $i end ($end) must be > start ($start)", end > start)
            if (i > 0) {
                assertTrue(
                    "Word $i start ($start) must be >= previous start (${spans[i - 1].first})",
                    start >= spans[i - 1].first,
                )
            }
        }
    }
}
