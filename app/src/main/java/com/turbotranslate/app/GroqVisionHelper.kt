package com.turbotranslate.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val TAG = "TurboTranslateVision"
private const val MAX_DIMENSION = 1280

// What Groq's vision docs point to (qwen3.6-27b was retired in late 2026).
// If this goes away too, findVisionModel picks a replacement at runtime.
private const val DEFAULT_VISION_MODEL = "qwen/qwen3.8-27b"

/** Result of a photo translation: [detectedLanguage] is whatever the
 *  model reports the extracted text was written in (not necessarily the
 *  configured "other language" — you might photograph a menu in a third
 *  language entirely). [fromEnglish] is derived from that, so photo
 *  entries slot into the same left/right transcript convention as
 *  spoken ones. */
data class PhotoTranslationResult(
    val detectedLanguage: String,
    val extractedText: String,
    val translatedText: String
)

/** Sends a photo to Groq's vision-capable chat model to read whatever
 *  text is in it and translate it — same account/API used elsewhere in
 *  the app (see GroqVoiceInputHelper, GroqTranslateHelper), just a
 *  vision-capable model and an image payload instead of audio or text. */
object GroqVisionHelper {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** [otherLanguage] is only used as the *fallback* target when the
     *  photographed text turns out to already be English — the model
     *  otherwise always translates non-English text found in the photo
     *  to English, whatever language it actually is. */
    fun extractAndTranslate(
        context: Context,
        imageFile: File,
        otherLanguage: String,
        onResult: (PhotoTranslationResult) -> Unit,
        onError: (String) -> Unit
    ) {
        if (GroqConfig.getApiKey().isBlank()) {
            onError(GroqConfig.missingKeyMessage())
            return
        }
        val appContext = context.applicationContext
        Thread {
            try {
                val base64Image = downscaleAndEncode(imageFile)
                if (base64Image == null) {
                    mainHandler.post { onError("Couldn't read that photo") }
                    return@Thread
                }

                val promptText = "Find all readable text in this photo and extract it exactly. " +
                    "Then translate it: if it's already in English, translate it to " +
                    "$otherLanguage; otherwise translate it to English. Respond in EXACTLY " +
                    "this format and nothing else:\nLANGUAGE: <language the extracted text is written in>\n" +
                    "EXTRACTED: <the extracted text, verbatim>\nTRANSLATED: <the translation>\n" +
                    "If there is no readable text in the photo, respond with exactly: NO_TEXT_FOUND"

                val content = JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "text")
                        put("text", promptText)
                    })
                    put(JSONObject().apply {
                        put("type", "image_url")
                        put("image_url", JSONObject().apply {
                            put("url", "data:image/jpeg;base64,$base64Image")
                        })
                    })
                }

                // Groq retires vision models every few months (llama-3.2
                // vision, then qwen3.6-27b). If the model is gone, look up
                // what the key can use now and switch to it, rather than
                // breaking until the next app update.
                val savedModel = SettingsHelper.getVisionModel(appContext)
                var model = savedModel ?: DEFAULT_VISION_MODEL
                var sendReasoning = usesReasoningParams(model)
                var switchedModel = false
                var droppedReasoning = false
                while (true) {
                    val (code, bodyStr) = postChat(model, content, sendReasoning)
                    if (code in 200..299) {
                        if (model != savedModel) SettingsHelper.setVisionModel(appContext, model)
                        handleSuccess(bodyStr, onResult, onError)
                        return@Thread
                    }
                    Log.e(TAG, "Groq error $code from $model: $bodyStr")
                    val (errCode, errMessage) = errorInfo(bodyStr)

                    val modelGone = code == 404 ||
                        errCode == "model_not_found" || errCode == "model_decommissioned"
                    if (modelGone && !switchedModel) {
                        switchedModel = true
                        val next = findVisionModel(exclude = model)
                        if (next == null) {
                            mainHandler.post { onError("Groq has no image model available for this key right now") }
                            return@Thread
                        }
                        Log.i(TAG, "vision model $model unavailable, switching to $next")
                        model = next
                        sendReasoning = usesReasoningParams(next)
                        droppedReasoning = false
                        continue
                    }
                    // A model that doesn't know the reasoning parameters
                    // rejects the whole request; try once without them.
                    if (code == 400 && sendReasoning && !droppedReasoning &&
                        errMessage?.contains("reasoning", ignoreCase = true) == true
                    ) {
                        droppedReasoning = true
                        sendReasoning = false
                        continue
                    }
                    // Show Groq's own reason — the user has no logcat on
                    // the phone. A 401 says which key was sent instead.
                    val message = if (code == 401) {
                        GroqConfig.rejectedKeyMessage()
                    } else {
                        "Photo translation error $code: ${errMessage ?: "check your API key / internet"}"
                    }
                    mainHandler.post { onError(message) }
                    return@Thread
                }
            } catch (e: IOException) {
                Log.e(TAG, "network error", e)
                mainHandler.post { onError("No internet connection reached Groq") }
            } catch (e: Exception) {
                Log.e(TAG, "vision error", e)
                mainHandler.post { onError("Photo translation error: ${e.message}") }
            }
        }.start()
    }

    // reasoning_effort/reasoning_format are Qwen-on-Groq parameters; other
    // model families may reject them.
    private fun usesReasoningParams(model: String) = model.startsWith("qwen/")

    /** POSTs one chat-completions request and returns (HTTP code, body). */
    private fun postChat(model: String, content: JSONArray, sendReasoning: Boolean): Pair<Int, String> {
        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("content", content)
            })
        }
        val requestBody = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("temperature", 0.2)
            put("max_completion_tokens", 2048)
            if (sendReasoning) {
                // reasoning_format=hidden only hides the model's
                // chain-of-thought from the response — it doesn't stop the
                // model from generating it, and that hidden reasoning still
                // counts against max_completion_tokens. 500 was too tight:
                // the model spent the whole budget thinking and never wrote
                // the visible answer. reasoning_effort "none" skips the
                // thinking step for Qwen (it only accepts "none" or
                // "default"), and the token cap above is well past what a
                // short three-line answer needs either way.
                put("reasoning_effort", "none")
                put("reasoning_format", "hidden")
            }
        }.toString().toRequestBody("application/json".toMediaTypeOrNull())

        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/chat/completions")
            .addHeader("Authorization", "Bearer ${GroqConfig.getApiKey()}")
            .post(requestBody)
            .build()
        Log.i(TAG, "vision request to $model")
        return client.newCall(request).execute().use { response ->
            response.code to (response.body?.string() ?: "")
        }
    }

    /** Groq's error JSON as (error.code, error.message), either null if absent. */
    private fun errorInfo(body: String): Pair<String?, String?> =
        try {
            val err = JSONObject(body).getJSONObject("error")
            err.optString("code").ifEmpty { null } to err.optString("message").ifEmpty { null }
        } catch (e: Exception) {
            null to null
        }

    /**
     * Picks the best image-capable model the key can use, from Groq's
     * model list (which doesn't say which models take images, so this goes
     * by known vision-model families): the newest Qwen 27B first (what
     * Groq's vision docs have pointed to since 2026), then anything
     * labelled VL/vision, then Llama 4 Scout and Maverick.
     */
    private fun findVisionModel(exclude: String): String? {
        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/models")
            .addHeader("Authorization", "Bearer ${GroqConfig.getApiKey()}")
            .get()
            .build()
        val ids = client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                Log.e(TAG, "model list error ${response.code}: $body")
                return null
            }
            val data = JSONObject(body).getJSONArray("data")
            (0 until data.length()).map { data.getJSONObject(it) }
                .filter { it.optBoolean("active", true) }
                .map { it.getString("id") }
                .filter { it != exclude }
        }
        Log.i(TAG, "available models: $ids")

        val qwenVersion = Regex("^qwen/qwen([0-9.]+)-27b")
        val qwen = ids.mapNotNull { id -> qwenVersion.find(id)?.let { id to it.groupValues[1] } }
            .sortedWith(Comparator { a, b ->
                when {
                    UpdateChecker.isNewer(a.second, b.second) -> -1
                    UpdateChecker.isNewer(b.second, a.second) -> 1
                    else -> 0
                }
            })
            .map { it.first }
        val labelled = ids.filter { it.contains("-vl") || it.contains("vision") }
        val scout = ids.filter { it.startsWith("meta-llama/llama-4-scout") }
        val maverick = ids.filter { it.startsWith("meta-llama/llama-4-maverick") }
        return (qwen + labelled + scout + maverick).firstOrNull()
    }

    private fun handleSuccess(
        bodyStr: String,
        onResult: (PhotoTranslationResult) -> Unit,
        onError: (String) -> Unit
    ) {
        val content2 = JSONObject(bodyStr).getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .optString("content")
            .trim()
        Log.i(TAG, "vision response: \"$content2\"")

        if (content2.equals("NO_TEXT_FOUND", ignoreCase = true)) {
            mainHandler.post { onError("No readable text found in that photo") }
            return
        }
        if (content2.isEmpty()) {
            mainHandler.post { onError("Groq returned an empty response — try again") }
            return
        }

        val result = parseResponse(content2)
        if (result == null) {
            mainHandler.post { onError("Couldn't parse the translation response") }
        } else {
            mainHandler.post { onResult(result) }
        }
    }

    private fun parseResponse(rawContent: String): PhotoTranslationResult? {
        // Defensive: reasoning_format=hidden should already prevent this,
        // but strip a <think>...</think> block if the model includes one
        // anyway rather than letting it leak into the result.
        val content = rawContent.replace(Regex("(?s)<think>.*?</think>"), "").trim()

        val langIdx = content.indexOf("LANGUAGE:")
        val extractedIdx = content.indexOf("EXTRACTED:")
        val translatedIdx = content.indexOf("TRANSLATED:")
        if (langIdx == -1 || extractedIdx == -1 || translatedIdx == -1) return null
        val language = content.substring(langIdx + "LANGUAGE:".length, extractedIdx).trim()
        val extracted = content.substring(extractedIdx + "EXTRACTED:".length, translatedIdx).trim()
        // Stop at a blank line (double newline) if there is one, rather
        // than consuming to the very end of the string — guards against
        // the model appending anything after the expected three fields.
        val afterTranslated = content.substring(translatedIdx + "TRANSLATED:".length)
        val blankLineIdx = afterTranslated.indexOf("\n\n")
        val translated = (if (blankLineIdx != -1) afterTranslated.substring(0, blankLineIdx) else afterTranslated).trim()
        if (extracted.isEmpty() || translated.isEmpty()) return null
        return PhotoTranslationResult(language, extracted, translated)
    }

    /** Downsamples to a manageable size (keeps the upload small and fast
     *  on a phone that's likely on a slow connection), corrects for
     *  camera EXIF rotation (phone cameras routinely save "sideways"
     *  with just an orientation tag — left uncorrected, that tends to
     *  hurt OCR accuracy), and returns base64-encoded JPEG bytes. */
    private fun downscaleAndEncode(file: File): String? {
        val boundsOnly = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, boundsOnly)
        if (boundsOnly.outWidth <= 0 || boundsOnly.outHeight <= 0) return null

        var sample = 1
        while (boundsOnly.outWidth / sample > MAX_DIMENSION || boundsOnly.outHeight / sample > MAX_DIMENSION) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bitmap = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null

        val rotationDegrees = try {
            when (ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (e: IOException) {
            0
        }
        if (rotationDegrees != 0) {
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            bitmap.recycle()
            bitmap = rotated
        }

        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 82, baos)
        bitmap.recycle()
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }
}
