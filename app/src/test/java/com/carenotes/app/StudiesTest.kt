package com.carenotes.app

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class StudiesTest {
    private val date = LocalDate.of(2026, 10, 1)
    private fun value(number: String, unit: String = "мкмоль/л", specimen: String = "Кровь", name: String = "Креатинин") = StudyMeasurement(name = name, value = number, unit = unit, specimen = specimen)
    private fun study(id: String, day: Long, vararg values: StudyMeasurement) = Study(id = id, title = "Анализ", date = date.plusDays(day), measurements = values.toList())

    @Test fun decimalCommaAndPrecisionArePreserved() {
        assertEquals("80.125", measurementNumber("80,125")!!.toPlainString())
        assertEquals("0.5", measurementNumber(",5")!!.toPlainString())
        assertEquals("-2", measurementNumber("-2")!!.toPlainString())
        listOf("NaN", "Infinity", "<5", "80 мг", "1e309", "", "1,2.3").forEach { assertNull(it, measurementNumber(it)) }
    }
    @Test fun seriesUsesDateAndLinksToOriginalStudy() {
        val a = value("90"); val b = value("80")
        val points = studySeries(listOf(study("new", 10, a), study("old", 0, b))).values.single()
        assertEquals(listOf("old", "new"), points.map { it.studyId })
        assertEquals(b.id, points.first().measurementId)
        assertEquals("80", points.first().number.toPlainString())
    }
    @Test fun unitsAndSpecimensStayInSeparateCharts() {
        val series = studySeries(listOf(study("one", 0, value("80"), value("1", "мг/дл"), value("90", specimen = "Моча"))))
        assertEquals(3, series.size)
    }
    @Test fun casingAndWhitespaceDoNotSplitSeries() {
        val series = studySeries(listOf(study("one", 0, value("80")), study("two", 1, value("90", " МКМОЛЬ/Л ", " кровь ", " креатинин "))))
        assertEquals(1, series.size); assertEquals(2, series.values.single().size)
    }
    @Test fun sameDateAndSameValueKeepBothSources() {
        val series = studySeries(listOf(study("one", 0, value("80")), study("two", 0, value("80")))).values.single()
        assertEquals(2, series.size)
        assertEquals(.5f, studyPointX(date, date, date), .001f)
        assertEquals(.1f, studyPointX(date.plusDays(1), date, date.plusDays(10)), .001f)
    }
    @Test fun documentsMustHaveExactlyOneExistingOwner() {
        assertTrue(validDocumentOwner("v", "", setOf("v"), setOf("s")))
        assertTrue(validDocumentOwner("", "s", setOf("v"), setOf("s")))
        assertFalse(validDocumentOwner("v", "s", setOf("v"), setOf("s")))
        assertFalse(validDocumentOwner("", "", setOf("v"), setOf("s")))
        assertFalse(validDocumentOwner("", "gone", setOf("v"), setOf("s")))
    }
    @Test fun validationRejectsNonNumericValuesAndDuplicateMeasurementIds() {
        val v = value("80")
        listOf(study("one", 0, value("<80")), study("one", 0, v, v)).forEach {
            try { validateStudy(it); fail("Invalid study accepted") } catch (_: IllegalArgumentException) { }
        }
    }
}
