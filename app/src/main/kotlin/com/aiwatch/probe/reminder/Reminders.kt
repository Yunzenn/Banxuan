package com.aiwatch.probe.reminder

import java.util.UUID

enum class ReminderStatus { PENDING, POSTING, POSTED, BLOCKED, FAILED, CANCELLED }
data class Reminder(val id: String, val text: String, val dueAt: Long, val status: ReminderStatus = ReminderStatus.PENDING)
interface ReminderStorage {
    fun read(): List<Reminder>
    fun write(records: List<Reminder>)
}
interface ReminderPlatform {
    fun notificationsEnabled(): Boolean
    fun schedule(reminder: Reminder)
    fun remove(id: String)
    fun post(reminder: Reminder)
}

/** One owner per process. Durable intent before effects, no automatic retry of an uncertain post. */
class Reminders(private val storage: ReminderStorage, private val platform: ReminderPlatform,
    private val now: () -> Long = System::currentTimeMillis) {
    @Synchronized fun list(): List<Reminder> = storage.read().sortedByDescending { it.dueAt }

    @Synchronized fun create(text: String, dueAt: Long): Reminder {
        val value = text.trim()
        require(value.isNotEmpty() && value.length <= 160 && value.none { it.isISOControl() })
        require(dueAt > now() && dueAt - now() <= 365L * 24 * 60 * 60 * 1000)
        check(platform.notificationsEnabled()) { "Notifications disabled" }
        val records = storage.read()
        check(records.count { it.status == ReminderStatus.PENDING } < 32) { "Too many pending reminders" }
        val reminder = Reminder(UUID.randomUUID().toString(), value, dueAt)
        // Never evict pending work; bound terminal history instead.
        storage.write((records.filter { it.status == ReminderStatus.PENDING } +
            records.filter { it.status != ReminderStatus.PENDING }.takeLast(31)) + reminder)
        try { platform.schedule(reminder) }
        catch (failure: Exception) { update(reminder.copy(status = ReminderStatus.FAILED)); throw failure }
        return reminder
    }

    @Synchronized fun cancel(id: String) {
        val reminder = storage.read().find { it.id == id } ?: return
        // A broadcast already enqueued must see CANCELLED before it can post.
        update(reminder.copy(status = ReminderStatus.CANCELLED))
        platform.remove(id)
    }

    @Synchronized fun deliver(id: String) {
        val reminder = storage.read().find { it.id == id && it.status == ReminderStatus.PENDING } ?: return
        if (reminder.dueAt > now()) { platform.schedule(reminder); return }
        if (!platform.notificationsEnabled()) {
            update(reminder.copy(status = ReminderStatus.BLOCKED)); return
        }
        // Crash after this write means uncertain delivery, not permission to post twice.
        update(reminder.copy(status = ReminderStatus.POSTING))
        try {
            platform.post(reminder)
            update(reminder.copy(status = ReminderStatus.POSTED))
        } catch (_: Exception) { update(reminder.copy(status = ReminderStatus.FAILED)) }
    }

    /** Android clears alarms on reboot. Re-arm pending IDs, without creating a second job identity. */
    @Synchronized fun restore() {
        storage.read().filter { it.status == ReminderStatus.PENDING }.forEach {
            try { platform.schedule(it) }
            catch (_: Exception) { update(it.copy(status = ReminderStatus.FAILED)) }
        }
    }
    private fun update(reminder: Reminder) = storage.write(storage.read().map { if (it.id == reminder.id) reminder else it })
}
