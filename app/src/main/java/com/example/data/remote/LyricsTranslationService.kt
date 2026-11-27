package com.example.data.remote

import android.util.Log
import com.example.model.LyricLine
import com.example.model.TranslationLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

private const val TAG = "LyricsTranslation"

data class TranslatedLyricsResult(
    val sourceLangCode: String?,
    val targetLangCode: String,
    val lineTranslations: Map<Int, String>,
)

/**
 * Free real-time lyric line translator supporting line-aligned batch translation
 * (Google Translate public client endpoint with automatic MyMemory fallback).
 */
class LyricsTranslationService {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    suspend fun translateLines(
        lines: List<LyricLine>,
        targetLanguage: TranslationLanguage,
    ): TranslatedLyricsResult? = withContext(Dispatchers.IO) {
        val vocalLines = lines.filter { !it.isInstrumental && it.rawText.isNotBlank() }
        if (vocalLines.isEmpty()) {
            return@withContext TranslatedLyricsResult(
                sourceLangCode = null,
                targetLangCode = targetLanguage.code,
                lineTranslations = emptyMap(),
            )
        }

        val translatedMap = LinkedHashMap<Int, String>(vocalLines.size)
        var detectedSource: String? = null

        // Batch ~18 lines per request to stay well within URL query limits while keeping translation fast
        val batches = vocalLines.chunked(18)
        for (batch in batches) {
            val joined = batch.joinToString("\n") { it.rawText.replace("\n", " ").trim() }
            val primary = translateChunkViaPublicEndpoint(joined, targetLanguage.code)
            if (primary != null) {
                if (detectedSource == null && !primary.second.isNullOrBlank()) {
                    detectedSource = primary.second
                }
                val splitLines = primary.first.lines()
                if (splitLines.size == batch.size) {
                    for (i in batch.indices) {
                        val tr = splitLines[i].trim()
                        if (tr.isNotBlank()) {
                            translatedMap[batch[i].index] = tr
                        }
                    }
                    continue
                }
            }

            // Line-by-line fallback (either primary mismatch or MyMemory fallback)
            for (line in batch) {
                val single = translateChunkViaPublicEndpoint(line.rawText, targetLanguage.code)?.first
                    ?: translateSingleViaMyMemory(line.rawText, targetLanguage.code)
                if (!single.isNullOrBlank()) {
                    translatedMap[line.index] = single.trim()
                }
            }
        }

        if (translatedMap.isEmpty()) {
            null
        } else {
            TranslatedLyricsResult(
                sourceLangCode = detectedSource,
                targetLangCode = targetLanguage.code,
                lineTranslations = translatedMap,
            )
        }
    }

    /**
     * Translates a short song search query into the target script/language for cross-script
     * discovery (e.g., typing an English title while searching the Japanese or Korean catalog).
     */
    suspend fun translateQueryText(
        query: String,
        targetLangCode: String,
    ): String? = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@withContext null
        val translated = translateChunkViaPublicEndpoint(trimmed, targetLangCode)?.first?.trim()
        translated?.takeIf { it.isNotBlank() && !it.equals(trimmed, ignoreCase = true) }
    }

    private fun translateChunkViaPublicEndpoint(
        text: String,
        targetLangCode: String,
    ): Pair<String, String?>? {
        return try {
            val encoded = URLEncoder.encode(text, "UTF-8")
            val url = "https://translate.googleapis.com/translate_a/single" +
                "?client=gtx&sl=auto&tl=$targetLangCode&dt=t&q=$encoded"
            val request = Request.Builder()
                .url(url)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36",
                )
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val root = JSONArray(body)
                val segments = root.optJSONArray(0) ?: return null
                val out = StringBuilder()
                for (i in 0 until segments.length()) {
                    val seg = segments.optJSONArray(i) ?: continue
                    val piece = seg.optString(0, "")
                    out.append(piece)
                }
                val detected = root.optString(2, "").takeIf { it.isNotBlank() }
                val result = out.toString()
                if (result.isBlank()) null else result to detected
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Primary translate call failed", e)
            null
        }
    }

    private fun translateSingleViaMyMemory(
        text: String,
        targetLangCode: String,
    ): String? {
        return try {
            val encoded = URLEncoder.encode(text, "UTF-8")
            val url = "https://api.mymemory.translated.net/get?q=$encoded&langpair=autodetect|$targetLangCode"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "CadenceAndroid/1.3")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val json = JSONObject(body)
                val translated = json.optJSONObject("responseData")?.optString("translatedText", "")
                translated?.takeIf { it.isNotBlank() && !it.startsWith("PLEASE SELECT", ignoreCase = true) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "MyMemory fallback failed", e)
            null
        }
    }
}
