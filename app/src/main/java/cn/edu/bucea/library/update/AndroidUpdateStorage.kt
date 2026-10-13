package cn.edu.bucea.library.update

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.util.UUID

class AndroidUpdateStorage(context: Context) : UpdateStorage {
    private val packageName = context.packageName
    private val prefs = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
    private val record = AtomicFile(File(context.filesDir, "updates/task.json"))
    override var lastAttempt: Long
        get() = prefs.getLong("last_attempt", 0)
        set(value) { check(prefs.edit().putLong("last_attempt", value).commit()) }
    override var lastSuccess: Long
        get() = prefs.getLong("last_success", 0)
        set(value) { check(prefs.edit().putLong("last_success", value).commit()) }
    override var deferredVersion: Long
        get() = prefs.getLong("deferred_version", 0)
        set(value) { check(prefs.edit().putLong("deferred_version", value).commit()) }
    override var deferredUntil: Long
        get() = prefs.getLong("deferred_until", 0)
        set(value) { check(prefs.edit().putLong("deferred_until", value).commit()) }

    override fun load(): ConfirmedUpdate? {
        if (!record.baseFile.exists() && !File(record.baseFile.path + ".bak").exists()) return null
        return try {
            val json = JSONObject(record.openRead().use { input -> input.bufferedReader().readText() })
            val token = json.getString("token")
            require(UUID.fromString(token).toString() == token)
            val id = if (json.isNull("downloadId")) null else json.strictLong("downloadId").also { require(it > 0) }
            ConfirmedUpdate(UpdateManifest.parse(json.getJSONObject("manifest"), packageName), token, id,
                UpdatePhase.valueOf(json.getString("phase")), json.optString("error").takeIf { it.isNotBlank() })
        } catch (_: Exception) {
            record.delete()
            null  // Isolated update files/downloads are cleaned on restoration.
        }
    }

    override fun save(task: ConfirmedUpdate?) {
        if (task == null) { record.delete(); return }
        record.baseFile.parentFile?.mkdirs()
        val json = JSONObject().put("manifest", task.manifest.toJson()).put("token", task.token)
            .put("downloadId", task.downloadId ?: JSONObject.NULL).put("phase", task.phase.name)
            .put("error", task.error ?: "")
        val output = record.startWrite()
        try {
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            record.finishWrite(output)
        } catch (error: Exception) {
            record.failWrite(output)
            throw error
        }
    }
}
