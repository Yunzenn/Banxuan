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
            val matches = instrumentation.uiAutomation.rootInActiveWindow
                ?.findAccessibilityNodeInfosByText(context.getString(label)).orEmpty()
            node = matches.firstNotNullOfOrNull { match ->
                var candidate: android.view.accessibility.AccessibilityNodeInfo? = match
                while (candidate != null && (!candidate.isClickable || !candidate.isEnabled)) {
                    candidate = candidate.parent
                }
                candidate
            }
            if (node == null) android.os.SystemClock.sleep(50)
        }
        assertNotNull("Dialog action must appear", node)
        assertTrue("Dialog action must accept accessibility click", checkNotNull(node)
            .performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }
    private fun savedDue(activity: ReminderActivity): Long {
        val bundle = android.os.Bundle()
        instrumentation.runOnMainSync { instrumentation.callActivityOnSaveInstanceState(activity, bundle) }
        return bundle.getLong("dueAt")
    }
    @Test fun cancellingTimePickerDoesNotChangeCommittedDateOrLabel() {
        val activity = instrumentation.startActivitySync(Intent(context, ReminderActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ReminderActivity
        try {
            val before = savedDue(activity)
            var label = ""
            instrumentation.runOnMainSync {
                label = activity.findViewById<android.widget.Button>(com.aiwatch.probe.R.id.reminder_when).text.toString()
                val next = java.util.Calendar.getInstance().apply { timeInMillis = before; add(java.util.Calendar.DAY_OF_MONTH, 1) }
                val dialog = activity.pickDate()
                dialog.datePicker.updateDate(next.get(java.util.Calendar.YEAR), next.get(java.util.Calendar.MONTH), next.get(java.util.Calendar.DAY_OF_MONTH))
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
            }
            instrumentation.waitForIdleSync()
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            instrumentation.waitForIdleSync()
            assertEquals("Cancelling time must not commit the draft date", before, savedDue(activity))
            instrumentation.runOnMainSync {
                assertEquals(label, activity.findViewById<android.widget.Button>(com.aiwatch.probe.R.id.reminder_when).text.toString())
            }
        } finally { instrumentation.runOnMainSync { activity.finish() }; instrumentation.waitForIdleSync() }
    }
    @Test fun cancellingDatePickerDoesNotChangeCommittedDate() {
        val activity = instrumentation.startActivitySync(Intent(context, ReminderActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ReminderActivity
        try {
            val before = savedDue(activity)
            instrumentation.runOnMainSync {
                val next = java.util.Calendar.getInstance().apply { timeInMillis = before; add(java.util.Calendar.DAY_OF_MONTH, 1) }
                val dialog = activity.pickDate()
                dialog.datePicker.updateDate(next.get(java.util.Calendar.YEAR), next.get(java.util.Calendar.MONTH), next.get(java.util.Calendar.DAY_OF_MONTH))
                dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick()
            }
            assertEquals(before, savedDue(activity))
        } finally { instrumentation.runOnMainSync { activity.finish() }; instrumentation.waitForIdleSync() }
    }
    @Test fun confirmedCancellationIsNotLostWhileRefreshIsBusy() {
        val book = (context.applicationContext as ProbeApplication).reminders
        val item = book.create("busy-cancel-${UUID.randomUUID()}", System.currentTimeMillis() + 3_600_000)
        val activity = instrumentation.startActivitySync(Intent(context, ReminderActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ReminderActivity
        val held = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
        val blocker = Thread {
            synchronized(book) {
                held.countDown()
                if (!release.await(20, java.util.concurrent.TimeUnit.SECONDS)) timedOut.set(true)
            }
        }
        try {
            var cancel: android.widget.Button? = null
            val readyDeadline = android.os.SystemClock.uptimeMillis() + 5000
            while (cancel == null && android.os.SystemClock.uptimeMillis() < readyDeadline) {
                instrumentation.runOnMainSync {
                    fun find(view: android.view.View): android.widget.TextView? {
                        if (view is android.widget.TextView && view.text.toString() == item.text) return view
                        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
                        return null
                    }
                    find(activity.findViewById(android.R.id.content))?.let { title ->
                        val parent = title.parent as android.view.ViewGroup
                        cancel = parent.getChildAt(parent.indexOfChild(title) + 2) as? android.widget.Button
                    }
                }
                if (cancel == null) android.os.SystemClock.sleep(50)
            }
            instrumentation.runOnMainSync { checkNotNull(cancel).performClick() }
            blocker.start()
            assertTrue("Refresh blocker must acquire the real repository", held.await(5, java.util.concurrent.TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                instrumentation.callActivityOnPause(activity)
                instrumentation.callActivityOnResume(activity)
                assertFalse(activity.findViewById<android.widget.Button>(com.aiwatch.probe.R.id.reminder_save).isEnabled)
            }
            // Confirm an already-open dialog while a real refresh is blocked on local storage ownership.
            clickDialog(android.R.string.ok)
            release.countDown(); blocker.join(2000)
            assertFalse("Fixture must not release itself by timeout", timedOut.get())
            val deadline = android.os.SystemClock.uptimeMillis() + 5000
            while (book.list().find { it.id == item.id }?.status != ReminderStatus.CANCELLED && android.os.SystemClock.uptimeMillis() < deadline) {
                android.os.SystemClock.sleep(50)
            }
            assertEquals(ReminderStatus.CANCELLED, book.list().find { it.id == item.id }?.status)
        } finally {
            release.countDown()
            if (blocker.isAlive) blocker.join(2000)
            book.cancel(item.id)
            instrumentation.runOnMainSync { activity.finish() }; instrumentation.waitForIdleSync()
        }
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

    @Test fun notificationSnoozeRearmsOnceAndDismissesTheCurrentNotification() {
        val book = (context.applicationContext as ProbeApplication).reminders
        val activity = instrumentation.startActivitySync(Intent(context, ReminderActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ReminderActivity
        instrumentation.runOnMainSync { activity.finish() }
        context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
        val item = book.create("稍后提醒测试", System.currentTimeMillis() + 3000)
        try {
            val notifications = context.getSystemService(NotificationManager::class.java)
            val deadline = android.os.SystemClock.uptimeMillis() + 90_000
            while ((notifications.activeNotifications.none { it.tag == item.id } ||
                book.list().find { it.id == item.id }?.status != ReminderStatus.POSTED) && android.os.SystemClock.uptimeMillis() < deadline) {
                android.os.SystemClock.sleep(100)
            }
            val active = notifications.activeNotifications.single { it.tag == item.id }
            val action = active.notification.actions?.firstOrNull {
                it.title == context.getString(com.aiwatch.probe.R.string.reminder_snooze)
            }
            assertNotNull("Notification must expose the snooze action", action)

            checkNotNull(action).actionIntent.send()
            val snoozeDeadline = android.os.SystemClock.uptimeMillis() + 5000
            while ((book.list().find { it.id == item.id }?.status != ReminderStatus.PENDING ||
                notifications.activeNotifications.any { it.tag == item.id }) && android.os.SystemClock.uptimeMillis() < snoozeDeadline) {
                android.os.SystemClock.sleep(50)
            }
            val snoozed = book.list().single { it.id == item.id }
            assertEquals(ReminderStatus.PENDING, snoozed.status)
            assertTrue("Snooze must move the due time forward by about ten minutes",
                snoozed.dueAt - System.currentTimeMillis() in (9 * 60_000L)..(10 * 60_000L))
            assertFalse("Current notification must be dismissed", notifications.activeNotifications.any { it.tag == item.id })

        } finally { book.cancel(item.id) }
    }
}
