package com.carenotes.app

import org.junit.Assert.*
import org.junit.Test

class PasswordHashTest {
    @Test fun correctPasswordMatchesAndWrongPasswordFails() {
        val salt = PasswordHash.salt()
        val hash = PasswordHash.derive("Семейный пароль 42", salt)
        assertTrue(PasswordHash.matches("Семейный пароль 42", salt, hash))
        assertFalse(PasswordHash.matches("Семейный пароль 43", salt, hash))
        assertFalse(PasswordHash.matches("Семейный пароль 42", PasswordHash.salt(), hash))
    }
}
