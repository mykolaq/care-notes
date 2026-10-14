package com.carenotes.app

import java.time.LocalDate
import java.time.LocalTime
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

data class Prescription(val id: String = UUID.randomUUID().toString(), val name: String, val dose: String, val schedule: String, val doctor: String, val notes: String, val prescribedOn: LocalDate? = null, val startOn: LocalDate? = null, val courseDays: Int? = null, val intervalDays: Int = 1, val weekdays: List<Int> = emptyList(), val periods: List<Int> = emptyList(), val remindersEnabled: Boolean = false, val application: String = "", val meal: String = "", val inn: String = "", val photo: String = "")
data class Visit(val id: String = UUID.randomUUID().toString(), val doctor: String, val place: String, val dateTime: LocalDateTime, val notes: String, val reminders: List<Int> = listOf(1440, 120), val primary: Boolean? = null, val visitNumber: Int? = null)
val dateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
fun reminderTimes(visit: Visit, now: Long, zone: ZoneId = ZoneId.systemDefault()): List<Pair<Int, Long>> = visit.reminders.distinct().map { it to visit.dateTime.atZone(zone).toInstant().toEpochMilli() - it * 60_000L }.filter { it.second > now }
val defaultCompatibilityInstructions = """Помоги подготовить вопросы врачу или фармацевту о совместимости следующих назначений.
Проверь взаимодействия действующих веществ, дублирование терапии, влияние еды и способа применения. Для существенных выводов приведи проверяемые источники: официальные инструкции или авторитетный справочник. Отдели факты от предположений. Если данных недостаточно, задай вопросы; не делай вывод, что сочетание безопасно.
Не предлагай самостоятельно отменять препараты или менять дозы. Укажи, что обсудить с врачом."""
fun compatibilityPrompt(items: List<Prescription>, context: String, instructions: String = defaultCompatibilityInstructions): String = buildString {
    appendLine(instructions)
    appendLine("\nНазначения:")
    items.forEachIndexed { index, p -> appendLine("${index + 1}. ${p.name}; МНН: ${p.inn.ifBlank { "не указано" }}; дозировка: ${p.dose.ifBlank { "не указана" }}; способ применения: ${p.application.ifBlank { "не указан" }}; связь с едой: ${p.meal.ifBlank { "не указана" }}; применение: ${p.schedule.ifBlank { "не указан" }}; примечания: ${p.notes.ifBlank { "нет" }}") }
    items.forEach { p ->
        appendLine("${p.name}: назначено ${p.prescribedOn?.format(dateFormat) ?: "дата не указана"}; начало ${p.startOn?.format(dateFormat) ?: "не указано"}; курс ${p.courseDays?.let { "$it календарных дней" } ?: "не указан"}; схема ${if (p.startOn == null && p.periods.isEmpty()) "не указана" else p.regimenLabel()}; время суток ${p.periods.joinToString { periodNames[it] }.ifBlank { "не указано" }}")
    }
    appendLine("\nДополнительные сведения (возраст, аллергии, заболевания, другие лекарства и БАДы): ${context.ifBlank { "не указаны — уточни их перед оценкой" }}")
}
val periodNames = listOf("Утро", "День", "Вечер", "Ночь")
data class MedicineSettings(val times: List<LocalTime> = listOf(LocalTime.of(8, 0), LocalTime.of(13, 0), LocalTime.of(18, 0), LocalTime.of(21, 0)), val enabled: Boolean = true)
fun Prescription.endOn(): LocalDate? = courseDays?.let { startOn?.plusDays(it.toLong() - 1) }
fun Prescription.isDoseDay(date: LocalDate): Boolean {
    val start = startOn ?: return false
    if (date < start || endOn()?.let { date > it } == true) return false
    return if (weekdays.isNotEmpty()) date.dayOfWeek.value in weekdays else ChronoUnit.DAYS.between(start, date) % intervalDays.coerceAtLeast(1) == 0L
}
fun Prescription.nextDose(time: LocalTime, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
    val start = startOn ?: return null
    var day = maxOf(start, Instant.ofEpochMilli(now).atZone(zone).toLocalDate())
    if (weekdays.isEmpty()) {
        val interval = intervalDays.coerceAtLeast(1).toLong()
        val remainder = ChronoUnit.DAYS.between(start, day) % interval
        if (remainder != 0L) day = day.plusDays(interval - remainder)
        if (day.atTime(time).atZone(zone).toInstant().toEpochMilli() <= now) day = day.plusDays(interval)
        return if (isDoseDay(day)) day.atTime(time).atZone(zone).toInstant().toEpochMilli() else null
    }
    repeat(8) {
        val timestamp = day.atTime(time).atZone(zone).toInstant().toEpochMilli()
        if (isDoseDay(day) && timestamp > now) return timestamp
        day = day.plusDays(1)
    }
    return null
}
fun Prescription.regimenLabel(): String = if (weekdays.isNotEmpty()) weekdays.sorted().joinToString(", ") { listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")[it - 1] } else when(intervalDays) { 1 -> "Каждый день"; 2 -> "Через день"; else -> "Каждые $intervalDays дн." }

fun Visit.kindLabel(): String = listOfNotNull(when(primary) { true -> "Первичный приём"; false -> "Повторный приём"; null -> null }, visitNumber?.let { "Визит №$it" }).joinToString(" · ")
