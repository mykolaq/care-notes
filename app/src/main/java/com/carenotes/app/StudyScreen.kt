package com.carenotes.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import kotlin.math.abs

@Composable internal fun StudySection(context: Context, canEdit: Boolean, family: () -> Unit, security: () -> Unit) {
    val storage = remember(context) { StudyStore(context) }
    var studies by remember { mutableStateOf(storage.all()) }
    var query by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf("") }
    var form by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Study?>(null) }
    var openedId by rememberSaveable { mutableStateOf<String?>(null) }
    var trend by remember { mutableStateOf<MeasurementKey?>(null) }
    var trendsOpen by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    DisposableEffect(context) {
        val prefs = context.getSharedPreferences("care_notes", Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key == "studies") studies = storage.all() }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val series = remember(studies) { studySeries(studies) }
    val shown = studies.filter { (type.isEmpty() || it.type == type) && (query.isBlank() || listOf(it.title, it.facility, it.notes, it.date.format(dateFormat), it.measurements.joinToString { m -> m.name }).any { field -> field.contains(query.trim(), ignoreCase = true) }) }.sortedByDescending { it.date }
    LazyColumn(Modifier.widthIn(max = 760.dp).fillMaxSize(), contentPadding = PaddingValues(LocalContentPadding.current), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("МОЁ ЗДОРОВЬЕ", Modifier.weight(1f), color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                IconButton(onClick = family) { Icon(Icons.Outlined.People, "Семейный доступ") }
                IconButton(onClick = security) { Icon(Icons.Outlined.Lock, "Защита входа") }
            }
            Text("Исследования", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text("Анализы, снимки и результаты в одном месте", Modifier.padding(top = 6.dp))
        }
        if (canEdit) item { Button(onClick = { editing = null; form = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text("Добавить исследование") } }
        item { OutlinedButton(onClick = { trend = null; trendsOpen = true }, enabled = series.isNotEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Icon(Icons.Outlined.ShowChart, null); Spacer(Modifier.width(8.dp)); Text("Динамика показателей") } }
        item { Field("Найти исследование или показатель", query, { query = it }) }
        item { ChoiceField("Тип исследования", type, studyTypes) { type = it } }
        if (error.isNotBlank()) item { Text(error, color = MaterialTheme.colorScheme.error) }
        if (shown.isEmpty()) item { Text(if (studies.isEmpty()) "Добавьте результат из лаборатории или клиники: PDF, фотографию или запись с показателями." else "По этому запросу исследований нет.", Modifier.padding(vertical = 20.dp)) }
        items(shown, key = { it.id }) { study ->
            Card(onClick = { openedId = study.id }, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${study.date.format(dateFormat)} · ${study.type}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text(study.title, style = MaterialTheme.typography.titleLarge)
                    if (study.facility.isNotBlank()) Text(study.facility)
                    if (study.measurements.isNotEmpty()) Text("Показателей: ${study.measurements.size}")
                    Text("Открыть результат →", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
    if (form && canEdit) StudyForm(context, editing, { form = false }) { draft ->
        try {
            val latest = storage.all().firstOrNull { it.id == draft.id }
            check(editing == null || latest != null) { "Исследование удалено на другом устройстве" }
            storage.save(draft.copy(measurements = latest?.measurements.orEmpty()))
            form = false; openedId = draft.id
        } catch (e: Exception) { error = e.message ?: "Не удалось сохранить исследование"; form = false }
    }
    if (trendsOpen && LocalUnlocked.current) StudyTrends(series, trend, { trend = it }, { trendsOpen = false }) { id -> trendsOpen = false; openedId = id }
    studies.firstOrNull { it.id == openedId }?.let { study ->
        if (LocalUnlocked.current) StudyDetails(context, study, canEdit, { openedId = null }, { editing = study; openedId = null; form = true }, { storage.delete(study.id); openedId = null }, { m -> openedId = null; trend = m.key(); trendsOpen = true })
    }
}

@Composable private fun StudyForm(context: Context, existing: Study?, dismiss: () -> Unit, save: (Study) -> Unit) {
    var title by rememberSaveable { mutableStateOf(existing?.title ?: "") }
    var type by rememberSaveable { mutableStateOf(existing?.type ?: studyTypes.first()) }
    var date by remember { mutableStateOf(existing?.date ?: LocalDate.now()) }
    var facility by rememberSaveable { mutableStateOf(existing?.facility ?: "") }
    var notes by rememberSaveable { mutableStateOf(existing?.notes ?: "") }
    FormDialog(if (existing == null) "Новое исследование" else "Изменить исследование", dismiss) {
        Field("Название, например биохимия крови", title, { title = it.take(500) })
        ChoiceField("Тип исследования", type, studyTypes, allowEmpty = false) { type = it }
        DateField(context, "Дата исследования", date) { date = it }
        Field("Лаборатория или клиника", facility, { facility = it.take(500) })
        Field("Заметки", notes, { notes = it.take(50000) }, singleLine = false)
        Text("После сохранения прикрепите PDF или фото и нажмите «Распознать показатели».", style = MaterialTheme.typography.bodyMedium)
        Button(onClick = { save(Study(id = existing?.id ?: java.util.UUID.randomUUID().toString(), title = title.trim(), date = date, type = type, facility = facility.trim(), notes = notes.trim())) }, enabled = title.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Сохранить") }
        TextButton(onClick = dismiss, modifier = Modifier.fillMaxWidth()) { Text("Отмена") }
    }
}

private data class MeasurementSuggestion(val name: String, val unit: String, val specimen: String = "Кровь")
private val measurementSuggestions = listOf(MeasurementSuggestion("Креатинин", "мкмоль/л"), MeasurementSuggestion("Гемоглобин", "г/л"), MeasurementSuggestion("Лейкоциты", "10^9/л"), MeasurementSuggestion("Тромбоциты", "10^9/л"), MeasurementSuggestion("АЛТ", "Ед/л"), MeasurementSuggestion("АСТ", "Ед/л"), MeasurementSuggestion("Глюкоза", "ммоль/л"), MeasurementSuggestion("С-реактивный белок", "мг/л"))

@Composable private fun MeasurementForm(existing: StudyMeasurement?, dismiss: () -> Unit, save: (StudyMeasurement) -> Unit) {
    var name by rememberSaveable { mutableStateOf(existing?.name ?: "") }
    var value by rememberSaveable { mutableStateOf(existing?.value ?: "") }
    var unit by rememberSaveable { mutableStateOf(existing?.unit ?: "") }
    var specimen by rememberSaveable { mutableStateOf(existing?.specimen ?: "Кровь") }
    FormDialog(if (existing == null) "Новый показатель" else "Изменить показатель", dismiss) {
        if (existing == null) ChoiceField("Быстро заполнить", "", measurementSuggestions.map { it.name }, allowEmpty = false) { selected ->
            measurementSuggestions.first { it.name == selected }.let { name = it.name; unit = it.unit; specimen = it.specimen }
        }
        Field("Название показателя", name, { name = it.take(200) })
        OutlinedTextField(value, { value = it.take(32) }, label = { Text("Значение, например 80,5") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(), isError = value.isNotBlank() && measurementNumber(value) == null)
        if (value.isNotBlank() && measurementNumber(value) == null) Text("Для графика введите число. Результаты «<5», «положительно» и другие текстовые значения сохраните в заметках.", color = MaterialTheme.colorScheme.error)
        Field("Единица измерения из результата", unit, { unit = it.take(80) })
        ChoiceField("Материал: быстрый выбор", specimen, listOf("Кровь", "Сыворотка крови", "Плазма крови", "Моча", "Другое"), allowEmpty = false) { specimen = if (it == "Другое") "" else it }
        Field("Материал анализа", specimen, { specimen = it.take(200) })
        Text("Перепишите значение, единицу и материал из бланка. Подсказки можно изменить. Разные единицы и материалы отображаются на разных графиках.", style = MaterialTheme.typography.bodyMedium)
        Button(onClick = { save(StudyMeasurement(existing?.id ?: java.util.UUID.randomUUID().toString(), name.trim(), value.trim(), unit.trim(), specimen.trim())) }, enabled = name.isNotBlank() && measurementNumber(value) != null && unit.isNotBlank() && specimen.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Сохранить показатель") }
        TextButton(onClick = dismiss, modifier = Modifier.fillMaxWidth()) { Text("Отмена") }
    }
}

@Composable private fun StudyDetails(context: Context, study: Study, canEdit: Boolean, dismiss: () -> Unit, edit: () -> Unit, delete: () -> Unit, trend: (StudyMeasurement) -> Unit) {
    val storage = remember(context) { StudyStore(context) }
    val documents = remember(context) { Documents(context) }
    var files by remember { mutableStateOf(documents.all().filter { it.studyId == study.id }) }
    var measurementForm by remember { mutableStateOf(false) }
    var measurementEdit by remember { mutableStateOf<StudyMeasurement?>(null) }
    var deleteMeasurement by remember { mutableStateOf<StudyMeasurement?>(null) }
    var deleteDocument by remember { mutableStateOf<VisitDocument?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var openedFile by remember { mutableStateOf<VisitDocument?>(null) }
    var camera by remember { mutableStateOf<File?>(null) }
    var recognition by remember { mutableStateOf<StudyRecognition?>(null) }
    var recognitionDocument by remember { mutableStateOf<VisitDocument?>(null) }
    var recognitionJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var recognitionProgress by remember { mutableStateOf("") }
    val detailsState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    DisposableEffect(study.id) {
        val prefs = context.getSharedPreferences("care_documents", Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> files = documents.all().filter { it.studyId == study.id } }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    fun attach(uri: android.net.Uri, temp: File? = null) {
        busy = true; error = ""
        scope.launch {
            try { withContext(Dispatchers.IO) { documents.add(uri, studyId = study.id) } }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Не удалось прикрепить документ" }
            finally { temp?.delete(); busy = false }
        }
    }
    fun recognize(document: VisitDocument) {
        busy = true; error = ""; recognitionDocument = document
        recognitionJob = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { readStudyDocument(context, document) { message -> withContext(Dispatchers.Main) { recognitionProgress = message } } }
                synchronized(FamilySync.localGuard) {
                    check(FamilySync(context).editable) { "Доступ только для просмотра" }
                    if (document.mime != "application/pdf") documents.updateText(document, result.text)
                }
                recognition = result
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Не удалось распознать документ" }
            finally { busy = false; recognitionProgress = ""; recognitionJob = null }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) attach(uri) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success -> val f = camera; camera = null; if (success && f != null) attach(documents.cameraUri(f), f) else f?.delete() }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding(), color = MaterialTheme.colorScheme.background) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Результат исследования", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = dismiss) { Icon(Icons.Outlined.Close, "Закрыть исследование") }
                }
                LazyColumn(Modifier.weight(1f), state = detailsState, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    item { Text(study.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("${study.date.format(dateFormat)} · ${study.type}", color = MaterialTheme.colorScheme.primary); if (study.facility.isNotBlank()) Text(study.facility); if (study.notes.isNotBlank()) Text(study.notes, Modifier.padding(top = 10.dp))
                        TextButton(onClick = { scope.launch { detailsState.animateScrollToItem(2 + study.measurements.size + (if (study.measurements.isEmpty()) 1 else 0) + (if (canEdit) 1 else 0)) } }) { Text("К документам (${files.size})") }
                    }
                    item { Text("Показатели", style = MaterialTheme.typography.titleLarge) }
                    if (study.measurements.isEmpty()) item { Text("Прикрепите бланк ниже и нажмите «Распознать показатели», чтобы видеть динамику результатов.") }
                    items(study.measurements, key = { "value-${it.id}" }) { m ->
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(m.name, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                                Text("${m.value} ${m.unit}", color = MaterialTheme.colorScheme.primary, fontSize = 22.sp)
                                Text(m.specimen)
                                TextButton(onClick = { trend(m) }) { Icon(Icons.Outlined.ShowChart, null); Spacer(Modifier.width(8.dp)); Text("Посмотреть динамику") }
                                if (canEdit) AdaptiveActions {
                                    TextButton(onClick = { measurementEdit = m; measurementForm = true }) { Text("Изменить") }
                                    TextButton(onClick = { deleteMeasurement = m }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
                                }
                            }
                        }
                    }
                    if (canEdit) item { OutlinedButton(onClick = { measurementEdit = null; measurementForm = true }, enabled = study.measurements.size < 500, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text("Добавить показатель") } }
                    item { Text("Документы", style = MaterialTheme.typography.titleLarge); Text("PDF и фотографии сохраняются на телефоне. Один файл — до 25 МБ.", style = MaterialTheme.typography.bodyMedium) }
                    items(files, key = { "file-${it.id}" }) { d ->
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                            Column(Modifier.padding(16.dp)) {
                                Text(d.name, fontWeight = FontWeight.Bold)
                                AdaptiveActions {
                                    TextButton(onClick = { openedFile = d }) { Text("Открыть документ") }
                                    TextButton(onClick = { openIntent(context, Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = d.mime; putExtra(Intent.EXTRA_STREAM, documents.uri(d)); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, "Поделиться результатом")) }) { Text("Поделиться") }
                                }
                                if (canEdit) OutlinedButton(onClick = { recognize(d) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Распознать показатели") }
                                if (canEdit) TextButton(onClick = { deleteDocument = d }, enabled = !busy) { Text("Удалить файл", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                    if (canEdit) item {
                        OutlinedButton(onClick = { picker.launch(arrayOf("application/pdf", "image/*")) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.AttachFile, null); Text("Прикрепить PDF или фото") }
                        OutlinedButton(onClick = { val f = documents.cameraFile(); camera = f; try { takePhoto.launch(documents.cameraUri(f)) } catch (_: Exception) { f.delete(); camera = null; error = "Не удалось открыть камеру" } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.PhotoCamera, null); Spacer(Modifier.width(8.dp)); Text("Сфотографировать") }
                    }
                    if (busy) item {
                        LinearProgressIndicator(Modifier.fillMaxWidth()); Text(recognitionProgress.ifBlank { "Сохраняю документ…" })
                        if (recognitionJob != null) TextButton(onClick = { recognitionJob?.cancel() }) { Text("Остановить распознавание") }
                    }
                    if (error.isNotBlank()) item { Text(error, color = MaterialTheme.colorScheme.error) }
                    if (canEdit) item {
                        OutlinedButton(onClick = edit, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Изменить исследование") }
                        TextButton(onClick = { deleting = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Удалить исследование", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
    if (measurementForm && canEdit) MeasurementForm(measurementEdit, { measurementForm = false }) { next ->
        try { storage.updateMeasurements(study.id) { values -> values.filterNot { it.id == next.id } + next }; measurementForm = false }
        catch (e: Exception) { error = e.message ?: "Не удалось сохранить показатель"; measurementForm = false }
    }
    deleteMeasurement?.takeIf { canEdit }?.let { m -> ConfirmDelete(m.name, { deleteMeasurement = null }) { storage.updateMeasurements(study.id) { it.filterNot { item -> item.id == m.id } }; deleteMeasurement = null } }
    deleteDocument?.takeIf { canEdit }?.let { d -> ConfirmDelete(d.name, { deleteDocument = null }) { documents.delete(d); deleteDocument = null } }
    if (deleting && canEdit) ConfirmDelete("${study.title} — вместе с показателями и файлами", { deleting = false }, delete)
    recognition?.takeIf { canEdit }?.let { result ->
        StudyRecognitionReview(result, { recognition = null }, { openedFile = recognitionDocument }) { values ->
            try {
                storage.updateMeasurements(study.id) { current ->
                    mergeRecognizedMeasurements(current, values)
                }
                recognition = null
            } catch (e: Exception) { error = e.message ?: "Не удалось сохранить показатели"; recognition = null }
        }
    }
    openedFile?.takeIf { LocalUnlocked.current }?.let { d ->
        if (d.mime == "application/pdf") InstructionReader(documents.file(d), d.name) { openedFile = null }
        else StudyPhoto(documents.file(d), d.name) { openedFile = null }
    }
}

@Composable private fun StudyPhoto(file: File, name: String, dismiss: () -> Unit) {
    var bitmap by remember(file) { mutableStateOf<Bitmap?>(null) }
    var error by remember(file) { mutableStateOf(false) }
    var zoom by rememberSaveable { mutableIntStateOf(100) }
    LaunchedEffect(file) {
        try {
            bitmap = withContext(Dispatchers.IO) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val scale = minOf(1f, 2400f / maxOf(info.size.width, info.size.height))
                    decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { error = true }
    }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) { Text(name, Modifier.weight(1f).padding(16.dp)); IconButton(onClick = dismiss) { Icon(Icons.Outlined.Close, "Закрыть фото") } }
                Row(verticalAlignment = Alignment.CenterVertically) { TextButton(onClick = { zoom = (zoom - 10).coerceAtLeast(100) }) { Text("−") }; Text("$zoom%"); TextButton(onClick = { zoom = (zoom + 10).coerceAtMost(300) }) { Text("+") } }
                if (error) Text("Не удалось открыть изображение", Modifier.padding(20.dp))
                bitmap?.let { b -> BoxWithConstraints(Modifier.fillMaxSize()) {
                    val width = maxWidth * zoom / 100
                    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
                        Image(b.asImageBitmap(), name, Modifier.width(width).height(width * b.height / b.width))
                    }
                } }
            }
        }
    }
}

@Composable private fun StudyTrends(series: Map<MeasurementKey, List<StudyPoint>>, selected: MeasurementKey?, select: (MeasurementKey) -> Unit, dismiss: () -> Unit, open: (String) -> Unit) {
    val keys = series.keys.sortedWith(compareBy({ it.name }, { it.specimen }, { it.unit }))
    val key = selected?.takeIf { it in series } ?: keys.firstOrNull()
    val points = series[key].orEmpty()
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding(), color = MaterialTheme.colorScheme.background) {
            Column {
                Row(Modifier.padding(start = 20.dp), verticalAlignment = Alignment.CenterVertically) { Text("Динамика показателей", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge); IconButton(onClick = dismiss) { Icon(Icons.Outlined.Close, "Закрыть динамику") } }
                LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    item {
                        if (keys.isEmpty()) Text("Пока нет показателей")
                        else {
                            var expanded by remember { mutableStateOf(false) }
                            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text(points.firstOrNull()?.measurement?.let { "${it.name} · ${it.specimen} · ${it.unit}" } ?: "Выбрать показатель") }
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                keys.forEach { k -> val m = series.getValue(k).first().measurement; DropdownMenuItem(text = { Text("${m.name} · ${m.specimen} · ${m.unit}") }, onClick = { select(k); expanded = false }) }
                            }
                        }
                    }
                    if (points.isNotEmpty()) {
                        item { Text(points.first().measurement.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("${points.first().measurement.specimen} · ${points.first().measurement.unit}") }
                        item { StudyChart(points, open) }
                        item { Text("Нажмите на точку или строку, чтобы открыть исходное исследование. Значения с другими единицами или материалом находятся на отдельных графиках.", style = MaterialTheme.typography.bodyMedium) }
                        items(points.asReversed(), key = { "${it.studyId}/${it.measurementId}" }) { point ->
                            Card(onClick = { open(point.studyId) }, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("${point.measurement.value} ${point.measurement.unit}", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                    Text(point.date.format(dateFormat), fontWeight = FontWeight.Bold)
                                    Text(point.studyTitle)
                                    Text("Открыть исследование →", color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun StudyChart(points: List<StudyPoint>, open: (String) -> Unit) {
    val values = points.map { it.number.toDouble() }
    val min = values.min(); val max = values.max()
    val padding = if (max == min) maxOf(abs(max) * .1, 1.0) else (max - min) * .12
    val low = min - padding; val high = max + padding
    val color = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Макс.: ${points[values.indexOf(max)].measurement.value}"); Text("Мин.: ${points[values.indexOf(min)].measurement.value}") }
        fun positions(width: Float, height: Float): List<Offset> = points.mapIndexed { index, p -> Offset(24f + studyPointX(p.date, points.first().date, points.last().date) * (width - 48f), 24f + ((high - values[index]) / (high - low)).toFloat() * (height - 48f)) }
        Canvas(Modifier.fillMaxWidth().height(220.dp).semantics { contentDescription = "График ${points.first().measurement.name}, точек: ${points.size}. Все значения доступны в списке ниже." }.pointerInput(points) {
            detectTapGestures { tap ->
                val locations = positions(size.width.toFloat(), size.height.toFloat())
                val nearest = locations.indices.minBy { (locations[it] - tap).getDistance() }
                if ((locations[nearest] - tap).getDistance() <= 32.dp.toPx()) open(points[nearest].studyId)
            }
        }) {
            repeat(3) { i -> val y = 24f + (size.height - 48f) * i / 2; drawLine(grid, Offset(24f, y), Offset(size.width - 24f, y), 1.dp.toPx()) }
            val locations = positions(size.width, size.height)
            locations.zipWithNext().forEach { (a, b) -> drawLine(color, a, b, 2.dp.toPx()) }
            locations.forEach { drawCircle(Color.White, 7.dp.toPx(), it); drawCircle(color, 5.dp.toPx(), it) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(points.first().date.format(dateFormat), style = MaterialTheme.typography.bodySmall); if (points.first().date != points.last().date) Text(points.last().date.format(dateFormat), style = MaterialTheme.typography.bodySmall) }
        if (points.size == 1) Text("Пока одно значение. Следующее исследование добавит точку на график.", style = MaterialTheme.typography.bodyMedium)
    }
}
