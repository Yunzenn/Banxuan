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
}
