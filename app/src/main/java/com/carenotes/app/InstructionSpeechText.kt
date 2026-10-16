package com.carenotes.app

internal data class SpeechChunk(val text: String, val start: InstructionLocation, val next: InstructionLocation)

// Keep offsets in the original text: numbers, units and punctuation are never rewritten.
internal fun instructionSpeechChunk(pages: List<String>, location: InstructionLocation, limit: Int = 500): SpeechChunk? {
    require(limit >= 4)
    val text = pages.getOrNull(location.page) ?: return null
    if (text.isBlank()) return null
    var start = location.offset.coerceIn(0, text.length)
    if (start in 1 until text.length && text[start].isLowSurrogate() && text[start - 1].isHighSurrogate()) start--
    while (start < text.length && text[start].isWhitespace()) start++
    if (start == text.length) return instructionSpeechChunk(pages, InstructionLocation(location.page + 1, 0), limit)
    var end = (start + limit).coerceAtMost(text.length)
    if (end < text.length) {
        val part = text.substring(start, end)
        val sentence = Regex("[.!?](?=\\s)").findAll(part).lastOrNull()?.range?.last?.plus(1) ?: -1
        val paragraph = part.lastIndexOf("\n\n").takeIf { it >= 0 }?.plus(2) ?: -1
        val boundary = maxOf(sentence, paragraph)
        end = when {
            boundary >= limit / 3 -> start + boundary
            part.indexOfLast { it.isWhitespace() } > 0 -> start + part.indexOfLast { it.isWhitespace() } + 1
            else -> end
        }
        if (text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
    }
    val next = if (text.substring(end).isBlank()) InstructionLocation(location.page + 1, 0) else InstructionLocation(location.page, end)
    return SpeechChunk(text.substring(start, end), InstructionLocation(location.page, start), next)
}

internal fun instructionSpeechSignature(pages: List<String>): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    pages.forEach { page ->
        val bytes = page.toByteArray(Charsets.UTF_8)
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
        digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
