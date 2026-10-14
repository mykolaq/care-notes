package com.carenotes.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

internal fun validMedicinePhotoName(name: String): Boolean = name.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\\.jpg"))

internal class MedicinePhotos(private val context: Context) {
    private val dir get() = File(context.filesDir, "photos").apply { mkdirs() }
    fun file(name: String): File { require(validMedicinePhotoName(name)); return File(dir, name) }
    fun remove(name: String) { if (validMedicinePhotoName(name)) file(name).delete() }
    fun import(uri: Uri): String {
        val temp = File.createTempFile("medicine-photo-", ".tmp", context.cacheDir)
        val name = "${UUID.randomUUID()}.jpg"
        val output = file(name)
        try {
            (context.contentResolver.openInputStream(uri) ?: error("Не удалось открыть фото")).use { input ->
                temp.outputStream().use { out ->
                    val buffer = ByteArray(8192); var total = 0
                    while (true) { val n = input.read(buffer); if (n < 0) break; total += n; require(total <= 25 * 1024 * 1024) { "Фото больше 25 МБ" }; out.write(buffer, 0, n) }
                }
            }
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(temp)) { decoder, info, _ ->
                val w = info.size.width; val h = info.size.height
                require(w > 0 && h > 0 && w.toLong() * h <= 80_000_000) { "Изображение слишком большое" }
                val scale = minOf(1f, 1200f / maxOf(w, h))
                decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            try { output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it)) } }
            finally { bitmap.recycle() }
            validate(output)
            return name
        } catch (e: Exception) { output.delete(); throw e }
        finally { temp.delete() }
    }
    fun validate(file: File) {
        require(file.isFile && file.length() in 1..4L * 1024 * 1024) { "Некорректное фото лекарства" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outMimeType == "image/jpeg" && bounds.outWidth in 1..1200 && bounds.outHeight in 1..1200) { "Некорректный формат фото лекарства" }
    }
    fun bitmap(name: String): Bitmap? = if (validMedicinePhotoName(name)) {
        val file = file(name)
        if (!file.exists()) null else { validate(file); BitmapFactory.decodeFile(file.path) }
    } else null
}

@Composable internal fun MedicinePhoto(context: Context, name: String, modifier: Modifier = Modifier) {
    var bitmap by remember(name) { mutableStateOf<Bitmap?>(null) }
    var enlarged by remember { mutableStateOf(false) }
    var error by remember(name) { mutableStateOf(false) }
    LaunchedEffect(name) { bitmap = withContext(Dispatchers.IO) { runCatching { MedicinePhotos(context).bitmap(name) }.getOrNull() }; error = bitmap == null }
    bitmap?.let { b ->
        Image(b.asImageBitmap(), "Фото упаковки — нажмите для увеличения", modifier.clickable { enlarged = true }, contentScale = ContentScale.Fit)
        if (enlarged) Dialog(onDismissRequest = { enlarged = false }) {
            Surface { Column(Modifier.padding(16.dp)) {
                Image(b.asImageBitmap(), "Фото упаковки", Modifier.fillMaxWidth().heightIn(max = 500.dp), contentScale = ContentScale.Fit)
                TextButton(onClick = { enlarged = false }) { Text("Закрыть") }
            } }
        }
    }
    if (error) Text("Фото недоступно", style = MaterialTheme.typography.bodySmall)
}
