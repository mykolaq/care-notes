package com.carenotes.app

import org.junit.Assert.*
import org.junit.Test

class InstructionTextTest {
    @Test fun sectionsUseActualHeadingsAndOffsets() {
        val page = "Документ\n\n4.1. Показания к применению\n\nОбычный текст\n\n4.2. Режим дозирования и способ применения\n"
        val sections = instructionSections(listOf(page, "Противопоказания\n\nНе принимать препарат при..."))
        assertEquals(3, sections.size)
        assertEquals(page.indexOf("4.2."), sections[1].location.offset)
        assertEquals(1, sections[2].location.page)
    }
    @Test fun narrativeAndDuplicateHeadingsAreNotSections() {
        assertEquals(listOf("Противопоказания"), instructionSections(listOf("Противопоказания\nПротивопоказания перечислены ниже.\nПротивопоказания")).map { it.title })
    }
    @Test fun patientLeafletHeadingsAreRecognized() {
        assertEquals(3, instructionSections(listOf("3. Как принимать препарат Форсига\n4. Возможные нежелательные реакции\n5. Как хранить препарат Форсига")).size)
    }
    @Test fun searchIgnoresCaseAndKeepsPageAndOffsets() {
        val matches = instructionMatches(listOf("Доза, доза", "ДОЗА"), " дОза ")
        assertEquals(listOf(InstructionLocation(0,0,4), InstructionLocation(0,6,4), InstructionLocation(1,0,4)), matches)
    }
    @Test fun searchTreatsSymbolsLiterallyAndRejectsShortQuery() {
        assertEquals(1, instructionMatches(listOf("2.5 мг, 205 мг"), "2.5").size)
        assertTrue(instructionMatches(listOf("Препарат"), "п").isEmpty())
    }
    @Test fun searchIsBoundedForLargeDocuments() {
        assertEquals(2000, instructionMatches(listOf("доза ".repeat(3000)), "доза").size)
    }
}
