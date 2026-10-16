package com.aiwatch.probe.operator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeviceOperatorTest {
    private class Port : DeviceActionPort {
        var calls = 0
        var available = true
        var denied = false
        var actual = 3
        var failDispatch = false
        override fun mediaRange() = if (available) 0..10 else null
        override fun setMediaVolume(level: Int): Int { calls++; if (denied) throw SecurityException(); return actual }
        override fun canLaunch(packageName: String) = available
        override fun launch(packageName: String) { dispatch() }
        override fun canSetTimer() = available
        override fun openTimer(seconds: Int) { dispatch() }
        override fun canSetAlarm() = available
        override fun openAlarm(hour: Int, minute: Int) { dispatch() }
        override fun canShowAlarms() = available
        override fun showAlarms() { dispatch() }
        private fun dispatch() { calls++; if (denied) throw SecurityException(); if (failDispatch) error("uncertain IPC") }
    }
    @Test fun volumeReadsBackBeforeSuccess() {
        val port = Port(); val operator = DeviceOperator(port)
        assertEquals(ActionStatus.VERIFIED, operator.execute(DeviceAction.SetMediaVolume(3)).status)
        assertEquals(ActionStatus.UNVERIFIED, operator.execute(DeviceAction.SetMediaVolume(4)).status)
    }
    @Test fun invalidInputNeverExecutes() {
        val port = Port(); val operator = DeviceOperator(port)
        listOf(DeviceAction.SetMediaVolume(-1), DeviceAction.SetMediaVolume(11), DeviceAction.LaunchApp("exec shell"),
            DeviceAction.SetTimer(0), DeviceAction.SetTimer(86401), DeviceAction.SetAlarm(-1, 0),
            DeviceAction.SetAlarm(24, 0), DeviceAction.SetAlarm(0, -1), DeviceAction.SetAlarm(0, 60)).forEach {
            assertEquals(ActionStatus.INVALID, operator.execute(it).status)
        }
        assertEquals(0, port.calls)
    }
    @Test fun capabilitiesAreCheckedBeforeExecution() {
        val port = Port().apply { available = false }; val operator = DeviceOperator(port)
        listOf(DeviceAction.SetMediaVolume(3), DeviceAction.LaunchApp("example.app"), DeviceAction.SetTimer(60),
            DeviceAction.SetAlarm(7, 30), DeviceAction.ShowAlarms).forEach {
            assertEquals(ActionStatus.UNAVAILABLE, operator.execute(it).status)
        }
        assertEquals(0, port.calls)
    }
    @Test fun permissionDenialIsAuditedWithoutRetry() {
        val port = Port().apply { denied = true }; val operator = DeviceOperator(port) { 123L }
        val action = DeviceAction.LaunchApp("example.app")
        assertEquals(ActionStatus.DENIED, operator.execute(action).status)
        assertEquals(1, port.calls)
        assertEquals(listOf(ActionAudit(action, ActionStatus.DENIED, 123L)), operator.audit())
    }
    @Test fun intentDispatchNeverClaimsCompletion() {
        val port = Port(); val operator = DeviceOperator(port)
        assertEquals(ActionStatus.HANDOFF, operator.execute(DeviceAction.SetTimer(600)).status)
        assertEquals(ActionStatus.HANDOFF, operator.execute(DeviceAction.LaunchApp("example.app")).status)
    }
    @Test fun auditRetentionIsBounded() {
        val operator = DeviceOperator(Port())
        repeat(100) { operator.execute(DeviceAction.SetTimer(it + 1)) }
        assertEquals(20, operator.audit().size)
        assertTrue(operator.audit().all { it.status == ActionStatus.HANDOFF })
    }
    @Test fun executionFailureDoesNotRetry() {
        var calls = 0
        val port = object : DeviceActionPort {
            override fun mediaRange() = 0..10
            override fun setMediaVolume(level: Int): Int { calls++; error("system failure") }
            override fun canLaunch(packageName: String) = false
            override fun launch(packageName: String) = Unit
            override fun canSetTimer() = false
            override fun openTimer(seconds: Int) = Unit
            override fun canSetAlarm() = false
            override fun openAlarm(hour: Int, minute: Int) = Unit
            override fun canShowAlarms() = false
            override fun showAlarms() = Unit
        }
        assertEquals(ActionStatus.FAILED, DeviceOperator(port).execute(DeviceAction.SetMediaVolume(3)).status)
        assertEquals(1, calls)
    }
    @Test fun alarmBoundaryTimesHandOffWithoutClaimingCreation() {
        val port = Port(); val operator = DeviceOperator(port)
        for (action in listOf(DeviceAction.SetAlarm(0, 0), DeviceAction.SetAlarm(23, 59), DeviceAction.ShowAlarms)) {
            assertEquals(ActionStatus.HANDOFF, operator.execute(action).status)
        }
        assertEquals(3, port.calls)
        assertEquals(3, operator.audit().size)
    }
    @Test fun alarmPermissionDenialIsAuditedExactlyOnce() {
        val port = Port().apply { denied = true }; val operator = DeviceOperator(port) { 42L }
        val action = DeviceAction.SetAlarm(7, 30)
        assertEquals(ActionStatus.DENIED, operator.execute(action).status)
        assertEquals(1, port.calls)
        assertEquals(listOf(ActionAudit(action, ActionStatus.DENIED, 42L)), operator.audit())
    }
    @Test fun uncertainIntentDispatchIsNeverRetriedOrMisreportedAsFailure() {
        for (action in listOf(DeviceAction.SetAlarm(7, 30), DeviceAction.ShowAlarms,
            DeviceAction.LaunchApp("example.app"), DeviceAction.SetTimer(60))) {
            val port = Port().apply { failDispatch = true }; val operator = DeviceOperator(port)
            assertEquals(ActionStatus.UNKNOWN, operator.execute(action).status)
            assertEquals(1, port.calls)
            assertEquals(ActionStatus.UNKNOWN, operator.audit().single().status)
        }
    }
}
