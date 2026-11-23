package com.example.engine

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

sealed interface VocalCaptureEvent {
    data object Ready : VocalCaptureEvent
    data class Level(val normalized: Float) : VocalCaptureEvent
    data class RollingPhrase(
        val latestSegment: String,
        val accumulatedBuffer: String,
        val observedAtElapsedMs: Long,
        val isSegmentFinal: Boolean,
    ) : VocalCaptureEvent
    data class Retrying(val attempt: Int) : VocalCaptureEvent
    data class Stopped(val reason: String?) : VocalCaptureEvent
}

/**
 * Resilient continuous vocal phrase listener built on Android SpeechRecognizer.
 * Automatically rides through instrumental intros and short silence gaps by
 * restarting listening cycles until maxSessionMs expires or the caller stops it.
 */
class VocalCaptureController(private val appContext: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var sessionActive = false
    private var sessionDeadlineMs = 0L
    private var cycleCount = 0
    private val accumulatedWords = ArrayDeque<String>()
    private var callback: ((VocalCaptureEvent) -> Unit)? = null

    private val restartRunnable = Runnable {
        if (sessionActive) {
            beginCycle()
        }
    }

    private val timeoutRunnable = Runnable {
        if (sessionActive) {
            val cb = callback
            stopInternal()
            cb?.invoke(
                VocalCaptureEvent.Stopped(
                    "Didn't catch enough clear singing yet. Hold closer to the speaker during vocals or search directly.",
                ),
            )
        }
    }

    fun startContinuous(
        maxSessionMs: Long = 24_000L,
        onEvent: (VocalCaptureEvent) -> Unit,
    ) {
        mainHandler.post {
            stopInternal()
            if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
                onEvent(VocalCaptureEvent.Stopped("Speech recognition service unavailable on this device"))
                return@post
            }
            callback = onEvent
            sessionActive = true
            cycleCount = 0
            accumulatedWords.clear()
            sessionDeadlineMs = SystemClock.elapsedRealtime() + maxSessionMs
            mainHandler.postDelayed(timeoutRunnable, maxSessionMs)
            beginCycle()
        }
    }

    fun stop() {
        mainHandler.post { stopInternal() }
    }

    private fun beginCycle() {
        if (!sessionActive) return
        if (SystemClock.elapsedRealtime() >= sessionDeadlineMs) {
            val cb = callback
            stopInternal()
            cb?.invoke(VocalCaptureEvent.Stopped(null))
            return
        }

        destroyRecognizerOnly()
        cycleCount++

        val instance = try {
            SpeechRecognizer.createSpeechRecognizer(appContext)
        } catch (e: Exception) {
            val cb = callback
            stopInternal()
            cb?.invoke(VocalCaptureEvent.Stopped("Unable to initialize vocal capture: ${e.localizedMessage}"))
            return
        }
        recognizer = instance

        instance.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (sessionActive) callback?.invoke(VocalCaptureEvent.Ready)
            }

            override fun onBeginningOfSpeech() = Unit

            override fun onRmsChanged(rmsdB: Float) {
                if (sessionActive) {
                    val level = ((rmsdB + 2f) / 11f).coerceIn(0f, 1f)
                    callback?.invoke(VocalCaptureEvent.Level(level))
                }
            }

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() = Unit

            override fun onError(error: Int) {
                if (!sessionActive) return
                when (error) {
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                        val cb = callback
                        stopInternal()
                        cb?.invoke(VocalCaptureEvent.Stopped("Microphone permission required"))
                    }
                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                        val cb = callback
                        stopInternal()
                        cb?.invoke(VocalCaptureEvent.Stopped("Network required for vocal recognition"))
                    }
                    else -> {
                        // Transient musical noise / instrumental intro / no-match: keep listening!
                        scheduleNextCycle(delayMs = 240L)
                    }
                }
            }

            override fun onResults(results: Bundle?) {
                if (!sessionActive) return
                val best = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                    .trim()
                if (best.isNotEmpty()) {
                    appendToRollingBuffer(best)
                    val bufferText = accumulatedWords.joinToString(" ")
                    callback?.invoke(
                        VocalCaptureEvent.RollingPhrase(
                            latestSegment = best,
                            accumulatedBuffer = bufferText,
                            observedAtElapsedMs = SystemClock.elapsedRealtime(),
                            isSegmentFinal = true,
                        ),
                    )
                }
                // Continue listening smoothly in case the first short phrase wasn't enough for a lock
                if (sessionActive) {
                    scheduleNextCycle(delayMs = 200L)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (!sessionActive) return
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                    .trim()
                if (partial.isNotEmpty()) {
                    val combined = buildString {
                        if (accumulatedWords.isNotEmpty()) {
                            append(accumulatedWords.joinToString(" "))
                            append(' ')
                        }
                        append(partial)
                    }.trim()
                    callback?.invoke(
                        VocalCaptureEvent.RollingPhrase(
                            latestSegment = partial,
                            accumulatedBuffer = combined,
                            observedAtElapsedMs = SystemClock.elapsedRealtime(),
                            isSegmentFinal = false,
                        ),
                    )
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 5_500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3_200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2_500L)
        }
        runCatching { instance.startListening(intent) }.onFailure {
            scheduleNextCycle(delayMs = 350L)
        }
    }

    private fun scheduleNextCycle(delayMs: Long) {
        if (!sessionActive) return
        if (SystemClock.elapsedRealtime() + delayMs >= sessionDeadlineMs) {
            val cb = callback
            stopInternal()
            cb?.invoke(VocalCaptureEvent.Stopped(null))
            return
        }
        callback?.invoke(VocalCaptureEvent.Retrying(cycleCount))
        mainHandler.removeCallbacks(restartRunnable)
        mainHandler.postDelayed(restartRunnable, delayMs)
    }

    private fun appendToRollingBuffer(segment: String) {
        val tokens = segment.split(Regex("\\s+")).filter { it.isNotBlank() }
        for (tok in tokens) {
            accumulatedWords.addLast(tok)
            while (accumulatedWords.size > 16) {
                accumulatedWords.removeFirst()
            }
        }
    }

    private fun destroyRecognizerOnly() {
        recognizer?.let { rec ->
            runCatching { rec.cancel() }
            runCatching { rec.destroy() }
        }
        recognizer = null
    }

    private fun stopInternal() {
        sessionActive = false
        callback = null
        mainHandler.removeCallbacks(restartRunnable)
        mainHandler.removeCallbacks(timeoutRunnable)
        destroyRecognizerOnly()
    }
}
