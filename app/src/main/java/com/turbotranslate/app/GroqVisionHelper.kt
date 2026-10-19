package com.turbotranslate.app

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
        imageFile: File,
        otherLanguage: String,
        onResult: (PhotoTranslationResult) -> Unit,
        onError: (String) -> Unit
    ) {
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
                val messages = JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", content)
                    })
                }
                val requestBody = JSONObject().apply {
                    put("model", "qwen/qwen3.6-27b")
                    put("messages", messages)
                    put("temperature", 0.2)
                    // reasoning_format=hidden only hides the model's
                    // chain-of-thought from the response — it doesn't stop
                    // the model from generating it, and that hidden
                    // reasoning still counts against max_completion_tokens.
                    // 500 was too tight: the model spent the whole budget
                    // thinking and never got to write the visible answer,
                    // which came back as an empty string. reasoning_effort
                    // "none" skips the thinking step for this model (it
                    // only accepts "none" or "default" — "low"/"medium"/
                    // "high" are for other Groq-hosted reasoning models
                    // and get rejected here with a 400), and the token cap
                    // is raised well past what a short three-line answer
                    // should ever need either way.
                    put("reasoning_effort", "none")
                    put("max_completion_tokens", 2048)
                    put("reasoning_format", "hidden")
                }.toString().toRequestBody("application/json".toMediaTypeOrNull())

                val request = Request.Builder()
                    .url("https://api.groq.com/openai/v1/chat/completions")
                    .addHeader("Authorization", "Bearer ${GroqConfig.getApiKey()}")
                    .post(requestBody)
                    .build()

                client.newCall(request).execute().use { response ->
                    val bodyStr = response.body?.string() ?: ""
                    if (!response.isSuccessful) {
                        Log.e(TAG, "Groq error ${response.code}: $bodyStr")
                        mainHandler.post { onError("Photo translation error ${response.code} — check your API key / internet") }
                        return@Thread
                    }
                    val content2 = JSONObject(bodyStr).getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                        .trim()
                    Log.i(TAG, "vision response: \"$content2\"")

                    if (content2.equals("NO_TEXT_FOUND", ignoreCase = true)) {
                        mainHandler.post { onError("No readable text found in that photo") }
                        return@Thread
                    }
                    if (content2.isEmpty()) {
                        mainHandler.post { onError("Groq returned an empty response — try again") }
                        return@Thread
                    }

                    val result = parseResponse(content2)
                    if (result == null) {
                        mainHandler.post { onError("Couldn't parse the translation response") }
                    } else {
                        mainHandler.post { onResult(result) }
                    }
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
