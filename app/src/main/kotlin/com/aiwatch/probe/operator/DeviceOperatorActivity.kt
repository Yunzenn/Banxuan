package com.aiwatch.probe.operator

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.text.format.DateFormat
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.aiwatch.probe.R
import com.aiwatch.probe.product.ProductUi

/** User-driven native tools. No agent/autonomous execution or accessibility permission required. */
class DeviceOperatorActivity : Activity() {
    private lateinit var feedback: TextView
    private lateinit var operator: DeviceOperator
    private lateinit var platform: AndroidDeviceActions

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        platform = AndroidDeviceActions(this)
        operator = DeviceOperator(platform)
        val ui = ProductUi(this)
        val root = ui.page()
        ui.add(root, ui.button(getString(R.string.product_back)) { finish() })
        ui.add(root, ui.title(getString(R.string.operator_title)))
        ui.add(root, ui.text(getString(R.string.operator_intro), 14f, ui.muted))
        feedback = ui.text("", 14f, ui.accent).apply {
            accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        ui.add(root, feedback)
        ui.add(root, ui.text(getString(R.string.operator_volume)))
        val range = platform.mediaRange()
        val label = ui.text("")
        val slider = SeekBar(this).apply {
            min = range?.first ?: 0; max = range?.last ?: 0
            progress = platform.currentVolume(); isEnabled = range != null
            contentDescription = getString(R.string.operator_volume)
        }
        fun volumeLabel() { label.text = getString(R.string.operator_volume_level, slider.progress, slider.max) }
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) { volumeLabel() }
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })
        volumeLabel()
        ui.add(root, label); ui.add(root, slider)
        ui.add(root, ui.button(getString(R.string.operator_apply_volume)) {
            confirm(getString(R.string.operator_volume_level, slider.progress, slider.max), DeviceAction.SetMediaVolume(slider.progress))
        }.apply { isEnabled = range != null })
        if (range == null) ui.add(root, ui.text(getString(R.string.operator_unavailable), 14f, ui.muted))
        ui.add(root, ui.button(getString(R.string.operator_launch)) { chooseApp() })
        ui.add(root, ui.button(getString(R.string.operator_timer)) {
            val minutes = listOf(1, 5, 10, 25)
            AlertDialog.Builder(this).setTitle(R.string.operator_timer)
                .setItems(minutes.map { getString(R.string.operator_minutes, it) }.toTypedArray()) { _, index ->
                    confirm(getString(R.string.operator_timer_confirm, minutes[index]), DeviceAction.SetTimer(minutes[index] * 60))
                }.setNegativeButton(android.R.string.cancel, null).show()
        }.apply { isEnabled = platform.canSetTimer() })
        if (!platform.canSetTimer()) ui.add(root, ui.text(getString(R.string.operator_no_clock), 14f, ui.muted))
        ui.add(root, ui.button(getString(R.string.operator_alarm)) { chooseAlarm() }.apply { isEnabled = platform.canSetAlarm() })
        ui.add(root, ui.text(getString(R.string.operator_alarm_hint), 13f, ui.muted))
        if (!platform.canSetAlarm()) ui.add(root, ui.text(getString(R.string.operator_no_alarm), 14f, ui.muted))
        ui.add(root, ui.button(getString(R.string.operator_show_alarms)) {
            feedback.setText(statusText(operator.execute(DeviceAction.ShowAlarms).status))
        }.apply { isEnabled = platform.canShowAlarms() })
        if (!platform.canShowAlarms()) ui.add(root, ui.text(getString(R.string.operator_no_alarm_list), 14f, ui.muted))
        ui.add(root, ui.button(getString(R.string.operator_calendar)) { chooseCalendarEvent() }
            .apply { isEnabled = platform.canCreateCalendarEvent() })
        if (!platform.canCreateCalendarEvent()) ui.add(root, ui.text(getString(R.string.operator_no_calendar), 14f, ui.muted))
        ui.add(root, ui.button(getString(R.string.operator_audit)) {
            val lines = operator.audit().map { record ->
                val labelText = when(record.action) {
                    is DeviceAction.SetMediaVolume -> getString(R.string.operator_volume)
                    is DeviceAction.LaunchApp -> getString(R.string.operator_launch)
                    is DeviceAction.SetTimer -> getString(R.string.operator_timer)
                    is DeviceAction.SetAlarm -> getString(R.string.operator_alarm_time, record.action.hour, record.action.minute)
                    DeviceAction.ShowAlarms -> getString(R.string.operator_show_alarms)
                    is DeviceAction.CreateCalendarEvent -> getString(R.string.operator_calendar_event, record.action.title)
                }
                "$labelText · ${getString(statusText(record.status))}"
            }
            AlertDialog.Builder(this).setTitle(R.string.operator_audit)
                .setMessage(lines.joinToString("\n").ifEmpty { getString(R.string.operator_no_audit) })
                .setPositiveButton(android.R.string.ok, null).show()
        })
    }
    internal fun chooseAlarm(): android.app.TimePickerDialog {
        val now = java.util.Calendar.getInstance()
        return android.app.TimePickerDialog(this, { _, hour, minute ->
            confirm(getString(R.string.operator_alarm_confirm, hour, minute), DeviceAction.SetAlarm(hour, minute))
        }, now.get(java.util.Calendar.HOUR_OF_DAY), now.get(java.util.Calendar.MINUTE),
            android.text.format.DateFormat.is24HourFormat(this)).apply { show() }
    }
    /** Intercepts only the final calendar intent boundary in instrumentation tests. */
    internal fun setCalendarDispatcherForTesting(dispatch: (Intent) -> Unit) {
        platform = AndroidDeviceActions(this, dispatch)
        operator = DeviceOperator(platform)
    }
    internal fun chooseCalendarEvent(): AlertDialog {
        val draft = java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.HOUR_OF_DAY, 1)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val ui = ProductUi(this)
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(20), ui.dp(4), ui.dp(20), 0)
        }
        val title = EditText(this).apply {
            hint = getString(R.string.operator_calendar_title_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(InputFilter.LengthFilter(80))
            setSingleLine(true)
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
            contentDescription = getString(R.string.operator_calendar_title_hint)
        }
        form.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(56)))
        val validation = ui.text("", 13f, ui.accent)
        form.addView(validation, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val dateButton = ui.button("") {}
        val timeButton = ui.button("") {}
        fun updateLabels() {
            dateButton.text = getString(R.string.operator_calendar_date,
                draft.get(java.util.Calendar.YEAR), draft.get(java.util.Calendar.MONTH) + 1,
                draft.get(java.util.Calendar.DAY_OF_MONTH))
            timeButton.text = getString(R.string.operator_calendar_time,
                draft.get(java.util.Calendar.HOUR_OF_DAY), draft.get(java.util.Calendar.MINUTE))
        }
        dateButton.setOnClickListener {
            DatePickerDialog(this, { _, year, month, day ->
                draft.set(java.util.Calendar.YEAR, year)
                draft.set(java.util.Calendar.MONTH, month)
                draft.set(java.util.Calendar.DAY_OF_MONTH, day)
                updateLabels()
            }, draft.get(java.util.Calendar.YEAR), draft.get(java.util.Calendar.MONTH),
                draft.get(java.util.Calendar.DAY_OF_MONTH)).show()
        }
        timeButton.setOnClickListener {
            TimePickerDialog(this, { _, hour, minute ->
                draft.set(java.util.Calendar.HOUR_OF_DAY, hour)
                draft.set(java.util.Calendar.MINUTE, minute)
                updateLabels()
            }, draft.get(java.util.Calendar.HOUR_OF_DAY), draft.get(java.util.Calendar.MINUTE),
                DateFormat.is24HourFormat(this)).show()
        }
        updateLabels()
        form.addView(dateButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        form.addView(timeButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        form.addView(ui.text(getString(R.string.operator_calendar_duration_hint), 13f, ui.muted),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val dialog = AlertDialog.Builder(this).setTitle(R.string.operator_calendar)
            .setView(form)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.operator_calendar_continue, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val eventTitle = title.text.toString().trim()
                val startAt = draft.timeInMillis
                val action = DeviceAction.CreateCalendarEvent(eventTitle, startAt, CALENDAR_DEFAULT_DURATION_MINUTES)
                if (!isValidCalendarEvent(action, System.currentTimeMillis())) {
                    validation.setText(R.string.operator_calendar_invalid)
                } else if (!platform.canCreateCalendarEvent()) {
                    dialog.dismiss()
                    feedback.setText(R.string.operator_no_calendar)
                } else {
                    dialog.dismiss()
                    confirm(calendarConfirmation(eventTitle, startAt, CALENDAR_DEFAULT_DURATION_MINUTES), action)
                }
            }
        }
        return dialog.apply { show() }
    }
    private fun chooseApp() {
        val pm = packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .distinctBy { it.activityInfo.packageName }.sortedBy { it.loadLabel(pm).toString() }
        if (apps.isEmpty()) { feedback.setText(R.string.operator_unavailable); return }
        AlertDialog.Builder(this).setTitle(R.string.operator_launch)
            .setItems(apps.map { it.loadLabel(pm).toString() }.toTypedArray()) { _, index ->
                val app = apps[index]
                confirm(getString(R.string.operator_launch_confirm, app.loadLabel(pm)), DeviceAction.LaunchApp(app.activityInfo.packageName))
            }.setNegativeButton(android.R.string.cancel, null).show()
    }
    private fun confirm(message: String, action: DeviceAction) {
        AlertDialog.Builder(this).setTitle(R.string.operator_confirm).setMessage(message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> feedback.setText(statusText(operator.execute(action).status)) }.show()
    }
    private fun calendarConfirmation(title: String, startAtEpochMs: Long, durationMinutes: Int): String {
        val date = DateFormat.getLongDateFormat(this).format(java.util.Date(startAtEpochMs))
        val time = DateFormat.getTimeFormat(this).format(java.util.Date(startAtEpochMs))
        return getString(R.string.operator_calendar_confirm, title, date, time, durationMinutes)
    }
    private fun statusText(status: ActionStatus): Int = when(status) {
        ActionStatus.VERIFIED -> R.string.operator_verified
        ActionStatus.HANDOFF -> R.string.operator_handoff
        ActionStatus.UNAVAILABLE -> R.string.operator_unavailable
        ActionStatus.DENIED -> R.string.operator_denied
        ActionStatus.INVALID -> R.string.operator_invalid
        ActionStatus.FAILED -> R.string.operator_failed
        ActionStatus.UNVERIFIED -> R.string.operator_unverified
        ActionStatus.UNKNOWN -> R.string.operator_unknown
    }

    private companion object { const val CALENDAR_DEFAULT_DURATION_MINUTES = 30 }
}
