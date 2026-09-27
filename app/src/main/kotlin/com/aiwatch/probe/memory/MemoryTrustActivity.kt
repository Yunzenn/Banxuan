package com.aiwatch.probe.memory

import android.app.Activity
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import com.aiwatch.memory.CanonicalMemory
import com.aiwatch.memory.EditOutcome
import com.aiwatch.memory.Importance
import com.aiwatch.memory.MemoryGateway
import com.aiwatch.memory.MemoryId
import com.aiwatch.memory.MemoryIdentityConflictException
import com.aiwatch.memory.MemorySource
import com.aiwatch.memory.MemoryStatus
import com.aiwatch.memory.MemoryType
import com.aiwatch.probe.R
import com.aiwatch.probe.product.ProductUi
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "我的记忆" — the trust surface.
 *
 * This screen exists so the user can see what the companion believes, and decide. It is not a settings
 * page and not a memory browser: every card is something she would act on, with the user's own words
 * underneath it so the claim can be judged rather than trusted.
 *
 * What it does, and only this: lists memories, confirms or ignores a staged candidate, and deletes a
 * confirmed one after a second confirmation. Rejected memories stay visible under their own filter.
 *
 * **No editing.** `MemoryGateway` has no edit/replace contract, and composing one out of
 * `remember()` or a repeated `stage()` would rest on behaviour the interface never promised: an edit
 * that changes an identity (an event's date, a relation's name) could collide with another confirmed
 * fact. Edit is a separate increment that starts by defining the contract.
 *
 * **No modal dialogs, deliberately.** The screen is about 205x251dp. A dialog is a second window the
 * user has to dismiss, and instrumentation cannot reach its buttons through the activity's view tree,
 * which would leave the second-confirmation requirement unverifiable. Both the delete confirmation and
 * the filter are therefore inline state changes inside this one view tree.
 *
 * Evidence boundary: with an injected gateway this proves the screen works **against the
 * MemoryGateway contract**. It does not prove that a user manages live server memories, because there
 * is no live server to manage.
 */
class MemoryTrustActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var ui: ProductUi
    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var feedback: android.widget.TextView
    private lateinit var filterButton: Button

    /** null means "all". The default view is ordered rather than filtered: pending first. */
    private var filter: MemoryStatus? = null

    /** The record whose delete is awaiting its second confirmation. Inline, not a dialog. */
    private var pendingDelete: MemoryId? = null

    /** The record currently being edited, and its draft. At most one at a time. */
    private var editing: MemoryId? = null
    private var draft: MemoryEditDraft? = null

    /**
     * The open editor's error line.
     *
     * Held as a reference so a failed save can report in place. Re-rendering the list instead would
     * rebuild every field and throw away exactly the text the user just typed - the only copy of what
     * she meant - as well as the caret position.
     */
    private var editorError: android.widget.TextView? = null

    private var memories: List<CanonicalMemory> = emptyList()
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ProductUi(this)
        root = ui.page()

        // One compact header row, not three full-width ones.
        //
        // The panel is about 205x251dp. Three stacked 52dp controls plus a 28sp title consumed roughly
        // 410px of the 502px window, which pushed every memory card below the fold: the screen opened
        // looking empty even when it had content. Caught by capturing the rendered frame at the real
        // geometry - the assertions all passed, because the views existed and were merely off-screen.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        header.addView(
            compact(ui.button(getString(R.string.product_back)) { finish() }),
            LinearLayout.LayoutParams(-2, -2),
        )
        header.addView(
            android.view.View(this),
            LinearLayout.LayoutParams(0, 1, 1f),
        )
        filterButton = compact(ui.button(filterLabel()) { cycleFilter() }).apply {
            // The button shows only the current state; "筛选 · 全部" was long enough as a
            // wrap_content child that it squeezed the weighted title to zero width and ran off the
            // right edge of the panel. The word 筛选 moves to the content description, where it also
            // serves a screen reader.
            contentDescription = getString(R.string.memory_filter)
            tag = TAG_FILTER
        }
        header.addView(filterButton, LinearLayout.LayoutParams(-2, -2))
        ui.add(root, header, 0)

        // The title gets its own row rather than competing for width with two buttons via `weight`.
        // A weighted child beside wrap_content buttons is silently collapsed to zero when the buttons
        // need the space, and the failure looks like a missing heading rather than a layout error -
        // which is exactly how it presented on the panel.
        ui.add(root, ui.text(getString(R.string.memory_title), 19f), 4)

        feedback = ui.text("", 13f, ui.accent).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            visibility = View.GONE
        }
        ui.add(root, feedback, 2)

        content = ui.column()
        ui.add(root, content, 2)
        load()
    }

    /**
     * A header-sized control: still 40dp tall to stay tappable, but no longer a full-width bar.
     *
     * `minWidth = 0` is required, not cosmetic. The platform `Button` style carries a default
     * `minWidth` of 88dp, so two "compact" buttons still occupied 352px of the 370px content row and
     * squeezed the weighted title to nothing - which is exactly what the captured frame showed.
     */
    private fun compact(button: Button): Button = button.apply {
        setPadding(ui.dp(10), ui.dp(4), ui.dp(10), ui.dp(4))
        minHeight = ui.dp(40)
        minWidth = 0
    }

    // ---------------------------------------------------------------- loading

    private fun load() {
        val gateway = MemoryGatewayRegistry.resolve()
        if (gateway == null) {
            renderUnavailable()
            return
        }
        setBusy(true)
        feedback.setText(R.string.memory_busy); feedback.visibility = View.VISIBLE
        scope.launch {
            try {
                memories = withContext(Dispatchers.IO) { gateway.list() }
                feedback.setText("")
                feedback.visibility = View.GONE
                renderList()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                content.removeAllViews()
                feedback.setText(R.string.memory_error); feedback.visibility = View.VISIBLE
            } finally {
                setBusy(false)
            }
        }
    }

    // ---------------------------------------------------------------- rendering

    /**
     * The product path today. There is no local memory database, and refusing to invent one is the
     * whole point: an empty list would read as "she knows nothing about you", which is a different and
     * untrue statement from "this screen is not connected yet".
     */
    private fun renderUnavailable() {
        content.removeAllViews()
        ui.add(content, ui.text(getString(R.string.memory_unavailable_title), 19f))
        ui.add(content, ui.text(getString(R.string.memory_unavailable_body), 14f, ui.muted), 4)
        ui.add(content, ui.text(getString(R.string.memory_unavailable_note), 12f, ui.muted), 8)
    }

    private fun renderList() {
        content.removeAllViews()
        editorError = null
        val visible = memories
            .filter { filter == null || it.status == filter }
            .let(MemoryDisplay::defaultOrder)
        if (visible.isEmpty()) {
            ui.add(content, ui.text(getString(R.string.memory_empty), 15f, ui.muted))
            return
        }
        visible.forEach { ui.add(content, card(it), 10) }
    }

    private fun card(memory: CanonicalMemory): View {
        // Editing replaces the card rather than appending a form underneath it. A staged card already
        // carries two buttons on one row in a ~185dp content width; a third would repeat the clipping
        // that the layout regression test exists to catch.
        val openDraft = draft
        if (editing == memory.id && openDraft != null) {
            return editor(memory, openDraft)
        }

        val box = ui.column().apply {
            background = ui.shape(ui.surface)
            setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(12))
        }

        val eyebrow = buildString {
            append(getString(statusLabel(memory.status)))
            append(" · ")
            append(getString(typeLabel(memory.type)))
            if (memory.importance == Importance.HIGH) {
                append(" · ")
                append(getString(R.string.memory_importance_high))
            }
        }
        // The edit affordance sits on the eyebrow row, which has spare width, rather than in the action
        // row, which does not. A rejected memory gets none: the gateway refuses to edit it, and offering
        // a control that can only fail would be worse than offering nothing.
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        headerRow.addView(ui.text(eyebrow, 12f, ui.accent), LinearLayout.LayoutParams(0, -2, 1f))
        if (memory.status != MemoryStatus.REJECTED) {
            headerRow.addView(
                compact(ui.button(getString(R.string.memory_action_edit)) { beginEdit(memory) }).apply {
                    setPadding(ui.dp(8), ui.dp(2), ui.dp(8), ui.dp(2))
                    minHeight = ui.dp(28)
                    tag = TAG_EDIT + memory.id.value
                },
                LinearLayout.LayoutParams(-2, -2),
            )
        }
        box.addView(headerRow)
        box.addView(ui.text(MemoryDisplay.headline(memory), 17f))

        if (MemoryDisplay.isUndated(memory)) {
            box.addView(ui.text(getString(R.string.memory_undated), 12f, ui.muted))
        }
        MemoryDisplay.eventLocation(memory)?.let {
            box.addView(ui.text(getString(R.string.memory_location, it), 13f, ui.muted))
        }
        MemoryDisplay.episodeTone(memory)?.let {
            box.addView(ui.text(getString(R.string.memory_episode_emotion, it), 13f, ui.muted))
        }
        MemoryDisplay.episodeRelations(memory).takeIf { it.isNotEmpty() }?.let {
            box.addView(ui.text(getString(R.string.memory_episode_relations, it.joinToString("、")), 13f, ui.muted))
        }
        MemoryDisplay.relationNote(memory)?.let { box.addView(ui.text(it, 13f, ui.muted)) }

        // The user's own sentence. Forbidden in the model prompt, required here: it is how she judges
        // whether the companion has understood her, instead of being asked to trust a summary.
        val excerpt = memory.provenance.excerpt
        if (excerpt.isNotBlank()) {
            box.addView(ui.text(getString(R.string.memory_excerpt, excerpt), 13f, ui.muted))
        }
        box.addView(
            ui.text(
                getString(
                    R.string.memory_event_line,
                    MemoryDisplay.scheduledAt(memory) ?: MemoryDisplay.recordedOn(memory.recordedAt),
                    getString(R.string.memory_source, getString(sourceLabel(memory.source))),
                ),
                11f,
                ui.muted,
            ),
        )

        box.addView(actions(memory), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        return box
    }

    /**
     * Two buttons on one row, for a 205dp-wide screen.
     *
     * `ProductUi.button`'s default 16dp horizontal padding leaves too little for two weighted buttons
     * at this width, so the padding is reduced rather than the touch target: minHeight stays 52dp.
     */
    private fun actions(memory: CanonicalMemory): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun slot(button: Button) {
            button.setPadding(ui.dp(4), ui.dp(8), ui.dp(4), ui.dp(8))
            row.addView(button, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(4) })
        }
        when (memory.status) {
            MemoryStatus.STAGED -> {
                ui.button(getString(R.string.memory_action_ignore)) { reject(memory) }
                    .also { it.tag = TAG_IGNORE + memory.id.value }.let(::slot)
                ui.button(getString(R.string.memory_action_confirm), primary = true) { confirm(memory) }
                    .also { it.tag = TAG_CONFIRM + memory.id.value }.let(::slot)
            }
            MemoryStatus.CONFIRMED -> {
                if (pendingDelete == memory.id) {
                    ui.button(getString(android.R.string.cancel)) { cancelDelete() }
                        .also { it.tag = TAG_DELETE_CANCEL + memory.id.value }.let(::slot)
                    ui.button(getString(R.string.memory_action_delete), primary = true) { forget(memory) }
                        .also { it.tag = TAG_DELETE_CONFIRM + memory.id.value }.let(::slot)
                } else {
                    ui.button(getString(R.string.memory_action_delete)) { askDelete(memory) }
                        .also { it.tag = TAG_DELETE + memory.id.value }.let(::slot)
                }
            }
            // A rejected memory is kept as a negative signal and stays inspectable. Offering no action
            // is deliberate: it must not be quietly erasable into "this never happened".
            MemoryStatus.REJECTED -> Unit
        }
        return row
    }

    // ---------------------------------------------------------------- editing

    /**
     * The editor, in place of the card's content and actions.
     *
     * Only 取消 and 保存 are offered while editing: leaving confirm/reject/delete on screen next to a
     * half-typed correction invites the user to act on a record whose content is no longer what she is
     * looking at.
     */
    private fun editor(memory: CanonicalMemory, draft: MemoryEditDraft): View {
        val box = ui.column().apply {
            background = ui.shape(ui.surface)
            setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(12))
        }
        box.addView(
            ui.text(
                getString(R.string.memory_edit_heading, getString(typeLabel(memory.type))),
                12f,
                ui.accent,
            ),
        )

        draft.keys.forEach { key ->
            box.addView(ui.text(getString(fieldLabel(key)), 12f, ui.muted))
            box.addView(field(draft, key))
        }

        val error = ui.text("", 12f, ui.accent).apply { visibility = View.GONE }
        editorError = error
        box.addView(error)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun slot(button: Button) {
            button.setPadding(ui.dp(4), ui.dp(8), ui.dp(4), ui.dp(8))
            row.addView(button, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(4) })
        }
        ui.button(getString(android.R.string.cancel)) { cancelEdit() }
            .also { it.tag = TAG_EDIT_CANCEL + memory.id.value }.let(::slot)
        ui.button(getString(R.string.product_save), primary = true) { saveEdit(memory) }
            .also { it.tag = TAG_EDIT_SAVE + memory.id.value }.let(::slot)
        box.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        return box
    }

    /**
     * One text field, writing straight into the draft.
     *
     * The draft rather than the view is the source of truth while editing, so a failed save can report
     * in place without rebuilding anything and losing the caret.
     */
    private fun field(draft: MemoryEditDraft, key: String): EditText {
        val input = EditText(this).apply {
            setTextColor(ui.ink)
            setHintTextColor(ui.muted)
            backgroundTintList = ColorStateList.valueOf(ui.accent)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6))
            minHeight = ui.dp(44)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            tag = TAG_EDIT_FIELD + key
        }
        input.setText(draft.text(key))
        input.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                draft.set(key, s?.toString().orEmpty())
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })
        return input
    }

    private fun beginEdit(memory: CanonicalMemory) {
        editing = memory.id
        draft = MemoryEditDraft(memory)
        pendingDelete = null
        feedback.visibility = View.GONE
        renderList()
    }

    private fun finishEditing() {
        editing = null
        draft = null
        editorError = null
    }

    private fun cancelEdit() {
        // No gateway call of any kind: cancelling must not be able to change anything.
        finishEditing()
        renderList()
    }

    /**
     * Report a problem without tearing the editor down.
     *
     * This is the whole reason editing does not reuse `mutate()`. That path re-reads the gateway and
     * re-renders on every failure, which would recreate the fields from the draft's last known state -
     * fine for a conflict, but it also clears the caret, and for any failure it signals "your input is
     * gone" when the user's typing is the only copy of what she meant.
     */
    private fun showEditorError(res: Int) {
        editorError?.setText(res)
        editorError?.visibility = View.VISIBLE
    }

    private fun saveEdit(memory: CanonicalMemory) {
        val openDraft = draft ?: return
        val gateway = MemoryGatewayRegistry.resolve() ?: return
        if (busy) return

        // Input validation is the screen's job and stays out of the gateway contract: an empty required
        // field or an unparseable time is not a memory-semantics error, and nothing is called for it.
        val problem = openDraft.problem()
        if (problem != null) {
            showEditorError(
                if (problem == EditProblem.TIME_UNPARSEABLE) {
                    R.string.memory_edit_time_invalid
                } else {
                    R.string.memory_edit_required
                },
            )
            return
        }

        val edit = openDraft.build(Instant.now())
        setBusy(true)
        scope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) { gateway.edit(memory.id, edit) }
                // The gateway is the source of truth, and an identity-moving edit can reorder the list,
                // so the screen re-reads rather than pushing the draft back into `memories`.
                memories = withContext(Dispatchers.IO) { gateway.list() }
                finishEditing()
                feedback.setText(
                    if (outcome is EditOutcome.Unchanged) {
                        R.string.memory_edit_unchanged
                    } else {
                        R.string.memory_edit_saved
                    },
                )
                feedback.visibility = View.VISIBLE
                renderList()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: MemoryIdentityConflictException) {
                // The gateway wrote nothing, so the editor stays open with the user's text intact and
                // says why. Refreshing here would delete the only copy of what she meant.
                showEditorError(R.string.memory_edit_conflict)
            } catch (_: Exception) {
                showEditorError(R.string.memory_edit_failed)
            } finally {
                setBusy(false)
            }
        }
    }

    private fun fieldLabel(key: String): Int = when (key) {
        EditField.ATTRIBUTE -> R.string.memory_field_attribute
        EditField.VALUE -> R.string.memory_field_value
        EditField.TITLE -> R.string.memory_field_title
        EditField.SCHEDULED_FOR -> R.string.memory_field_scheduled
        EditField.LOCATION -> R.string.memory_field_location
        EditField.SUMMARY -> R.string.memory_field_summary
        EditField.OCCURRED_AT -> R.string.memory_field_occurred
        EditField.EMOTIONAL_TONE -> R.string.memory_field_tone
        EditField.RELATIONS -> R.string.memory_field_relations
        EditField.NAME -> R.string.memory_field_name
        EditField.ROLE -> R.string.memory_field_role
        EditField.NOTE -> R.string.memory_field_note
        else -> error("unknown edit field '$key'")
    }

    // ---------------------------------------------------------------- actions

    private fun cycleFilter() {
        filter = when (filter) {
            null -> MemoryStatus.STAGED
            MemoryStatus.STAGED -> MemoryStatus.CONFIRMED
            MemoryStatus.CONFIRMED -> MemoryStatus.REJECTED
            MemoryStatus.REJECTED -> null
        }
        pendingDelete = null
        filterButton.text = filterLabel()
        renderList()
    }

    private fun confirm(memory: CanonicalMemory) =
        mutate(R.string.memory_confirm_done) { it.confirm(memory.id) }

    private fun reject(memory: CanonicalMemory) =
        mutate(R.string.memory_ignore_done) { it.reject(memory.id) }

    /** First tap: show the confirmation. The record is not touched yet. */
    private fun askDelete(memory: CanonicalMemory) {
        pendingDelete = memory.id
        renderList()
    }

    private fun cancelDelete() {
        pendingDelete = null
        renderList()
    }

    /**
     * Second tap. Deletion is irreversible, so it never happens on the first one. `reject` is
     * deliberately different: it keeps the record as a negative signal, which is why its button says
     * 忽略 rather than 删除.
     */
    private fun forget(memory: CanonicalMemory) {
        pendingDelete = null
        mutate(R.string.memory_delete_done) { it.forget(memory.id); Unit }
    }

    private fun mutate(success: Int, action: suspend (MemoryGateway) -> Unit) {
        val gateway = MemoryGatewayRegistry.resolve() ?: return
        if (busy) return
        setBusy(true)
        scope.launch {
            try {
                withContext(Dispatchers.IO) { action(gateway) }
                feedback.setText(success); feedback.visibility = View.VISIBLE
                memories = withContext(Dispatchers.IO) { gateway.list() }
                renderList()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The record is left exactly as it was, and the screen re-reads rather than assumes.
                feedback.setText(R.string.memory_action_error); feedback.visibility = View.VISIBLE
                runCatching {
                    memories = withContext(Dispatchers.IO) { gateway.list() }
                    renderList()
                }
            } finally {
                setBusy(false)
            }
        }
    }

    // ---------------------------------------------------------------- small helpers

    private fun setBusy(value: Boolean) {
        busy = value
        filterButton.isEnabled = !value
    }

    private fun filterLabel(): String = getString(
        when (filter) {
            null -> R.string.memory_filter_all
            MemoryStatus.STAGED -> R.string.memory_filter_staged
            MemoryStatus.CONFIRMED -> R.string.memory_filter_confirmed
            MemoryStatus.REJECTED -> R.string.memory_filter_rejected
        },
    )

    private fun statusLabel(status: MemoryStatus): Int = when (status) {
        MemoryStatus.STAGED -> R.string.memory_status_staged
        MemoryStatus.CONFIRMED -> R.string.memory_status_confirmed
        MemoryStatus.REJECTED -> R.string.memory_status_rejected
    }

    private fun typeLabel(type: MemoryType): Int = when (type) {
        MemoryType.PROFILE -> R.string.memory_type_profile
        MemoryType.EVENT -> R.string.memory_type_event
        MemoryType.EPISODE -> R.string.memory_type_episode
        MemoryType.RELATION -> R.string.memory_type_relation
    }

    private fun sourceLabel(source: MemorySource): Int = when (source) {
        MemorySource.CONVERSATION -> R.string.memory_source_conversation
        MemorySource.USER_EDIT -> R.string.memory_source_user_edit
        MemorySource.INFERRED -> R.string.memory_source_inferred
        MemorySource.IMPORT -> R.string.memory_source_import
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /**
         * View tags, so instrumentation can act on a specific record's button rather than on whatever
         * happens to be the only button with that label. Tags rather than content descriptions on
         * purpose: an id in a content description would be read aloud by a screen reader.
         */
        const val TAG_CONFIRM = "memory:confirm:"
        const val TAG_IGNORE = "memory:ignore:"
        const val TAG_DELETE = "memory:delete:"
        const val TAG_DELETE_CONFIRM = "memory:delete-confirm:"
        const val TAG_DELETE_CANCEL = "memory:delete-cancel:"
        const val TAG_FILTER = "memory:filter"
        const val TAG_EDIT = "memory:edit:"
        const val TAG_EDIT_SAVE = "memory:edit-save:"
        const val TAG_EDIT_CANCEL = "memory:edit-cancel:"
        const val TAG_EDIT_FIELD = "memory:edit-field:"
    }
}
