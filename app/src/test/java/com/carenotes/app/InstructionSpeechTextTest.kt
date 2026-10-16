package com.carenotes.app

import org.junit.Assert.*
import org.junit.Test

class InstructionSpeechTextTest {
    @Test fun chunksKeepAllNumbersUnitsAndPunctuation() {
        val page = "Доза 2,5 мг. Значение 0.25 мкг/мл; 10–20 % — учебный текст. ".repeat(80)
        val chunks = mutableListOf<SpeechChunk>()
        var p = InstructionLocation(0, 0)
        while (true) {
            val c = instructionSpeechChunk(listOf(page), p, 90) ?: break
            assertTrue(c.text.length <= 90)
            assertEquals(page.substring(c.start.offset, c.start.offset + c.text.length), c.text)
            chunks += c; p = c.next
        }
        assertEquals(page.filterNot { it.isWhitespace() }, chunks.joinToString("") { it.text }.filterNot { it.isWhitespace() })
        assertEquals(1, p.page)
    }
    @Test fun sentenceBoundaryIsPreferredAndResumeUsesOriginalOffset() {
        val page = "Первое предложение завершено. Следующее предложение существенно длиннее и продолжается."
        val c = instructionSpeechChunk(listOf(page), InstructionLocation(0, 0), 48)!!
        assertEquals("Первое предложение завершено.", c.text)
        val next = instructionSpeechChunk(listOf(page), c.next, 48)!!
        assertEquals(page.indexOf("Следующее"), next.start.offset)
        assertTrue(next.text.startsWith("Следующее"))
    }
    @Test fun emptyScanStopsInsteadOfSilentlySkippingPage() {
        val pages = listOf("Страница один.", "", "Страница три.")
        val c = instructionSpeechChunk(pages, InstructionLocation(0, 0))!!
        assertEquals(InstructionLocation(1, 0), c.next)
        assertNull(instructionSpeechChunk(pages, c.next))
        assertEquals("Страница три.", instructionSpeechChunk(pages, InstructionLocation(2, 0))!!.text)
    }
    @Test fun longWordsAndSurrogatePairsRespectEngineLimit() {
        val text = "a".repeat(7) + "😀" + "b".repeat(30)
        val c = instructionSpeechChunk(listOf(text), InstructionLocation(0, 0), 8)!!
        assertEquals(7, c.text.length)
        val next = instructionSpeechChunk(listOf(text), c.next, 8)!!
        assertTrue(next.text.startsWith("😀"))
        assertFalse(next.text.last().isHighSurrogate())
    }
    @Test fun startsFromSelectedSectionAndEndsAtDocumentBoundary() {
        val pages = listOf("Начало. Дозировка. Учебный текст.", "Конец.")
        val c = instructionSpeechChunk(pages, InstructionLocation(0, 8))!!
        assertEquals("Дозировка. Учебный текст.", c.text)
        val last = instructionSpeechChunk(pages, c.next)!!
        assertEquals(InstructionLocation(2, 0), last.next)
        assertNull(instructionSpeechChunk(pages, last.next))
    }
    @Test fun bookmarkSignatureChangesWhenTextOrPageBoundariesChange() {
        assertEquals(instructionSpeechSignature(listOf("текст")), instructionSpeechSignature(listOf("текст")))
        assertNotEquals(instructionSpeechSignature(listOf("ab", "c")), instructionSpeechSignature(listOf("a", "bc")))
        assertNotEquals(instructionSpeechSignature(listOf("скан")), instructionSpeechSignature(listOf("распознанный текст")))
    }
}
