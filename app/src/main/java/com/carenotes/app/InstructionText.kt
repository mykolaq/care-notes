package com.carenotes.app

internal data class InstructionLocation(val page: Int, val offset: Int, val length: Int = 0)
internal data class InstructionSection(val title: String, val location: InstructionLocation)

/** Headings are taken from the document; no medical content is generated. */
internal fun instructionSections(pages: List<String>): List<InstructionSection> {
    val heading = Regex("^(?:\\d{1,2}(?:\\.\\d{1,2})*\\.?\\s+)?(?:показания(?: к применению)?|противопоказания|режим дозирования(?: и способ применения)?|способ применения(?: и дозы)?|дозировка|побочн(?:ые действия|ое действие|ые реакции)|нежелательные реакции|особые указания(?: и меры предосторожности при применении)?|меры предосторожности|взаимодействи[ея](?: с другими лекарственными (?:средствами|препаратами).*)?|передозировка|применение при беременности и (?:в период )?(?:грудного вскармливания|кормления грудью)|фертильность, беременность и лактация|условия хранения|срок годности|состав|как (?:принимать|применять|хранить) препарат.*|возможные нежелательные реакции|содержание упаковки и прочие сведения)[.:]?$", RegexOption.IGNORE_CASE)
    return pages.flatMapIndexed { page, text ->
        var offset = 0
        text.split('\n').mapNotNull { line ->
            val title = line.trim().replace(Regex("\\s+"), " ")
            val section = if (title.length in 5..160 && heading.matches(title)) InstructionSection(title, InstructionLocation(page, offset + line.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0))) else null
            offset += line.length + 1
            section
        }
    }.distinctBy { it.title.lowercase() }
}

internal fun instructionMatches(pages: List<String>, query: String): List<InstructionLocation> {
    val value = query.trim()
    if (value.length < 2) return emptyList()
    val pattern = Regex(Regex.escape(value), RegexOption.IGNORE_CASE)
    return pages.asSequence().withIndex().flatMap { (page, text) ->
        pattern.findAll(text).map { InstructionLocation(page, it.range.first, it.value.length) }
    }.take(2000).toList()
}
