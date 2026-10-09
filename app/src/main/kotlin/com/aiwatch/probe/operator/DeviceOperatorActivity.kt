package com.aiwatch.probe.operator

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
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
        ui.add(root, ui.button(getString(R.string.operator_audit)) {
            val lines = operator.audit().map { record ->
                val labelText = when(record.action) {
                    is DeviceAction.SetMediaVolume -> getString(R.string.operator_volume)
                    is DeviceAction.LaunchApp -> getString(R.string.operator_launch)
                    is DeviceAction.SetTimer -> getString(R.string.operator_timer)
                }
                "$labelText · ${getString(statusText(record.status))}"
            }
            AlertDialog.Builder(this).setTitle(R.string.operator_audit)
                .setMessage(lines.joinToString("\n").ifEmpty { getString(R.string.operator_no_audit) })
                .setPositiveButton(android.R.string.ok, null).show()
        })
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
    private fun statusText(status: ActionStatus): Int = when(status) {
        ActionStatus.VERIFIED -> R.string.operator_verified
        ActionStatus.HANDOFF -> R.string.operator_handoff
        ActionStatus.UNAVAILABLE -> R.string.operator_unavailable
        ActionStatus.DENIED -> R.string.operator_denied
        ActionStatus.INVALID -> R.string.operator_invalid
        ActionStatus.FAILED -> R.string.operator_failed
        ActionStatus.UNVERIFIED -> R.string.operator_unverified
    }
}
