package com.aiwatch.probe.reminder

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputFilter
import android.widget.*
import com.aiwatch.probe.ProbeApplication
import com.aiwatch.probe.R
import com.aiwatch.probe.product.ProductUi
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

class ReminderActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val book get() = (application as ProbeApplication).reminders
    private val selected = Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY, 1); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
    private lateinit var ui: ProductUi
    private lateinit var records: LinearLayout
    private lateinit var feedback: TextView
    private lateinit var content: EditText
    private lateinit var whenButton: Button
    private lateinit var save: Button
    private val workMutex = Mutex()
    private var pendingWork = 0
    private val busy get() = pendingWork > 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.getLong("dueAt")?.let { selected.timeInMillis = it }
        ui = ProductUi(this)
        val root = ui.page()
        ui.add(root, ui.button(getString(R.string.product_back)) { finish() })
        ui.add(root, ui.title(getString(R.string.reminder_title)))
        ui.add(root, ui.text(getString(R.string.reminder_intro), 14f, ui.muted))
        feedback = ui.text("", 14f, ui.accent).apply {
            accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        ui.add(root, feedback)
        content = EditText(this).apply {
            id = R.id.reminder_content
            hint = getString(R.string.reminder_content_hint)
            setTextColor(ui.ink); setHintTextColor(ui.muted)
            setSingleLine(true); filters = arrayOf(InputFilter.LengthFilter(160))
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
            minHeight = ui.dp(52)
        }
        ui.add(root, content)
        whenButton = ui.button(whenText()) { pickDate() }.apply { id = R.id.reminder_when }
        ui.add(root, whenButton)
        ui.add(root, ui.button(getString(R.string.reminder_permission)) {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 81)
            } else {
                try { startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) }
                catch (_: android.content.ActivityNotFoundException) { feedback.setText(R.string.reminder_permission_blocked) }
            }
        })
        ui.add(root, ui.button(getString(R.string.reminder_settings)) {
            try { startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) }
            catch (_: android.content.ActivityNotFoundException) { feedback.setText(R.string.reminder_permission_blocked) }
        })
        save = ui.button(getString(R.string.reminder_create), true) {
            val text = content.text.toString()
            val due = selected.timeInMillis
            AlertDialog.Builder(this).setTitle(R.string.reminder_confirm)
                .setMessage("${text.trim()}\n${whenText()}")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    work(R.string.reminder_saved) { book.create(text, due) }
                }.show()
        }.apply { id = R.id.reminder_save }
        ui.add(root, save)
        ui.add(root, ui.button(getString(R.string.reminder_help_title)) {
            AlertDialog.Builder(this).setTitle(R.string.reminder_help_title).setMessage(R.string.reminder_help)
                .setPositiveButton(android.R.string.ok, null).show()
        })
        ui.add(root, ui.text(getString(R.string.reminder_list), 20f), 20)
        records = ui.column()
        ui.add(root, records)
    }
    override fun onResume() { super.onResume(); if (!busy) work(null) { book.restore() } }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong("dueAt", selected.timeInMillis)
        super.onSaveInstanceState(outState)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 81) feedback.setText(if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
            R.string.reminder_permission_granted else R.string.reminder_permission_blocked)
    }
    private fun whenText() = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(selected.time)
    internal fun pickDate(): DatePickerDialog {
        // Commit the date and time together only after the final confirmation.
        val draft = selected.clone() as Calendar
        return DatePickerDialog(this, { _, year, month, day ->
            draft.set(year, month, day)
            TimePickerDialog(this, { _, hour, minute ->
                draft.set(Calendar.HOUR_OF_DAY, hour); draft.set(Calendar.MINUTE, minute)
                selected.timeInMillis = draft.timeInMillis
                whenButton.text = whenText()
            }, draft.get(Calendar.HOUR_OF_DAY), draft.get(Calendar.MINUTE), android.text.format.DateFormat.is24HourFormat(this)).show()
        }, draft.get(Calendar.YEAR), draft.get(Calendar.MONTH), draft.get(Calendar.DAY_OF_MONTH)).apply { show() }
    }
    private fun work(success: Int?, operation: () -> Unit) {
        // Already-open confirmation dialogs can complete during refresh. Queue that consent
        // exactly once instead of dropping it; disable new mutations until all work finishes.
        pendingWork++
        updateControls()
        scope.launch {
            try {
                workMutex.withLock {
                    try {
                        val snapshot = withContext(Dispatchers.IO) { operation(); book.list() }
                        if (success != null) feedback.setText(success)
                        render(snapshot)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: IllegalArgumentException) { feedback.setText(R.string.reminder_invalid) }
                    catch (_: IllegalStateException) { feedback.setText(R.string.reminder_permission_blocked) }
                    catch (_: Exception) { feedback.setText(R.string.reminder_error) }
                }
            } finally { pendingWork--; updateControls() }
        }
    }
    private fun updateControls() {
        save.isEnabled = !busy
        for (index in 0 until records.childCount) (records.getChildAt(index) as? Button)?.isEnabled = !busy
    }
    private fun render(snapshot: List<Reminder>) {
        records.removeAllViews()
        if (snapshot.isEmpty()) ui.add(records, ui.text(getString(R.string.reminder_empty), 14f, ui.muted))
        snapshot.forEach { reminder ->
            ui.add(records, ui.text(reminder.text))
            val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(reminder.dueAt))
            ui.add(records, ui.text("$date · ${getString(statusText(reminder.status))}", 13f, ui.muted), 0)
            if (reminder.status != ReminderStatus.CANCELLED) {
                ui.add(records, ui.button(getString(R.string.reminder_cancel)) {
                    AlertDialog.Builder(this).setMessage(R.string.reminder_cancel_confirm)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok) { _, _ -> work(R.string.reminder_cancelled) { book.cancel(reminder.id) } }.show()
                })
            }
        }
    }
    private fun statusText(status: ReminderStatus) = when (status) {
        ReminderStatus.PENDING -> R.string.reminder_pending
        ReminderStatus.POSTING -> R.string.reminder_unknown
        ReminderStatus.POSTED -> R.string.reminder_posted
        ReminderStatus.BLOCKED -> R.string.reminder_blocked
        ReminderStatus.FAILED -> R.string.reminder_failed
        ReminderStatus.CANCELLED -> R.string.reminder_cancelled
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
