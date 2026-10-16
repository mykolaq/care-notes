package com.carenotes.app

import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

internal val studyTypes = listOf("Анализы", "УЗИ", "КТ", "МРТ", "Гистология", "Другое")

internal data class Study(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val date: LocalDate,
    val type: String = "Анализы",
    val facility: String = "",
    val notes: String = "",
    val measurements: List<StudyMeasurement> = emptyList()
)

internal data class StudyMeasurement(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val value: String,
    val unit: String,
    val specimen: String
)

internal fun measurementNumber(text: String): BigDecimal? {
    val input = text.trim()
    if (input.length > 32 || !input.matches(Regex("[+-]?(?:\\d+(?:[.,]\\d*)?|[.,]\\d+)"))) return null
    return input.replace(',', '.').toBigDecimalOrNull()?.takeIf { it.toDouble().isFinite() }
}

private fun normalizedMeasurement(text: String) = text.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
internal data class MeasurementKey(val name: String, val unit: String, val specimen: String)
internal fun StudyMeasurement.key() = MeasurementKey(normalizedMeasurement(name), normalizedMeasurement(unit), normalizedMeasurement(specimen))
internal data class StudyPoint(val studyId: String, val measurementId: String, val date: LocalDate, val studyTitle: String, val measurement: StudyMeasurement) {
    val number: BigDecimal get() = requireNotNull(measurementNumber(measurement.value))
}
internal fun studySeries(studies: List<Study>): Map<MeasurementKey, List<StudyPoint>> = studies.flatMap { study ->
    study.measurements.filter { measurementNumber(it.value) != null }.map { StudyPoint(study.id, it.id, study.date, study.title, it) }
}.groupBy { it.measurement.key() }.mapValues { (_, points) -> points.sortedWith(compareBy({ it.date }, { it.studyId }, { it.measurementId })) }

// Preserve the actual spacing between dates; rows below the chart expose coincident points individually.
internal fun studyPointX(date: LocalDate, first: LocalDate, last: LocalDate): Float =
    if (first == last) .5f else ((date.toEpochDay() - first.toEpochDay()).toDouble() / (last.toEpochDay() - first.toEpochDay())).toFloat()

internal fun validDocumentOwner(visitId: String, studyId: String, visits: Set<String>, studies: Set<String>): Boolean =
    (visitId.isNotEmpty() && studyId.isEmpty() && visitId in visits) ||
        (studyId.isNotEmpty() && visitId.isEmpty() && studyId in studies)

internal fun validateStudy(study: Study) {
    require(study.id.matches(Regex("[a-zA-Z0-9-]{1,80}")))
    require(study.title.isNotBlank() && study.title.length <= 500 && study.type in studyTypes)
    require(study.facility.length <= 500 && study.notes.length <= 50000 && study.measurements.size <= 500)
    require(study.measurements.map { it.id }.distinct().size == study.measurements.size)
    study.measurements.forEach {
        require(it.id.matches(Regex("[a-zA-Z0-9-]{1,80}")))
        require(it.name.isNotBlank() && it.name.length <= 200 && it.unit.isNotBlank() && it.unit.length <= 80)
        require(it.specimen.isNotBlank() && it.specimen.length <= 200 && measurementNumber(it.value) != null)
    }
}
