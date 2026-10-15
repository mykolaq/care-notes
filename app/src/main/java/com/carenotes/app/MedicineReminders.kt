package com.carenotes.app

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import java.time.Instant
import java.time.ZoneId

object MedicineReminders {
    const val CHANNEL = "medicines"
    private fun pending(context: Context, p: Prescription, period: Int, due: Long = 0): PendingIntent = PendingIntent.getBroadcast(context, 0, Intent(context, MedicineReceiver::class.java).setData(Uri.parse("carenotes://medicine/${p.id}/$period")).putExtra("medicineId", p.id).putExtra("period", period).putExtra("due", due), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun cancel(context: Context, p: Prescription, clear: Boolean = true) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        (0..3).forEach { alarms.cancel(pending(context, p, it)) }
        if (clear) (0..3).forEach { context.getSystemService(NotificationManager::class.java).cancel("medicine:${p.id}", it) }
    }
    fun schedule(context: Context, p: Prescription, settings: MedicineSettings = Store(context).medicineSettings(), after: Long = System.currentTimeMillis(), onlyPeriod: Int? = null) {
        if (!settings.enabled || !p.remindersEnabled) return
        val alarms = context.getSystemService(AlarmManager::class.java)
        p.periods.distinct().filter { it in 0..3 && (onlyPeriod == null || it == onlyPeriod) }.forEach { slot ->
            p.nextDose(settings.times[slot], after)?.let { due ->
                val pending = pending(context, p, slot, due)
                try { if (alarms.canScheduleExactAlarms()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, pending) else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, pending) }
                catch (_: SecurityException) { alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, pending) }
            }
        }
    }
    fun restore(context: Context) { val store = Store(context); val settings = store.medicineSettings(); store.prescriptions().forEach { cancel(context, it, false); schedule(context, it, settings) }; BreakfastReminders.restore(context) }
}
class MedicineReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = Store(context)
        val p = store.prescriptions().firstOrNull { it.id == intent.getStringExtra("medicineId") } ?: return
        val settings = store.medicineSettings()
        val slot = intent.getIntExtra("period", -1)
        val due = intent.getLongExtra("due", 0)
        if (!settings.enabled || !p.remindersEnabled || slot !in p.periods || slot !in 0..3) return
        MedicineReminders.schedule(context, p, settings, maxOf(System.currentTimeMillis(), due), onlyPeriod = slot)
        val date = Instant.ofEpochMilli(due).atZone(ZoneId.systemDefault()).toLocalDate()
        if (!p.isDoseDay(date) || date != java.time.LocalDate.now()) return
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(MedicineReminders.CHANNEL, "Применение назначений", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).setData(Uri.parse("carenotes://medicine-open/${p.id}")).putExtra("medicineId", p.id).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val protected = AppLock(context).enabled
        val text = listOf("${periodNames[slot]} · ${settings.times[slot].format(timeFormat)}", p.dose, p.application, p.meal, p.schedule).filter { it.isNotBlank() }.joinToString(" • ")
        manager.notify("medicine:${p.id}", slot, Notification.Builder(context, MedicineReminders.CHANNEL).setSmallIcon(R.drawable.ic_health).setContentTitle(if (protected) "Выполнить назначение" else "${if (p.application == "Внутрь") "Принять" else "Применить"}: ${p.name}").setContentText(if (protected) "Откройте приложение для подробностей" else text).setStyle(Notification.BigTextStyle().bigText(if (protected) "Откройте приложение для подробностей" else text)).setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(open).setAutoCancel(true).build())
    }
}
