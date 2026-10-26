package com.aiwatch.probe.reminder

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.aiwatch.probe.ProbeApplication
import com.aiwatch.probe.R

/** Platform AlarmManager + notification channel; no exact-alarm permission, service or polling loop. */
class AndroidReminders(private val context: Context) : ReminderPlatform {
    private val alarms get() = checkNotNull(context.getSystemService(AlarmManager::class.java))
    private val notifications get() = checkNotNull(context.getSystemService(NotificationManager::class.java))
    private fun channel() {
        notifications.createNotificationChannel(NotificationChannel(CHANNEL,
            context.getString(R.string.reminder_title), NotificationManager.IMPORTANCE_DEFAULT))
    }
    override fun notificationsEnabled(): Boolean {
        channel()
        return (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            notifications.areNotificationsEnabled() && notifications.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }
    private fun operation(id: String) = PendingIntent.getBroadcast(context, 0,
        Intent(context, ReminderReceiver::class.java).setAction(ACTION_FIRE)
            .setData(Uri.parse("banxuan://reminder/$id")), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun snoozeOperation(id: String) = PendingIntent.getBroadcast(context, 0,
        Intent(context, ReminderReceiver::class.java).setAction(ACTION_SNOOZE)
            .setData(Uri.parse("banxuan://reminder-snooze/$id")), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    override fun schedule(reminder: Reminder) {
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.dueAt, operation(reminder.id))
    }
    override fun remove(id: String) {
        val pending = operation(id)
        alarms.cancel(pending); pending.cancel()
        notifications.cancel(id, 0)
        snoozeOperation(id).cancel()
    }
    override fun dismiss(id: String) {
        notifications.cancel(id, 0)
        snoozeOperation(id).cancel()
    }
    override fun post(reminder: Reminder) {
        check(notificationsEnabled())
        val open = PendingIntent.getActivity(context, 0, Intent(context, ReminderActivity::class.java)
            .setData(Uri.parse("banxuan://reminder/${reminder.id}")), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val generic = Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_probe)
            .setContentTitle(context.getString(R.string.reminder_title)).setContentText(context.getString(R.string.reminder_private)).build()
        notifications.notify(reminder.id, 0, Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_probe).setContentTitle(context.getString(R.string.reminder_title))
            .setContentText(reminder.text).setStyle(Notification.BigTextStyle().bigText(reminder.text))
            .setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(generic)
            .setCategory(Notification.CATEGORY_REMINDER).setAutoCancel(true).setOnlyAlertOnce(true)
            .addAction(R.drawable.ic_probe, context.getString(R.string.reminder_snooze), snoozeOperation(reminder.id))
            .setContentIntent(open).build())
    }
    companion object {
        const val CHANNEL = "local-reminders"
        const val ACTION_FIRE = "com.aiwatch.probe.LOCAL_REMINDER"
        const val ACTION_SNOOZE = "com.aiwatch.probe.SNOOZE_LOCAL_REMINDER"
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        // Bounded local IO only. PendingResult keeps the process eligible until completion.
        java.util.concurrent.Executors.newSingleThreadExecutor().let { executor ->
            executor.execute {
                try {
                    val reminders = (context.applicationContext as ProbeApplication).reminders
                    when (intent.action) {
                        AndroidReminders.ACTION_FIRE -> intent.data?.lastPathSegment?.let { reminders.deliver(it) }
                        AndroidReminders.ACTION_SNOOZE -> intent.data?.lastPathSegment?.let { reminders.snooze(it) }
                        Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED -> reminders.restore()
                    }
                } catch (_: Exception) {
                    // Preserve the file on storage failure; never log private reminder content or reset it.
                } finally { result.finish(); executor.shutdown() }
            }
        }
    }
}
