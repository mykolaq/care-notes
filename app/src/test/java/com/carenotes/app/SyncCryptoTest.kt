package com.carenotes.app

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class SyncCryptoTest {
    @Test fun encryptionRoundTripAndTamperingFails() {
        val dir = Files.createTempDirectory("care-crypto").toFile()
        try {
            val plain = java.io.File(dir, "plain"); val sealed = java.io.File(dir, "sealed"); val out = java.io.File(dir, "out")
            plain.writeText("Медицинские данные".repeat(1000)); val key = SyncCrypto.key()
            SyncCrypto.encrypt(plain, sealed, key); SyncCrypto.decrypt(sealed, out, key)
            assertArrayEquals(plain.readBytes(), out.readBytes())
            val bytes = sealed.readBytes(); bytes[30] = (bytes[30].toInt() xor 1).toByte(); sealed.writeBytes(bytes)
            try { SyncCrypto.decrypt(sealed, out, key); fail("Tampering accepted") } catch (_: IllegalArgumentException) { assertFalse(out.exists()) }
        } finally { dir.deleteRecursively() }
    }
    @Test fun mergeSeparateChangesAndDeletions() {
        assertEquals(mapOf("a" to "updated", "b" to "added"), SyncMerge.merge(mapOf("a" to "old", "c" to "deleted"), mapOf("a" to "updated"), mapOf("a" to "old", "b" to "added", "c" to "deleted")))
    }
    @Test fun simultaneousChangesRequireExplicitChoice() {
        val base = mapOf("a" to "old"); val local = mapOf("a" to "local"); val remote = mapOf("a" to "remote")
        try { SyncMerge.merge(base, local, remote); fail("Conflict lost") } catch (e: MergeConflict) { assertEquals(listOf("a"), e.ids) }
        assertEquals(local, SyncMerge.merge(base, local, remote, true))
        assertEquals(remote, SyncMerge.merge(base, local, remote, false))
    }
}
