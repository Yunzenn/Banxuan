package com.aiwatch.probe.product

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.widget.*
import com.aiwatch.probe.MainActivity
import com.aiwatch.probe.ProbeApplication
import com.aiwatch.probe.R
import com.aiwatch.probe.memory.MemoryTrustActivity
import kotlinx.coroutines.*

class SettingsActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val store get() = (application as ProbeApplication).productStore
    private lateinit var feedback: TextView
    private lateinit var name: EditText
    private lateinit var endpoint: EditText
    private lateinit var saveName: Button
    private lateinit var saveServer: Button
    private lateinit var import: Button
    private lateinit var restore: Button
    private var busy = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ui = ProductUi(this)
        val root = ui.page()
        ui.add(root, ui.button(getString(R.string.product_back)) { finish() })
        ui.add(root, ui.title(getString(R.string.product_settings)))
        ui.add(root, ui.text(getString(R.string.product_settings_intro), 16f, ui.muted))
        feedback = ui.text("", 14f, ui.accent).apply { accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE }
        ui.add(root, feedback)
        fun section(title: Int, hint: Int) {
            ui.add(root, ui.text(getString(title), 20f), 20)
            ui.add(root, ui.text(getString(hint), 13f, ui.muted), 0)
        }
        fun field(hint: Int, max: Int, type: Int) = EditText(this).apply {
            setTextColor(ui.ink); setHintTextColor(ui.muted); setHint(hint)
            backgroundTintList = android.content.res.ColorStateList.valueOf(ui.accent)
            inputType = type; filters = arrayOf(InputFilter.LengthFilter(max)); setSingleLine(true)
            setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8)); minHeight = ui.dp(52)
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
        }
        section(R.string.product_character, R.string.product_character_hint)
        name = field(R.string.product_character, 32, InputType.TYPE_CLASS_TEXT)
        ui.add(root, name)
        saveName = ui.button(getString(R.string.product_save)) {
            val value = name.text.toString().trim()
            if (value.isEmpty() || value.length > 32 || value.any { it.isISOControl() }) feedback.setText(R.string.product_name_error)
            else work(R.string.product_saved) { store.saveCharacter(value) }
        }
        ui.add(root, saveName)
        section(R.string.product_avatar, R.string.product_avatar_hint)
        import = ui.button(getString(R.string.product_import), true) {
            try {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "image/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/png", "image/jpeg", "image/webp"))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, 41)
            } catch (_: android.content.ActivityNotFoundException) { feedback.setText(R.string.product_import_failed) }
        }
        ui.add(root, import)
        restore = ui.button(getString(R.string.product_restore)) {
            AlertDialog.Builder(this).setMessage(R.string.product_restore_warning)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.product_restore) { _, _ -> work(R.string.product_saved) { store.resetAvatar() } }.show()
        }
        ui.add(root, restore)
        ui.add(root, ui.button(getString(R.string.product_live2d)) { explain(R.string.product_live2d, R.string.product_live2d_hint) })
        ui.add(root, ui.button(getString(R.string.product_voice)) { explain(R.string.product_voice, R.string.product_voice_hint) })
        ui.add(root, ui.button(getString(R.string.product_personality)) { explain(R.string.product_personality, R.string.product_personality_hint) })
        // The trust surface lives here rather than on Home: Home stays the character, the state and the
        // three-dot settings, and a memory screen is a thing you go and look at on purpose.
        section(R.string.memory_entry, R.string.memory_entry_hint)
        ui.add(root, ui.button(getString(R.string.memory_entry)) { startActivity(Intent(this, MemoryTrustActivity::class.java)) })
        section(R.string.product_server, R.string.product_server_hint)
        endpoint = field(R.string.bootstrap_endpoint, 2048, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        ui.add(root, endpoint)
        saveServer = ui.button(getString(R.string.product_save)) {
            val value = endpoint.text.toString().trim()
            if (!ProductStore.validEndpoint(value)) feedback.setText(R.string.product_endpoint_error)
            else work(R.string.product_saved) { store.saveServer(value) }
        }
        ui.add(root, saveServer)
        ui.add(root, ui.button(getString(R.string.product_diagnostics)) { startActivity(Intent(this, MainActivity::class.java)) }, 24)
        setBusy(true)
        scope.launch {
            try {
                val values = withContext(Dispatchers.IO) { store.character() to store.server() }
                name.setText(values.first.name); endpoint.setText(values.second.endpoint)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { feedback.setText(R.string.product_store_error) }
            finally { setBusy(false) }
        }
    }
    private fun explain(title: Int, text: Int) {
        AlertDialog.Builder(this).setTitle(title).setMessage(text).setPositiveButton(android.R.string.ok, null).show()
    }
    private fun setBusy(value: Boolean) {
        busy = value
        listOf(saveName, saveServer, import, restore).forEach { it.isEnabled = !value }
    }
    private fun work(success: Int, failure: Int = R.string.product_store_error, action: () -> Unit) {
        if (busy) return
        setBusy(true); feedback.setText(R.string.product_busy)
        scope.launch {
            try { withContext(Dispatchers.IO) { action() }; feedback.setText(success) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { feedback.setText(failure) }
            finally { setBusy(false) }
        }
    }
    @Deprecated("Platform activity result bridge for API 28 baseline")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 41 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        work(R.string.product_imported, R.string.product_import_failed) {
            val input = contentResolver.openInputStream(uri) ?: error("No image stream")
            store.importImage(input)
        }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
