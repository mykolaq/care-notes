package com.carenotes.app

import org.junit.Assert.*
import org.junit.Test

class StudyRecognitionTest {
    @Test fun multipleBiochemistryRowsKeepValueUnitAndSource() {
        val result = recognizeMeasurements("""
            Учебный бланк
            Материал: кровь
            Креатинин:90 мкмоль/л 44–80
            АЛТ 24,5 Ед/л 0–35
            Глюкоза 5.2 ммоль/л 3.9–6.1
        """.trimIndent())
        assertEquals(listOf("Креатинин", "АЛТ", "Глюкоза"), result.map { it.measurement.name })
        assertEquals(listOf("90", "24,5", "5.2"), result.map { it.measurement.value })
        assertEquals(listOf("мкмоль/л", "Ед/л", "ммоль/л"), result.map { it.measurement.unit })
        assertTrue(result.all { it.measurement.specimen == "кровь" })
        assertEquals("Креатинин:90 мкмоль/л 44–80", result.first().source)
    }
    @Test fun bloodCountPowersPercentAndFlags() {
        val result = recognizeMeasurements("Лейкоциты 6,2 10^9/л\nЭритроциты 4.8 ×10^12/л\nЛимфоциты 35 %\nГемоглобин 125* г/л")
        assertEquals(listOf("6,2", "4.8", "35", "125"), result.map { it.measurement.value })
        assertEquals("×10^12/л", result[1].measurement.unit)
    }
    @Test fun unitsBeforeResultsAndLatinUnits() {
        val result = recognizeMeasurements("Креатинин мкмоль/л 90 44–80\nГлюкоза 5.2 mmol/L\nБелок 12 mg/dL")
        assertEquals(listOf("90", "5.2", "12"), result.map { it.measurement.value })
        assertEquals("Креатинин", result.first().measurement.name)
    }
    @Test fun uncertainRangesAndComparatorsAreNotConvertedToExactValues() {
        val text = "Креатинин 44-80 мкмоль/л\nКреатинин 44–80 мкмоль/л\nБелок <5 мг/л\nБелок >10 мг/л\nНорма 44 мкмоль/л\nГлюкоза ммоль/л 3.9–6.1\nКреатинин 90"
        assertTrue(recognizeMeasurements(text).isEmpty())
    }
    @Test fun missingSpecimenIsNotGuessedAndNonbreakingSpacesAreAccepted() {
        val m = recognizeMeasurements("Креатинин\u00a090\u00a0мкмоль / л").single().measurement
        assertEquals("мкмоль/л", m.unit)
        assertEquals("", m.specimen)
    }
    @Test fun repeatedImportKeepsOriginalIdAndIgnoresDecimalFormatting() {
        val a = StudyMeasurement(name = "Креатинин", value = "80", unit = "мкмоль/л", specimen = "Кровь")
        val b = a.copy(id = "other", value = "80,0")
        val result = mergeRecognizedMeasurements(listOf(a), listOf(b, b))
        assertEquals(listOf(a), result)
        assertEquals(2, mergeRecognizedMeasurements(listOf(a), listOf(b.copy(unit = "мг/дл"))).size)
        assertEquals(2, mergeRecognizedMeasurements(listOf(a), listOf(b.copy(specimen = "Моча"))).size)
    }
}
