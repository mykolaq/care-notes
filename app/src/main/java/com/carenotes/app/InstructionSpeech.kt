package com.carenotes.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.*

/** One reader owns one engine. All state and callback handling run on the main thread. */
internal class InstructionSpeech(context: Context, private val key: String) {
    private val context = context.applicationContext
    private val prefs = context.getSharedPreferences("instruction_speech", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attributes).setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener({ change -> if (change < 0) pause() }, handler).build()
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { pause() }
    }
    private var registered = false
    private var tts: TextToSpeech? = null
    private var generation = 0
    private var utterance = 0
    private var closed = false
    private var candidates = emptyList<String>()
    private var candidateIndex = 0
    private var pages = emptyList<String>()
    private var signature = ""
    private var position: InstructionLocation? = null
    private var active: SpeechChunk? = null
    private var activeId = ""
    var playing by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var ready by mutableStateOf(false); private set
    var status by mutableStateOf("Проверим установленный русский голос без интернета."); private set
    var engineName by mutableStateOf(""); private set
    var checkpoint by mutableStateOf<InstructionLocation?>(null); private set
    var speed by mutableFloatStateOf(prefs.getFloat("speed", 1f).takeIf { it in listOf(.75f, 1f, 1.25f) } ?: 1f); private set

    fun document(text: List<String>, digest: String) {
        if (signature == digest) return
        pause()
        pages = text; signature = digest
        checkpoint = if (prefs.getString("$key.digest", "") == digest) {
            InstructionLocation(prefs.getInt("$key.page", 0), prefs.getInt("$key.offset", 0))
                .takeIf { it.page in pages.indices && it.offset in 0..pages[it.page].length }
        } else null
        position = checkpoint
    }

    fun prepare() {
        if (closed || busy || ready) return
        busy = true; status = "Проверяю русский голос…"
        val installed = context.packageManager.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
            .map { it.serviceInfo.packageName }.distinct()
        val preferred = Settings.Secure.getString(context.contentResolver, "tts_default_synth")
        candidates = (listOf("com.google.android.tts") + listOfNotNull(preferred) + installed).distinct().filter { it in installed }
        candidateIndex = 0
        tryEngine()
    }

    private fun tryEngine() {
        tts?.shutdown(); tts = null
        if (closed) return
        val pkg = candidates.getOrNull(candidateIndex++)
        if (pkg == null) {
            busy = false; ready = false
            status = "Нет установленного русского голоса без интернета. Откройте настройки речи и скачайте русский голос, затем повторите проверку."
            return
        }
        val token = ++generation
        try {
            tts = TextToSpeech(context, { result -> handler.post {
                if (closed || token != generation) return@post
                val engine = tts ?: return@post
                val voice = if (result == TextToSpeech.SUCCESS) runCatching {
                    engine.voices.orEmpty().filter { it.locale.language == "ru" && !it.isNetworkConnectionRequired && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty() }
                        .sortedWith(compareByDescending<android.speech.tts.Voice> { it.locale.country == "RU" }.thenByDescending { it.quality }.thenBy { it.name }).firstOrNull()
                }.getOrNull() else null
                if (voice == null || engine.setVoice(voice) != TextToSpeech.SUCCESS) { tryEngine(); return@post }
                engine.setAudioAttributes(attributes)
                engine.setSpeechRate(speed)
                engineName = if (pkg == "com.google.android.tts") "Google · русский голос офлайн" else "${engine.engines.firstOrNull { it.name == pkg }?.label ?: pkg} · русский голос офлайн"
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) = Unit
                    override fun onDone(id: String?) { handler.post {
                        if (!closed && playing && id == activeId) {
                            position = active?.next; active = null
                            savePosition(); speakNext()
                        }
                    } }
                    @Deprecated("Legacy TTS callback")
                    override fun onError(id: String?) { handler.post {
                        if (!closed && id == activeId) { pause(); ready = false; status = "Не удалось озвучить текст. Проверьте русский голос в настройках речи и повторите проверку." }
                    } }
                })
                busy = false; ready = true; status = "Голос готов. Выберите, откуда читать."
            } }, pkg)
            handler.postDelayed({
                if (!closed && busy && token == generation) tryEngine()
            }, 10000)
        } catch (_: Exception) { tryEngine() }
    }

    fun start(from: InstructionLocation) {
        if (!ready || pages.isEmpty() || closed) return
        pause()
        position = from
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            status = "Звук сейчас занят. Попробуйте снова."; return
        }
        if (!registered) {
            context.registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), Context.RECEIVER_NOT_EXPORTED)
            registered = true
        }
        playing = true
        speakNext()
    }

    private fun speakNext() {
        val from = position ?: return
        val chunk = instructionSpeechChunk(pages, from, minOf(500, TextToSpeech.getMaxSpeechInputLength()))
        if (chunk == null) {
            pause()
            if (from.page >= pages.size) {
                position = null; checkpoint = null; clearPosition(); status = "Документ прочитан."
            } else status = "На странице ${from.page + 1} нет текста для чтения. Сначала нажмите «Распознать для поиска» или выберите другую страницу."
            return
        }
        active = chunk; position = chunk.start; savePosition()
        activeId = "${generation}-${++utterance}"
        status = "Читаю страницу ${chunk.start.page + 1} из ${pages.size}"
        val params = Bundle()
        if (tts?.speak(chunk.text, TextToSpeech.QUEUE_FLUSH, params, activeId) != TextToSpeech.SUCCESS) {
            pause(); ready = false; status = "Не удалось начать чтение. Проверьте настройки речи и повторите проверку."
        }
    }

    private fun savePosition() {
        val p = position ?: return
        checkpoint = p.takeIf { it.page in pages.indices }
        prefs.edit().putString("$key.digest", signature).putInt("$key.page", p.page).putInt("$key.offset", p.offset).apply()
    }
    private fun clearPosition() { prefs.edit().remove("$key.digest").remove("$key.page").remove("$key.offset").apply() }

    fun pause() {
        val wasPlaying = playing
        playing = false; activeId = ""
        tts?.stop()
        if (wasPlaying) { savePosition(); status = "Пауза. Продолжим с начала текущего фрагмента." }
        audio.abandonAudioFocusRequest(focus)
        if (registered) { context.unregisterReceiver(noisy); registered = false }
    }
    fun stop() {
        pause(); position = null; active = null; checkpoint = null; clearPosition()
        status = "Чтение остановлено. Выберите место для нового чтения."
    }
    fun changeSpeed(value: Float) {
        if (value !in listOf(.75f, 1f, 1.25f)) return
        val resume = playing; val p = position
        pause(); speed = value; prefs.edit().putFloat("speed", value).apply(); tts?.setSpeechRate(value)
        if (resume && p != null) start(p)
    }
    fun settings() {
        pause()
        try { context.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: android.content.ActivityNotFoundException) { status = "Не удалось открыть настройки речи. Найдите «Синтез речи» в настройках телефона." }
        ready = false
    }
    fun close() {
        pause(); closed = true; generation++; handler.removeCallbacksAndMessages(null)
        tts?.shutdown(); tts = null
    }
}
