package com.example

import com.example.engine.AcousticFeatureVector
import com.example.engine.LocalCadenceNeuralNet
import com.example.engine.WordFeatureVector
import com.example.engine.WordPacingMlEngine
import com.example.model.TimingSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CadenceLocalAiUnitTest {

    private val neuralNet = LocalCadenceNeuralNet()
    private val pacingEngine = WordPacingMlEngine(neuralNet)

    @Test
    fun acousticHeadDistinguishesVocalsKickAndSilence() {
        val vocalFrame = neuralNet.inferAcousticFrame(
            AcousticFeatureVector(
                bandSubBass = 0.12f,
                bandBass = 0.18f,
                bandLowMid = 0.45f,
                bandVocalLow = 0.85f,
                bandVocalMid = 0.92f,
                bandVocalHigh = 0.78f,
                bandPresence = 0.40f,
                bandBrilliance = 0.15f,
                spectralCentroid = 0.52f,
                spectralFlatness = 0.18f,
                zeroCrossingRate = 0.25f,
                bassOnsetFlux = 0.08f,
            ),
        )
        val kickFrame = neuralNet.inferAcousticFrame(
            AcousticFeatureVector(
                bandSubBass = 0.95f,
                bandBass = 0.90f,
                bandLowMid = 0.35f,
                bandVocalLow = 0.12f,
                bandVocalMid = 0.10f,
                bandVocalHigh = 0.08f,
                bandPresence = 0.12f,
                bandBrilliance = 0.10f,
                spectralCentroid = 0.18f,
                spectralFlatness = 0.35f,
                zeroCrossingRate = 0.12f,
                bassOnsetFlux = 0.92f,
            ),
        )
        val silenceFrame = neuralNet.inferAcousticFrame(
            AcousticFeatureVector(
                bandSubBass = 0.01f,
                bandBass = 0.01f,
                bandLowMid = 0.02f,
                bandVocalLow = 0.02f,
                bandVocalMid = 0.02f,
                bandVocalHigh = 0.01f,
                bandPresence = 0.01f,
                bandBrilliance = 0.01f,
                spectralCentroid = 0.50f,
                spectralFlatness = 0.90f,
                zeroCrossingRate = 0.65f,
                bassOnsetFlux = 0.00f,
            ),
        )

        assertTrue("Expected vocal prob > 0.65, got ${vocalFrame.vocalProbability}", vocalFrame.vocalProbability > 0.65f)
        assertTrue("Expected beat prob > 0.65, got ${kickFrame.beatOnsetProbability}", kickFrame.beatOnsetProbability > 0.65f)
        assertTrue("Expected silence music prob < 0.30, got ${silenceFrame.musicPresenceProbability}", silenceFrame.musicPresenceProbability < 0.30f)
    }

    @Test
    fun alignmentHeadMatchesPhoneticMishearings() {
        val highMatch = neuralNet.scoreLyricAlignment(
            heardPhrase = "dancing in the moonlight tonight",
            candidateText = "Dancin' in the moonlight tonight",
        )
        val unrelated = neuralNet.scoreLyricAlignment(
            heardPhrase = "dancing in the moonlight tonight",
            candidateText = "morning coffee rain outside the window",
        )
        assertTrue("Expected high match >= 0.70, got $highMatch", highMatch >= 0.70f)
        assertTrue("Expected unrelated match < 0.35, got $unrelated", unrelated < 0.35f)
    }

    @Test
    fun wordPacingHeadStretchesPhraseFinalSustainedWords() {
        val shortFunc = neuralNet.inferWordPacing(
            WordFeatureVector(
                normSyllables = 0.33f,
                vowelRatio = 0.33f,
                sustainRatio = 0.0f,
                plosiveRatio = 0.33f,
                normLength = 0.28f,
                isFunctionWord = 1f,
                isLineStart = 0f,
                isPhraseFinal = 0f,
                hasPausePunctuation = 0f,
                normTempo = 0.85f,
            ),
        )
        val sustainedFinal = neuralNet.inferWordPacing(
            WordFeatureVector(
                normSyllables = 1.0f,
                vowelRatio = 0.50f,
                sustainRatio = 0.50f,
                plosiveRatio = 0.0f,
                normLength = 1.15f,
                isFunctionWord = 0f,
                isLineStart = 0f,
                isPhraseFinal = 1f,
                hasPausePunctuation = 1f,
                normTempo = 0.85f,
            ),
        )
        assertTrue(sustainedFinal.durationWeight > shortFunc.durationWeight * 1.5f)
    }

    @Test
    fun wordPacingEngineProducesMonotonicWordTimings() {
        val sampleLrc = """
            [00:08.00] Hello world under bright neon skies
            [00:12.50] Feeling the rhythm as the melody flies
        """.trimIndent()

        val result = pacingEngine.synthesizeFromSyncedLrc(
            rawLrc = sampleLrc,
            trackDurationMs = 30_000L,
            bpm = 120f,
        )

        assertEquals(TimingSource.LOCAL_NEURAL_CADENCE_ENGINE, result.timingSource)
        val vocalLines = result.lines.filter { !it.isInstrumental }
        assertEquals(2, vocalLines.size)
        for (line in vocalLines) {
            assertTrue(line.words.isNotEmpty())
            for (i in 0 until line.words.size - 1) {
                assertTrue(line.words[i].endMs <= line.words[i + 1].startMs + 5L)
            }
        }
    }
}
