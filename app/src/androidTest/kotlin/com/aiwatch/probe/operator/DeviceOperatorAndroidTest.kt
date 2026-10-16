package com.aiwatch.probe.operator

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Platform volume readback and real user surface, not acoustic/timer-provider certification. */
class DeviceOperatorAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun launch() = instrumentation.startActivitySync(
        Intent(instrumentation.targetContext, DeviceOperatorActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    ) as DeviceOperatorActivity
    private fun close(activity: DeviceOperatorActivity) {
        instrumentation.runOnMainSync { activity.finish() }; instrumentation.waitForIdleSync()
    }
    @Test fun userSurfaceRequiresConfirmationAndCancelDoesNotChangeVolume() {
        val activity = launch()
        try {
            val original = AndroidDeviceActions(activity).currentVolume()
            assertEquals("设备操作", activity.getString(com.aiwatch.probe.R.string.operator_title))
            instrumentation.runOnMainSync {
                val before = AndroidDeviceActions(activity).currentVolume()
                fun find(view: android.view.View): android.widget.Button? {
                    if (view is android.widget.Button && view.text.toString() == "应用音量") return view
                    if (view is android.view.ViewGroup) for (i in 0 until view.childCount) {
                        find(view.getChildAt(i))?.let { return it }
                    }
                    return null
                }
                checkNotNull(find(activity.findViewById(android.R.id.content))).performClick()
                assertEquals(before, AndroidDeviceActions(activity).currentVolume())
            }
            // Platform UiAutomation inspects the dialog button; no fixture changes the real action.
            instrumentation.waitForIdleSync()
            // Main-thread idle does not imply the accessibility window snapshot is published.
            // Wait for the actual dialog node, never skip the confirmation/cancel assertion.
            val deadline = android.os.SystemClock.uptimeMillis() + 5000
            var cancel: android.view.accessibility.AccessibilityNodeInfo? = null
            while (cancel == null && android.os.SystemClock.uptimeMillis() < deadline) {
                cancel = instrumentation.uiAutomation.rootInActiveWindow
                    ?.findAccessibilityNodeInfosByText(activity.getString(android.R.string.cancel))?.firstOrNull()
                if (cancel == null) android.os.SystemClock.sleep(50)
            }
            assertNotNull("Confirmation dialog cancel button must appear within 5 seconds", cancel)
            assertTrue(checkNotNull(cancel).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.waitForIdleSync()
            assertEquals(original, AndroidDeviceActions(activity).currentVolume())
        } finally { close(activity) }
    }
    @Test fun mediaVolumeIsVerifiedUsingTheRealAndroidManager() {
        val activity = launch()
        try {
            instrumentation.runOnMainSync {
                val port = AndroidDeviceActions(activity)
                assertNotNull("Fixture must expose adjustable media volume", port.mediaRange())
                val original = port.currentVolume()
                val range = checkNotNull(port.mediaRange())
                val target = if (original == range.first) range.last else range.first
                try {
                    assertEquals(ActionStatus.VERIFIED, DeviceOperator(port).execute(DeviceAction.SetMediaVolume(target)).status)
                    assertEquals(target, port.currentVolume())
                } finally { port.setMediaVolume(original) }
            }
        } finally { close(activity) }
    }
    @Test fun missingApplicationReturnsUnavailableWithoutLaunchingAnything() {
        val activity = launch()
        try {
            instrumentation.runOnMainSync {
                assertEquals(ActionStatus.UNAVAILABLE,
                    DeviceOperator(AndroidDeviceActions(activity)).execute(DeviceAction.LaunchApp("example.banxuan.missing")).status)
            }
        } finally { close(activity) }
    }
    @Test fun installedApplicationExposesARealLaunchIntentAndInvalidTimerIsRefused() {
        val activity = launch()
        try {
            instrumentation.runOnMainSync {
                val port = AndroidDeviceActions(activity)
                assertTrue(port.canLaunch(activity.packageName))
                assertEquals(ActionStatus.INVALID, DeviceOperator(port).execute(DeviceAction.SetTimer(-1)).status)
            }
        } finally { close(activity) }
    }
    @Test fun launchAppHandsOffToTheRealOwnedLauncherActivity() {
        val activity = launch()
        val monitor = instrumentation.addMonitor("com.aiwatch.probe.home.CompanionActivity", null, false)
        try {
            instrumentation.runOnMainSync {
                assertEquals(ActionStatus.HANDOFF,
                    DeviceOperator(AndroidDeviceActions(activity)).execute(DeviceAction.LaunchApp(activity.packageName)).status)
            }
            val launched = instrumentation.waitForMonitorWithTimeout(monitor, 5000)
            assertNotNull("Real launcher must open", launched)
            instrumentation.runOnMainSync { launched.finish() }
        } finally {
            instrumentation.removeMonitor(monitor)
            close(activity)
        }
    }
    @Test fun timerIntentPreservesDurationAndRequiresSystemClockUi() {
        val activity = launch()
        try {
            val intent = AndroidDeviceActions(activity).timerIntent(600)
            assertEquals(android.provider.AlarmClock.ACTION_SET_TIMER, intent.action)
            assertEquals(600, intent.getIntExtra(android.provider.AlarmClock.EXTRA_LENGTH, -1))
            assertFalse(intent.getBooleanExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true))
        } finally { close(activity) }
    }
    @Test fun alarmIntentsPreserveTimeWithoutInventingRepeatOrSoundPolicy() {
        val activity = launch()
        try {
            val port = AndroidDeviceActions(activity)
            for ((hour, minute) in listOf(0 to 0, 23 to 59)) {
                val intent = port.alarmIntent(hour, minute)
                assertEquals(android.provider.AlarmClock.ACTION_SET_ALARM, intent.action)
                assertEquals(hour, intent.getIntExtra(android.provider.AlarmClock.EXTRA_HOUR, -1))
                assertEquals(minute, intent.getIntExtra(android.provider.AlarmClock.EXTRA_MINUTES, -1))
                assertFalse(intent.getBooleanExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true))
                assertFalse(intent.hasExtra(android.provider.AlarmClock.EXTRA_DAYS))
                assertFalse(intent.hasExtra(android.provider.AlarmClock.EXTRA_RINGTONE))
            }
            assertEquals(android.provider.AlarmClock.ACTION_SHOW_ALARMS, port.showAlarmsIntent().action)
        } finally { close(activity) }
    }
    @Test fun cancellingEitherAlarmDialogDispatchesNothing() {
        val activity = launch()
        val monitor = alarmMonitor(android.provider.AlarmClock.ACTION_SET_ALARM)
        try {
            instrumentation.runOnMainSync {
                activity.chooseAlarm().getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick()
            }
            instrumentation.waitForIdleSync()
            assertEquals(0, monitor.hits)
            chooseTime(activity, 7, 15)
            clickConfirmation(activity, android.R.string.cancel)
            assertEquals(0, monitor.hits)
        } finally { instrumentation.removeMonitor(monitor); close(activity) }
    }
    @Test fun explicitAlarmConfirmationDispatchesExactlyOnceAndReportsOnlyHandoff() {
        val activity = launch()
        // Block the real receiver: this is consent/dispatch evidence, not alarm creation or ringing.
        val monitor = alarmMonitor(android.provider.AlarmClock.ACTION_SET_ALARM)
        try {
            assertTrue("Fixture must have a system clock handler", AndroidDeviceActions(activity).canSetAlarm())
            chooseTime(activity, 7, 15)
            assertEquals(0, monitor.hits)
            clickConfirmation(activity, android.R.string.ok)
            assertEquals(1, monitor.hits)
            instrumentation.runOnMainSync {
                assertTrue(allText(activity.findViewById(android.R.id.content))
                    .contains(activity.getString(com.aiwatch.probe.R.string.operator_handoff)))
            }
        } finally { instrumentation.removeMonitor(monitor); close(activity) }
    }
    @Test fun showAlarmsDispatchesOnceWithoutCreatingAnAlarm() {
        val activity = launch()
        val show = alarmMonitor(android.provider.AlarmClock.ACTION_SHOW_ALARMS)
        val create = alarmMonitor(android.provider.AlarmClock.ACTION_SET_ALARM)
        try {
            instrumentation.runOnMainSync {
                val port = AndroidDeviceActions(activity)
                assertTrue("Fixture must expose alarm list", port.canShowAlarms())
                assertEquals(ActionStatus.HANDOFF, DeviceOperator(port).execute(DeviceAction.ShowAlarms).status)
            }
            assertEquals(1, show.hits)
            assertEquals(0, create.hits)
        } finally {
            instrumentation.removeMonitor(show); instrumentation.removeMonitor(create); close(activity)
        }
    }
    private fun alarmMonitor(action: String) = instrumentation.addMonitor(
        android.content.IntentFilter(action),
        android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED, null), true
    )
    private fun chooseTime(activity: DeviceOperatorActivity, hour: Int, minute: Int) {
        instrumentation.runOnMainSync {
            val dialog = activity.chooseAlarm()
            fun find(view: android.view.View): android.widget.TimePicker? {
                if (view is android.widget.TimePicker) return view
                if (view is android.view.ViewGroup) for (i in 0 until view.childCount) {
                    find(view.getChildAt(i))?.let { return it }
                }
                return null
            }
            val picker = checkNotNull(find(checkNotNull(dialog.window).decorView))
            picker.hour = hour; picker.minute = minute
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        }
        instrumentation.waitForIdleSync()
    }
    private fun clickConfirmation(activity: DeviceOperatorActivity, label: Int) {
        val automation = instrumentation.uiAutomation
        val previousFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        try {
            val deadline = android.os.SystemClock.uptimeMillis() + 5000
            val buttonId = if (label == android.R.string.ok) "android:id/button1" else "android:id/button2"
            var button: android.view.accessibility.AccessibilityNodeInfo? = null
            while (button == null && android.os.SystemClock.uptimeMillis() < deadline) {
                // The TimePicker dismisses after its listener opens our dialog. The active accessibility
                // root can still name the old picker; match our actual confirmation window, not its OK.
                val roots = automation.windows.mapNotNull { it.root } + listOfNotNull(automation.rootInActiveWindow)
                button = roots.firstOrNull { root ->
                    root.findAccessibilityNodeInfosByText(activity.getString(com.aiwatch.probe.R.string.operator_confirm)).isNotEmpty()
                }?.findAccessibilityNodeInfosByViewId(buttonId)
                    ?.firstOrNull { it.isClickable && it.isEnabled }
                if (button == null) android.os.SystemClock.sleep(50)
            }
            fun describe(node: android.view.accessibility.AccessibilityNodeInfo?): String = buildString {
                if (node == null) return@buildString
                append("[${node.text}/${node.className}/click=${node.isClickable}]")
                for (i in 0 until node.childCount) append(describe(node.getChild(i)))
            }
            assertNotNull("Confirmation button must appear: ${describe(automation.rootInActiveWindow)}", button)
            assertTrue(checkNotNull(button).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.waitForIdleSync()
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { flags = previousFlags }
        }
    }
    private fun allText(view: android.view.View): String = buildString {
        if (view is android.widget.TextView) append(view.text)
        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) append(allText(view.getChildAt(i)))
    }
}
