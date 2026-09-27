package com.aiwatch.probe.memory

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import com.aiwatch.memory.CanonicalMemory
import com.aiwatch.memory.Importance
import com.aiwatch.memory.MemoryGateway
import com.aiwatch.memory.MemoryId
import com.aiwatch.memory.MemorySource
import com.aiwatch.memory.MemoryStatus
import com.aiwatch.memory.MemoryType
import com.aiwatch.probe.R
import com.aiwatch.probe.product.ProductUi
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
        box.addView(ui.text(eyebrow, 12f, ui.accent))
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
    }
}
