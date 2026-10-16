package com.carenotes.app

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.KeyStore
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class SyncConfig(val url: String, val token: String, val key: String, val role: String, val certificate: String = "")
internal class SyncHttpError(val status: Int) : Exception(when(status) {
    401, 403 -> "Доступ закрыт. Устройство могло быть отключено владельцем."
    409 -> "Данные изменились на другом устройстве. Повторите синхронизацию."
    429 -> "Слишком много попыток. Повторите через минуту."
    else -> "Ошибка сервера ($status)"
})

internal class FamilySync(private val context: Context) {
    private val prefs = context.getSharedPreferences("family_sync", Context.MODE_PRIVATE)
    private val data = context.getSharedPreferences("care_notes", Context.MODE_PRIVATE)
    private val docs = context.getSharedPreferences("care_documents", Context.MODE_PRIVATE)
    val role: String get() = prefs.getString("role", "") ?: ""
    val connected get() = role.isNotEmpty()
    val editable get() = role != "viewer"
    val status get() = prefs.getString("status", "Не подключено") ?: ""
    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE)
    private fun un64(text: String) = Base64.decode(text, Base64.NO_WRAP or Base64.URL_SAFE)
    private fun wrappingKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("care-sync-wrap", null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("care-sync-wrap", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun saveConfig(config: SyncConfig) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, wrappingKey()) }
        val value = JSONObject().put("url", config.url).put("token", config.token).put("key", config.key).put("role", config.role).put("certificate", config.certificate).toString()
        check(prefs.edit().putString("config", b64(cipher.iv + cipher.doFinal(value.toByteArray()))).putString("role", config.role).commit())
        schedule(context)
    }
    fun config(): SyncConfig {
        val value = un64(prefs.getString("config", null) ?: error("Синхронизация не подключена"))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, value.copyOfRange(0,12))) }
        val json = JSONObject(String(cipher.doFinal(value.copyOfRange(12,value.size)), Charsets.UTF_8))
        return SyncConfig(json.getString("url"), json.getString("token"), json.getString("key"), json.getString("role"), json.optString("certificate"))
    }
    private fun normalizeUrl(value: String): String {
        val uri = URI(value.trim().trimEnd('/'))
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && (uri.path.isNullOrEmpty() || uri.path == "/")) { "Укажите HTTPS-адрес сервера, например https://care.example.ru" }
        return uri.toString().trimEnd('/')
    }
    private fun request(url: String, path: String, token: String = "", method: String = "GET", json: JSONObject? = null, upload: File? = null, download: File? = null, revision: Int = 0, certificate: String = ""): JSONObject {
        val conn = URI(normalizeUrl(url) + path).toURL().openConnection() as HttpURLConnection
        try {
            if (certificate.isNotEmpty()) (conn as javax.net.ssl.HttpsURLConnection).sslSocketFactory = SyncTls.socketFactory(un64(certificate))
            conn.requestMethod = method; conn.connectTimeout = 15000; conn.readTimeout = 60000; conn.instanceFollowRedirects = false
            if (token.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer $token")
            if (method != "GET") {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", if (upload == null) "application/json" else "application/octet-stream")
                conn.setRequestProperty("X-Revision", revision.toString())
                if (upload != null) {
                    conn.setFixedLengthStreamingMode(upload.length()); conn.outputStream.use { out -> upload.inputStream().use { it.copyTo(out) } }
                } else {
                    val bytes = (json ?: JSONObject()).toString().toByteArray(); conn.setFixedLengthStreamingMode(bytes.size); conn.outputStream.use { it.write(bytes) }
                }
            }
            if (conn.responseCode != 200) throw SyncHttpError(conn.responseCode)
            if (download != null) {
                conn.inputStream.use { input -> download.outputStream().use { out -> copyLimited(input, out, MAX_SIZE) } }
                return JSONObject().put("revision", conn.getHeaderField("X-Revision").toInt())
            }
            val text = conn.inputStream.use { it.readNBytes(65537).also { require(it.size <= 65536) }.toString(Charsets.UTF_8) }
            return JSONObject(text)
        } finally { conn.disconnect() }
    }
    fun connectOwner(url: String, secret: String, name: String, certificate: String = "") = synchronized(mutex) {
        check(!connected)
        val normalized = normalizeUrl(url)
        val response = request(normalized, "/v1/bootstrap", method = "POST", json = JSONObject().put("secret", secret.trim()).put("name", name.trim()), certificate = certificate)
        saveConfig(SyncConfig(normalized, response.getString("token"), b64(SyncCrypto.key()), "owner", certificate))
        prefs.edit().putString("base", "{}").commit()
        sync()
    }
    fun invite(edit: Boolean): String {
        val c = config(); check(c.role == "owner")
        val result = request(c.url, "/v1/invites", c.token, "POST", JSONObject().put("role", if(edit) "editor" else "viewer"), certificate = c.certificate)
        return "CARE1:" + b64(JSONObject().put("url", c.url).put("code", result.getString("code")).put("key", c.key).put("certificate", c.certificate).toString().toByteArray())
    }
    fun recovery(): String {
        val c = config()
        return "CAREREC1:" + b64(JSONObject().put("url", c.url).put("token", c.token).put("key", c.key).put("certificate", c.certificate).toString().toByteArray())
    }
    fun join(code: String, name: String) = synchronized(mutex) {
        check(!connected)
        require(code.trim().startsWith("CARE1:") || code.trim().startsWith("CAREREC1:")) { "Неверный код подключения" }
        val recovery = code.trim().startsWith("CAREREC1:")
        val json = JSONObject(String(un64(code.trim().substringAfter(':')), Charsets.UTF_8))
        val certificate = json.optString("certificate"); if (certificate.isNotEmpty()) SyncTls.parse(un64(certificate))
        val url = normalizeUrl(json.getString("url")); val key = json.getString("key"); require(un64(key).size == 32)
        val response = if (recovery) request(url, "/v1/me", json.getString("token"), certificate = certificate) else
            request(url, "/v1/join", method = "POST", json = JSONObject().put("code", json.getString("code")).put("name", name.trim()), certificate = certificate)
        saveConfig(SyncConfig(url, if (recovery) json.getString("token") else response.getString("token"), key, response.getString("role"), certificate))
        // Existing local records are preserved and merged; viewers explicitly agree to replace them in the UI.
        prefs.edit().putString("base", "{}").commit()
        sync()
    }
    fun devices(): JSONArray { val c = config(); return request(c.url, "/v1/devices", c.token, certificate = c.certificate).getJSONArray("devices") }
    fun revoke(id: String) { val c = config(); request(c.url, "/v1/revoke", c.token, "POST", JSONObject().put("id", id), certificate = c.certificate) }
    fun disconnect() = synchronized(mutex) {
        check(prefs.edit().clear().commit()); context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
    }
    private fun canonical(value: Any?): String = when(value) {
        is JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        null, JSONObject.NULL -> "null"
        else -> value.toString()
    }
    private fun records(json: JSONObject): Map<String,String> = buildMap {
        listOf("prescriptions", "visits", "studies", "documents").forEach { type ->
            val array = json.optJSONArray(type) ?: JSONArray()
            require(array.length() <= 10000)
            for (i in 0 until array.length()) {
                val record = array.getJSONObject(i); val id = record.getString("id")
                require(id.matches(Regex("[a-zA-Z0-9-]{1,80}")))
                val clean = JSONObject(record.toString())
                if (type == "prescriptions") clean.remove("remindersEnabled")
                if (type == "visits") clean.remove("reminders")
                require(put("$type/$id", canonical(clean)) == null) { "Повторяющийся идентификатор" }
            }
        }
    }
    private fun snapshot(map: Map<String,String>): JSONObject = JSONObject().put("format", 2).apply {
        listOf("prescriptions", "visits", "studies", "documents").forEach { type -> put(type, JSONArray(map.filterKeys { it.startsWith("$type/") }.values.map { JSONObject(it) })) }
    }
    private fun local(): JSONObject = synchronized(localGuard) { JSONObject().put("prescriptions", JSONArray(data.getString("prescriptions", "[]")))
        .put("studies", JSONArray(data.getString("studies", "[]"))).put("visits", JSONArray(data.getString("visits", "[]"))).put("documents", JSONArray(docs.getString("documents", "[]"))) }
    private fun docName(json: JSONObject): String {
        val name = json.getString("fileName")
        require(name.matches(Regex("[a-zA-Z0-9-]{1,80}\\.(pdf|image)"))) { "Неверное имя документа" }
        return name
    }
    private fun photoNames(json: JSONObject): Set<String> {
        val array = json.getJSONArray("prescriptions")
        return (0 until array.length()).map { array.getJSONObject(it).optString("photo") }.filter { it.isNotEmpty() }.toSet().also { names ->
            require(names.all(::validMedicinePhotoName)) { "Неверное имя фото лекарства" }
        }
    }
    private fun pack(json: JSONObject, remoteDir: File, archive: File) {
        validateSnapshot(json)
        val manifest = json.toString().toByteArray()
        require(manifest.size <= 2*1024*1024) { "Слишком много текстовых данных для синхронизации" }
        var total = 0L
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("data.json")); zip.write(manifest); zip.closeEntry()
            val array = json.getJSONArray("documents")
            for (i in 0 until array.length()) {
                val name = docName(array.getJSONObject(i))
                val own = File(context.filesDir, "documents/$name")
                val file = if (own.exists()) own else File(remoteDir, name)
                require(file.isFile && file.length() <= 25L*1024*1024) { "Не найден документ $name" }
                total += file.length(); require(total < 100L*1024*1024) { "Для синхронизации документы должны занимать менее 100 МБ" }
                zip.putNextEntry(ZipEntry("documents/$name")); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
            for (name in photoNames(json)) {
                val own = MedicinePhotos(context).file(name)
                val file = if (own.isFile) own else File(remoteDir, name)
                MedicinePhotos(context).validate(file)
                total += file.length(); require(total < 100L * 1024 * 1024) { "Документы и фото должны занимать менее 100 МБ" }
                zip.putNextEntry(ZipEntry("photos/$name")); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
        require(archive.length() <= MAX_SIZE - 64)
    }
    private fun unpack(archive: File, dir: File): JSONObject {
        var total = 0L; var manifest: JSONObject? = null
        val seen = mutableSetOf<String>()
        ZipInputStream(archive.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(seen.add(entry.name))
                if (entry.name == "data.json") {
                    val bytes = zip.readNBytes(2*1024*1024 + 1); require(bytes.size <= 2*1024*1024)
                    manifest = JSONObject(String(bytes, Charsets.UTF_8)); require(manifest.getInt("format") in 1..2) { "Обновите приложение для чтения новой версии данных" }
                } else {
                    require(entry.name.matches(Regex("documents/[a-zA-Z0-9-]{1,80}\\.(pdf|image)")) || (entry.name.startsWith("photos/") && validMedicinePhotoName(entry.name.removePrefix("photos/")))) { "Неверный путь документа" }
                    val file = File(dir, entry.name.substringAfter('/'))
                    file.outputStream().use { out -> total += copyLimited(zip, out, 25L*1024*1024) }
                    require(total < 100L*1024*1024)
                }
                zip.closeEntry()
            }
        }
        val json = requireNotNull(manifest)
        records(json)
        validateSnapshot(json)
        val array = json.getJSONArray("documents")
        val visits = (0 until json.getJSONArray("visits").length()).map { json.getJSONArray("visits").getJSONObject(it).getString("id") }.toSet()
        val studies = parseStudies(json.optJSONArray("studies") ?: JSONArray()).map { it.id }.toSet()
        for (i in 0 until array.length()) {
            val record = array.getJSONObject(i)
            require(validDocumentOwner(record.optString("visitId"), record.optString("studyId"), visits, studies))
            require(File(dir, docName(record)).isFile) { "Документ отсутствует в архиве" }
        }
        for (name in photoNames(json)) MedicinePhotos(context).validate(File(dir, name))
        return json
    }
    fun sync(choice: Boolean? = null) = synchronized(mutex) {
        if (!connected) return@synchronized
        val workspace = File(context.cacheDir, "family-sync").apply { deleteRecursively(); mkdirs() }
        try {
            val c = config()
            val actualRole = request(c.url, "/v1/me", c.token, certificate = c.certificate).getString("role")
            check(actualRole == c.role)
            val localJson = local(); val localMap = records(localJson)
            val sealed = File(workspace,"remote.enc")
            val revision = request(c.url, "/v1/snapshot", c.token, download = sealed, certificate = c.certificate).getInt("revision")
            val remoteDir = File(workspace,"docs").apply { mkdirs() }
            val remoteJson = if (revision == 0) snapshot(emptyMap()) else {
                val plain = File(workspace,"remote.zip"); SyncCrypto.decrypt(sealed, plain, un64(c.key)); unpack(plain, remoteDir)
            }
            val remoteMap = records(remoteJson)
            val base = records(JSONObject(prefs.getString("base", "{}")!!))
            val merged = if(c.role == "viewer") remoteMap else SyncMerge.merge(base, localMap, remoteMap, choice)
            val json = snapshot(merged)
            // Owner deletion wins over orphan document metadata.
            val visitIds = merged.keys.filter { it.startsWith("visits/") }.map { it.substringAfter('/') }.toSet()
            val studyIds = merged.keys.filter { it.startsWith("studies/") }.map { it.substringAfter('/') }.toSet()
            val documents = json.getJSONArray("documents")
            for (i in documents.length()-1 downTo 0) {
                val d = documents.getJSONObject(i)
                require(d.optString("visitId").isEmpty() xor d.optString("studyId").isEmpty()) { "У документа должна быть одна запись-владелец" }
                if (!validDocumentOwner(d.optString("visitId"), d.optString("studyId"), visitIds, studyIds)) documents.remove(i)
            }
            validateSnapshot(json)
            val resultMap = records(json)
            if (c.role != "viewer" && resultMap != remoteMap) {
                val archive = File(workspace,"upload.zip"); pack(json, remoteDir, archive)
                val encrypted = File(workspace,"upload.enc"); SyncCrypto.encrypt(archive, encrypted, un64(c.key))
                if (records(local()) != localMap) error("Появились новые изменения на этом телефоне. Повторите синхронизацию.")
                request(c.url, "/v1/snapshot", c.token, "PUT", upload = encrypted, revision = revision, certificate = c.certificate)
            }
            prepareDocuments(json, remoteDir)
            preparePhotos(json, remoteDir)
            val apply = java.util.concurrent.FutureTask<Unit> { synchronized(localGuard) {
                if (records(local()) != localMap) error("Появились новые изменения на этом телефоне. Повторите синхронизацию.")
                if (resultMap != localMap) applySnapshot(json, remoteDir, local())
                check(prefs.edit().putString("base", json.toString()).putString("status", "Обновлено: ${java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm"))}").remove("conflict").commit())
            } }
            android.os.Handler(android.os.Looper.getMainLooper()).post(apply)
            try { apply.get() } catch(e: java.util.concurrent.ExecutionException) { throw (e.cause as? Exception ?: e) }
        } catch (e: Exception) {
            prefs.edit().putString("status", e.message ?: "Ошибка синхронизации").putBoolean("conflict", e is MergeConflict).commit()
            throw e
        } finally { workspace.deleteRecursively() }
    }
    private fun validateSnapshot(json: JSONObject) {
        val studies = parseStudies(json.optJSONArray("studies") ?: JSONArray())
        val studyIds = studies.map { it.id }.toSet()
        val visitArray = json.getJSONArray("visits")
        val visitIds = (0 until visitArray.length()).map { visitArray.getJSONObject(it).getString("id") }.toSet()
        Store(context).validateSync(json.getJSONArray("prescriptions"), json.getJSONArray("visits"))
        val names = mutableSetOf<String>()
        val array = json.getJSONArray("documents")
        for (i in 0 until array.length()) {
            val d = array.getJSONObject(i)
            require(validDocumentOwner(d.optString("visitId"), d.optString("studyId"), visitIds, studyIds)) { "Не найдена запись для документа" }
            require(names.add(docName(d))) { "Повторяющийся файл документа" }
            require(d.getString("name").length <= 500 && d.optString("text").length <= 50000)
            require(d.getString("mime") == "application/pdf" || d.getString("mime").startsWith("image/"))
        }
    }
    private fun prepareDocuments(json: JSONObject, remoteDir: File) {
        val directory = File(context.filesDir, "documents").apply { mkdirs() }
        val array = json.getJSONArray("documents")
        for (i in 0 until array.length()) {
            val name = docName(array.getJSONObject(i)); val target = File(directory, name)
            if (!target.exists()) {
                val temp = File(directory,"$name.sync-tmp")
                try {
                    File(remoteDir,name).inputStream().use { input -> temp.outputStream().use { input.copyTo(it) } }
                    check(temp.renameTo(target))
                } finally { temp.delete() }
            }
        }
    }
    private fun preparePhotos(json: JSONObject, remoteDir: File) {
        val photos = MedicinePhotos(context)
        for (name in photoNames(json)) {
            val target = photos.file(name)
            if (!target.exists()) {
                val source = File(remoteDir, name); photos.validate(source)
                val temp = File(target.parentFile, "$name.sync-tmp")
                try { source.copyTo(temp, overwrite = true); check(temp.renameTo(target)) }
                finally { temp.delete() }
            }
            photos.validate(target)
        }
    }
    private fun applySnapshot(json: JSONObject, remoteDir: File, before: JSONObject) {
        // Validate through the existing model parsers before committing any replacement.
        val ps = json.getJSONArray("prescriptions"); val vs = json.getJSONArray("visits")
        val oldPs = before.getJSONArray("prescriptions"); val oldVs = before.getJSONArray("visits")
        for (i in 0 until ps.length()) {
            val p = ps.getJSONObject(i); val old = (0 until oldPs.length()).map { oldPs.getJSONObject(it) }.firstOrNull { it.getString("id") == p.getString("id") }
            p.put("remindersEnabled", old?.optBoolean("remindersEnabled", false) ?: false)
        }
        for (i in 0 until vs.length()) {
            val v = vs.getJSONObject(i); val old = (0 until oldVs.length()).map { oldVs.getJSONObject(it) }.firstOrNull { it.getString("id") == v.getString("id") }
            v.put("reminders", old?.optJSONArray("reminders") ?: JSONArray())
        }
        // Parser validation and rollback are handled by Store.validateSync below.
        Store(context).validateSync(ps, vs)
        parseStudies(json.optJSONArray("studies") ?: JSONArray())
        val array = json.getJSONArray("documents")
        val store = Store(context)
        store.prescriptions().forEach { MedicineReminders.cancel(context, it) }; store.visits().forEach { Reminders.cancel(context, it) }
        check(docs.edit().putString("documents", array.toString()).commit())
        check(data.edit().putString("prescriptions", ps.toString()).putString("visits", vs.toString()).putString("studies", (json.optJSONArray("studies") ?: JSONArray()).toString()).commit())
        Reminders.restore(context)
    }
    companion object {
        private const val MAX_SIZE = 105L * 1024 * 1024
        private const val JOB_ID = 73102
        private val mutex = Any()
        internal val localGuard = Any()
        private val executor = Executors.newSingleThreadScheduledExecutor()
        private var pending: java.util.concurrent.ScheduledFuture<*>? = null
        @Synchronized fun async(context: Context) {
            val app = context.applicationContext
            if (!FamilySync(app).connected) return
            pending?.cancel(false)
            pending = executor.schedule({ runCatching { FamilySync(app).sync() } }, 2, java.util.concurrent.TimeUnit.SECONDS)
        }
        fun schedule(context: Context) {
            if (!FamilySync(context).connected) return
            context.getSystemService(JobScheduler::class.java).schedule(JobInfo.Builder(JOB_ID, ComponentName(context, FamilySyncJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(15L*60*1000).setPersisted(true).build())
        }
        private fun copyLimited(input: java.io.InputStream, out: java.io.OutputStream, limit: Long): Long {
            var size = 0L; val buffer = ByteArray(65536)
            while (true) { val n = input.read(buffer); if (n < 0) break; size += n; require(size <= limit) { "Превышен размер данных" }; out.write(buffer, 0, n) }
            return size
        }
    }
}

class FamilySyncJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread { runCatching { FamilySync(applicationContext).sync() }; jobFinished(params, false) }.start()
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean = true
}
