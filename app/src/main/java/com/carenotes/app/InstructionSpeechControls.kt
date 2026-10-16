package com.carenotes.app

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable internal fun rememberInstructionSpeech(file: File, pages: List<String>?): InstructionSpeech {
    val context = LocalContext.current
    val speech = remember(file) { InstructionSpeech(context, file.nameWithoutExtension) }
    val owner = LocalView.current.findViewTreeLifecycleOwner()
    LaunchedEffect(pages) {
        val text = pages ?: return@LaunchedEffect
        val digest = withContext(Dispatchers.Default) { instructionSpeechSignature(text) }
        speech.document(text, digest)
    }
    DisposableEffect(speech, owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) speech.pause() }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer); speech.close() }
    }
    return speech
}

@Composable internal fun InstructionSpeechControls(speech: InstructionSpeech, pages: List<String>?, from: InstructionLocation, selected: Boolean, hasOcr: Boolean) {
    var options by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        FilledTonalButton(onClick = {
            if (speech.playing) speech.pause()
            else if (speech.ready) speech.start(speech.checkpoint ?: from)
            else { options = true; speech.prepare() }
        }, enabled = pages != null && pages.any { it.isNotBlank() }) {
            Icon(if (speech.playing) Icons.Outlined.Pause else Icons.AutoMirrored.Outlined.VolumeUp, null)
            Spacer(Modifier.width(6.dp))
            Text(if (speech.playing) "Пауза" else if (speech.checkpoint != null) "Продолжить" else "Слушать")
        }
        if (speech.playing || speech.checkpoint != null) IconButton(onClick = { speech.stop() }) { Icon(Icons.Outlined.Stop, "Остановить чтение") }
        TextButton(onClick = { options = true; speech.prepare() }, modifier = Modifier.weight(1f)) { Text("Настройки чтения") }
    }
    if (speech.playing || speech.checkpoint != null) Text(speech.status, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
    if (options) FormDialog("Читать вслух", { options = false }) {
        Text(speech.status)
        if (speech.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (speech.engineName.isNotBlank()) Text(speech.engineName, style = MaterialTheme.typography.bodySmall)
        Text("Чтение работает без интернета после установки русского голоса. Для скачивания голоса интернет нужен один раз. При закрытии документа или сворачивании приложения — пауза.", style = MaterialTheme.typography.bodySmall)
        if (hasOcr) Text("Есть текст, распознанный из изображения. Голос прочитает и возможные ошибки распознавания — сверяйте названия и числа с PDF.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        if (pages.orEmpty().any { it.isBlank() }) Text("На страницах без текста чтение остановится. Для сканов сначала используйте «Распознать для поиска».", style = MaterialTheme.typography.bodySmall)
        Text("Скорость", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(.75f to "0,75×", 1f to "1×", 1.25f to "1,25×").forEach { (speed, label) ->
                FilterChip(selected = speech.speed == speed, onClick = { speech.changeSpeed(speed) }, label = { Text(label) })
            }
        }
        Button(onClick = { speech.start(from); options = false }, enabled = speech.ready && !pages.isNullOrEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text(if (selected) "С выбранного места · стр. ${from.page + 1}" else "С начала страницы ${from.page + 1}")
        }
        speech.checkpoint?.let { p -> OutlinedButton(onClick = { speech.start(p); options = false }, enabled = speech.ready, modifier = Modifier.fillMaxWidth()) { Text("Продолжить · страница ${p.page + 1}") } }
        if (speech.playing) OutlinedButton(onClick = { speech.pause() }, modifier = Modifier.fillMaxWidth()) { Text("Пауза") }
        if (speech.checkpoint != null) TextButton(onClick = { speech.stop() }) { Text("Остановить и сбросить позицию") }
        Text("Чтобы начать с раздела, откройте оглавление, выберите заголовок и нажмите «С выбранного места». Чтение продолжится до конца документа.", style = MaterialTheme.typography.bodySmall)
        if (!speech.ready && !speech.busy) OutlinedButton(onClick = { speech.prepare() }, modifier = Modifier.fillMaxWidth()) { Text("Повторить проверку голоса") }
        TextButton(onClick = { speech.settings() }) { Text("Настройки речи Android") }
        TextButton(onClick = { options = false }) { Text("Закрыть") }
    }
}
