package com.carenotes.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.LocalDateTime

class Store(private val context: Context) {
    private val prefs = context.getSharedPreferences("care_notes", Context.MODE_PRIVATE)
    fun prescriptions(array: JSONArray = JSONArray(prefs.getString("prescriptions", "[]"))): List<Prescription> {
        return (0 until array.length()).map { i -> array.getJSONObject(i).let { Prescription(it.getString("id"), it.getString("name"), it.getString("dose"), it.getString("schedule"), it.getString("doctor"), it.getString("notes"), it.optString("prescribedOn").takeIf { v -> v.isNotBlank() }?.let(LocalDate::parse), it.optString("startOn").takeIf { v -> v.isNotBlank() }?.let(LocalDate::parse), it.optInt("courseDays").takeIf { v -> v > 0 }, it.optInt("intervalDays", 1).coerceAtLeast(1), ints(it.optJSONArray("weekdays")), ints(it.optJSONArray("periods")), it.optBoolean("remindersEnabled", false), it.optString("application"), it.optString("meal"), it.optString("inn"), it.optString("photo")) } }
    }
    private fun ints(a: JSONArray?): List<Int> = if (a == null) emptyList() else (0 until a.length()).map { a.getInt(it) }
    fun medicineSettings(): MedicineSettings = MedicineSettings((0..3).map { LocalTime.parse(prefs.getString("period$it", MedicineSettings().times[it].toString())) }, prefs.getBoolean("medicineEnabled", true))
    fun saveMedicineSettings(settings: MedicineSettings) { val edit = prefs.edit().putBoolean("medicineEnabled", settings.enabled); settings.times.forEachIndexed { i, time -> edit.putString("period$i", time.toString()) }; edit.apply() }
    fun visits(array: JSONArray = JSONArray(prefs.getString("visits", "[]"))): List<Visit> {
        return (0 until array.length()).map { i -> array.getJSONObject(i).let { o ->
            val r = o.optJSONArray("reminders") ?: JSONArray()
            Visit(o.getString("id"), o.getString("doctor"), o.getString("place"), LocalDateTime.parse(o.getString("dateTime")), o.getString("notes"), (0 until r.length()).map { r.getInt(it) }, if (o.has("primary")) o.getBoolean("primary") else null, o.optInt("visitNumber").takeIf { it > 0 })
        } }
    }
    fun savePrescriptions(items: List<Prescription>) { val a = JSONArray(); items.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("dose", it.dose).put("schedule", it.schedule).put("doctor", it.doctor).put("notes", it.notes).put("prescribedOn", it.prescribedOn?.toString() ?: "").put("startOn", it.startOn?.toString() ?: "").put("courseDays", it.courseDays ?: 0).put("intervalDays", it.intervalDays).put("weekdays", JSONArray(it.weekdays)).put("periods", JSONArray(it.periods)).put("remindersEnabled", it.remindersEnabled).put("application", it.application).put("meal", it.meal).put("inn", it.inn).put("photo", it.photo)) }; prefs.edit().putString("prescriptions", a.toString()).apply(); FamilySync.async(context) }
    fun saveVisits(items: List<Visit>) { val a = JSONArray(); items.forEach { a.put(JSONObject().put("id", it.id).put("doctor", it.doctor).put("place", it.place).put("dateTime", it.dateTime.toString()).put("notes", it.notes).put("reminders", JSONArray(it.reminders)).put("primary", it.primary).put("visitNumber", it.visitNumber ?: 0)) }; prefs.edit().putString("visits", a.toString()).apply(); FamilySync.async(context) }
    internal fun validateSync(ps: JSONArray, vs: JSONArray) {
        prescriptions(ps).forEach { p ->
            require(p.periods.all { it in 0..3 } && p.weekdays.all { it in 1..7 })
            require(p.intervalDays in 1..36500 && (p.courseDays == null || p.courseDays <= 36500))
            require(p.name.length <= 500 && p.inn.length <= 500 && p.notes.length <= 50000)
            require(p.photo.isEmpty() || validMedicinePhotoName(p.photo)) { "Неверное имя фото лекарства" }
        }
        visits(vs).forEach { v -> require(v.reminders.all { it in 0..525600 } && v.notes.length <= 50000) }
    }
}
