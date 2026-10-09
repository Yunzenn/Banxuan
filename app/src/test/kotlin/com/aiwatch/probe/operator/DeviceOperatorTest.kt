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
        override fun mediaRange() = if (available) 0..10 else null
        override fun setMediaVolume(level: Int): Int { calls++; if (denied) throw SecurityException(); return actual }
        override fun canLaunch(packageName: String) = available
        override fun launch(packageName: String) { calls++; if (denied) throw SecurityException() }
        override fun canSetTimer() = available
        override fun openTimer(seconds: Int) { calls++; if (denied) throw SecurityException() }
    }
    @Test fun volumeReadsBackBeforeSuccess() {
        val port = Port(); val operator = DeviceOperator(port)
        assertEquals(ActionStatus.VERIFIED, operator.execute(DeviceAction.SetMediaVolume(3)).status)
        assertEquals(ActionStatus.UNVERIFIED, operator.execute(DeviceAction.SetMediaVolume(4)).status)
    }
    @Test fun invalidInputNeverExecutes() {
        val port = Port(); val operator = DeviceOperator(port)
        listOf(DeviceAction.SetMediaVolume(-1), DeviceAction.SetMediaVolume(11), DeviceAction.LaunchApp("exec shell"),
            DeviceAction.SetTimer(0), DeviceAction.SetTimer(86401)).forEach {
            assertEquals(ActionStatus.INVALID, operator.execute(it).status)
        }
        assertEquals(0, port.calls)
    }
    @Test fun capabilitiesAreCheckedBeforeExecution() {
        val port = Port().apply { available = false }; val operator = DeviceOperator(port)
        listOf(DeviceAction.SetMediaVolume(3), DeviceAction.LaunchApp("example.app"), DeviceAction.SetTimer(60)).forEach {
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
        }
        assertEquals(ActionStatus.FAILED, DeviceOperator(port).execute(DeviceAction.SetMediaVolume(3)).status)
        assertEquals(1, calls)
    }
}
