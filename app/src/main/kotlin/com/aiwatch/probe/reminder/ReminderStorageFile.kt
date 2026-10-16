package com.aiwatch.probe.reminder

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Private, no-backup local text, not a server memory authority. Corruption never silently resets it. */
class ReminderStorageFile(file: File) : ReminderStorage {
    private val atomic = AtomicFile(file)
    override fun read(): List<Reminder> {
        val raw = try { atomic.openRead().use { input ->
            val bytes = input.readBytesBounded()
            String(bytes, Charsets.UTF_8)
        } } catch (failure: java.io.FileNotFoundException) {
            // A missing first file is empty; an existing but unreadable file is not.
            if (atomic.baseFile.exists() || File(atomic.baseFile.path + ".bak").exists()) throw failure
            return emptyList()
        }
        val array = JSONArray(raw)
        require(array.length() <= 64)
        val records = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val id = item.getString("id")
            require(UUID.fromString(id).toString() == id)
            val text = item.getString("text")
            require(text.isNotBlank() && text.length <= 160 && text.none { it.isISOControl() })
            val due = item.getLong("dueAt")
            require(due > 0)
            Reminder(id, text, due, ReminderStatus.valueOf(item.getString("status")))
        }
        require(records.map { it.id }.distinct().size == records.size)
        return records
    }
    override fun write(records: List<Reminder>) {
        require(records.size <= 64)
        val array = JSONArray()
        records.forEach { array.put(JSONObject().put("id", it.id).put("text", it.text)
            .put("dueAt", it.dueAt).put("status", it.status.name)) }
        val stream = atomic.startWrite()
        try { stream.write(array.toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
        catch (failure: Exception) { atomic.failWrite(stream); throw failure }
    }
    private fun java.io.InputStream.readBytesBounded(): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            require(output.size() + count <= 128 * 1024)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
