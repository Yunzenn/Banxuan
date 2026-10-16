package com.aiwatch.probe.reminder

import android.app.NotificationManager
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.aiwatch.probe.ProbeApplication
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class RemindersAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun file() = File(context.cacheDir, "reminder-test-${UUID.randomUUID()}.json")
    @Test fun atomicStorageReopensWithStatusAndContent() {
        val file = file()
        try {
            val expected = Reminder(UUID.randomUUID().toString(), "测试提醒", 2000, ReminderStatus.CANCELLED)
            ReminderStorageFile(file).write(listOf(expected))
            assertEquals(listOf(expected), ReminderStorageFile(file).read())
        } finally { file.delete() }
    }
    @Test fun corruptStorageIsNotSilentlyReset() {
        val file = file()
        try {
            file.writeText("broken JSON")
            try { ReminderStorageFile(file).read(); fail("Corrupt file must fail closed") }
            catch (_: org.json.JSONException) { }
            assertEquals("broken JSON", file.readText())
        } finally { file.delete() }
    }
    @Test fun oversizedStorageIsRejected() {
        val file = file()
        try {
            file.writeText("x".repeat(128 * 1024 + 1))
            try { ReminderStorageFile(file).read(); fail("Unbounded input") }
            catch (_: IllegalArgumentException) { }
        } finally { file.delete() }
    }
    @Test fun pageExposesNativeCreateAndTimeControls() {
        val activity = instrumentation.startActivitySync(Intent(context, ReminderActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ReminderActivity
        try {
            instrumentation.runOnMainSync {
                assertNotNull(activity.findViewById<android.view.View>(com.aiwatch.probe.R.id.reminder_content))
                assertNotNull(activity.findViewById<android.view.View>(com.aiwatch.probe.R.id.reminder_when))
                assertNotNull(activity.findViewById<android.view.View>(com.aiwatch.probe.R.id.reminder_save))
            }
        } finally { instrumentation.runOnMainSync { activity.finish() }; instrumentation.waitForIdleSync() }
    }
    @Test fun cancelledReminderCannotPostFromLateDelivery() {
        val book = (context.applicationContext as ProbeApplication).reminders
        val item = book.create("取消测试", System.currentTimeMillis() + 60_000)
        try {
            book.cancel(item.id); book.deliver(item.id)
            assertEquals(ReminderStatus.CANCELLED, book.list().find { it.id == item.id }?.status)
            assertFalse(context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.tag == item.id })
        } finally { book.cancel(item.id) }
    }
    @Test fun createUiRequiresConfirmationAndPersistsTheEnteredText() {
        val book = (context.applicationContext as ProbeApplication).reminders
        val text = "UI 提醒 ${UUID.randomUUID().toString().take(8)}"
        val before = book.list().map { it.id }.toSet()
        val activity = instrumentation.startActivitySync(Intent(context, ReminderActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ReminderActivity
        try {
            val deadline = android.os.SystemClock.uptimeMillis() + 5000
            var ready = false
            while (!ready && android.os.SystemClock.uptimeMillis() < deadline) {
                instrumentation.runOnMainSync { ready = activity.findViewById<android.widget.Button>(com.aiwatch.probe.R.id.reminder_save).isEnabled }
                if (!ready) android.os.SystemClock.sleep(50)
            }
            assertTrue("Create form must be ready", ready)
            instrumentation.runOnMainSync {
                activity.findViewById<android.widget.EditText>(com.aiwatch.probe.R.id.reminder_content).setText(text)
                activity.findViewById<android.widget.Button>(com.aiwatch.probe.R.id.reminder_save).performClick()
            }
            clickDialog(android.R.string.cancel)
            assertEquals(before, book.list().map { it.id }.toSet())
            instrumentation.runOnMainSync { activity.findViewById<android.widget.Button>(com.aiwatch.probe.R.id.reminder_save).performClick() }
            clickDialog(android.R.string.ok)
            val savedDeadline = android.os.SystemClock.uptimeMillis() + 5000
            while (book.list().none { it.text == text } && android.os.SystemClock.uptimeMillis() < savedDeadline) android.os.SystemClock.sleep(50)
            assertEquals(ReminderStatus.PENDING, book.list().single { it.text == text }.status)
        } finally {
            book.list().filter { it.text == text }.forEach { book.cancel(it.id) }
            instrumentation.runOnMainSync { activity.finish() }; instrumentation.waitForIdleSync()
        }
    }
    private fun clickDialog(label: Int) {
        val deadline = android.os.SystemClock.uptimeMillis() + 5000
        var node: android.view.accessibility.AccessibilityNodeInfo? = null
        while (node == null && android.os.SystemClock.uptimeMillis() < deadline) {
            node = instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(context.getString(label))?.firstOrNull()
            if (node == null) android.os.SystemClock.sleep(50)
        }
        assertNotNull("Dialog action must appear", node)
        assertTrue(checkNotNull(node).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }
    @Test fun realAlarmPostsNotificationAfterActivityFinishes() {
        val book = (context.applicationContext as ProbeApplication).reminders
        val activity = instrumentation.startActivitySync(Intent(context, ReminderActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ReminderActivity
        instrumentation.runOnMainSync { activity.finish() }
        context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
        val item = book.create("后台系统通知测试", System.currentTimeMillis() + 3000)
        try {
            val notifications = context.getSystemService(NotificationManager::class.java)
            val deadline = android.os.SystemClock.uptimeMillis() + 90_000
            while ((notifications.activeNotifications.none { it.tag == item.id } ||
                book.list().find { it.id == item.id }?.status != ReminderStatus.POSTED) && android.os.SystemClock.uptimeMillis() < deadline) {
                android.os.SystemClock.sleep(100)
            }
            val notification = notifications.activeNotifications.single { it.tag == item.id }
            assertEquals("后台系统通知测试", notification.notification.extras.getString(android.app.Notification.EXTRA_TEXT))
            assertEquals(android.app.Notification.VISIBILITY_PRIVATE, notification.notification.visibility)
            assertEquals(ReminderStatus.POSTED, book.list().find { it.id == item.id }?.status)
        } finally { book.cancel(item.id) }
    }
}
