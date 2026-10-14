package com.carenotes.app
import org.junit.Assert.*
import org.junit.Test
import java.time.*
class ModelsTest {
    @Test fun expiredRemindersAreSkippedAndDuplicatesRemoved() {
        val v = Visit(doctor = "Врач", place = "", dateTime = LocalDateTime.of(2026, 10, 13, 10, 0), notes = "", reminders = listOf(1440, 120, 120, 60))
        val now = v.dateTime.toInstant(ZoneOffset.UTC).toEpochMilli() - 90 * 60_000L
        val result = reminderTimes(v, now, ZoneOffset.UTC)
        assertEquals(listOf(60), result.map { it.first })
        assertEquals(v.dateTime.toInstant(ZoneOffset.UTC).toEpochMilli() - 60 * 60_000L, result.single().second)
    }
    @Test fun localAppointmentUsesSuppliedTimezone() {
        val v = Visit(doctor = "Врач", place = "", dateTime = LocalDateTime.of(2026, 10, 13, 10, 0), notes = "", reminders = listOf(60))
        assertEquals(Instant.parse("2026-10-13T06:00:00Z").toEpochMilli(), reminderTimes(v, 0, ZoneId.of("Asia/Baghdad")).single().second)
    }
    @Test fun promptIncludesAllMedicinesAndMissingInformation() {
        val prompt = compatibilityPrompt(listOf(Prescription(name = "Препарат А", dose = "5 мг", schedule = "Утром", doctor = "", notes = ""), Prescription(name = "Препарат Б", dose = "", schedule = "", doctor = "", notes = "")), "Аллергия")
        assertTrue(prompt.contains("Препарат А")); assertTrue(prompt.contains("Препарат Б")); assertTrue(prompt.contains("5 мг")); assertTrue(prompt.contains("не указана")); assertTrue(prompt.contains("Аллергия")); assertTrue(prompt.contains("Не предлагай самостоятельно отменять"))
    }
}