package com.receiptbox.app.ocr

import android.content.Context
import java.io.File
import java.io.FileOutputStream

/** Copies bundled eng+heb traineddata from assets into app filesDir/tessdata. */
object TessDataInstaller {
    private const val ASSET_DIR = "tessdata"
    private val REQUIRED = listOf("eng.traineddata", "heb.traineddata")

    fun ensureInstalled(context: Context): File {
        val root = context.filesDir // parent of tessdata/
        val dir = File(root, ASSET_DIR)
        if (!dir.exists()) dir.mkdirs()
        for (name in REQUIRED) {
            val dest = File(dir, name)
            if (dest.exists() && dest.length() > 10_000L) continue
            context.assets.open("$ASSET_DIR/$name").use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            }
        }
        return root
    }
}
