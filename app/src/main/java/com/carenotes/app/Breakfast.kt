package com.carenotes.app

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal class BreakfastStore(context: Context) {
    private val prefs = context.getSharedPreferences("breakfast", Context.MODE_PRIVATE)
    private val store = Store(context)
    fun read(): BreakfastState? = runCatching {
        val o = JSONObject(prefs.getString("state", "")!!)
        val ids = o.getJSONArray("ids")
        val taken = o.getJSONObject("taken")
        BreakfastState(LocalDate.parse(o.getString("day")), o.getLong("wokeAt"), (0 until ids.length()).map { ids.getString(it) }.toSet(), taken.keys().asSequence().associateWith { taken.getLong(it) }, o.optLong("mealAt").takeIf { it > 0 }, o.optLong("finishedAt").takeIf { it > 0 }, o.optJSONArray("plan")?.let(store::prescriptions))
    }.getOrNull()
    fun save(state: BreakfastState) {
        check(prefs.edit().putString("state", JSONObject().put("day", state.day.toString()).put("wokeAt", state.wokeAt).put("ids", JSONArray(state.ids.toList())).put("taken", JSONObject(state.taken)).put("mealAt", state.mealAt ?: 0).put("finishedAt", state.finishedAt ?: 0).put("plan", state.snapshot?.let(::prescriptionsJson)).toString()).commit())
    }
}

internal data class BreakfastAlarm(val id: String, val due: Long, val title: String, val text: String)
internal object BreakfastReminders {
    private fun pending(context: Context, id: String, due: Long = 0) = PendingIntent.getBroadcast(context, 0,
        Intent(context, BreakfastReceiver::class.java).setData(Uri.parse("carenotes://breakfast/$id")).putExtra("id", id).putExtra("due", due), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun alarms(context: Context): List<BreakfastAlarm> {
        val store = Store(context)
        if (!store.medicineSettings().enabled) return emptyList()
        val state = BreakfastStore(context).read()?.takeIf { it.day == LocalDate.now() } ?: return emptyList()
        val plan = BreakfastPlan(store.prescriptions(), state)
        val reminders = plan.items.filter { it.remindersEnabled }
        return reminders.filter { it.id !in state.taken }.mapNotNull { p ->
            // Stages before breakfast must never reappear once the meal has started.
            if (p.breakfastStage in listOf("wake", "before") && state.mealAt != null || p.breakfastStage == "with" && state.finishedAt != null) return@mapNotNull null
            plan.due(p)?.let { BreakfastAlarm("dose:${p.id}", it, p.name, listOf(p.breakfastLabel(), p.dose, p.schedule).filter(String::isNotBlank).joinToString(" · ")) }
        } + if (state.mealAt == null && reminders.isNotEmpty()) listOfNotNull(plan.mealReady()?.let { BreakfastAlarm("meal", it, "Можно начинать завтрак", "Указанные интервалы после подтверждённых приёмов прошли. Отметьте начало еды в приложении.") }) else emptyList()
    }
    fun restore(context: Context) {
        val prefs = context.getSharedPreferences("breakfast_alarms", Context.MODE_PRIVATE)
        val manager = context.getSystemService(AlarmManager::class.java)
        val old = prefs.getStringSet("scheduled", emptySet())!!.toSet()
        val wanted = alarms(context)
        old.forEach { id ->
            manager.cancel(pending(context, id))
            if (wanted.none { it.id == id }) context.getSystemService(NotificationManager::class.java).cancel("breakfast:$id", 0)
        }
        val delivered = prefs.getStringSet("delivered", emptySet())!!.toSet()
        wanted.filter { "${it.id}:${it.due}" !in delivered }.forEach { alarm ->
            val p = pending(context, alarm.id, alarm.due)
            val due = maxOf(System.currentTimeMillis() + 500, alarm.due)
            try { if (manager.canScheduleExactAlarms()) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, p) else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, p) }
            catch (_: SecurityException) { manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, p) }
        }
        // The registry also includes delivered notifications, so turning reminders off clears them.
        prefs.edit().putStringSet("scheduled", wanted.map { it.id }.toSet()).putStringSet("delivered", delivered.intersect(wanted.map { "${it.id}:${it.due}" }.toSet())).apply()
    }
    fun notify(context: Context, id: String?, due: Long) {
        val alarm = alarms(context).firstOrNull { it.id == id && it.due == due } ?: return
        if (due > System.currentTimeMillis() || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val prefs = context.getSharedPreferences("breakfast_alarms", Context.MODE_PRIVATE)
        val key = "${alarm.id}:${alarm.due}"
        val delivered = prefs.getStringSet("delivered", emptySet())!!.toSet()
        if (key in delivered) return
        prefs.edit().putStringSet("delivered", delivered + key).apply()
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).setData(Uri.parse("carenotes://breakfast-open")).putExtra("breakfast", true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(MedicineReminders.CHANNEL, "Применение назначений", NotificationManager.IMPORTANCE_HIGH))
        val protected = AppLock(context).enabled
        val title = if (protected) "Утренние назначения" else alarm.title
        val text = if (protected) "Откройте приложение для подробностей" else alarm.text
        manager.notify("breakfast:${alarm.id}", 0, Notification.Builder(context, MedicineReminders.CHANNEL).setSmallIcon(R.drawable.ic_health).setContentTitle(title).setContentText(text).setStyle(Notification.BigTextStyle().bigText(text)).setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(open).setAutoCancel(true).build())
    }
}
class BreakfastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { BreakfastReminders.notify(context, intent.getStringExtra("id"), intent.getLongExtra("due", 0)) }
}

@Composable internal fun BreakfastEntry(context: Context, items: List<Prescription>, openRequested: Boolean, consumed: () -> Unit) {
    val store = remember { BreakfastStore(context) }
    var state by remember { mutableStateOf(store.read()) }
    var visible by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
    val current = state?.takeIf { it.day == today }
    val todayItems = items.filter { it.breakfastStage in breakfastStages && it.isDoseDay(today) }
    LaunchedEffect(openRequested) { if (openRequested) { state = store.read(); visible = true; consumed() } }
    fun update(next: BreakfastState) { store.save(next); state = next; BreakfastReminders.restore(context) }
    fun start() { update(BreakfastState(today, System.currentTimeMillis(), todayItems.map { it.id }.toSet(), snapshot = todayItems)) }
    if (items.none { it.breakfastStage in breakfastStages } && !visible) return
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(LocalContentPadding.current), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Моё утро", style = MaterialTheme.typography.titleLarge)
            Text("Приём лекарств от пробуждения и завтрака. Время подъёма может меняться.")
            if (todayItems.isEmpty() && current == null) Text("На сегодня утренних назначений нет.")
            Button(onClick = { if (current == null && todayItems.isNotEmpty()) start(); visible = true }, enabled = todayItems.isNotEmpty() || current != null, modifier = Modifier.fillMaxWidth()) { Text(if (current == null) "Я проснулся" else if (BreakfastPlan(items, current).complete()) "Утро завершено · посмотреть" else "Продолжить моё утро") }
        }
    }
    if (visible) FormDialog("Моё утро · ${today.format(dateFormat)}", { visible = false }) {
        if (current == null) {
            Text("Нажмите после пробуждения. Приём каждого лекарства нужно подтвердить отдельно.")
            Button(onClick = { start() }, enabled = todayItems.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Я проснулся") }
        } else {
            val plan = BreakfastPlan(items, current)
            Text("Проснулись в ${clock(current.wokeAt)}. Отсчёт интервалов идёт от отметок «Принял» и окончания еды.")
            if (!Store(context).medicineSettings().enabled) Text("Напоминания о лекарствах выключены. Отметки и отсчёт в приложении доступны.")
            listOf("wake", "before").forEach { stage -> BreakfastDoses(plan, stage, now) { p -> update(current.copy(taken = current.taken + (p.id to System.currentTimeMillis()))) } }
            HorizontalDivider()
            Text("Завтрак", style = MaterialTheme.typography.titleLarge)
            val ready = plan.mealReady()
            if (current.mealAt == null) {
                Text(if (ready == null) "Сначала подтвердите приём лекарств после пробуждения и перед завтраком." else if (now < ready) "Начать не раньше ${clock(ready)} · осталось ${minutesLeft(ready, now)} мин." else "Указанные интервалы прошли. Можно начинать завтрак.")
                Button(onClick = { if (plan.canStartMeal(System.currentTimeMillis())) update(current.copy(mealAt = System.currentTimeMillis())) }, enabled = plan.canStartMeal(now), modifier = Modifier.fillMaxWidth()) { Text("Начинаю завтрак") }
            } else Text("Начало завтрака: ${clock(current.mealAt)}")
            BreakfastDoses(plan, "with", now) { p -> update(current.copy(taken = current.taken + (p.id to System.currentTimeMillis()))) }
            if (current.finishedAt == null) {
                Button(onClick = { if (plan.canFinishMeal()) update(current.copy(finishedAt = System.currentTimeMillis())) }, enabled = plan.canFinishMeal(), modifier = Modifier.fillMaxWidth()) { Text("Завтрак закончен") }
                if (current.mealAt != null && !plan.canFinishMeal()) Text("Подтвердите лекарства, которые нужно принять во время еды.")
            } else Text("Завтрак закончен в ${clock(current.finishedAt)}")
            BreakfastDoses(plan, "after", now) { p -> update(current.copy(taken = current.taken + (p.id to System.currentTimeMillis()))) }
            if (plan.complete()) Text("Все утренние приёмы отмечены.", fontWeight = FontWeight.Bold)
            Text("Сценарий и отметки сохраняются на этом устройстве. Добавление и изменение назначений действуют со следующего утра. Удаление записей и выключение напоминаний действуют сразу.", style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = { visible = false }, modifier = Modifier.fillMaxWidth()) { Text("Закрыть") }
    }
}

@Composable private fun BreakfastDoses(plan: BreakfastPlan, stage: String, now: Long, take: (Prescription) -> Unit) {
    val items = plan.group(stage)
    if (items.isEmpty()) return
    Text(breakfastLabels[breakfastStages.indexOf(stage)], style = MaterialTheme.typography.titleMedium)
    items.forEach { p ->
        OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(p.name, fontWeight = FontWeight.Bold)
            if (p.dose.isNotBlank()) Text(p.dose)
            if (p.schedule.isNotBlank()) Text(p.schedule)
            if (stage == "wake") Text("До еды — не менее ${p.breakfastMinutes} мин.")
            if (stage == "before") Text("Перед едой — не менее ${p.breakfastMinutes} мин.")
            if (stage == "after") Text("Через ${p.breakfastMinutes} мин. после окончания еды")
            val taken = plan.state.taken[p.id]
            val due = plan.due(p)
            if (taken != null) Text("Принято в ${clock(taken)}")
            else {
                if (due != null && due > now) Text("Не раньше ${clock(due)} · осталось ${minutesLeft(due, now)} мин.")
                Button(onClick = { if (plan.canTake(p, System.currentTimeMillis())) take(p) }, enabled = plan.canTake(p, now), modifier = Modifier.fillMaxWidth()) { Text("Принял") }
            }
        } }
    }
}
private fun clock(time: Long): String = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).format(timeFormat)
private fun minutesLeft(due: Long, now: Long) = ((due - now + 59_999) / 60_000).coerceAtLeast(0)
