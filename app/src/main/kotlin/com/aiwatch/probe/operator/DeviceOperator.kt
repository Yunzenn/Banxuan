package com.aiwatch.probe.operator

/** Finite commands, never free-form instructions or shell. Called only after explicit user consent. */
sealed interface DeviceAction {
    data class SetMediaVolume(val level: Int) : DeviceAction
    data class LaunchApp(val packageName: String) : DeviceAction
    data class SetTimer(val seconds: Int) : DeviceAction
}

enum class ActionStatus { VERIFIED, HANDOFF, UNAVAILABLE, DENIED, INVALID, FAILED, UNVERIFIED }
data class ActionResult(val status: ActionStatus)
data class ActionAudit(val action: DeviceAction, val status: ActionStatus, val timestampMs: Long)

interface DeviceActionPort {
    fun mediaRange(): IntRange?
    fun setMediaVolume(level: Int): Int
    fun canLaunch(packageName: String): Boolean
    fun launch(packageName: String)
    fun canSetTimer(): Boolean
    fun openTimer(seconds: Int)
}

/** Small testable policy layer; platform adapters own capabilities and permission failures. */
class DeviceOperator(private val port: DeviceActionPort, private val clock: () -> Long = System::currentTimeMillis) {
    private val records = ArrayDeque<ActionAudit>()
    fun audit(): List<ActionAudit> = records.toList()

    fun execute(action: DeviceAction): ActionResult {
        val status = try {
            when (action) {
                is DeviceAction.SetMediaVolume -> {
                    val range = port.mediaRange()
                    when {
                        range == null -> ActionStatus.UNAVAILABLE
                        action.level !in range -> ActionStatus.INVALID
                        port.setMediaVolume(action.level) == action.level -> ActionStatus.VERIFIED
                        else -> ActionStatus.UNVERIFIED
                    }
                }
                is DeviceAction.LaunchApp -> when {
                    !action.packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")) -> ActionStatus.INVALID
                    !port.canLaunch(action.packageName) -> ActionStatus.UNAVAILABLE
                    else -> { port.launch(action.packageName); ActionStatus.HANDOFF }
                }
                is DeviceAction.SetTimer -> when {
                    action.seconds !in 1..86400 -> ActionStatus.INVALID
                    !port.canSetTimer() -> ActionStatus.UNAVAILABLE
                    else -> { port.openTimer(action.seconds); ActionStatus.HANDOFF }
                }
            }
        } catch (_: SecurityException) { ActionStatus.DENIED }
        catch (_: Exception) { ActionStatus.FAILED }
        if (records.size == 20) records.removeFirst()
        records.addLast(ActionAudit(action, status, clock()))
        return ActionResult(status)
    }
}
