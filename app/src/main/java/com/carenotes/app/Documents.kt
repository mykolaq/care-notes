package com.carenotes.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.googlecode.tesseract.android.TessBaseAPI
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class Documents(private val context: Context) {
    private val prefs = context.getSharedPreferences("care_documents", Context.MODE_PRIVATE)
    private val directory = File(context.filesDir, "documents").apply { mkdirs() }
    fun all(): List<VisitDocument> {
        val a = JSONArray(prefs.getString("documents", "[]"))
        return (0 until a.length()).map { i -> a.getJSONObject(i).let { VisitDocument(it.getString("id"), it.getString("visitId"), it.getString("name"), it.getString("mime"), it.getString("fileName"), it.optString("text")) } }
    }
    private fun save(items: List<VisitDocument>) {
        val a = JSONArray()
        items.forEach { a.put(JSONObject().put("id", it.id).put("visitId", it.visitId).put("name", it.name).put("mime", it.mime).put("fileName", it.fileName).put("text", it.text)) }
        prefs.edit().putString("documents", a.toString()).commit(); FamilySync.async(context)
    }
    fun file(d: VisitDocument) = File(directory, d.fileName)
    fun uri(d: VisitDocument): Uri = FileProvider.getUriForFile(context, "${context.packageName}.documents", file(d))
    fun add(uri: Uri, visitId: String): VisitDocument {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: "image/jpeg"
        require(mime == "application/pdf" || mime.startsWith("image/")) { "Выберите фотографию или PDF." }
        var name = "Документ"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if(c.moveToFirst()) name = c.getString(0) ?: name }
        val id = UUID.randomUUID().toString()
        val d = VisitDocument(id, visitId, name.take(160), mime, "$id.${if (mime == "application/pdf") "pdf" else "image"}")
        try {
            resolver.openInputStream(uri)?.use { input -> file(d).outputStream().use { output ->
                val buffer = ByteArray(8192); var total = 0L
                while(true) { val count = input.read(buffer); if(count < 0) break; total += count; require(total <= 25L * 1024 * 1024) { "Документ больше 25 МБ. Выберите файл поменьше." }; output.write(buffer, 0, count) }
                require(total > 0) { "Файл пуст." }
            } } ?: error("Не удалось открыть файл.")
            synchronized(FamilySync.localGuard) { save(all() + d) }
            return d
        } catch (e: Exception) { file(d).delete(); throw e }
    }
    fun updateText(d: VisitDocument, text: String): VisitDocument = d.copy(text = text.take(50000)).also { next -> synchronized(FamilySync.localGuard) { save(all().map { if(it.id == d.id) next else it }) } }
    fun delete(d: VisitDocument) = synchronized(FamilySync.localGuard) { save(all().filterNot { it.id == d.id }); file(d).delete(); Unit }
    fun deleteVisit(id: String) { all().filter { it.visitId == id }.forEach(::delete) }
    fun cameraFile(): File = File(context.cacheDir, "camera").apply { mkdirs() }.let { File(it, "${UUID.randomUUID()}.jpg") }
    fun cameraUri(file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.documents", file)
    fun recognize(d: VisitDocument): String {
        val data = File(context.filesDir, "ocr")
        synchronized(Documents::class.java) {
            val models = File(data, "tessdata").apply { mkdirs() }
            listOf("rus", "eng").forEach { lang -> val target = File(models, "$lang.traineddata"); if (!target.exists()) context.assets.open("tessdata/$lang.traineddata").use { input -> target.outputStream().use { input.copyTo(it) } } }
        }
        val tess = TessBaseAPI()
        try {
            check(tess.init(data.absolutePath, "rus+eng", TessBaseAPI.OEM_LSTM_ONLY)) { "Не удалось запустить распознавание." }
            tess.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
            fun read(bitmap: Bitmap): String = try { tess.setImage(bitmap); tess.utF8Text ?: "" } finally { tess.clear(); bitmap.recycle() }
            val text = if (d.mime == "application/pdf") {
                ParcelFileDescriptor.open(file(d), ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    PdfRenderer(descriptor).use { pdf ->
                        require(pdf.pageCount <= 10) { "В PDF больше 10 страниц. Разделите документ на части." }
                        buildString { repeat(pdf.pageCount) { pageNumber -> pdf.openPage(pageNumber).use { page ->
                            val scale = 2000f / maxOf(page.width, page.height)
                            val bitmap = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                            bitmap.eraseColor(Color.WHITE); page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            appendLine(read(bitmap)); appendLine()
                        } } }
                    }
                }
            } else {
                val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file(d))) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val scale = minOf(1f, 2400f / maxOf(info.size.width, info.size.height))
                    decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
                }
                read(bitmap)
            }
            return text.trim().take(50000)
        } finally { tess.recycle() }
    }
}
