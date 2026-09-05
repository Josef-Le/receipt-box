package com.receiptbox.app.ocr

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages Tesseract tessdata packs under app filesDir.
 * Default pack ships eng+heb in assets (tessdata_fast) and is copied on first use.
 * Extra languages download from tessdata_fast GitHub raw URLs.
 *
 * Default bundled size ≈ 28 MB (eng+heb+ara+rus+deu+fra+spa+por+ita+tur+pol) from tessdata_fast.
 * Extra languages downloadable in Settings.
 */
class TessLanguagePackManager(private val context: Context) {

    data class LangPack(
        val code: String,
        val label: String,
        val installed: Boolean,
        val bundled: Boolean,
        val approxBytes: Long
    )

    private val _packs = MutableStateFlow<List<LangPack>>(emptyList())
    val packs: StateFlow<List<LangPack>> = _packs.asStateFlow()

    private val _busy = MutableStateFlow<String?>(null)
    val busyMessage: StateFlow<String?> = _busy.asStateFlow()

    val tessParentDir: File
        get() = File(context.filesDir, "tesseract").apply { mkdirs() }

    val tessdataDir: File
        get() = File(tessParentDir, "tessdata").apply { mkdirs() }

    fun installedLanguageCodes(): List<String> {
        ensureBundledCopied()
        return CATALOG.map { it.code }.filter { code -> File(tessdataDir, "$code.traineddata").exists() }
    }

    fun defaultOcrLangString(): String {
        val installed = installedLanguageCodes()
        val preferred = DEFAULT_CODES.filter { it in installed }
        val langs = preferred.ifEmpty { installed.ifEmpty { listOf("eng") } }
        // Tesseract multi-lang init cost grows with count; cap joined set for speed.
        return langs.take(8).joinToString("+")
    }

    fun refresh() {
        ensureBundledCopied()
        _packs.value = CATALOG.map { meta ->
            val f = File(tessdataDir, "${meta.code}.traineddata")
            LangPack(
                code = meta.code,
                label = meta.label,
                installed = f.exists() && f.length() > 10_000,
                bundled = meta.code in BUNDLED_CODES,
                approxBytes = meta.approxBytes
            )
        }
    }

    /** Copy default packs from assets → filesDir/tesseract/tessdata (idempotent). */
    fun ensureBundledCopied() {
        tessdataDir.mkdirs()
        val assetMgr = context.assets
        val assetNames = try {
            assetMgr.list("tessdata")?.toList().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
        for (name in assetNames) {
            if (!name.endsWith(".traineddata")) continue
            val dest = File(tessdataDir, name)
            if (dest.exists() && dest.length() > 10_000) continue
            try {
                assetMgr.open("tessdata/$name").use { input ->
                    FileOutputStream(dest).use { output -> input.copyTo(output) }
                }
            } catch (_: Exception) {
                // optional asset missing — user can download
            }
        }
    }

    suspend fun downloadLanguage(code: String): Result<Unit> = withContext(Dispatchers.IO) {
        val meta = CATALOG.firstOrNull { it.code == code }
            ?: return@withContext Result.failure(IllegalArgumentException("Unknown language: $code"))
        _busy.value = "Downloading ${meta.label}…"
        try {
            ensureBundledCopied()
            val url = URL("$TESSDATA_FAST_BASE/${code}.traineddata")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 120_000
                instanceFollowRedirects = true
            }
            conn.inputStream.use { input ->
                val tmp = File(tessdataDir, "${code}.traineddata.part")
                FileOutputStream(tmp).use { output -> input.copyTo(output) }
                val dest = File(tessdataDir, "${code}.traineddata")
                if (dest.exists()) dest.delete()
                if (!tmp.renameTo(dest)) {
                    tmp.copyTo(dest, overwrite = true)
                    tmp.delete()
                }
            }
            conn.disconnect()
            refresh()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            _busy.value = null
        }
    }

    suspend fun deleteLanguage(code: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (code in listOf("eng", "heb")) {
            return@withContext Result.failure(IllegalStateException("eng/heb are required defaults"))
        }
        val f = File(tessdataDir, "$code.traineddata")
        if (f.exists() && !f.delete()) {
            return@withContext Result.failure(IllegalStateException("Could not delete $code"))
        }
        refresh()
        Result.success(Unit)
    }

    companion object {
        const val TESSDATA_FAST_BASE =
            "https://github.com/tesseract-ocr/tessdata_fast/raw/main"

        /** Bundled in APK assets (tessdata_fast). eng+heb ≈ 5 MB — Hebrew first-class. */
        val BUNDLED_CODES = listOf("eng", "heb")

        /** Default OCR languages for Israeli receipts. */
        val DEFAULT_CODES = listOf("eng", "heb")

        data class Meta(val code: String, val label: String, val approxBytes: Long)

        /** Popular + extras downloadable from tessdata_fast. */
        val CATALOG = listOf(
            Meta("eng", "English", 4_000_000),
            Meta("heb", "Hebrew", 950_000),
            Meta("ara", "Arabic", 1_400_000),
            Meta("rus", "Russian", 3_700_000),
            Meta("deu", "German", 1_500_000),
            Meta("fra", "French", 1_100_000),
            Meta("spa", "Spanish", 2_200_000),
            Meta("por", "Portuguese", 1_900_000),
            Meta("ita", "Italian", 2_600_000),
            Meta("tur", "Turkish", 4_400_000),
            Meta("pol", "Polish", 4_600_000),
            Meta("ukr", "Ukrainian", 3_500_000),
            Meta("ell", "Greek", 1_700_000),
            Meta("ron", "Romanian", 1_500_000),
            Meta("hun", "Hungarian", 3_000_000),
            Meta("ces", "Czech", 2_500_000),
            Meta("slk", "Slovak", 2_200_000),
            Meta("nld", "Dutch", 2_500_000),
            Meta("swe", "Swedish", 2_000_000),
            Meta("fin", "Finnish", 2_500_000),
            Meta("nor", "Norwegian", 2_000_000),
            Meta("dan", "Danish", 1_800_000),
            Meta("chi_sim", "Chinese Simplified", 2_500_000),
            Meta("chi_tra", "Chinese Traditional", 2_500_000),
            Meta("jpn", "Japanese", 2_500_000),
            Meta("kor", "Korean", 2_500_000),
            Meta("hin", "Hindi", 1_500_000),
            Meta("tha", "Thai", 2_000_000),
            Meta("vie", "Vietnamese", 1_200_000),
            Meta("fas", "Persian", 1_200_000),
            Meta("urd", "Urdu", 1_500_000),
            Meta("ben", "Bengali", 1_200_000)
        )
    }
}
