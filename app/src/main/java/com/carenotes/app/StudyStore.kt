package com.carenotes.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

internal class StudyStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("care_notes", Context.MODE_PRIVATE)
    fun all(): List<Study> = parseStudies(JSONArray(prefs.getString("studies", "[]")))
    fun save(study: Study) = synchronized(FamilySync.localGuard) {
        check(FamilySync(context).editable) { "Доступ только для просмотра" }
        validateStudy(study)
        // Read again at commit time so edits to other studies received through sync are retained.
        val items = all().filterNot { it.id == study.id } + study
        check(prefs.edit().putString("studies", studiesJson(items).toString()).commit())
        FamilySync.async(context)
    }
    fun delete(id: String) = synchronized(FamilySync.localGuard) {
        check(FamilySync(context).editable) { "Доступ только для просмотра" }
        Documents(context).deleteStudy(id)
        check(prefs.edit().putString("studies", studiesJson(all().filterNot { it.id == id }).toString()).commit())
        FamilySync.async(context)
    }
    fun updateMeasurements(id: String, change: (List<StudyMeasurement>) -> List<StudyMeasurement>) = synchronized(FamilySync.localGuard) {
        val study = all().firstOrNull { it.id == id } ?: error("Исследование уже удалено")
        save(study.copy(measurements = change(study.measurements)))
    }
}

internal fun parseStudies(array: JSONArray): List<Study> = (0 until array.length()).map { i ->
    val o = array.getJSONObject(i)
    val values = o.optJSONArray("measurements") ?: JSONArray()
    Study(o.getString("id"), o.getString("title"), LocalDate.parse(o.getString("date")), o.getString("type"), o.optString("facility"), o.optString("notes"),
        (0 until values.length()).map { j -> values.getJSONObject(j).let { m -> StudyMeasurement(m.getString("id"), m.getString("name"), m.getString("value"), m.getString("unit"), m.getString("specimen")) } }
    ).also(::validateStudy)
}

internal fun studiesJson(items: List<Study>): JSONArray = JSONArray(items.map { s ->
    JSONObject().put("id", s.id).put("title", s.title).put("date", s.date.toString()).put("type", s.type).put("facility", s.facility).put("notes", s.notes)
        .put("measurements", JSONArray(s.measurements.map { m -> JSONObject().put("id", m.id).put("name", m.name).put("value", m.value).put("unit", m.unit).put("specimen", m.specimen) }))
})
