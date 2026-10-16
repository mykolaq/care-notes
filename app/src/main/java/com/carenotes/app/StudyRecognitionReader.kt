package com.carenotes.app

import android.content.Context
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class StudyRecognition(val text: String, val fromImage: Boolean)

internal suspend fun readStudyDocument(context: Context, document: VisitDocument, progress: suspend (String) -> Unit): StudyRecognition {
    val documents = Documents(context)
    if (document.mime != "application/pdf") {
        progress("Распознаю фотографию на телефоне…")
        val text = document.text.ifBlank { documents.recognize(document) }
        currentCoroutineContext().ensureActive()
        return StudyRecognition(text, true)
    }
    val file = documents.file(document)
    val cache = InstructionOcr(context, file)
    val cached = cache.read()
    com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context.applicationContext)
    var fromImage = false
    val pages = com.tom_roush.pdfbox.pdmodel.PDDocument.load(file).use { pdf ->
        require(pdf.numberOfPages <= 10) { "Для распознавания выберите PDF не больше 10 страниц" }
        (0 until pdf.numberOfPages).map { index ->
            currentCoroutineContext().ensureActive()
            progress("Читаю страницу ${index + 1} из ${pdf.numberOfPages}…")
            if (index in cached) { fromImage = true; cached.getValue(index) }
            else com.tom_roush.pdfbox.text.PDFTextStripper().apply {
                startPage = index + 1; endPage = index + 1; sortByPosition = true
                lineSeparator = "\n"; paragraphEnd = "\n"; wordSeparator = " "
            }.getText(pdf).trim()
        }.toMutableList()
    }
    val missing = pages.indices.filter { instructionNeedsOcr(pages[it]) }
    if (missing.isNotEmpty()) {
        fromImage = true
        cache.recognize(missing) { index, text ->
            progress("Распознаю изображение: страница ${index + 1} из ${pages.size}…")
            if (text != null) pages[index] = text
        }
    }
    return StudyRecognition(pages.joinToString("\n\n").take(50000), fromImage)
}
