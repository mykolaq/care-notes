package com.carenotes.app

import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object SyncCrypto {
    private val magic = "CareNotesSync1".toByteArray(Charsets.US_ASCII)
    fun key(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }
    fun encrypt(source: File, target: File, key: ByteArray) {
        require(key.size == 32)
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(magic)
        target.outputStream().use { out ->
            out.write(magic); out.write(nonce)
            source.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) { val n = input.read(buffer); if (n < 0) break; cipher.update(buffer, 0, n)?.let(out::write) }
            }
            out.write(cipher.doFinal())
        }
    }
    fun decrypt(source: File, target: File, key: ByteArray) {
        require(key.size == 32)
        try {
            source.inputStream().use { input ->
                require(input.readNBytes(magic.size).contentEquals(magic)) { "Неизвестный формат данных" }
                val nonce = input.readNBytes(12); require(nonce.size == 12)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)); cipher.updateAAD(magic)
                // Only consume the resulting archive after the authentication tag is verified.
                CipherInputStream(input, cipher).use { encrypted -> target.outputStream().use { encrypted.copyTo(it) } }
            }
        } catch (e: Exception) { target.delete(); throw IllegalArgumentException("Не удалось расшифровать данные: неверный ключ или повреждённый файл", e) }
    }
}

internal class MergeConflict(val ids: List<String>) : Exception("Есть разные изменения одной записи (${ids.size}). Выберите версию для этих записей.")

internal object SyncMerge {
    fun merge(base: Map<String, String>, local: Map<String, String>, remote: Map<String, String>, choice: Boolean? = null): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val conflicts = mutableListOf<String>()
        (base.keys + local.keys + remote.keys).sorted().forEach { id ->
            val b = base[id]; val l = local[id]; val r = remote[id]
            val picked = when {
                l == r -> l
                l == b -> r
                r == b -> l
                choice != null -> if (choice) l else r
                else -> { conflicts.add(id); null }
            }
            if (picked != null) result[id] = picked
        }
        if (conflicts.isNotEmpty()) throw MergeConflict(conflicts)
        return result
    }
}
