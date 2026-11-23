package com.example.engine

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

sealed interface VocalCaptureEvent {
    data object Ready : VocalCaptureEvent
    data class Level(val normalized: Float) : VocalCaptureEvent
    data class PartialPhrase(val phrase: String) : VocalCaptureEvent
    data class FinalPhrase(val phrase: String) : VocalCaptureEvent
    data class Stopped(val reason: String?) : VocalCaptureEvent
}

class VocalCaptureController(private val appContext: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var active = false

    fun start(onEvent: (VocalCaptureEvent) -> Unit) {
        mainHandler.post {
            stopInternal()
            if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
                onEvent(VocalCaptureEvent.Stopped("Speech recognition service unavailable on this device"))
                return@post
            }

            val instance = SpeechRecognizer.createSpeechRecognizer(appContext)
            recognizer = instance
            active = true

            instance.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    if (active) onEvent(VocalCaptureEvent.Ready)
                }

                override fun onBeginningOfSpeech() = Unit

                override fun onRmsChanged(rmsdB: Float) {
                    if (active) {
                        val level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                        onEvent(VocalCaptureEvent.Level(level))
                    }
                }

                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() = Unit

                override fun onError(error: Int) {
                    if (!active) return
                    active = false
                    val note = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> null
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network unavailable for vocal recognition"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required"
                        else -> "Could not hear clear vocals — try moving closer to the speaker"
                    }
                    onEvent(VocalCaptureEvent.Stopped(note))
                }

                override fun onResults(results: Bundle?) {
                    if (!active) return
                    active = false
                    val best = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                        .trim()
                    if (best.isNotEmpty()) {
                        onEvent(VocalCaptureEvent.FinalPhrase(best))
                    } else {
                        onEvent(VocalCaptureEvent.Stopped(null))
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    if (!active) return
                    val text = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                        .trim()
                    if (text.isNotEmpty()) {
                        onEvent(VocalCaptureEvent.PartialPhrase(text))
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
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3_500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2_800L)
            }
            instance.startListening(intent)
        }
    }

    fun stop() {
        mainHandler.post { stopInternal() }
    }

    private fun stopInternal() {
        active = false
        recognizer?.let { rec ->
            runCatching { rec.cancel() }
            runCatching { rec.destroy() }
        }
        recognizer = null
    }
}
