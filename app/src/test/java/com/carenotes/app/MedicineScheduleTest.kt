package com.carenotes.app
import org.junit.Assert.*
import org.junit.Test
import java.time.*
class MedicineScheduleTest {
    private val start = LocalDate.of(2026, 10, 12)
    private fun medicine() = Prescription(name = "Лекарство", dose = "", schedule = "", doctor = "", notes = "", startOn = start)
    @Test fun alternateDaysAreAnchoredToStartAndCourseIsInclusive() {
        val p = medicine().copy(intervalDays = 2, courseDays = 5)
        assertFalse(p.isDoseDay(start.minusDays(1)))
        assertTrue(p.isDoseDay(start)); assertFalse(p.isDoseDay(start.plusDays(1)))
        assertTrue(p.isDoseDay(start.plusDays(4))); assertFalse(p.isDoseDay(start.plusDays(6)))
        assertEquals(start.plusDays(4), p.endOn())
    }
    @Test fun nextDoseSkipsElapsedTimeAndStopsAtEndOfCourse() {
        val p = medicine().copy(intervalDays = 2, courseDays = 3)
        val now = start.atTime(9, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val next = start.plusDays(2).atTime(8, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals(next, p.nextDose(LocalTime.of(8, 0), now, ZoneOffset.UTC))
        assertNull(p.nextDose(LocalTime.of(8, 0), next, ZoneOffset.UTC))
    }
    @Test fun weeklyScheduleUsesSelectedWeekdaysAndTimezone() {
        val p = medicine().copy(weekdays = listOf(1, 5))
        val now = Instant.parse("2026-10-12T06:00:00Z").toEpochMilli()
        assertEquals(Instant.parse("2026-10-16T05:00:00Z").toEpochMilli(), p.nextDose(LocalTime.of(8, 0), now, ZoneId.of("Asia/Baghdad")))
        assertFalse(p.isDoseDay(start.plusDays(1)))
    }
    @Test fun intervalDoesNotRequireScanningEveryDay() {
        val p = medicine().copy(intervalDays = 999)
        val now = start.atTime(9, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals(start.plusDays(999).atTime(8, 0).toInstant(ZoneOffset.UTC).toEpochMilli(), p.nextDose(LocalTime.of(8, 0), now, ZoneOffset.UTC))
    }
    @Test fun oldPrescriptionsHaveNoInventedStartOrReminders() {
        val p = medicine().copy(startOn = null)
        assertFalse(p.isDoseDay(start)); assertFalse(p.remindersEnabled)
        assertNull(p.nextDose(LocalTime.NOON, 0, ZoneOffset.UTC))
    }
    @Test fun primaryAndRepeatVisitLabels() {
        val v = Visit(doctor = "Врач", place = "", dateTime = start.atTime(10, 0), notes = "")
        assertEquals("", v.kindLabel())
        assertEquals("Первичный приём · Визит №1", v.copy(primary = true, visitNumber = 1).kindLabel())
        assertEquals("Повторный приём · Визит №3", v.copy(primary = false, visitNumber = 3).kindLabel())
    }
}
