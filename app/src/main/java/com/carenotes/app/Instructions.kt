package com.carenotes.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.webkit.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.URL
import java.time.LocalDate
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

internal const val FORXIGA_PDF = "https://www.astrazeneca.ru/content/dam/az-ru/PDFs/2022/Forxiga-Leaflet-RU-V1.pdf"
internal const val PITAVASTOR_PDF = "https://storage.brandquad.ru/accounts-media/TEVA/DAM/origin/4deb50d0-ca9d-11f0-8031-ea31be8da094.pdf"
private val officialHosts = setOf("teva.ru", "www.teva.ru", "catalog.teva.ru", "www.drreddys.ru", "drreddys.ru", "promomed.pro", "www.promomed.pro", "promomed.ru", "www.promomed.ru", "grls.rosminzdrav.ru", "grls.minzdrav.gov.ru", "astrazeneca.ru", "www.astrazeneca.ru", "renewal.ru", "www.renewal.ru", "binnopharmgroup.ru", "www.binnopharmgroup.ru", "ozonpharm.ru", "www.ozonpharm.ru")
internal fun officialInstructionUrl(url: String): Boolean = runCatching {
    val u = URL(url)
    val host = u.host.lowercase()
    val permitted = host in officialHosts || (host == "storage.brandquad.ru" && u.path.matches(Regex("/accounts-media/TEVA/DAM/origin/[a-fA-F0-9-]{36}\\.pdf")) && u.query == null)
    u.protocol == "https" && permitted && u.userInfo == null && (u.port == -1 || u.port == 443)
}.getOrDefault(false)
internal data class SavedInstruction(val id: String, val name: String, val file: String, val source: String, val date: String)
internal class Instructions(private val context: Context) {
    private val prefs = context.getSharedPreferences("instructions", Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, "instructions").apply { mkdirs() }
    fun all(): List<SavedInstruction> {
        val a = JSONArray(prefs.getString("items", "[]"))
        return (0 until a.length()).map { a.getJSONObject(it).let { o -> SavedInstruction(o.getString("id"), o.getString("name"), o.getString("file"), o.getString("source"), o.getString("date")) } }.filter { file(it).isFile }
    }
    fun file(d: SavedInstruction): File { require(d.file.matches(Regex("[a-f0-9-]+\\.pdf"))); return File(dir, d.file) }
    private fun save(p: Prescription, source: String, stream: InputStream): SavedInstruction {
        val temp = File(dir, "${UUID.randomUUID()}.tmp")
        try {
            temp.outputStream().use { out -> val buffer = ByteArray(8192); var total = 0; while (true) { val n = stream.read(buffer); if (n < 0) break; total += n; require(total <= 25 * 1024 * 1024) { "Файл больше 25 МБ" }; out.write(buffer, 0, n) } }
            require(temp.inputStream().use { String(it.readNBytes(5), Charsets.US_ASCII) } == "%PDF-") { "По этой ссылке нет PDF. Выберите ссылку на инструкцию, а не страницу сайта." }
            ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { require(it.pageCount > 0) { "PDF пуст" } } }
            val old = all(); val saved = SavedInstruction(p.id, p.name, "${UUID.randomUUID()}.pdf", source, LocalDate.now().format(dateFormat))
            require(temp.renameTo(file(saved))) { "Не удалось сохранить PDF" }
            val a = JSONArray(); (old.filterNot { it.id == p.id } + saved).forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("file", it.file).put("source", it.source).put("date", it.date)) }
            if (!prefs.edit().putString("items", a.toString()).commit()) { file(saved).delete(); error("Не удалось сохранить список инструкций") }
            old.filter { it.id == p.id }.forEach { file(it).delete() }
            return saved
        } finally { temp.delete() }
    }
    fun import(p: Prescription, uri: Uri): SavedInstruction = (context.contentResolver.openInputStream(uri) ?: error("Не удалось открыть файл")).use { save(p, "PDF, выбранный на телефоне", it) }
    fun download(p: Prescription, address: String, cookies: String?): SavedInstruction {
        var url = address
        repeat(6) {
            require(officialInstructionUrl(url)) { "Поддерживается HTTPS официальных источников из списка. Другой PDF можно прикрепить с телефона." }
            val c = URL(url).openConnection() as HttpsURLConnection
            try {
                c.connectTimeout = 20000; c.readTimeout = 30000; c.instanceFollowRedirects = false
                c.setRequestProperty("User-Agent", "Mozilla/5.0 CareNotes/0.5")
                // Cookies are sent only to the same origin as the selected link.
                if (!cookies.isNullOrBlank() && URL(url).host == URL(address).host) c.setRequestProperty("Cookie", cookies)
                if (c.responseCode in 300..399) { url = URL(URL(url), c.getHeaderField("Location") ?: error("Пустое перенаправление")).toString() }
                else { require(c.responseCode == 200) { "Сайт ответил ${c.responseCode}. Попробуйте поиск в ГРЛС или прикрепите PDF." }; return c.inputStream.use { save(p, url, it) } }
            } finally { c.disconnect() }
        }
        error("Слишком много перенаправлений")
    }
}

@Composable internal fun ChoiceField(label: String, value: String, options: List<String>, change: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("$label: ${value.ifBlank { "не указан" }}") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            (listOf("") + options).forEach { option -> DropdownMenuItem(text = { Text(option.ifBlank { "Не указан" }) }, onClick = { change(option); expanded = false }) }
        }
    }
}

@Composable internal fun InstructionLibrary(context: Context, items: List<Prescription>, dismiss: () -> Unit, open: (Prescription) -> Unit) {
    val saved = Instructions(context).all()
    FormDialog("Инструкции", dismiss) {
        Text("Сохранённые PDF доступны без интернета. Они хранятся на этом телефоне и пока не синхронизируются с семьёй.")
        saved.forEach { d ->
            Text(d.name, style = MaterialTheme.typography.titleLarge)
            Text("Скачано ${d.date} · доступно офлайн")
            Text(d.source, style = MaterialTheme.typography.bodySmall)
            Button(onClick = { open(items.firstOrNull { it.id == d.id } ?: Prescription(id = d.id, name = d.name, dose = "", schedule = "", doctor = "", notes = "")) }, modifier = Modifier.fillMaxWidth()) { Text("Открыть инструкцию") }
        }
        val missing = items.filter { p -> saved.none { it.id == p.id } }
        if (saved.isEmpty()) Text("Пока нет сохранённых инструкций")
        if (missing.isNotEmpty()) Text("Добавить инструкцию к назначению", style = MaterialTheme.typography.titleLarge)
        missing.forEach { p -> OutlinedButton(onClick = { open(p) }, modifier = Modifier.fillMaxWidth()) { Text(p.name) } }
        TextButton(onClick = dismiss) { Text("Закрыть") }
    }
}

@Composable internal fun InstructionScreen(context: Context, p: Prescription, dismiss: () -> Unit) {
    val storage = remember { Instructions(context) }; val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf(storage.all().firstOrNull { it.id == p.id }) }
    var busy by remember { mutableStateOf(false) }; var error by remember { mutableStateOf("") }
    var browser by remember { mutableStateOf<String?>(null) }; var selectedUrl by remember { mutableStateOf<String?>(null) }
    var reading by remember { mutableStateOf(false) }; var verified by remember { mutableStateOf(false) }
    fun download(url: String) {
        busy = true; error = ""; val cookies = CookieManager.getInstance().getCookie(url)
        scope.launch { try { saved = withContext(Dispatchers.IO) { storage.download(p, url, cookies) }; selectedUrl = null; browser = null; verified = false } catch (e: Exception) { error = e.message ?: "Не удалось скачать. Проверьте интернет." } finally { busy = false } }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) { busy = true; scope.launch { try { saved = withContext(Dispatchers.IO) { storage.import(p, uri) } } catch (e: Exception) { error = e.message ?: "Не удалось сохранить PDF" } finally { busy = false } } } }
    if (reading && saved != null) { InstructionReader(storage.file(saved!!), p.name) { reading = false }; return }
    if (browser != null && selectedUrl == null) {
        InstructionBrowser(browser!!, p.name, { browser = null }) { url -> selectedUrl = url; verified = false }; return
    }
    FormDialog("Инструкция: ${p.name}", { if (!busy) dismiss() }) {
        if (saved != null) {
            Text("Доступна без интернета · скачано ${saved!!.date}")
            Text(saved!!.source, style = MaterialTheme.typography.bodySmall)
            Button(onClick = { reading = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Открыть инструкцию") }
        }
        Text("Перед сохранением проверьте название, форму, дозировку / концентрацию и производителя по упаковке. Скачанный документ не обновляется автоматически.")
        MedicalTermsHelp("ОХЛП", "ЛВ", "РУ", "ГРЛС")
        if (selectedUrl != null) {
            Text("Источник: $selectedUrl", style = MaterialTheme.typography.bodySmall)
            if (selectedUrl == PITAVASTOR_PDF) Text("ОХЛП — общая характеристика лекарственного препарата, документ для медицинских специалистов. Teva · Питавастор, таблетки 1 / 2 / 4 мг · РУ ЛП-№(001595)-(РГ-RU). Сверьте с упаковкой.")
            if (selectedUrl == FORXIGA_PDF) Text("AstraZeneca · Форсига, таблетки 5 / 10 мг · РУ ЛП-002596. На сайте опубликована эта редакция; сверяйте РУ с упаковкой и актуальной записью ГРЛС.")
            Row { Checkbox(verified, { verified = it }, enabled = !busy); Text("Документ соответствует моему препарату", Modifier.padding(top = 12.dp)) }
            Button(onClick = { download(selectedUrl!!) }, enabled = verified && !busy, modifier = Modifier.fillMaxWidth()) { Text("Скачать для чтения офлайн") }
            TextButton(onClick = { selectedUrl = null; error = "" }, enabled = !busy) { Text("Выбрать другой документ") }
        } else {
            Button(onClick = { browser = "https://grls.rosminzdrav.ru/GRLS.aspx?TradeNmR=${Uri.encode(p.name)}" }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Найти в ГРЛС") }
            Text("Название уже подставлено. Если появится код на картинке, введите его в окне сайта. Откройте карточку своего препарата и нажмите ссылку на PDF-инструкцию.")
            if (p.name.trim().lowercase() in listOf("форсига", "forxiga")) OutlinedButton(onClick = { selectedUrl = FORXIGA_PDF; verified = false }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Инструкция с сайта AstraZeneca") }
            if (p.name.trim().lowercase() == "питавастор") OutlinedButton(onClick = { selectedUrl = PITAVASTOR_PDF; verified = false }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Питавастор: документ Teva (ОХЛП)") }
            knownMedicine(p.name)?.takeIf { it.source != FORXIGA_PDF }?.let { medicine -> OutlinedButton(onClick = { browser = medicine.source }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Карточка моего препарата на сайте производителя") } }
            Text("Другие официальные источники", style = MaterialTheme.typography.titleMedium)
            listOf("Teva" to "https://catalog.teva.ru/", "Dr. Reddy’s" to "https://www.drreddys.ru/new-product-list-page", "Промомед" to "https://promomed.pro/catalog/", "Renewal / Обновление" to "https://www.renewal.ru/products/", "Биннофарм Групп" to "https://www.binnopharmgroup.ru/catalog/", "Озон Фармацевтика" to "https://ozonpharm.ru/products/catalog/").forEach { (name, url) -> OutlinedButton(onClick = { browser = url }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(name) } }
            OutlinedButton(onClick = { picker.launch(arrayOf("application/pdf")) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Прикрепить PDF с телефона") }
        }
        if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Сохраняю инструкцию…") }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = dismiss, enabled = !busy) { Text("Закрыть") }
    }
}

@Composable private fun InstructionBrowser(url: String, name: String, dismiss: () -> Unit, download: (String) -> Unit) {
    var web by remember { mutableStateOf<WebView?>(null) }; var error by remember { mutableStateOf("") }
    DisposableEffect(Unit) { onDispose { web?.stopLoading(); web?.destroy(); web = null } }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column {
                Text("Инструкция: $name", Modifier.padding(12.dp), style = MaterialTheme.typography.titleMedium)
                Text("Проверьте препарат по упаковке. Код с картинки вводится на сайте.", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall)
                if (error.isNotBlank()) Text(error, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error)
                AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = { ctx -> WebView(ctx).apply {
                    web = this; settings.javaScriptEnabled = true; settings.domStorageEnabled = true; settings.allowFileAccess = false; settings.allowContentAccess = false; settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val target = request.url.toString()
                            if (!request.isForMainFrame) return false
                            if (!officialInstructionUrl(target)) { error = "Переход на другой сайт не поддерживается. Можно прикрепить готовый PDF."; return true }
                            if (request.url.path?.endsWith(".pdf", true) == true || request.url.path?.contains("InstrImg", true) == true) { download(target); return true }
                            return false
                        }
                        override fun onPageFinished(view: WebView, url: String) {
                            // Keep PDF links that open a new window inside this viewer.
                            view.evaluateJavascript("document.querySelectorAll('a[target]').forEach(function(a){a.removeAttribute('target');});", null)
                        }
                        override fun onReceivedError(view: WebView, request: WebResourceRequest, e: WebResourceError) { if (request.isForMainFrame) error = "Не удалось загрузить сайт. Проверьте интернет или попробуйте другой источник." }
                    }
                    setDownloadListener { target, _, _, mime, _ -> if (officialInstructionUrl(target) && (mime.contains("pdf") || target.contains(".pdf", true) || target.contains("InstrImg", true))) download(target) else error = "Выберите PDF-инструкцию с официального сайта." }
                    loadUrl(url)
                } })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = { if (web?.canGoBack() == true) web?.goBack() else dismiss() }) { Text("Назад") }
                    TextButton(onClick = { error = ""; web?.reload() }) { Text("Обновить") }
                    TextButton(onClick = dismiss) { Text("Закрыть") }
                }
            }
        }
    }
}

@Composable private fun InstructionReader(file: File, name: String, dismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var page by remember { mutableIntStateOf(0) }; var count by remember { mutableIntStateOf(0) }
    var zoom by remember { mutableIntStateOf(100) }; var textMode by remember { mutableStateOf(false) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }; var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(true) }; var error by remember { mutableStateOf("") }
    LaunchedEffect(file, page, textMode) {
        busy = true; error = ""; text = ""; bitmap = null
        try {
            if (textMode) {
                val result = withContext(Dispatchers.IO) {
                    com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context.applicationContext)
                    com.tom_roush.pdfbox.pdmodel.PDDocument.load(file).use { document ->
                        val stripper = com.tom_roush.pdfbox.text.PDFTextStripper().apply {
                            startPage = page + 1; endPage = page + 1
                            lineSeparator = " "; paragraphStart = ""; paragraphEnd = "\n\n"
                        }
                        stripper.getText(document).trim() to document.numberOfPages
                    }
                }
                text = result.first; count = result.second
            } else {
                val result = withContext(Dispatchers.IO) { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { renderer -> renderer.openPage(page).use { p -> val width = 1600; val b = Bitmap.createBitmap(width, (width.toFloat() * p.height / p.width).toInt().coerceIn(1, 5000), Bitmap.Config.ARGB_8888); b.eraseColor(android.graphics.Color.WHITE); p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY); b to renderer.pageCount } } } }
                bitmap = result.first; count = result.second
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { error = if (textMode) "Не удалось извлечь текст. Откройте режим PDF." else "Не удалось открыть PDF. Сохраните инструкцию ещё раз." }
        finally { busy = false }
    }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column {
                Text(name, Modifier.padding(12.dp), style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = { page-- }, enabled = page > 0 && !busy) { Text("Назад") }
                    Text(if (count > 0) "${page + 1} / $count" else "…", Modifier.padding(top = 12.dp))
                    TextButton(onClick = { page++ }, enabled = page + 1 < count && !busy) { Text("Далее") }
                    TextButton(onClick = dismiss) { Text("Закрыть") }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !textMode, onClick = { textMode = false }, label = { Text("PDF") })
                    FilterChip(selected = textMode, onClick = { textMode = true }, label = { Text("Текст с переносами") })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    TextButton(onClick = { zoom = (zoom - 10).coerceAtLeast(100) }, enabled = zoom > 100) { Text("−") }
                    Text("Масштаб $zoom%", Modifier.padding(top = 12.dp))
                    TextButton(onClick = { zoom = (zoom + 10).coerceAtMost(300) }, enabled = zoom < 300) { Text("+") }
                }
                if (textMode) Text("В текстовом режиме таблицы могут потерять расположение. Сверяйте их с PDF.", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (error.isNotBlank()) Text(error, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error)
                if (textMode) {
                    val scroll = rememberScrollState()
                    LaunchedEffect(page, textMode) { scroll.scrollTo(0) }
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(16.dp)) {
                        if (!busy && text.isBlank() && error.isBlank()) Text("На этой странице нет извлекаемого текста. Используйте режим PDF.")
                        if (text.isNotBlank()) Text(text, fontSize = (18f * zoom / 100).sp, lineHeight = (27f * zoom / 100).sp)
                    }
                } else {
                    val vertical = rememberScrollState(); val horizontal = rememberScrollState()
                    LaunchedEffect(page, textMode) { vertical.scrollTo(0); horizontal.scrollTo(0) }
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                        val width = maxWidth * (zoom / 100f)
                        Box(Modifier.fillMaxSize().verticalScroll(vertical).horizontalScroll(horizontal)) {
                            bitmap?.let { b -> Image(b.asImageBitmap(), "Страница ${page + 1}", Modifier.width(width).height(width * b.height.toFloat() / b.width)) }
                        }
                    }
                }
            }
        }
    }
}
