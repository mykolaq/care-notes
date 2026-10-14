package com.carenotes.app

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

class DocumentParserTest {
    private val visit = Visit(doctor = "Терапевт", place = "", dateTime = LocalDateTime.of(2026, 10, 13, 10, 0), notes = "")
    @Test fun explicitFieldsBecomeDraftWithoutAutomaticReminders() {
        val p = documentDraft("1. Амоксиклав 875/125 мг — 7 дней", visit, "Лист.pdf")
        assertEquals("Амоксиклав", p.name)
        assertEquals("875/125 мг", p.dose)
        assertEquals(7, p.courseDays)
        assertNull(p.startOn)
        assertFalse(p.remindersEnabled)
        assertTrue(p.periods.isEmpty())
        assertEquals(visit.dateTime.toLocalDate(), p.prescribedOn)
    }
    @Test fun absentFieldsAreNotInvented() {
        val p = documentDraft("Препарат: уточнить у врача", visit, "Фото")
        assertEquals("", p.dose)
        assertNull(p.courseDays)
        assertNull(p.startOn)
    }
    @Test fun decimalDoseIsPreserved() {
        val p = documentDraft("Лекарство 2,5 мг, 14 дней", visit, "Фото")
        assertEquals("2,5 мг", p.dose)
        assertEquals(14, p.courseDays)
    }
}
