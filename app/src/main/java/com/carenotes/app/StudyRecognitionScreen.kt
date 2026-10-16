package com.carenotes.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable internal fun StudyRecognitionReview(result: StudyRecognition, dismiss: () -> Unit, original: () -> Unit, save: (List<StudyMeasurement>) -> Unit) {
    var drafts by remember(result) { mutableStateOf(recognizeMeasurements(result.text)) }
    var chosen by remember(result) { mutableStateOf(drafts.map { it.measurement.id }.toSet()) }
    var textEditor by remember { mutableStateOf(false) }
    var text by remember(result) { mutableStateOf(result.text) }
    var commonSpecimen by remember(result) { mutableStateOf(drafts.firstOrNull()?.measurement?.specimen.orEmpty()) }
    val selected = drafts.filter { it.measurement.id in chosen }.map { it.measurement }
    val valid = selected.isNotEmpty() && selected.all { it.name.isNotBlank() && it.unit.isNotBlank() && it.specimen.isNotBlank() && measurementNumber(it.value) != null }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding(), color = MaterialTheme.colorScheme.background) {
            Column {
                Row(Modifier.padding(start = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Проверка показателей", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = dismiss) { Icon(Icons.Outlined.Close, "Закрыть распознавание") }
                }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    item {
                        Text(if (result.fromImage) "Текст распознан из изображения. Возможны ошибки в названиях, числах и единицах." else "Текст получен из PDF. При разборе таблицы возможны ошибки в колонках.")
                        Text("Сверьте каждый результат с оригиналом, особенно значение и единицу. Референсные интервалы не добавляются на график. Ничего не сохранено автоматически.")
                        OutlinedButton(onClick = original, modifier = Modifier.fillMaxWidth()) { Text("Сверить с оригиналом") }
                        TextButton(onClick = { textEditor = true }) { Text("Посмотреть и исправить текст") }
                    }
                    item {
                        ChoiceField("Материал для всех строк", commonSpecimen, listOf("Кровь", "Сыворотка крови", "Плазма крови", "Моча"), allowEmpty = false) { next -> commonSpecimen = next; drafts = drafts.map { it.copy(measurement = it.measurement.copy(specimen = next)) } }
                        Text("Материал не найден? Выберите его из бланка. Для отдельной строки его можно изменить ниже.", style = MaterialTheme.typography.bodySmall)
                    }
                    if (drafts.isEmpty()) item { Text("Числовые показатели с единицами не найдены. Можно исправить распознанный текст и повторить разбор или добавить показатели вручную.") }
                    itemsIndexed(drafts, key = { _, d -> d.measurement.id }) { index, draft ->
                        val m = draft.measurement
                        fun change(next: StudyMeasurement) { drafts = drafts.toMutableList().also { it[index] = draft.copy(measurement = next) } }
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                CheckRow("Добавить показатель ${index + 1}", m.id in chosen) { checked -> chosen = if (checked) chosen + m.id else chosen - m.id }
                                Text("Из документа: ${draft.source}", style = MaterialTheme.typography.bodySmall)
                                Field("Название показателя", m.name, { change(m.copy(name = it.take(200))) })
                                Field("Значение", m.value, { change(m.copy(value = it.take(32))) })
                                if (measurementNumber(m.value) == null) Text("Нужно число; текстовый результат сохраните в заметки", color = MaterialTheme.colorScheme.error)
                                Field("Единица измерения", m.unit, { change(m.copy(unit = it.take(80))) })
                                Field("Материал анализа", m.specimen, { change(m.copy(specimen = it.take(200))) })
                            }
                        }
                    }
                }
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    if (selected.any { it.specimen.isBlank() }) Text("Укажите материал анализа для выбранных строк", color = MaterialTheme.colorScheme.error)
                    Button(onClick = { save(selected) }, enabled = valid, modifier = Modifier.fillMaxWidth()) { Text("Добавить выбранные (${selected.size})") }
                }
            }
        }
    }
    if (textEditor) FormDialog("Текст документа", { textEditor = false }) {
        Field("Распознанный текст", text, { text = it.take(50000) }, singleLine = false)
        Text("Разбор ищет строки вида «Креатинин 90 мкмоль/л». Сверьте исправления с оригиналом.")
        Button(onClick = { drafts = recognizeMeasurements(text); chosen = drafts.map { it.measurement.id }.toSet(); commonSpecimen = drafts.firstOrNull()?.measurement?.specimen.orEmpty(); textEditor = false }, modifier = Modifier.fillMaxWidth()) { Text("Повторить разбор") }
    }
}
