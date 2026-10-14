package com.carenotes.app

import android.Manifest
import android.app.AlarmManager
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal val LocalCompact = staticCompositionLocalOf { false }
internal val LocalContentPadding = staticCompositionLocalOf { 20.dp }

internal val LocalUnlocked = staticCompositionLocalOf { true }

private val Green = Color(0xFF546B58)
private val Paper = Color(0xFFF7F8F3)
private val Lavender = Color(0xFFEEE8F5)
private val Ru = Locale.forLanguageTag("ru")

class MainActivity : ComponentActivity() {
    private var unlocked by mutableStateOf(false)
    private var openedOnce by mutableStateOf(false)
    private var openedMedicine by mutableStateOf<String?>(null)
    private var openedVisit by mutableStateOf<String?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openedMedicine = intent.getStringExtra("medicineId"); openedVisit = intent.getStringExtra("visitId")
        Reminders.channel(this)
        FamilySync.schedule(this)
        unlocked = !AppLock(this).enabled
        openedOnce = unlocked
        updatePrivacy()
        setContent {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val compact = maxWidth < 380.dp
                CompositionLocalProvider(LocalCompact provides compact, LocalContentPadding provides if (compact) 16.dp else 20.dp) {
                    MaterialTheme(colorScheme = lightColorScheme(primary = Green, secondaryContainer = Lavender, background = Paper, surface = Color.White, onSurface = Color(0xFF202A23)), typography = Typography(bodyLarge = androidx.compose.ui.text.TextStyle(fontSize = 17.sp, lineHeight = 25.sp), bodyMedium = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, lineHeight = 22.sp), titleLarge = androidx.compose.ui.text.TextStyle(fontSize = if (compact) 20.sp else 22.sp, lineHeight = if (compact) 26.sp else 28.sp, fontWeight = FontWeight.SemiBold))) {
                        if (openedOnce) CompositionLocalProvider(LocalUnlocked provides unlocked) { CareApp(this@MainActivity, if (unlocked) openedVisit else null, if (unlocked) openedMedicine else null) { openedVisit = null; openedMedicine = null } }
                        if (!unlocked) {
                            Dialog(onDismissRequest = {}, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false, dismissOnClickOutside = false, securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn)) {
                                UnlockScreen(this@MainActivity) { openedOnce = true; unlocked = true }
                            }
                        }
                    }
            }
                }
        }
    }
    internal fun updatePrivacy() {
        if (AppLock(this).enabled) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            getSystemService(android.app.NotificationManager::class.java).cancelAll()
        }
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
    }
    override fun onStop() {
        if (AppLock(this).enabled) unlocked = false
        super.onStop()
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); openedMedicine = intent.getStringExtra("medicineId"); openedVisit = intent.getStringExtra("visitId") }
}

@Composable
private fun CareApp(activity: MainActivity, openedVisit: String?, openedMedicine: String?, consumed: () -> Unit) {
    val store = remember { Store(activity) }
    var prescriptions by remember { mutableStateOf(store.prescriptions()) }
    var visits by remember { mutableStateOf(store.visits()) }
    var medicineSettings by remember { mutableStateOf(store.medicineSettings()) }
    var familyVisible by remember { mutableStateOf(false) }
    var canEdit by remember { mutableStateOf(FamilySync(activity).editable) }
    var securityVisible by remember { mutableStateOf(false) }
    var settingsVisible by remember { mutableStateOf(false) }
    var showMedicines by remember { mutableStateOf(true) }
    fun saveMedicines(next: List<Prescription>) { prescriptions = next; store.savePrescriptions(next) }
    var tab by remember { mutableIntStateOf(0) }
    var medicineForm by remember { mutableStateOf(false) }
    var medicineEdit by remember { mutableStateOf<Prescription?>(null) }
    var visitForm by remember { mutableStateOf(false) }
    var visitEdit by remember { mutableStateOf<Visit?>(null) }
    var visitDetails by remember { mutableStateOf<Visit?>(null) }
    var documentVisit by remember { mutableStateOf<Visit?>(null) }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var month by remember { mutableStateOf(YearMonth.now()) }
    var instructionsVisible by remember { mutableStateOf(false) }
    var instructionMedicine by remember { mutableStateOf<Prescription?>(null) }
    var promptVisible by remember { mutableStateOf(false) }
    var deleteMedicine by remember { mutableStateOf<Prescription?>(null) }
    var deleteVisit by remember { mutableStateOf<Visit?>(null) }
    var notificationsEnabled by remember { mutableStateOf(activity.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()) }
    var exactEnabled by remember { mutableStateOf(activity.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notificationsEnabled = activity.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled() }
    DisposableEffect(activity) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            activity.runOnUiThread {
                prescriptions = store.prescriptions(); visits = store.visits()
                canEdit = FamilySync(activity).editable
            }
        }
        val dataPrefs = activity.getSharedPreferences("care_notes", Context.MODE_PRIVATE)
        dataPrefs.registerOnSharedPreferenceChangeListener(listener)
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) {
            notificationsEnabled = activity.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()
            exactEnabled = activity.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
            Reminders.restore(activity)
            prescriptions = store.prescriptions(); visits = store.visits(); canEdit = FamilySync(activity).editable
            FamilySync.async(activity)
        } }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer); dataPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    LaunchedEffect(openedVisit) { if (openedVisit != null) { tab = 1; visitDetails = visits.firstOrNull { it.id == openedVisit }; consumed() } }
    LaunchedEffect(openedMedicine) { if (openedMedicine != null) { tab = 0; medicineEdit = prescriptions.firstOrNull { it.id == openedMedicine }; medicineForm = canEdit && medicineEdit != null; consumed() } }
    fun saveVisits(next: List<Visit>) { visits = next; store.saveVisits(next) }
    Scaffold(containerColor = Paper, bottomBar = {
        NavigationBar(containerColor = Color.White) {
            listOf("Назначения", "Посещения", "Календарь").forEachIndexed { index, label -> NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { Icon(when (index) { 0 -> Icons.Outlined.Medication; 1 -> Icons.Outlined.EventNote; else -> Icons.Outlined.CalendarMonth }, null) }, label = { NavigationLabel(label) }) }
        }
    }, floatingActionButton = {
        if (canEdit) ExtendedFloatingActionButton(onClick = { if (tab == 0) { medicineEdit = null; medicineForm = true } else { visitEdit = null; visitForm = true } }, containerColor = Green, contentColor = Color.White, icon = { Icon(Icons.Outlined.Add, null) }, text = { Text(if (tab == 0) "Назначение" else "Посещение", fontSize = 16.sp) })
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 760.dp).fillMaxSize(), contentPadding = PaddingValues(start = LocalContentPadding.current, end = LocalContentPadding.current, top = 16.dp, bottom = 110.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("МОЁ ЗДОРОВЬЕ", color = Green, fontSize = 12.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        IconButton(onClick = { familyVisible = true }) { Icon(Icons.Outlined.People, "Семейный доступ") }
                        IconButton(onClick = { securityVisible = true }) { Icon(Icons.Outlined.Lock, "Защита входа") }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(listOf("Назначения", "Посещения врача", "Календарь")[tab], fontSize = if (LocalCompact.current) 26.sp else 28.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(listOf("Всё, что назначил врач, в одном месте", "Планы и напоминания о приёмах", "Посещения и назначения по дням")[tab], color = Color(0xFF68736A), fontSize = 16.sp)
                }
                if (!notificationsEnabled && tab == 0) item { Notice("Напоминания выключены", "Разрешите уведомления о приёме лекарств.", "Включить") { if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.POST_NOTIFICATIONS) else openIntent(activity, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)) } }
                if (!exactEnabled && tab == 0) item { Notice("Время напоминаний", "Разрешите точные напоминания в настройках Android.", "Настроить") { openIntent(activity, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${activity.packageName}"))) } }
                if (tab == 0) {
                    item { OutlinedButton(onClick = { instructionsVisible = true }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Description, null); Spacer(Modifier.width(8.dp)); Text("Инструкции к моим лекарствам") } }
                    item { OutlinedButton(onClick = { settingsVisible = true }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Schedule, null); Spacer(Modifier.width(8.dp)); Text("Время и напоминания", modifier = Modifier.weight(1f), textAlign = TextAlign.Center) } }
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = Lavender), shape = RoundedCornerShape(24.dp)) {
                            Column(Modifier.padding(LocalContentPadding.current), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(Icons.Outlined.AutoAwesome, null, tint = Color(0xFF67537B))
                                Text("Совместимость назначений", style = MaterialTheme.typography.titleLarge)
                                Text("Подготовьте список лекарств и вопрос для Алисы AI. Ответ поможет обсудить назначения с врачом.")
                                Button(onClick = { promptVisible = true }, enabled = prescriptions.isNotEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Проверить совместимость", textAlign = TextAlign.Center) }
                            }
                        }
                    }
                    if (prescriptions.isEmpty()) item { EmptyState("Пока нет назначений", "Нажмите «+ Назначение», чтобы записать лекарство, дозировку и порядок приёма.", Icons.Outlined.Medication) }
                    items(prescriptions, key = { it.id }) { p ->
                        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                            Column(Modifier.padding(LocalContentPadding.current), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(p.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                                    if (p.photo.isNotBlank()) MedicinePhoto(activity, p.photo, Modifier.size(80.dp))
                                }
                                if (p.inn.isNotBlank()) Text("Действующее вещество: ${p.inn}", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF68736A))
                                if (p.dose.isNotBlank()) Text(p.dose, color = Green, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                                if (p.application.isNotBlank()) Text(p.application, color = Green)
                                if (p.meal.isNotBlank()) Text(p.meal)
                                if (p.schedule.isNotBlank()) Text(p.schedule)
                                Text("Назначено: ${p.prescribedOn?.format(dateFormat) ?: "дата не указана"}", fontSize = 15.sp)
                                Text("Начало: ${p.startOn?.format(dateFormat) ?: "не указано"}${p.courseDays?.let { " · $it дн., до ${p.endOn()?.format(dateFormat)}" } ?: ""}", fontSize = 15.sp)
                                if (p.periods.isNotEmpty()) Text("${p.regimenLabel()} · ${p.periods.sorted().joinToString { "${periodNames[it]} ${medicineSettings.times[it].format(timeFormat)}" }}", color = Green)
                                if (p.periods.isNotEmpty() && p.startOn != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(if (medicineSettings.enabled) "Напоминать о применении" else "Все напоминания о лекарствах выключены", Modifier.weight(1f), fontSize = 15.sp)
                                    Switch(checked = p.remindersEnabled, onCheckedChange = { enabled -> val next = p.copy(remindersEnabled = enabled); MedicineReminders.cancel(activity, p); saveMedicines(prescriptions.map { if (it.id == p.id) next else it }); MedicineReminders.schedule(activity, next); if (enabled && !notificationsEnabled) permission.launch(Manifest.permission.POST_NOTIFICATIONS) })
                                }
                                if (p.doctor.isNotBlank()) Text("Врач: ${p.doctor}", color = Color(0xFF68736A))
                                if (p.notes.isNotBlank()) Text(p.notes)
                                OutlinedButton(onClick = { instructionMedicine = p }, modifier = Modifier.fillMaxWidth()) { Text("Инструкция") }
                                if (canEdit) AdaptiveActions { TextButton(onClick = { medicineEdit = p; medicineForm = true }) { Text("Изменить") }; TextButton(onClick = { deleteMedicine = p }) { Text("Удалить", color = MaterialTheme.colorScheme.error) } }
                            }
                        }
                    }
                } else {
                    if (!notificationsEnabled) item {
                        Notice("Напоминания выключены", "Разрешите уведомления, чтобы получать напоминания о посещениях.", "Включить") {
                            if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            else openIntent(activity, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName))
                        }
                    }
                    if (!exactEnabled) item { Notice("Время напоминаний", "Android может задерживать напоминания. Разрешите точные напоминания в настройках.", "Настроить") { openIntent(activity, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${activity.packageName}"))) } }
                    if (tab == 2) item { CalendarMonth(month, selectedDate, visits, if (showMedicines) prescriptions else emptyList(), { month = it }, { selectedDate = it }) }
                    if (tab == 2) item { Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = showMedicines, onCheckedChange = { showMedicines = it }); Text("Показывать назначения") } }
                    val shown = if (tab == 2) visits.filter { it.dateTime.toLocalDate() == selectedDate }.sortedBy { it.dateTime } else visits.sortedBy { it.dateTime }
                    item { Text(if (tab == 2) selectedDate.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Ru)) else "Всего посещений: ${visits.size}", fontWeight = FontWeight.SemiBold, color = Green, fontSize = 18.sp) }
                    if (tab == 2) item { Text("● Посещения врача", color = Green, fontWeight = FontWeight.Bold) }
                    if (shown.isEmpty()) item { if (tab == 2) Text("Посещений нет", color = Color(0xFF68736A)) else EmptyState("Пока нет посещений", "Нажмите «+ Посещение», чтобы выбрать дату, время и напоминания.", Icons.Outlined.EventAvailable) }
                    items(shown, key = { it.id }) { v ->
                        Card(modifier = Modifier.fillMaxWidth(), onClick = { visitDetails = v }, shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                            Column(Modifier.padding(LocalContentPadding.current), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(v.dateTime.format(DateTimeFormatter.ofPattern("d MMMM · HH:mm", Ru)), color = Green, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                                Text(v.doctor, style = MaterialTheme.typography.titleLarge)
                                if (v.kindLabel().isNotBlank()) Text(v.kindLabel(), color = Green)
                                if (v.place.isNotBlank()) Text(v.place)
                                Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.Notifications, null, Modifier.size(18.dp), tint = Green); Spacer(Modifier.width(6.dp)); Text(if (v.dateTime.isBefore(LocalDateTime.now())) "Посещение прошло" else if (v.reminders.isEmpty()) "Без напоминаний" else v.reminders.sortedDescending().joinToString(" · ") { offsetLabel(it) }, fontSize = 15.sp, color = Color(0xFF68736A)) }
                                Text("Открыть подробности →", color = Green, fontSize = 15.sp)
                            }
                        }
                    }
                    if (tab == 2 && showMedicines) {
                        item { Text("● Приём лекарств", color = Color(0xFF67537B), fontWeight = FontWeight.Bold, fontSize = 19.sp) }
                        val doses = prescriptions.filter { it.isDoseDay(selectedDate) }.flatMap { p -> p.periods.map { slot -> p to slot } }.sortedBy { medicineSettings.times[it.second] }
                        if (doses.isEmpty()) item { Text("Приём лекарств не запланирован", color = Color(0xFF68736A)) }
                        items(doses, key = { "dose:${it.first.id}:${it.second}" }) { (p, slot) ->
                            Card(onClick = { if (canEdit) { medicineEdit = p; medicineForm = true } }, colors = CardDefaults.cardColors(containerColor = Lavender), shape = RoundedCornerShape(22.dp)) {
                                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("${medicineSettings.times[slot].format(timeFormat)} · ${periodNames[slot]}", color = Color(0xFF67537B), fontWeight = FontWeight.Bold)
                                    Text(p.name, style = MaterialTheme.typography.titleLarge)
                                    if (p.dose.isNotBlank()) Text(p.dose)
                                    Text(if (p.remindersEnabled && medicineSettings.enabled) "Напоминание включено" else "Без напоминания", fontSize = 14.sp)
                                }
                            }
                        }
                    }
                }
            }
    }
    }
    if (familyVisible) FamilySettings(activity, { familyVisible = false }) {
        prescriptions = store.prescriptions(); visits = store.visits(); canEdit = FamilySync(activity).editable
    }
    if (securityVisible) SecuritySettings(activity) { securityVisible = false }
    if (settingsVisible) MedicineSettingsForm(activity, medicineSettings, { settingsVisible = false }) { next -> medicineSettings = next; store.saveMedicineSettings(next); MedicineReminders.restore(activity); settingsVisible = false; if (next.enabled && !notificationsEnabled) permission.launch(Manifest.permission.POST_NOTIFICATIONS) }
    if (medicineForm && canEdit) MedicineForm(activity, medicineEdit, medicineSettings, { medicineForm = false }) { p ->
        medicineEdit?.let { MedicineReminders.cancel(activity, it) }
        saveMedicines(prescriptions.filterNot { it.id == p.id } + p)
        MedicineReminders.schedule(activity, p)
        medicineForm = false
        if (p.remindersEnabled && !notificationsEnabled) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    if (visitForm && canEdit) VisitForm(activity, visitEdit, if (tab == 2) selectedDate else LocalDate.now(), { visitForm = false }) { v ->
        visitEdit?.let { Reminders.cancel(activity, it) }
        saveVisits(visits.filterNot { it.id == v.id } + v)
        Reminders.schedule(activity, v)
        visitForm = false
        if (v.reminders.isNotEmpty() && !notificationsEnabled) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    visitDetails?.takeIf { LocalUnlocked.current }?.let { v ->
        FormDialog(v.doctor, { visitDetails = null }) {
            Text("${v.dateTime.format(dateFormat)} в ${v.dateTime.format(timeFormat)}")
            if (v.kindLabel().isNotBlank()) Text(v.kindLabel())
            if (v.place.isNotBlank()) Text(v.place)
            if (v.notes.isNotBlank()) Text(v.notes)
            Text(if (v.reminders.isEmpty()) "Без напоминаний" else "Напомнить: ${v.reminders.sortedDescending().joinToString { offsetLabel(it) }}")
            Button(onClick = { visitDetails = null; documentVisit = v }, modifier = Modifier.fillMaxWidth()) { Text("Документы и назначения") }
            TextButton(onClick = { exportCalendar(activity, v) }) { Text("Добавить в календарь телефона") }
            if (canEdit) TextButton(onClick = { visitDetails = null; visitEdit = v; visitForm = true }) { Text("Изменить посещение") }
            if (canEdit) TextButton(onClick = { visitDetails = null; deleteVisit = v }) { Text("Удалить посещение", color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = { visitDetails = null }) { Text("Закрыть") }
        }
    }
    deleteMedicine?.takeIf { canEdit }?.let { p -> ConfirmDelete(p.name, { deleteMedicine = null }) { MedicineReminders.cancel(activity, p); saveMedicines(prescriptions.filterNot { it.id == p.id }); deleteMedicine = null } }
    deleteVisit?.takeIf { canEdit }?.let { v -> ConfirmDelete(v.doctor, { deleteVisit = null }) { Reminders.cancel(activity, v); Documents(activity).deleteVisit(v.id); saveVisits(visits.filterNot { it.id == v.id }); deleteVisit = null } }
    documentVisit?.let { v -> DocumentScreen(activity, v, medicineSettings, { documentVisit = null }, readOnly = !canEdit) { p -> saveMedicines(prescriptions + p); MedicineReminders.schedule(activity, p); if (p.remindersEnabled && !notificationsEnabled) permission.launch(Manifest.permission.POST_NOTIFICATIONS) } }
    if (instructionsVisible && LocalUnlocked.current) InstructionLibrary(activity, prescriptions, { instructionsVisible = false }) { instructionMedicine = it }
    instructionMedicine?.takeIf { LocalUnlocked.current }?.let { p -> InstructionScreen(activity, p) { instructionMedicine = null } }
    if (promptVisible) PromptDialog(activity, prescriptions) { promptVisible = false }
}

// Fit complete navigation words without disabling the system font scale.
@Composable private fun NavigationLabel(label: String) {
    val measurer = rememberTextMeasurer()
    val baseStyle = LocalTextStyle.current.copy(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.sp)
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val fittedStyle = remember(baseStyle, constraints.maxWidth, measurer) {
            var size = 12f
            var candidate = baseStyle
            val labels = listOf("Назначения", "Посещения", "Календарь")
            while (size > 1f && labels.any { measurer.measure(it, style = candidate, softWrap = false, maxLines = 1).size.width > constraints.maxWidth - 2 }) {
                size -= 0.25f
                candidate = baseStyle.copy(fontSize = size.sp)
            }
            candidate
        }
        Text(label, style = fittedStyle, maxLines = 1, softWrap = false, textAlign = TextAlign.Center)
    }
}

@Composable private fun Notice(title: String, body: String, action: String, onClick: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEFD1)), shape = RoundedCornerShape(20.dp)) { Column(Modifier.padding(18.dp)) { Text(title, fontWeight = FontWeight.Bold, fontSize = 19.sp); Text(body); TextButton(onClick = onClick) { Text(action) } } }
}
@Composable private fun EmptyState(title: String, text: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Column(Modifier.fillMaxWidth().padding(vertical = 34.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) { Icon(icon, null, Modifier.size(42.dp), tint = Green); Text(title, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center); Text(text, textAlign = TextAlign.Center, color = Color(0xFF68736A), fontSize = 16.sp) }
}
@Composable private fun CalendarMonth(month: YearMonth, selected: LocalDate, visits: List<Visit>, prescriptions: List<Prescription>, onMonth: (YearMonth) -> Unit, onDate: (LocalDate) -> Unit) {
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton(onClick = { onMonth(month.minusMonths(1)) }) { Icon(Icons.Outlined.ChevronLeft, "Предыдущий месяц") }
                Text(month.atDay(1).format(DateTimeFormatter.ofPattern("LLLL yyyy", Ru)).replaceFirstChar { it.titlecase(Ru) }, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = if (LocalCompact.current) 17.sp else 20.sp, fontWeight = FontWeight.SemiBold)
                IconButton(onClick = { onMonth(month.plusMonths(1)) }) { Icon(Icons.Outlined.ChevronRight, "Следующий месяц") }
            }
            Row { listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс").forEach { Text(it, Modifier.weight(1f).padding(vertical = 8.dp), textAlign = TextAlign.Center, color = Color(0xFF68736A)) } }
            val start = month.atDay(1).dayOfWeek.value - 1
            val count = month.lengthOfMonth()
            for (week in 0 until (start + count + 6) / 7) Row {
                for (day in 0..6) {
                    val number = week * 7 + day - start + 1
                    if (number !in 1..count) Spacer(Modifier.weight(1f).height(52.dp)) else {
                        val date = month.atDay(number)
                        val chosen = selected == date
                        Column(Modifier.weight(1f).heightIn(min = 52.dp).background(if (chosen) Green else if (date == LocalDate.now()) Color(0xFFEAF0E8) else Color.Transparent, RoundedCornerShape(14.dp)).clickable { onDate(date) }.padding(vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("$number", color = if (chosen) Color.White else Color(0xFF202A23), fontSize = if (LocalCompact.current) 16.sp else 18.sp, maxLines = 1)
                            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(if (visits.any { it.dateTime.toLocalDate() == date }) "●" else " ", fontSize = 10.sp, color = if (chosen) Color.White else Green)
                                Text(if (prescriptions.any { it.periods.isNotEmpty() && it.isDoseDay(date) }) "●" else " ", fontSize = 10.sp, color = if (chosen) Color(0xFFE1BEFF) else Color(0xFF816496))
                            }
                        }
                    }
                }
            }
            AdaptiveActions { Text("● Врач", color = Green, fontSize = 14.sp); Text("● Лекарства", color = Color(0xFF816496), fontSize = 14.sp) }
            TextButton(onClick = { onMonth(YearMonth.now()); onDate(LocalDate.now()) }) { Text("Сегодня") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun AdaptiveActions(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
}

@Composable internal fun FormDialog(title: String, dismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    if (!LocalUnlocked.current) return
    Dialog(onDismissRequest = dismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = 12.dp, vertical = 16.dp), contentAlignment = Alignment.Center) {
            val compact = maxWidth < 380.dp
            CompositionLocalProvider(LocalCompact provides compact, LocalContentPadding provides if (compact) 16.dp else 24.dp) {
                Surface(modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(), shape = RoundedCornerShape(26.dp), color = Paper) {
                    val scroll = androidx.compose.foundation.rememberScrollState()
                    Column(Modifier.heightIn(max = maxHeight).verticalScroll(scroll).padding(LocalContentPadding.current), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(title, fontSize = if (compact) 22.sp else 25.sp, lineHeight = if (compact) 28.sp else 32.sp, fontWeight = FontWeight.Bold)
                        content()
                    }
                }
            }
        }
    }
}

@Composable internal fun Field(label: String, value: String, changed: (String) -> Unit, singleLine: Boolean = true) { OutlinedTextField(value = value, onValueChange = changed, label = { Text(label, style = MaterialTheme.typography.bodyMedium) }, modifier = Modifier.fillMaxWidth(), singleLine = singleLine, placeholder = if (label == "Дозировка") ({ Text("Например, 5 мг") }) else null, shape = RoundedCornerShape(14.dp)) }
@Composable private fun DateField(context: Context, label: String, date: LocalDate?, change: (LocalDate) -> Unit) {
    OutlinedButton(onClick = { val d = date ?: LocalDate.now(); DatePickerDialog(context, { _, y, m, day -> change(LocalDate.of(y, m + 1, day)) }, d.year, d.monthValue - 1, d.dayOfMonth).show() }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("$label: ${date?.format(dateFormat) ?: "выбрать"}") }
}
@Composable internal fun CheckRow(label: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { change(!checked) }, verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = checked, onCheckedChange = change); Text(label, Modifier.weight(1f)) }
}
@Composable private fun MedicineSettingsForm(context: Context, existing: MedicineSettings, dismiss: () -> Unit, save: (MedicineSettings) -> Unit) {
    var times by remember { mutableStateOf(existing.times) }; var enabled by remember { mutableStateOf(existing.enabled) }
    FormDialog("Время напоминаний", dismiss) {
        Text("Общее время для всех назначений. Схему и время суток выбирайте в каждом лекарстве.")
        CheckRow("Напоминать о лекарствах", enabled) { enabled = it }
        periodNames.forEachIndexed { i, name -> OutlinedButton(onClick = { val t = times[i]; TimePickerDialog(context, { _, h, m -> times = times.mapIndexed { index, old -> if (index == i) LocalTime.of(h, m) else old } }, t.hour, t.minute, true).show() }, modifier = Modifier.fillMaxWidth()) { Text("$name: ${times[i].format(timeFormat)}") } }
        Text("Посещения врача настраиваются отдельно.", fontSize = 14.sp)
        Button(onClick = { save(MedicineSettings(times, enabled)) }, modifier = Modifier.fillMaxWidth()) { Text("Сохранить") }
        TextButton(onClick = dismiss, modifier = Modifier.fillMaxWidth()) { Text("Отмена") }
    }
}
@Composable internal fun MedicineForm(context: Context, existing: Prescription?, settings: MedicineSettings, dismiss: () -> Unit, save: (Prescription) -> Unit) {
    var name by remember { mutableStateOf(existing?.name ?: "") }; var dose by remember { mutableStateOf(existing?.dose ?: "") }; var schedule by remember { mutableStateOf(existing?.schedule ?: "") }; var doctor by remember { mutableStateOf(existing?.doctor ?: "") }; var notes by remember { mutableStateOf(existing?.notes ?: "") }
    var inn by remember { mutableStateOf(InnDraft(existing?.inn ?: "", existing?.inn.isNullOrBlank()).forName(existing?.name ?: "")) }
    var photo by remember { mutableStateOf(existing?.photo ?: "") }
    var photoBusy by remember { mutableStateOf(false) }; var photoError by remember { mutableStateOf("") }
    var photoCommitted by remember { mutableStateOf(false) }
    val photos = remember(context) { MedicinePhotos(context) }; val scope = rememberCoroutineScope()
    fun cancel() { if (!photoBusy) dismiss() }
    DisposableEffect(Unit) { onDispose { if (!photoCommitted && photo != existing?.photo) photos.remove(photo) } }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            photoBusy = true; photoError = ""
            scope.launch {
                try {
                    val imported = withContext(Dispatchers.IO) { photos.import(uri) }
                    if (photo != existing?.photo) photos.remove(photo)
                    photo = imported
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { photoError = e.message ?: "Не удалось открыть фото" }
                finally { photoBusy = false }
            }
        }
    }
    var application by remember { mutableStateOf(existing?.application ?: "") }
    var meal by remember { mutableStateOf(existing?.meal ?: "") }
    var prescribed by remember { mutableStateOf(if (existing == null) LocalDate.now() else existing.prescribedOn) }
    var start by remember { mutableStateOf(if (existing == null) LocalDate.now() else existing.startOn) }
    var same by remember { mutableStateOf(existing == null || (prescribed != null && prescribed == start)) }
    var days by remember { mutableStateOf(existing?.courseDays?.toString() ?: "") }
    var kind by remember { mutableIntStateOf(if (existing?.weekdays?.isNotEmpty() == true) 3 else when (existing?.intervalDays ?: 1) { 1 -> 0; 2 -> 1; else -> 2 }) }
    var interval by remember { mutableStateOf((existing?.intervalDays ?: 3).toString()) }
    var weekdays by remember { mutableStateOf(existing?.weekdays ?: emptyList()) }
    var periods by remember { mutableStateOf(existing?.periods ?: emptyList()) }
    var enabled by remember { mutableStateOf(existing?.remindersEnabled ?: false) }
    FormDialog(if (existing == null) "Новое назначение" else "Изменить назначение", { cancel() }) {
        Field("Лекарство / средство\u00a0*", name, { name = it; inn = inn.forName(it) })
        Field("МНН — действующее вещество", inn.value, { inn = inn.edited(it.take(500)) }, false)
        MedicalTermsHelp("МНН")
        val known = knownMedicine(name)
        if (known != null) {
            if (inn.automatic) Text("МНН подставлено из официального каталога. Сверьте с упаковкой.", style = MaterialTheme.typography.bodySmall)
            else TextButton(onClick = { inn = InnDraft(known.inn) }) { Text("Подставить МНН из каталога") }
        } else Text("МНН можно указать по инструкции или упаковке.", style = MaterialTheme.typography.bodySmall)
        if (photo.isNotBlank()) MedicinePhoto(context, photo, Modifier.fillMaxWidth().height(140.dp))
        OutlinedButton(onClick = { photoPicker.launch(arrayOf("image/*")) }, enabled = !photoBusy, modifier = Modifier.fillMaxWidth()) { Text(if (photo.isBlank()) "Добавить фото упаковки" else "Заменить фото упаковки") }
        if (photo.isNotBlank()) TextButton(onClick = { if (photo != existing?.photo) photos.remove(photo); photo = "" }, enabled = !photoBusy) { Text("Убрать фото") }
        if (photoBusy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Сохраняю фото…") }
        if (photoError.isNotBlank()) Text(photoError, color = MaterialTheme.colorScheme.error)
        ChoiceField("Способ применения", application, listOf("Внутрь", "Наружно / обработка раны", "Капли", "Ингаляция", "Инъекция", "Другое")) { application = it; if (it != "Внутрь") meal = "" }
        Field(if (application == "Наружно / обработка раны") "Концентрация / количество" else "Дозировка", dose, { dose = it })
        if (application == "Внутрь") ChoiceField("Связь с едой", meal, listOf("До еды", "Во время еды", "После еды", "Независимо от еды")) { meal = it }
        DateField(context, "Назначено врачом", prescribed) { prescribed = it; if (same) start = it }
        CheckRow("Начало совпадает с датой назначения", same) { same = it; if (it) start = prescribed }
        if (!same) DateField(context, "Начало применения", start) { start = it }
        Field("Сколько дней применять", days, { days = it.filter(Char::isDigit).take(4) })
        val duration = days.toIntOrNull()
        if (duration != null && duration > 0 && start != null) Text("Последний день: ${start!!.plusDays(duration.toLong() - 1).format(dateFormat)}", color = Green)
        Text("Пустое поле — без даты окончания. Длительность в календарных днях.", fontSize = 14.sp)
        Text("Схема применения", fontWeight = FontWeight.Bold, fontSize = 19.sp)
        listOf("Каждый день", "Через день", "Каждые N дней", "Дни недели").forEachIndexed { i, label -> Row(Modifier.fillMaxWidth().clickable { kind = i }, verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = kind == i, onClick = { kind = i }); Text(label) } }
        if (kind == 2) Field("Раз в сколько дней", interval, { interval = it.filter(Char::isDigit).take(3) })
        if (kind == 3) listOf("Понедельник", "Вторник", "Среда", "Четверг", "Пятница", "Суббота", "Воскресенье").forEachIndexed { i, label -> CheckRow(label, i + 1 in weekdays) { weekdays = if (it) (weekdays + (i + 1)).distinct() else weekdays - (i + 1) } }
        Text("Время суток", fontWeight = FontWeight.Bold, fontSize = 19.sp)
        periodNames.forEachIndexed { i, label -> CheckRow("$label · ${settings.times[i].format(timeFormat)}", i in periods) { periods = if (it) (periods + i).distinct() else periods - i } }
        Text("Часы меняются в разделе «Время и напоминания».", fontSize = 14.sp)
        CheckRow("Напоминать о применении", enabled) { enabled = it }
        Field("Как применять / указания врача", schedule, { schedule = it }, false); Field("Назначивший врач", doctor, { doctor = it }); Field("Примечания", notes, { notes = it }, false)
        val validScheme = kind != 3 || weekdays.isNotEmpty()
        val validInterval = kind != 2 || (interval.toIntOrNull() ?: 0) > 0
        val validDays = days.isBlank() || (duration ?: 0) > 0
        val validReminders = !enabled || (start != null && periods.isNotEmpty())
        if (!validReminders) Text("Для напоминаний выберите дату начала и время суток.", color = MaterialTheme.colorScheme.error)
        if (!validDays) Text("Количество дней должно быть больше нуля.", color = MaterialTheme.colorScheme.error)
        if (!validScheme || !validInterval) Text("Укажите интервал или дни недели.", color = MaterialTheme.colorScheme.error)
        Button(onClick = { photoCommitted = true; save(Prescription(existing?.id ?: java.util.UUID.randomUUID().toString(), name.trim(), dose.trim(), schedule.trim(), doctor.trim(), notes.trim(), prescribed, start, duration, when (kind) { 0 -> 1; 1 -> 2; else -> interval.toIntOrNull() ?: 1 }, if (kind == 3) weekdays else emptyList(), periods.sorted(), enabled, application, meal, inn.value.trim(), photo)) }, enabled = !photoBusy && name.isNotBlank() && validScheme && validInterval && validDays && validReminders, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Сохранить", fontSize = 18.sp) }
        TextButton(onClick = { cancel() }, enabled = !photoBusy, modifier = Modifier.fillMaxWidth()) { Text("Отмена") }
    }
}
@Composable private fun VisitForm(context: Context, existing: Visit?, initialDate: LocalDate, dismiss: () -> Unit, save: (Visit) -> Unit) {
    var doctor by remember { mutableStateOf(existing?.doctor ?: "") }; var place by remember { mutableStateOf(existing?.place ?: "") }; var notes by remember { mutableStateOf(existing?.notes ?: "") }
    var date by remember { mutableStateOf(existing?.dateTime?.toLocalDate() ?: initialDate) }; var time by remember { mutableStateOf(existing?.dateTime?.toLocalTime() ?: LocalTime.of(10, 0)) }
    var primary by remember { mutableStateOf(if (existing == null) false else existing.primary) }
    var number by remember { mutableStateOf(existing?.visitNumber?.toString() ?: "") }
    var reminders by remember { mutableStateOf(existing?.reminders ?: listOf(1440, 120)) }; var custom by remember { mutableStateOf("") }; var error by remember { mutableStateOf("") }
    FormDialog(if (existing == null) "Новое посещение" else "Изменить посещение", dismiss) {
        Field("Врач / специальность\u00a0*", doctor, { doctor = it })
        CheckRow("Первичный приём", primary == true) { primary = it; if (it) number = "1" else if (number == "1") number = "" }
        if (primary != true) Field("Какой по счёту визит (необязательно)", number, { number = it.filter(Char::isDigit).take(3); if (number.isNotBlank()) primary = false })
        if (primary == true) Text("Визит №1", color = Green)
        Field("Клиника, адрес, кабинет", place, { place = it }, false)
        OutlinedButton(onClick = { DatePickerDialog(context, { _, y, m, d -> date = LocalDate.of(y, m + 1, d) }, date.year, date.monthValue - 1, date.dayOfMonth).show() }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Icon(Icons.Outlined.CalendarMonth, null); Spacer(Modifier.width(8.dp)); Text(date.format(dateFormat), fontSize = 18.sp) }
        OutlinedButton(onClick = { TimePickerDialog(context, { _, h, m -> time = LocalTime.of(h, m) }, time.hour, time.minute, true).show() }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Icon(Icons.Outlined.Schedule, null); Spacer(Modifier.width(8.dp)); Text(time.format(timeFormat), fontSize = 18.sp) }
        Text("Когда напомнить", fontWeight = FontWeight.Bold, fontSize = 19.sp)
        (listOf(1440, 120, 60) + reminders).distinct().sortedDescending().forEach { offset ->
            Row(Modifier.fillMaxWidth().clickable { reminders = if (offset in reminders) reminders - offset else reminders + offset }, verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = offset in reminders, onCheckedChange = { checked -> reminders = if (checked) reminders + offset else reminders - offset }); Text(offsetLabel(offset), fontSize = 16.sp) }
        }
        Field("Другое: за сколько минут", custom, { custom = it.filter(Char::isDigit).take(6) })
        TextButton(onClick = { val value = custom.toIntOrNull(); if (value != null && value in 1..525600) { reminders = (reminders + value).distinct(); custom = ""; error = "" } else error = "Введите от 1 до 525600 минут" }) { Text("Добавить своё напоминание") }
        Field("Что взять / примечания", notes, { notes = it }, false)
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        Button(onClick = {
            val dateTime = LocalDateTime.of(date, time)
            if (custom.isNotBlank()) { error = "Нажмите «Добавить своё напоминание» или очистите поле"; return@Button }
            if (reminders.isNotEmpty() && !dateTime.isAfter(LocalDateTime.now())) { error = "Для прошедшего посещения снимите напоминания"; return@Button }
            if (number.isNotBlank() && (number.toIntOrNull() ?: 0) < 1) { error = "Номер визита должен быть больше нуля"; return@Button }
            save(Visit(existing?.id ?: java.util.UUID.randomUUID().toString(), doctor.trim(), place.trim(), dateTime, notes.trim(), reminders.distinct(), primary, if (primary == true) 1 else number.toIntOrNull()))
        }, enabled = doctor.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Сохранить", fontSize = 18.sp) }
        Text("Если время напоминания уже прошло, оно будет пропущено.", fontSize = 14.sp, color = Color(0xFF68736A))
        TextButton(onClick = dismiss, modifier = Modifier.fillMaxWidth()) { Text("Отмена") }
    }
}
@Composable internal fun ConfirmDelete(name: String, dismiss: () -> Unit, delete: () -> Unit) { if (!LocalUnlocked.current) return; AlertDialog(onDismissRequest = dismiss, title = { Text("Удалить запись?") }, text = { Text(name) }, confirmButton = { TextButton(onClick = delete) { Text("Удалить", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = dismiss) { Text("Отмена") } }) }
@Composable private fun PromptDialog(context: Context, items: List<Prescription>, dismiss: () -> Unit) {
    var details by remember { mutableStateOf("") }
    val prefs = remember { context.getSharedPreferences("compatibility", Context.MODE_PRIVATE) }
    var instructions by remember { mutableStateOf(prefs.getString("instructions", defaultCompatibilityInstructions) ?: defaultCompatibilityInstructions) }
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(instructions) }
    val prompt = compatibilityPrompt(items, details, instructions)
    if (editing) {
        FormDialog("Настройки запроса", { editing = false }) {
            Text("Этот текст используется перед автоматически добавляемым списком назначений и дополнительными сведениями. Настройка сохраняется на этом телефоне.")
            Field("Текст запроса для ИИ", draft, { draft = it }, false)
            Button(onClick = { instructions = draft.trim(); prefs.edit().putString("instructions", instructions).apply(); editing = false }, enabled = draft.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Сохранить") }
            OutlinedButton(onClick = { draft = defaultCompatibilityInstructions }, modifier = Modifier.fillMaxWidth()) { Text("Вернуть исходный текст") }
            TextButton(onClick = { editing = false }) { Text("Отмена") }
        }
        return
    }
    FormDialog("Запрос для Алисы AI", dismiss) {
        Text("ИИ может ошибаться. Не меняйте лечение без врача. Запрос включает все сохранённые назначения — проверьте, что список актуален.", fontSize = 16.sp)
        OutlinedButton(onClick = { draft = instructions; editing = true }, modifier = Modifier.fillMaxWidth()) { Text("Настроить текст запроса") }
        Field("Возраст, аллергии, другие лекарства", details, { details = it }, false)
        Text(prompt, fontSize = 15.sp)
        Button(onClick = { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Запрос о назначениях", prompt)); Toast.makeText(context, "Запрос скопирован. Вставьте его в Алису AI", Toast.LENGTH_LONG).show() }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Скопировать запрос") }
        OutlinedButton(onClick = { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Запрос о назначениях", prompt)); Toast.makeText(context, "Запрос скопирован. Вставьте его в чат Алисы", Toast.LENGTH_LONG).show(); openIntent(context, Intent(Intent.ACTION_VIEW, Uri.parse("https://alice.yandex.ru/"))) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Скопировать и открыть Алису") }
        Text("После открытия Алисы нажмите на поле сообщения и выберите «Вставить». Отправьте вопрос, когда будете готовы. При отправке эти сведения получит внешний сервис.", fontSize = 14.sp, color = Color(0xFF68736A))
        TextButton(onClick = dismiss, modifier = Modifier.fillMaxWidth()) { Text("Закрыть") }
    }
}
private fun offsetLabel(minutes: Int): String = when { minutes == 1440 -> "За день"; minutes % 1440 == 0 -> "За ${minutes / 1440} дн."; minutes % 60 == 0 -> "За ${minutes / 60} ч."; else -> "За $minutes мин." }
internal fun openIntent(context: Context, intent: Intent) { try { context.startActivity(intent) } catch (_: ActivityNotFoundException) { Toast.makeText(context, "Подходящее приложение не найдено", Toast.LENGTH_LONG).show() } }
private fun exportCalendar(context: Context, visit: Visit) {
    val begin = visit.dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    openIntent(context, Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI).putExtra(CalendarContract.Events.TITLE, visit.doctor).putExtra(CalendarContract.Events.EVENT_LOCATION, visit.place).putExtra(CalendarContract.Events.DESCRIPTION, visit.notes).putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin).putExtra(CalendarContract.EXTRA_EVENT_END_TIME, begin + 3600000))
}