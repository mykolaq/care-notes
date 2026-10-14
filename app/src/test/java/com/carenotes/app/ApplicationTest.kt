package com.carenotes.app
import org.junit.Assert.*
import org.junit.Test
class ApplicationTest {
    @Test fun oldPrescriptionDoesNotAssumeOralRoute() {
        val p = Prescription(name = "Хлоргексидин", dose = "", schedule = "", doctor = "", notes = "")
        assertEquals("", p.application)
        assertEquals("", p.meal)
    }
    @Test fun promptIncludesWoundCareAndCustomInstructions() {
        val p = Prescription(name = "Хлоргексидин", dose = "0,05%", schedule = "По назначению врача", doctor = "", notes = "", application = "Наружно / обработка раны")
        val prompt = compatibilityPrompt(listOf(p), "Аллергия", "Мой запрос")
        assertTrue(prompt.startsWith("Мой запрос"))
        assertTrue(prompt.contains("Наружно / обработка раны"))
        assertTrue(prompt.contains("Аллергия"))
        assertTrue(prompt.contains("0,05%"))
    }
    @Test fun instructionSourceMustBeVerifiedHttpsHost() {
        assertTrue(officialInstructionUrl(FORXIGA_PDF))
        assertFalse(officialInstructionUrl("http://www.astrazeneca.ru/file.pdf"))
        assertFalse(officialInstructionUrl("https://www.astrazeneca.ru.fake.ru/file.pdf"))
        assertFalse(officialInstructionUrl("https://user@www.astrazeneca.ru/file.pdf"))
        assertFalse(officialInstructionUrl("https://www.astrazeneca.ru:8443/file.pdf"))
    }

    @Test fun manufacturerOriginsAndSharedHostingAreRestricted() {
        assertTrue(officialInstructionUrl(PITAVASTOR_PDF))
        assertTrue(officialInstructionUrl("https://www.drreddys.ru/node/921"))
        assertTrue(officialInstructionUrl("https://promomed.pro/catalog/"))
        assertFalse(officialInstructionUrl(PITAVASTOR_PDF.replace("/TEVA/", "/OTHER/")))
        assertFalse(officialInstructionUrl(PITAVASTOR_PDF.replace("/origin/", "/origin/../")))
        assertFalse(officialInstructionUrl("https://catalog.teva.ru.fake.ru/"))
        assertFalse(officialInstructionUrl(PITAVASTOR_PDF + "?redirect=evil"))
    }
    @Test fun innAutofillDoesNotGuessOrReplaceManualEdits() {
        val matched = InnDraft().forName("Питавастор")
        assertEquals("Питавастатин", matched.value)
        assertEquals("", matched.forName("Неизвестное средство").value)
        assertNull(knownMedicine("Питавастор 5 мг"))
        assertEquals("Моё вещество", matched.edited("Моё вещество").forName("Амклав").value)
        assertEquals("", matched.edited("").forName("Питавастор").value)
        assertTrue(compatibilityPrompt(listOf(Prescription(name = "Питавастор", dose = "", schedule = "", doctor = "", notes = "", inn = matched.value)), "").contains("МНН: Питавастатин"))
    }
    @Test fun photoFileNamesCannotEscapeStorage() {
        val name = "e5c0a980-1489-4b15-854a-794322ac1ae3.jpg"
        assertTrue(validMedicinePhotoName(name))
        assertFalse(validMedicinePhotoName("../$name"))
        assertFalse(validMedicinePhotoName("$name/extra"))
        assertFalse(validMedicinePhotoName("photo.png"))
    }
}
