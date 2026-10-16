package com.carenotes.app

internal data class RecognizedMeasurement(val source: String, val measurement: StudyMeasurement)

/** Propose numeric results beside explicit units; leave uncertain rows for review. */
internal fun recognizeMeasurements(text: String): List<RecognizedMeasurement> {
    val unit = "(?:[xх×]?[ ]?10(?:\\^|\\*|[ ]?)[0-9]{1,2}\\s*/\\s*[лlL]|(?:мк|м|н|п|д|m|n|p|d|u|µ|μ)?(?:моль|г|Ед|ед|Е|МЕ|U|IU|mol|g)\\s*/\\s*(?:мл|дл|л|ml|dl|l)|мм\\s*/\\s*ч|мкм|фл|пг|fL|pg|%)"
    val number = "([+-]?(?:\\d+(?:[.,]\\d+)?|[.,]\\d+))"
    val after = Regex("^(.+?)(?:\\s*[:|]\\s*|\\s+)" + number + "\\s*[*↑↓HLhl]*\\s*(" + unit + ")(?=\\s|$|[;|])", RegexOption.IGNORE_CASE)
    val before = Regex("^(.+?)\\s+(" + unit + ")\\s+" + number + "(?=\\s|$|[*↑↓;|])", RegexOption.IGNORE_CASE)
    val specimen = Regex("(?:биоматериал|материал(?: исследования)?)\\s*[:—-]\\s*([^\\n;]+)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.trim()?.take(200).orEmpty()
    return text.lines().mapNotNull { raw ->
        val line = raw.trim().replace('\u00a0', ' ').replace(Regex("[\\t ]+"), " ")
        val a = after.find(line); val b = if (a == null) before.find(line) else null
        val match = a ?: b ?: return@mapNotNull null
        val name = match.groupValues[1].trim().trimEnd(':', '|', ',').trim()
        val value = match.groupValues[if (a != null) 2 else 3]
        val foundUnit = match.groupValues[if (a != null) 3 else 2].replace(Regex("\\s*/\\s*"), "/")
        if (name.length !in 2..200 || name.count(Char::isLetter) < 2 || name.any { it in "<>=≤≥" } || name.endsWith('-') || name.endsWith('–') || Regex("(?:^|\\s)\\d+(?:[.,]\\d+)?(?:\\s|$)").containsMatchIn(name) || Regex("референ|норма|результат|дата|возраст", RegexOption.IGNORE_CASE).containsMatchIn(name) || measurementNumber(value) == null) return@mapNotNull null
        RecognizedMeasurement(raw.trim(), StudyMeasurement(name = name, value = value, unit = foundUnit, specimen = specimen))
    }.take(500)
}

/** A repeated import preserves existing IDs and treats 80 and 80.0 as the same confirmed result. */
internal fun mergeRecognizedMeasurements(current: List<StudyMeasurement>, incoming: List<StudyMeasurement>): List<StudyMeasurement> {
    val result = current.toMutableList()
    incoming.forEach { next ->
        val number = requireNotNull(measurementNumber(next.value))
        if (result.none { it.key() == next.key() && measurementNumber(it.value)?.compareTo(number) == 0 }) result += next
    }
    require(result.size <= 500) { "В одном исследовании можно хранить до 500 показателей" }
    return result
}
