package com.receiptbox.app.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OcrHelper(private val context: Context) {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun recognizeStructured(uri: Uri): StructuredReceiptParse = withContext(Dispatchers.IO) {
        val bitmap = loadBitmap(uri)
        val image = InputImage.fromBitmap(bitmap, 0)
        val text = suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    if (cont.isActive) cont.resume(result.text.orEmpty())
                }
                .addOnFailureListener { e ->
                    if (cont.isActive) cont.resumeWithException(e)
                }
        }
        ReceiptOcrParser.parse(text)
    }

    @Suppress("DEPRECATION")
    private fun loadBitmap(uri: Uri): Bitmap {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.isMutableRequired = false
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
        }
    }

    fun close() {
        recognizer.close()
    }
}
