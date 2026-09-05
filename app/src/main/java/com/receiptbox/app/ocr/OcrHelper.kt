package com.receiptbox.app.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Multilingual on-device OCR — not Latin-only.
 *
 * Pipeline:
 * 1) Preprocess (grayscale/contrast) + orientation search
 * 2) ML Kit clients: Latin, Chinese, Japanese, Korean, Devanagari (merge non-empty)
 * 3) Tesseract with installed tessdata packs (`+`-joined default subset)
 * 4) [OcrTextMerger] → [ReceiptOcrParser]
 */
class OcrHelper(private val context: Context) {

    val languagePacks = TessLanguagePackManager(context)

    private val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val chinese = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    private val japanese = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    private val korean = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    private val devanagari = TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())

    private val mlKitClients: List<Pair<String, TextRecognizer>> = listOf(
        "latin" to latin,
        "chinese" to chinese,
        "japanese" to japanese,
        "korean" to korean,
        "devanagari" to devanagari
    )

    private val tessMutex = Mutex()
    @Volatile private var tess: TessBaseAPI? = null
    @Volatile private var tessLangs: String? = null

    suspend fun recognizeStructured(uri: Uri): StructuredReceiptParse = withContext(Dispatchers.IO) {
        languagePacks.ensureBundledCopied()
        val rawBitmap = loadBitmap(uri)
        var bestMerged = ""
        var bestScore = -1
        for (oriented in ImagePreprocessor.orientationCandidates(rawBitmap)) {
            val prepared = ImagePreprocessor.prepareForOcr(oriented)
            val merged = runAllEngines(prepared)
            val score = OcrTextMerger.score(merged)
            if (score > bestScore) {
                bestScore = score
                bestMerged = merged
            }
            if (score >= 8) break
        }
        Log.i(TAG, "OCR bestScore=$bestScore chars=${bestMerged.length}")
        ReceiptOcrParser.parse(bestMerged)
    }

    private suspend fun runAllEngines(bitmap: Bitmap): String = coroutineScope {
        val mlDeferred = mlKitClients.map { (name, client) ->
            async {
                name to runCatching { runMlKit(client, bitmap) }.getOrDefault("")
            }
        }
        val tessDeferred = async {
            "tesseract" to runCatching { runTesseract(bitmap) }.getOrElse { e ->
                Log.e(TAG, "Tesseract failed", e)
                ""
            }
        }
        val parts = (mlDeferred + tessDeferred).awaitAll()
        parts.forEach { (name, text) ->
            if (text.isNotBlank()) Log.d(TAG, "engine $name → ${text.length}c")
        }
        OcrTextMerger.merge(parts.map { it.second })
    }

    private suspend fun runMlKit(client: TextRecognizer, bitmap: Bitmap): String =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            client.process(image)
                .addOnSuccessListener { result ->
                    if (cont.isActive) cont.resume(result.text.orEmpty())
                }
                .addOnFailureListener { e ->
                    if (cont.isActive) cont.resumeWithException(e)
                }
        }

    private suspend fun runTesseract(bitmap: Bitmap): String = tessMutex.withLock {
        val api = ensureTess()
        // AUTO then sparse — helps thermal Israeli slips where totals float
        api.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
        api.setImage(bitmap)
        val primary = api.getUTF8Text().orEmpty()
        api.clear()
        api.pageSegMode = TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT
        api.setImage(bitmap)
        val sparse = api.getUTF8Text().orEmpty()
        api.clear()
        OcrTextMerger.merge(primary, sparse)
    }

    private fun ensureTess(): TessBaseAPI {
        languagePacks.ensureBundledCopied()
        val langs = languagePacks.defaultOcrLangString()
        tess?.let { existing ->
            if (tessLangs == langs) return existing
            existing.recycle()
            tess = null
        }
        val api = TessBaseAPI()
        var ok = api.init(languagePacks.tessParentDir.absolutePath, langs)
        if (!ok) {
            api.recycle()
            val fallback = TessBaseAPI()
            val engHeb = listOf("eng", "heb").filter {
                java.io.File(languagePacks.tessdataDir, "$it.traineddata").exists()
            }.joinToString("+").ifBlank { "eng" }
            ok = fallback.init(languagePacks.tessParentDir.absolutePath, engHeb)
            if (!ok) {
                fallback.recycle()
                throw IllegalStateException("Tesseract init failed ($langs / $engHeb)")
            }
            tess = fallback
            tessLangs = engHeb
            return fallback
        }
        tess = api
        tessLangs = langs
        return api
    }

    @Suppress("DEPRECATION")
    private fun loadBitmap(uri: Uri): Bitmap {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.isMutableRequired = false
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                .copy(Bitmap.Config.ARGB_8888, false)
        }
    }

    fun close() {
        mlKitClients.forEach { (_, c) ->
            try { c.close() } catch (_: Exception) {}
        }
        tess?.recycle()
        tess = null
        tessLangs = null
    }

    companion object {
        private const val TAG = "OcrHelper"
    }
}
