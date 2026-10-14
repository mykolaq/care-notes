package com.carenotes.app

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import java.time.LocalDateTime

object Reminders {
    const val CHANNEL = "visits"
    fun channel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Посещения врача", NotificationManager.IMPORTANCE_HIGH).apply { description = "Напоминания о предстоящих посещениях" })
    }
    private fun pending(context: Context, visit: Visit, offset: Int): PendingIntent = PendingIntent.getBroadcast(context, 0, Intent(context, ReminderReceiver::class.java).setData(Uri.parse("carenotes://reminder/${visit.id}/$offset")).putExtra("visitId", visit.id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun cancel(context: Context, visit: Visit, clearNotification: Boolean = true) { val manager = context.getSystemService(AlarmManager::class.java); visit.reminders.forEach { manager.cancel(pending(context, visit, it)) }; if (clearNotification) context.getSystemService(NotificationManager::class.java).cancel(visit.id, 1) }
    fun schedule(context: Context, visit: Visit) {
        val manager = context.getSystemService(AlarmManager::class.java)
        reminderTimes(visit, System.currentTimeMillis()).forEach { (offset, time) ->
            val p = pending(context, visit, offset)
            try { if (manager.canScheduleExactAlarms()) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, p) else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, p) }
            catch (_: SecurityException) { manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, p) }
        }
    }
    fun restore(context: Context) { MedicineReminders.restore(context); Store(context).visits().forEach { cancel(context, it, clearNotification = false); schedule(context, it) } }
}
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val visit = Store(context).visits().firstOrNull { it.id == intent.getStringExtra("visitId") } ?: return
        if (visit.dateTime.isBefore(LocalDateTime.now())) return
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        Reminders.channel(context)
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).setData(Uri.parse("carenotes://visit/${visit.id}")).putExtra("visitId", visit.id).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val protected = AppLock(context).enabled
        val text = "${visit.dateTime.format(dateFormat)} в ${visit.dateTime.format(timeFormat)}${if (visit.place.isBlank()) "" else " • ${visit.place}"}"
        context.getSystemService(NotificationManager::class.java).notify(visit.id, 1, Notification.Builder(context, Reminders.CHANNEL).setSmallIcon(R.drawable.ic_health).setContentTitle(if (protected) "Посещение врача" else visit.doctor).setContentText(if (protected) "Откройте приложение для подробностей" else text).setStyle(Notification.BigTextStyle().bigText(if (protected) "Откройте приложение для подробностей" else text)).setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(open).setAutoCancel(true).build())
    }
}
class RescheduleReceiver : BroadcastReceiver() { override fun onReceive(context: Context, intent: Intent) { Reminders.restore(context) } }