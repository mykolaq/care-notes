package com.carenotes.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.AtomicFile
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File

internal fun instructionNeedsOcr(text: String): Boolean = text.count(Char::isLetter) < 40

/** The immutable PDF filename identifies the locally cached OCR result. */
internal class InstructionOcr(private val context: Context, private val pdf: File) {
    private val cache = AtomicFile(File(File(context.filesDir, "instruction_text").apply { mkdirs() }, "${pdf.nameWithoutExtension}.json"))
    fun read(): Map<Int, String> = runCatching {
        val json = JSONObject(cache.openRead().bufferedReader().use { it.readText() })
        json.keys().asSequence().associate { it.toInt() to json.getString(it) }
    }.getOrDefault(emptyMap())

    private fun save(pages: Map<Int, String>) {
        val json = JSONObject(); pages.forEach { (page, text) -> json.put(page.toString(), text) }
        val output = cache.startWrite()
        try { output.write(json.toString().toByteArray(Charsets.UTF_8)); cache.finishWrite(output) }
        catch (e: Exception) { cache.failWrite(output); throw e }
    }

    suspend fun recognize(indices: List<Int>, progress: suspend (Int, String?) -> Unit) {
        val data = File(context.filesDir, "ocr")
        synchronized(Documents::class.java) {
            val models = File(data, "tessdata").apply { mkdirs() }
            listOf("rus", "eng").forEach { lang ->
                val target = File(models, "$lang.traineddata")
                if (!target.exists()) context.assets.open("tessdata/$lang.traineddata").use { input -> target.outputStream().use { input.copyTo(it) } }
            }
        }
        val results = read().toMutableMap()
        val tess = TessBaseAPI()
        try {
            check(tess.init(data.absolutePath, "rus+eng", TessBaseAPI.OEM_LSTM_ONLY)) { "Не удалось запустить распознавание" }
            tess.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
            ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { renderer ->
                for (index in indices) {
                    currentCoroutineContext().ensureActive()
                    progress(index, null)
                    val text = renderer.openPage(index).use { page ->
                        val scale = 2400f / maxOf(page.width, page.height)
                        val image = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                        try {
                            image.eraseColor(Color.WHITE)
                            page.render(image, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            tess.setImage(image)
                            (tess.utF8Text ?: "").trim().take(100000)
                        } finally { tess.clear(); image.recycle() }
                    }
                    currentCoroutineContext().ensureActive()
                    results[index] = text
                    save(results)
                    progress(index, text)
                }
            } }
        } finally { tess.recycle() }
    }
}
