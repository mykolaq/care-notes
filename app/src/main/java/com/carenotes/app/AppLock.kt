package com.carenotes.app

import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.util.Base64
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class AppLock(context: Context) {
    private val prefs = context.getSharedPreferences("app_lock", Context.MODE_PRIVATE)
    val enabled get() = prefs.contains("hash")
    val biometric get() = enabled && prefs.getBoolean("biometric", false)
    fun remainingSeconds(): Long = ((prefs.getLong("blockedUntil", 0) - System.currentTimeMillis() + 999) / 1000).coerceAtLeast(0)
    @Synchronized fun verify(password: String): Boolean {
        if (!enabled || remainingSeconds() > 0) return false
        val valid = PasswordHash.matches(password, Base64.decode(prefs.getString("salt", ""), Base64.NO_WRAP), Base64.decode(prefs.getString("hash", ""), Base64.NO_WRAP))
        if (valid) prefs.edit().remove("failures").remove("blockedUntil").commit()
        else {
            val failures = prefs.getInt("failures", 0) + 1
            prefs.edit().putInt("failures", failures).putLong("blockedUntil", if (failures >= 5) System.currentTimeMillis() + 30_000 else 0).commit()
        }
        return valid
    }
    fun configure(password: String?, biometric: Boolean) {
        val edit = prefs.edit()
        if (password != null) {
            require(password.length in 6..128)
            val salt = PasswordHash.salt()
            edit.putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
                .putString("hash", Base64.encodeToString(PasswordHash.derive(password, salt), Base64.NO_WRAP))
        }
        check(edit.putBoolean("biometric", biometric).remove("failures").remove("blockedUntil").commit())
    }
    fun disable() { check(prefs.edit().clear().commit()) }
}

internal fun biometricAvailable(context: Context): Boolean =
    context.getSystemService(BiometricManager::class.java).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

@Composable internal fun UnlockScreen(activity: MainActivity, unlocked: () -> Unit) {
    val lock = remember { AppLock(activity) }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var cancellation by remember { mutableStateOf<CancellationSignal?>(null) }
    DisposableEffect(Unit) { onDispose { cancellation?.cancel() } }
    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 440.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Моё здоровье", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(12.dp)); Text("Введите пароль для входа")
            Spacer(Modifier.height(24.dp))
            PasswordField(password, { password = it }, "Пароль", !busy)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 12.dp)) }
            Button(onClick = {
                busy = true; error = null
                scope.launch {
                    val result = withContext(Dispatchers.Default) { runCatching { lock.verify(password) } }
                    busy = false; password = ""
                    if (result.getOrDefault(false)) unlocked()
                    else error = if (lock.remainingSeconds() > 0) "Повторите через ${lock.remainingSeconds()} сек." else "Неверный пароль"
                }
            }, enabled = !busy && password.isNotEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(if (busy) "Проверка…" else "Войти") }
            if (lock.biometric && biometricAvailable(activity)) OutlinedButton(onClick = {
                busy = true; error = null
                val signal = CancellationSignal(); cancellation = signal
                BiometricPrompt.Builder(activity).setTitle("Вход в Моё здоровье")
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                    .setNegativeButton("Ввести пароль", activity.mainExecutor) { _, _ -> busy = false }
                    .build().authenticate(signal, activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { busy = false; unlocked() }
                        override fun onAuthenticationError(code: Int, message: CharSequence) { busy = false; error = "${message}. Можно войти паролем." }
                    })
            }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Войти по биометрии") }
            Spacer(Modifier.height(20.dp))
            Text("Если биометрия недоступна, используйте пароль приложения.", style = MaterialTheme.typography.bodyMedium)
        }
        }
    }
}

@Composable internal fun SecuritySettings(activity: MainActivity, dismiss: () -> Unit) {
    val lock = remember { AppLock(activity) }
    val wasEnabled = remember { lock.enabled }
    var current by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var biometric by remember { mutableStateOf(lock.biometric) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun save(disable: Boolean) {
        if (!disable && ((!wasEnabled || password.isNotEmpty()) && (password.length !in 6..128 || password != repeat))) {
            error = "Пароль: от 6 до 128 символов. Оба поля должны совпадать."; return
        }
        busy = true; error = null
        scope.launch {
            val result = withContext(Dispatchers.Default) { runCatching {
                if (wasEnabled && !lock.verify(current)) error("Неверный текущий пароль${if (lock.remainingSeconds() > 0) ". Повторите через ${lock.remainingSeconds()} сек." else ""}")
                if (disable) lock.disable() else lock.configure(password.takeIf { it.isNotEmpty() }, biometric && biometricAvailable(activity))
            } }
            busy = false
            result.onSuccess { activity.updatePrivacy(); dismiss() }.onFailure { error = it.message ?: "Не удалось сохранить настройки" }
        }
    }
    FormDialog("Защита входа", { if (!busy) dismiss() }) {
        Text(if (wasEnabled) "Пароль включён. Для изменения настроек введите текущий пароль." else "Защита необязательна. Установите пароль, чтобы закрыть доступ к приложению.")
        if (wasEnabled) PasswordField(current, { current = it }, "Текущий пароль", !busy)
        PasswordField(password, { password = it }, if (wasEnabled) "Новый пароль (необязательно)" else "Пароль, минимум 6 символов", !busy)
        PasswordField(repeat, { repeat = it }, "Повторите новый пароль", !busy)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = biometric, onCheckedChange = { biometric = it }, enabled = !busy && biometricAvailable(activity))
            Text("Вход по отпечатку или лицу")
        }
        if (!biometricAvailable(activity)) Text("Для биометрии настройте отпечаток или поддерживаемое распознавание лица в настройках Android.")
        Text("При уходе в фон приложение блокируется. Запишите пароль в надёжном месте: восстановление забытого пароля пока не предусмотрено. Защита входа не заменяет шифрование данных.", style = MaterialTheme.typography.bodyMedium)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = { save(false) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Сохранение…" else "Сохранить") }
        if (wasEnabled) TextButton(onClick = { save(true) }, enabled = !busy) { Text("Отключить защиту входа") }
    }
}

@Composable private fun PasswordField(value: String, changed: (String) -> Unit, label: String, enabled: Boolean) {
    OutlinedTextField(value, { if (it.length <= 128) changed(it) }, label = { Text(label, style = MaterialTheme.typography.bodyMedium) }, enabled = enabled,
        singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
}
