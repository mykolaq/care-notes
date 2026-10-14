package com.carenotes.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun DocumentScreen(context: Context, visit: Visit, settings: MedicineSettings, dismiss: () -> Unit, readOnly: Boolean = false, add: (Prescription) -> Unit) {
    val storage = remember { Documents(context) }
    val scope = rememberCoroutineScope()
    var documents by remember { mutableStateOf(storage.all().filter { it.visitId == visit.id }) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<VisitDocument?>(null) }
    var delete by remember { mutableStateOf<VisitDocument?>(null) }
    var drafts by remember { mutableStateOf<List<Prescription>>(emptyList()) }
    var camera by remember { mutableStateOf<File?>(null) }
    fun refresh() { documents = storage.all().filter { it.visitId == visit.id } }
    fun import(uri: Uri) {
        busy = true; error = ""
        scope.launch {
            try { withContext(Dispatchers.IO) { storage.add(uri, visit.id) }; refresh() }
            catch(e: Exception) { error = e.message ?: "Не удалось прикрепить документ." }
            finally { busy = false; camera?.delete(); camera = null }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) import(uri) }
    val capture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val file = camera
        if (success && file != null) import(storage.cameraUri(file)) else { file?.delete(); camera = null }
    }
    fun recognize(d: VisitDocument) {
        busy = true; error = ""
        scope.launch {
            try {
                val text = withContext(Dispatchers.IO) { storage.recognize(d) }
                if (text.isBlank()) error = "Текст не найден. Попробуйте более чёткий снимок или введите текст вручную."
                selected = storage.updateText(d, text); refresh()
            } catch(e: Exception) { error = "Не удалось распознать: ${e.message ?: "проверьте файл"}" }
            finally { busy = false }
        }
    }
    if (selected == null && drafts.isEmpty()) FormDialog("Документы посещения", { if (!busy) dismiss() }) {
        Text("${visit.doctor} · ${visit.dateTime.toLocalDate().format(dateFormat)}")
        Text("Фото и PDF хранятся на телефоне. Распознавание бесплатно и работает без интернета.")
        if (!readOnly) Button(onClick = { picker.launch(arrayOf("image/*", "application/pdf")) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Прикрепить фото или PDF") }
        if (!readOnly) OutlinedButton(onClick = {
            val file = storage.cameraFile(); camera = file
            try { capture.launch(storage.cameraUri(file)) } catch (_: android.content.ActivityNotFoundException) { file.delete(); camera = null; error = "Камера недоступна. Прикрепите готовое фото." }
        }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Сфотографировать документ") }
        Text("Лучше всего распознаётся печатный текст. Рукописные назначения проверяйте по оригиналу. PDF — до 10 страниц, файл — до 25 МБ.")
        if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Обрабатываю документ…") }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        if (documents.isEmpty()) Text("Пока нет документов")
        documents.forEach { d ->
            HorizontalDivider()
            Text(d.name, style = MaterialTheme.typography.titleMedium)
            TextButton(enabled = !busy, onClick = { openIntent(context, Intent(Intent.ACTION_VIEW).setDataAndType(storage.uri(d), d.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }) { Text("Открыть оригинал") }
            Button(enabled = !busy && (!readOnly || d.text.isNotBlank()), onClick = { if (d.text.isBlank()) recognize(d) else selected = d }, modifier = Modifier.fillMaxWidth()) { Text(if(d.text.isBlank()) "Распознать назначения" else "Посмотреть распознанный текст") }
            OutlinedButton(enabled = !busy, onClick = { openIntent(context, Intent.createChooser(Intent(Intent.ACTION_SEND).setType(d.mime).putExtra(Intent.EXTRA_STREAM, storage.uri(d)).putExtra(Intent.EXTRA_TEXT, documentAlicePrompt("")).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Передать документ")) }, modifier = Modifier.fillMaxWidth()) { Text("Поделиться документом") }
            if (!readOnly) TextButton(enabled = !busy, onClick = { selected = d }) { Text("Ввести текст / открыть Алису") }
            if (!readOnly) TextButton(enabled = !busy, onClick = { delete = d }) { Text("Удалить документ", color = MaterialTheme.colorScheme.error) }
        }
        TextButton(enabled = !busy, onClick = dismiss, modifier = Modifier.fillMaxWidth()) { Text("Закрыть") }
    }
    selected?.let { d ->
        var text by remember(d.id) { mutableStateOf(d.text) }
        var chosen by remember(d.id, text) { mutableStateOf<Set<Int>>(emptySet()) }
        val lines = text.lines().map(String::trim).filter(String::isNotBlank)
        if (readOnly) FormDialog("Текст документа", { selected = null }) {
            Text(d.text)
            TextButton(onClick = { selected = null }) { Text("Закрыть") }
        } else FormDialog("Проверка распознавания", { storage.updateText(d, text); selected = null; refresh() }) {
            Text("Сверьте текст с оригиналом и исправьте ошибки. Затем отметьте строки с лекарствами. Следующий шаг — проверка каждого назначения.")
            OutlinedButton(onClick = { openIntent(context, Intent(Intent.ACTION_VIEW).setDataAndType(storage.uri(d), d.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }, modifier = Modifier.fillMaxWidth()) { Text("Открыть оригинал") }
            Field("Текст документа", text, { text = it.take(50000) }, false)
            Text("Отметьте только назначения лекарств", style = MaterialTheme.typography.titleMedium)
            lines.forEachIndexed { i, line -> CheckRow(line, i in chosen) { checked -> chosen = if(checked) chosen + i else chosen - i } }
            Button(enabled = chosen.isNotEmpty(), onClick = {
                storage.updateText(d, text); refresh()
                drafts = chosen.sorted().map { documentDraft(lines[it], visit, d.name) }; selected = null
            }, modifier = Modifier.fillMaxWidth()) { Text("Проверить назначения (${chosen.size})") }
            OutlinedButton(onClick = {
                storage.updateText(d, text)
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Разбор назначений", documentAlicePrompt(text)))
                openIntent(context, Intent(Intent.ACTION_VIEW, Uri.parse("https://alice.yandex.ru/")))
            }, modifier = Modifier.fillMaxWidth()) { Text("Скопировать запрос и открыть Алису") }
            Text("В Алисе вставьте запрос. Если нужно, прикрепите оригинал вручную. Этот вариант требует интернета и может потребовать входа в Яндекс. При отправке сведения получит Яндекс; приложение не использует платный API.")
            TextButton(onClick = { storage.updateText(d, text); selected = null; refresh() }, modifier = Modifier.fillMaxWidth()) { Text("Сохранить текст и вернуться") }
        }
    }
    if (drafts.isNotEmpty()) key(drafts.first().id) {
        MedicineForm(context, drafts.first(), settings, { drafts = drafts.drop(1) }) { p -> add(p); drafts = drafts.drop(1) }
    }
    delete?.let { d -> ConfirmDelete(d.name, { delete = null }) { storage.delete(d); delete = null; refresh() } }
}
