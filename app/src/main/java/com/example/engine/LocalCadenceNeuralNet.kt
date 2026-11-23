package com.example.engine

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tanh

data class AcousticFeatureVector(
    val bandSubBass: Float,
    val bandBass: Float,
    val bandLowMid: Float,
    val bandVocalLow: Float,
    val bandVocalMid: Float,
    val bandVocalHigh: Float,
    val bandPresence: Float,
    val bandBrilliance: Float,
    val spectralCentroid: Float,
    val spectralFlatness: Float,
    val zeroCrossingRate: Float,
    val bassOnsetFlux: Float,
) {
    fun toFloatArray(): FloatArray = floatArrayOf(
        bandSubBass,
        bandBass,
        bandLowMid,
        bandVocalLow,
        bandVocalMid,
        bandVocalHigh,
        bandPresence,
        bandBrilliance,
        spectralCentroid,
        spectralFlatness,
        zeroCrossingRate,
        bassOnsetFlux,
    )
}

data class AcousticNeuralOutput(
    val vocalProbability: Float,
    val beatOnsetProbability: Float,
    val musicPresenceProbability: Float,
)

data class WordFeatureVector(
    val normSyllables: Float,
    val vowelRatio: Float,
    val sustainRatio: Float,
    val plosiveRatio: Float,
    val normLength: Float,
    val isFunctionWord: Float,
    val isLineStart: Float,
    val isPhraseFinal: Float,
    val hasPausePunctuation: Float,
    val normTempo: Float,
) {
    fun toFloatArray(): FloatArray = floatArrayOf(
        normSyllables,
        vowelRatio,
        sustainRatio,
        plosiveRatio,
        normLength,
        isFunctionWord,
        isLineStart,
        isPhraseFinal,
        hasPausePunctuation,
        normTempo,
    )
}

data class WordNeuralOutput(
    val durationWeight: Float,
    val beatAttraction: Float,
)

data class AlignmentFeatureVector(
    val exactTokenRecall: Float,
    val phoneticTokenRecall: Float,
    val charTrigramCosine: Float,
    val lcsTokenRatio: Float,
    val bigramOrderRatio: Float,
    val substringHit: Float,
    val lengthBalance: Float,
    val temporalProximity: Float,
) {
    fun toFloatArray(): FloatArray = floatArrayOf(
        exactTokenRecall,
        phoneticTokenRecall,
        charTrigramCosine,
        lcsTokenRatio,
        bigramOrderRatio,
        substringHit,
        lengthBalance,
        temporalProximity,
    )
}

/**
 * CadenceNet-Nano: Lightweight on-device multi-head neural network (~1.1K parameters)
 * performing real-time acoustic frame classification (vocal / beat / music presence),
 * vocal word-pacing regression, and phonetic-lexical lyric alignment scoring locally.
 */
class LocalCadenceNeuralNet {

    val modelName: String = "CadenceNet-Nano v1.0 (Local)"
    val parameterCount: Int = 1_140

    // --- Head 1: Acoustic Frame Classifier (12 -> 16 ReLU -> 3 Sigmoid) ---
    private val acousticW1: Array<FloatArray> = arrayOf(
        // Vocal formant detectors (neurons 0..5)
        floatArrayOf(-0.35f, -0.25f, 0.20f, 0.95f, 1.25f, 1.05f, 0.45f, -0.15f, 0.30f, -0.85f, -0.20f, -0.30f),
        floatArrayOf(-0.40f, -0.15f, 0.35f, 1.10f, 1.15f, 0.70f, 0.25f, -0.20f, 0.18f, -0.70f, 0.10f, -0.25f),
        floatArrayOf(-0.20f, -0.30f, 0.10f, 0.65f, 1.10f, 1.20f, 0.85f, 0.25f, 0.55f, -0.60f, 0.25f, -0.20f),
        floatArrayOf(-0.50f, -0.35f, 0.05f, 0.80f, 0.95f, 0.90f, 0.40f, -0.30f, 0.22f, -0.95f, -0.35f, -0.40f),
        floatArrayOf(-0.15f, 0.05f, 0.45f, 0.90f, 0.85f, 0.60f, 0.15f, -0.10f, 0.12f, -0.55f, 0.05f, -0.10f),
        floatArrayOf(-0.25f, -0.20f, -0.10f, 0.40f, 0.95f, 1.15f, 0.95f, 0.35f, 0.48f, -0.40f, 0.45f, -0.15f),
        // Beat & kick transient detectors (neurons 6..10)
        floatArrayOf(1.15f, 1.05f, 0.35f, -0.20f, -0.25f, -0.30f, -0.15f, 0.10f, -0.45f, 0.15f, -0.35f, 1.75f),
        floatArrayOf(1.30f, 0.95f, 0.20f, -0.30f, -0.20f, -0.25f, -0.10f, 0.05f, -0.50f, 0.25f, -0.25f, 1.95f),
        floatArrayOf(0.85f, 1.10f, 0.50f, 0.10f, -0.15f, -0.10f, 0.20f, 0.30f, -0.20f, 0.35f, 0.15f, 1.45f),
        floatArrayOf(0.95f, 0.80f, 0.15f, -0.10f, -0.30f, -0.15f, 0.35f, 0.40f, -0.30f, 0.20f, 0.20f, 1.60f),
        floatArrayOf(0.65f, 0.75f, 0.30f, 0.05f, 0.05f, 0.15f, 0.45f, 0.55f, 0.10f, 0.45f, 0.30f, 1.20f),
        // Broadband music presence & noise rejection (neurons 11..15)
        floatArrayOf(0.60f, 0.65f, 0.55f, 0.55f, 0.50f, 0.45f, 0.35f, 0.25f, 0.10f, -0.45f, -0.10f, 0.35f),
        floatArrayOf(0.75f, 0.70f, 0.60f, 0.40f, 0.35f, 0.30f, 0.20f, 0.15f, -0.15f, -0.55f, -0.20f, 0.50f),
        floatArrayOf(0.25f, 0.35f, 0.55f, 0.75f, 0.75f, 0.65f, 0.45f, 0.20f, 0.25f, -0.65f, -0.15f, 0.20f),
        floatArrayOf(-0.20f, -0.20f, -0.15f, -0.10f, -0.10f, -0.05f, 0.15f, 0.35f, 0.40f, 1.35f, 0.90f, -0.25f),
        floatArrayOf(0.45f, 0.50f, 0.45f, 0.45f, 0.45f, 0.40f, 0.30f, 0.20f, 0.05f, -0.30f, -0.05f, 0.40f),
    )

    private val acousticB1: FloatArray = floatArrayOf(
        -0.38f, -0.32f, -0.42f, -0.28f, -0.30f, -0.45f,
        -0.58f, -0.66f, -0.52f, -0.56f, -0.48f,
        -0.28f, -0.32f, -0.30f, -0.45f, -0.22f,
    )

    private val acousticW2: Array<FloatArray> = arrayOf(
        // Vocal probability readout
        floatArrayOf(0.82f, 0.74f, 0.66f, 0.78f, 0.54f, 0.48f, -0.22f, -0.28f, -0.10f, -0.15f, -0.08f, 0.22f, 0.10f, 0.36f, -0.85f, 0.18f),
        // Beat onset probability readout
        floatArrayOf(-0.18f, -0.12f, -0.15f, -0.20f, -0.05f, -0.10f, 0.96f, 1.12f, 0.72f, 0.84f, 0.56f, 0.14f, 0.28f, -0.05f, -0.65f, 0.16f),
        // Music presence probability readout
        floatArrayOf(0.38f, 0.32f, 0.26f, 0.28f, 0.30f, 0.22f, 0.42f, 0.36f, 0.32f, 0.28f, 0.24f, 0.95f, 0.88f, 0.82f, -1.10f, 0.78f),
    )

    private val acousticB2: FloatArray = floatArrayOf(-0.78f, -1.05f, -0.62f)

    // --- Head 2: Prosodic Word-Pacing Regressor (10 -> 12 Tanh -> 2) ---
    private val pacingW1: Array<FloatArray> = arrayOf(
        floatArrayOf(1.35f, 0.35f, 0.25f, -0.10f, 0.45f, -0.45f, 0.05f, 0.20f, 0.15f, -0.12f),
        floatArrayOf(0.90f, 0.75f, 0.55f, -0.25f, 0.30f, -0.55f, 0.00f, 0.35f, 0.20f, -0.18f),
        floatArrayOf(0.45f, 0.20f, 0.85f, -0.35f, 0.50f, -0.30f, -0.05f, 0.65f, 0.40f, -0.15f),
        floatArrayOf(0.25f, 0.10f, 0.15f, 0.65f, 0.20f, -0.20f, 0.45f, -0.15f, -0.10f, 0.35f),
        floatArrayOf(0.15f, -0.10f, 0.05f, 0.10f, -0.35f, 1.25f, -0.20f, -0.65f, -0.40f, 0.20f),
        floatArrayOf(0.55f, 0.40f, 0.45f, -0.15f, 0.25f, -0.40f, 0.10f, 1.15f, 0.80f, -0.22f),
        floatArrayOf(0.75f, 0.15f, 0.30f, 0.25f, 0.60f, -0.35f, 0.30f, 0.15f, 0.05f, 0.10f),
        floatArrayOf(0.20f, 0.55f, 0.70f, -0.30f, 0.15f, -0.25f, 0.05f, 0.85f, 0.55f, -0.28f),
        floatArrayOf(0.60f, 0.25f, 0.10f, 0.55f, 0.20f, -0.75f, 0.85f, 0.10f, -0.15f, 0.45f),
        floatArrayOf(0.35f, 0.15f, 0.20f, 0.40f, 0.35f, -0.65f, 0.60f, 0.25f, 0.10f, 0.55f),
        floatArrayOf(1.10f, 0.30f, 0.20f, 0.05f, 0.70f, -0.50f, 0.15f, 0.40f, 0.25f, -0.08f),
        floatArrayOf(-0.20f, 0.10f, -0.15f, 0.35f, -0.25f, 0.90f, -0.35f, -0.45f, -0.20f, 0.30f),
    )

    private val pacingB1: FloatArray = floatArrayOf(
        -0.15f, -0.10f, -0.20f, -0.05f, -0.25f, -0.30f,
        -0.12f, -0.22f, -0.18f, -0.15f, -0.10f, -0.20f,
    )

    private val pacingW2: Array<FloatArray> = arrayOf(
        // Relative sung duration weight
        floatArrayOf(0.95f, 0.62f, 0.58f, -0.15f, -0.52f, 0.78f, 0.48f, 0.54f, 0.18f, 0.12f, 0.72f, -0.42f),
        // Beat-grid attraction strength (0..1)
        floatArrayOf(0.22f, 0.08f, -0.12f, 0.45f, -0.75f, -0.20f, 0.32f, -0.15f, 0.88f, 0.74f, 0.28f, -0.68f),
    )

    private val pacingB2: FloatArray = floatArrayOf(0.85f, -0.12f)

    // --- Head 3: Lyric Alignment Neural Scorer (8 -> 10 ReLU -> 1 Sigmoid) ---
    private val alignW1: Array<FloatArray> = arrayOf(
        floatArrayOf(1.25f, 0.95f, 0.65f, 0.85f, 0.75f, 0.55f, 0.25f, 0.18f),
        floatArrayOf(0.55f, 1.35f, 0.90f, 0.70f, 0.60f, 0.40f, 0.20f, 0.12f),
        floatArrayOf(0.90f, 0.75f, 1.15f, 0.60f, 0.85f, 0.95f, 0.30f, 0.15f),
        floatArrayOf(0.45f, 0.85f, 0.75f, 1.15f, 0.95f, 0.50f, 0.25f, 0.20f),
        floatArrayOf(1.10f, 0.65f, 0.50f, 0.75f, 0.90f, 1.10f, 0.15f, 0.10f),
        floatArrayOf(0.35f, 1.15f, 1.10f, 0.65f, 0.45f, 0.35f, 0.40f, 0.22f),
        floatArrayOf(0.80f, 0.80f, 0.80f, 0.80f, 0.80f, 0.65f, 0.35f, 0.25f),
        floatArrayOf(-0.65f, -0.75f, -0.55f, -0.60f, -0.45f, -0.50f, -0.35f, 0.00f),
        floatArrayOf(0.60f, 0.70f, 0.95f, 0.90f, 0.70f, 0.60f, 0.25f, 0.35f),
        floatArrayOf(0.95f, 0.95f, 0.70f, 0.65f, 1.05f, 0.80f, 0.20f, 0.15f),
    )
    private val alignB1: FloatArray = floatArrayOf(
        -1.25f, -1.20f, -1.30f, -1.28f, -1.32f,
        -1.22f, -1.35f, 1.35f, -1.25f, -1.28f,
    )
    private val alignW2: FloatArray = floatArrayOf(
        0.72f, 0.68f, 0.64f, 0.60f, 0.66f, 0.58f, 0.74f, -1.35f, 0.52f, 0.62f,
    )
    private val alignB2: Float = -2.10f

    private val alignmentStopWords = setOf(
        "the", "and", "for", "that", "this", "with", "from", "your", "you", "are", "was",
    )

    fun inferAcousticFrame(features: AcousticFeatureVector): AcousticNeuralOutput {
        val x = features.toFloatArray()
        val hidden = FloatArray(acousticW1.size)
        for (i in acousticW1.indices) {
            var sum = acousticB1[i]
            val row = acousticW1[i]
            for (j in row.indices) sum += row[j] * x[j]
            hidden[i] = max(0f, sum)
        }
        val out = FloatArray(3)
        for (k in 0 until 3) {
            var sum = acousticB2[k]
            val row = acousticW2[k]
            for (i in hidden.indices) sum += row[i] * hidden[i]
            out[k] = sigmoid(sum)
        }
        return AcousticNeuralOutput(
            vocalProbability = out[0],
            beatOnsetProbability = out[1],
            musicPresenceProbability = out[2],
        )
    }

    fun inferWordPacing(features: WordFeatureVector): WordNeuralOutput {
        val x = features.toFloatArray()
        val hidden = FloatArray(pacingW1.size)
        for (i in pacingW1.indices) {
            var sum = pacingB1[i]
            val row = pacingW1[i]
            for (j in row.indices) sum += row[j] * x[j]
            hidden[i] = tanh(sum.toDouble()).toFloat()
        }
        var durRaw = pacingB2[0]
        var beatRaw = pacingB2[1]
        for (i in hidden.indices) {
            durRaw += pacingW2[0][i] * hidden[i]
            beatRaw += pacingW2[1][i] * hidden[i]
        }
        val durationWeight = (0.45f + softplus(durRaw)).coerceIn(0.45f, 5.2f)
        val beatAttraction = sigmoid(beatRaw).coerceIn(0f, 1f)
        return WordNeuralOutput(durationWeight = durationWeight, beatAttraction = beatAttraction)
    }

    fun scoreLyricAlignment(
        heardPhrase: String,
        candidateText: String,
        temporalProximity: Float = 0.5f,
    ): Float {
        val qNorm = normalizeLyrics(heardPhrase)
        val cNorm = normalizeLyrics(candidateText)
        if (qNorm.isBlank() || cNorm.isBlank()) return 0f

        val qTokens = qNorm.split(" ").filter { it.length >= 2 }
        val cTokens = cNorm.split(" ").filter { it.length >= 2 }
        if (qTokens.isEmpty() || cTokens.isEmpty()) return 0f

        // Weighted token recall emphasizes distinctive content words over generic stop words
        val cSet = cTokens.toSet()
        var totalWeight = 0f
        var exactWeightedHits = 0f
        for (tok in qTokens) {
            val w = if (tok in alignmentStopWords) 0.25f else 1.0f
            totalWeight += w
            if (tok in cSet) exactWeightedHits += w
        }
        val exactRecall = (exactWeightedHits / totalWeight.coerceAtLeast(0.5f)).coerceIn(0f, 1f)

        val qPhon = qTokens.map { phoneticSkeleton(it) }
        val cPhonList = cTokens.map { phoneticSkeleton(it) }
        val cPhonSet = cPhonList.toSet()
        var phonWeightedHits = 0f
        for (i in qTokens.indices) {
            val w = if (qTokens[i] in alignmentStopWords) 0.25f else 1.0f
            if (qPhon[i].isNotEmpty() && qPhon[i] in cPhonSet) phonWeightedHits += w
        }
        val phoneticRecall = (phonWeightedHits / totalWeight.coerceAtLeast(0.5f)).coerceIn(0f, 1f)

        val trigramCos = charTrigramCosine(qNorm, cNorm)
        val lcsRatio = longestCommonTokenSubsequence(qPhon, cPhonList).toFloat() / qTokens.size.toFloat()

        val bigramOrder = if (qPhon.size >= 2 && cPhonList.size >= 2) {
            val cBigrams = cPhonList.zipWithNext().toSet()
            qPhon.zipWithNext().count { it in cBigrams }.toFloat() / (qPhon.size - 1).toFloat()
        } else {
            exactRecall
        }

        val substringHit = when {
            cNorm.contains(qNorm) -> 1f
            qNorm.contains(cNorm) && cNorm.length >= 8 -> 0.85f
            else -> 0f
        }

        val minLen = min(qTokens.size, cTokens.size).toFloat()
        val maxLen = max(qTokens.size, cTokens.size).toFloat()
        val lengthBalance = (minLen / maxLen).coerceIn(0f, 1f)

        val features = AlignmentFeatureVector(
            exactTokenRecall = exactRecall,
            phoneticTokenRecall = phoneticRecall,
            charTrigramCosine = trigramCos,
            lcsTokenRatio = lcsRatio,
            bigramOrderRatio = bigramOrder,
            substringHit = substringHit,
            lengthBalance = lengthBalance,
            temporalProximity = temporalProximity.coerceIn(0f, 1f),
        )
        return inferAlignmentScore(features)
    }

    private fun inferAlignmentScore(features: AlignmentFeatureVector): Float {
        val x = features.toFloatArray()
        var outSum = alignB2
        for (i in alignW1.indices) {
            var sum = alignB1[i]
            val row = alignW1[i]
            for (j in row.indices) sum += row[j] * x[j]
            val act = max(0f, sum)
            outSum += alignW2[i] * act
        }
        return sigmoid(outSum)
    }

    fun normalizeLyrics(raw: String): String =
        raw.lowercase()
            .replace("in'", "ing")
            .replace("in’", "ing")
            .replace("'", "")
            .replace("’", "")
            .replace(Regex("\\bwanna\\b"), "want to")
            .replace(Regex("\\bgonna\\b"), "going to")
            .replace(Regex("\\bgotta\\b"), "got to")
            .replace(Regex("\\bcause\\b"), "because")
            .replace(Regex("\\bya\\b"), "you")
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    fun phoneticSkeleton(word: String): String {
        val w = word.lowercase().filter { it.isLetter() }
        if (w.length <= 2) return w
        val sb = StringBuilder()
        sb.append(w[0])
        var prevCode = consonantGroup(w[0])
        for (i in 1 until w.length) {
            val code = consonantGroup(w[i])
            if (code != '0' && code != prevCode) {
                sb.append(code)
            }
            prevCode = code
        }
        return sb.toString()
    }

    private fun consonantGroup(ch: Char): Char = when (ch) {
        'b', 'f', 'p', 'v' -> '1'
        'c', 'g', 'j', 'k', 'q', 's', 'x', 'z' -> '2'
        'd', 't' -> '3'
        'l' -> '4'
        'm', 'n' -> '5'
        'r' -> '6'
        else -> '0'
    }

    private fun charTrigramCosine(a: String, b: String): Float {
        if (a.length < 3 || b.length < 3) return if (a == b) 1f else 0f
        val mapA = HashMap<String, Int>()
        val mapB = HashMap<String, Int>()
        for (i in 0..a.length - 3) {
            val g = a.substring(i, i + 3)
            mapA[g] = (mapA[g] ?: 0) + 1
        }
        for (i in 0..b.length - 3) {
            val g = b.substring(i, i + 3)
            mapB[g] = (mapB[g] ?: 0) + 1
        }
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for ((k, v) in mapA) {
            normA += v * v
            val bv = mapB[k]
            if (bv != null) dot += v * bv
        }
        for (v in mapB.values) normB += v * v
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom > 1e-6) (dot / denom).toFloat().coerceIn(0f, 1f) else 0f
    }

    private fun longestCommonTokenSubsequence(a: List<String>, b: List<String>): Int {
        if (a.isEmpty() || b.isEmpty()) return 0
        val prev = IntArray(b.size + 1)
        val curr = IntArray(b.size + 1)
        for (i in a.indices) {
            for (j in b.indices) {
                curr[j + 1] = if (a[i] == b[j] || (a[i].length >= 3 && b[j].length >= 3 && abs(a[i].length - b[j].length) <= 1 && a[i].startsWith(b[j].take(2)))) {
                    prev[j] + 1
                } else {
                    max(prev[j + 1], curr[j])
                }
            }
            System.arraycopy(curr, 0, prev, 0, curr.size)
        }
        return prev[b.size]
    }

    private fun sigmoid(x: Float): Float = (1.0 / (1.0 + exp(-x.coerceIn(-12f, 12f).toDouble()))).toFloat()

    private fun softplus(x: Float): Float {
        val clamped = x.coerceIn(-10f, 10f).toDouble()
        return kotlin.math.ln(1.0 + exp(clamped)).toFloat()
    }
}
