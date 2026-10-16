package com.carenotes.app

import java.time.LocalDate

data class VisitDocument(val id: String, val visitId: String, val name: String, val mime: String, val fileName: String, val text: String = "", val studyId: String = "")

/** Only explicit dose and duration are copied. The user chooses lines and reviews every draft. */
fun documentDraft(line: String, visit: Visit, documentName: String): Prescription {
    val clean = line.trim().replace(Regex("^\\s*\\d+[.)]\\s*"), "")
    val dose = Regex("(?<![\\p{L}\\p{N}])\\d+(?:[.,]\\d+)?(?:\\s*[/+]\\s*\\d+(?:[.,]\\d+)?)*\\s*(?:мкг|мг|мл|mg|ml|г|%)(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE).find(clean)
    val days = Regex("\\b(\\d{1,3})\\s*(?:дней|дня|день|дн\\.?)(?=\\s|$|[,;])", RegexOption.IGNORE_CASE).find(clean)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
    val name = (dose?.let { clean.substring(0, it.range.first) } ?: clean).trim().trimEnd('-', '—', ':', ',')
    return Prescription(name = name, dose = dose?.value ?: "", schedule = clean, doctor = visit.doctor,
        notes = "Из документа «$documentName», посещение ${visit.dateTime.toLocalDate().format(dateFormat)}. Проверьте по оригиналу.",
        prescribedOn = visit.dateTime.toLocalDate(), courseDays = days)
}

fun documentAlicePrompt(text: String): String = "Извлеки из документа только явно записанные назначения: название лекарства, дозировку, схему приёма и длительность курса. Не добавляй своих рекомендаций, не угадывай неразборчивые слова или цифры. Если поле не указано, напиши «не указано». Отдельно перечисли неясные места для проверки по оригиналу.\n\nДокумент:\n$text"
