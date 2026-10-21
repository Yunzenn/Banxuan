package com.aiwatch.probe.operator

/** Finite commands, never shell. Mutations require consent; ShowAlarms only opens the clock list. */
sealed interface DeviceAction {
    data class SetMediaVolume(val level: Int) : DeviceAction
    data class LaunchApp(val packageName: String) : DeviceAction
    data class SetTimer(val seconds: Int) : DeviceAction
    data class SetAlarm(val hour: Int, val minute: Int) : DeviceAction
    data object ShowAlarms : DeviceAction
    data class CreateCalendarEvent(val title: String, val startAtEpochMs: Long, val durationMinutes: Int) : DeviceAction
}

enum class ActionStatus { VERIFIED, HANDOFF, UNAVAILABLE, DENIED, INVALID, FAILED, UNVERIFIED, UNKNOWN }
data class ActionResult(val status: ActionStatus)
data class ActionAudit(val action: DeviceAction, val status: ActionStatus, val timestampMs: Long)

internal fun isValidCalendarEvent(action: DeviceAction.CreateCalendarEvent, nowMs: Long): Boolean {
    val title = action.title.trim()
    val endAt = runCatching {
        Math.addExact(action.startAtEpochMs, Math.multiplyExact(action.durationMinutes.toLong(), 60_000L))
    }.getOrNull()
    return title.isNotEmpty() && title.length <= 80 && title.none(Char::isISOControl) &&
        action.startAtEpochMs > nowMs && action.durationMinutes in 1..1440 && endAt != null
}

interface DeviceActionPort {
    fun mediaRange(): IntRange?
    fun setMediaVolume(level: Int): Int
    fun canLaunch(packageName: String): Boolean
    fun launch(packageName: String)
    fun canSetTimer(): Boolean
    fun openTimer(seconds: Int)
    fun canSetAlarm(): Boolean
    fun openAlarm(hour: Int, minute: Int)
    fun canShowAlarms(): Boolean
    fun showAlarms()
    fun canCreateCalendarEvent(): Boolean
    fun openCalendarEvent(title: String, startAtEpochMs: Long, endAtEpochMs: Long)
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
                    else -> handoff { port.launch(action.packageName) }
                }
                is DeviceAction.SetTimer -> when {
                    action.seconds !in 1..86400 -> ActionStatus.INVALID
                    !port.canSetTimer() -> ActionStatus.UNAVAILABLE
                    else -> handoff { port.openTimer(action.seconds) }
                }
                is DeviceAction.SetAlarm -> when {
                    action.hour !in 0..23 || action.minute !in 0..59 -> ActionStatus.INVALID
                    !port.canSetAlarm() -> ActionStatus.UNAVAILABLE
                    else -> handoff { port.openAlarm(action.hour, action.minute) }
                }
                DeviceAction.ShowAlarms -> when {
                    !port.canShowAlarms() -> ActionStatus.UNAVAILABLE
                    else -> handoff { port.showAlarms() }
                }
                is DeviceAction.CreateCalendarEvent -> {
                    val title = action.title.trim()
                    val endAt = runCatching {
                        Math.addExact(action.startAtEpochMs, Math.multiplyExact(action.durationMinutes.toLong(), 60_000L))
                    }.getOrNull()
                    when {
                        !isValidCalendarEvent(action, clock()) -> ActionStatus.INVALID
                        !port.canCreateCalendarEvent() -> ActionStatus.UNAVAILABLE
                        else -> handoff { port.openCalendarEvent(title, action.startAtEpochMs, checkNotNull(endAt)) }
                    }
                }
            }
        } catch (_: SecurityException) { ActionStatus.DENIED }
        catch (_: Exception) { ActionStatus.FAILED }
        if (records.size == 20) records.removeFirst()
        records.addLast(ActionAudit(action, status, clock()))
        return ActionResult(status)
    }
    private fun handoff(dispatch: () -> Unit): ActionStatus = try {
        dispatch(); ActionStatus.HANDOFF
    } catch (denied: SecurityException) { throw denied }
    catch (_: Exception) {
        // The target may have accepted the request before IPC failed. Never retry or claim failure.
        ActionStatus.UNKNOWN
    }
}
