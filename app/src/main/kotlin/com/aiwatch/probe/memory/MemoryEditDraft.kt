package com.aiwatch.probe.memory

import com.aiwatch.memory.CanonicalMemory
import com.aiwatch.memory.EpisodeEdit
import com.aiwatch.memory.EpisodeMemory
import com.aiwatch.memory.EventEdit
import com.aiwatch.memory.EventMemory
import com.aiwatch.memory.MemoryEdit
import com.aiwatch.memory.MemoryType
import com.aiwatch.memory.ProfileEdit
import com.aiwatch.memory.ProfileMemory
import com.aiwatch.memory.RelationEdit
import com.aiwatch.memory.RelationMemory
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** The field keys a draft exposes. Keys, not resource ids, so the draft stays assertable on its own. */
internal object EditField {
    const val ATTRIBUTE = "attribute"
    const val VALUE = "value"
    const val TITLE = "title"
    const val SCHEDULED_FOR = "scheduledFor"
    const val LOCATION = "location"
    const val SUMMARY = "summary"
    const val OCCURRED_AT = "occurredAt"
    const val EMOTIONAL_TONE = "emotionalTone"
    const val RELATIONS = "relations"
    const val NAME = "name"
    const val ROLE = "role"
    const val NOTE = "note"
}

/** Why a draft cannot be turned into an edit. UI input validation; not part of the gateway contract. */
internal enum class EditProblem { REQUIRED_MISSING, TIME_UNPARSEABLE }

/**
 * The in-progress edit of one memory, holding the user's text field by field.
 *
 * The important property here is that **a time field is not round-tripped through its display text**.
 * An instant can carry seconds, milliseconds and a zone offset, and the text the user reads is formatted
 * `yyyy-MM-dd HH:mm`. If saving re-parsed that text unconditionally, editing only an event's location
 * would silently rewrite `2026-10-05T07:00:37.123Z` as `2026-10-05T07:00:00Z`; the gateway would then
 * correctly report a real content change and relabel the record as a user edit, even though the user
 * never touched the time. The original [Instant] is therefore kept aside and only re-derived from the
 * text when the text itself changed.
 *
 * Free of Android types so the behaviour above can be asserted directly.
 */
internal class MemoryEditDraft(val original: CanonicalMemory) {

    /** Content fields for this record's type, in display order. */
    val keys: List<String> = when (original.type) {
        MemoryType.PROFILE -> listOf(EditField.ATTRIBUTE, EditField.VALUE)
        MemoryType.EVENT -> listOf(EditField.TITLE, EditField.SCHEDULED_FOR, EditField.LOCATION)
        MemoryType.EPISODE ->
            listOf(EditField.SUMMARY, EditField.OCCURRED_AT, EditField.EMOTIONAL_TONE, EditField.RELATIONS)

        MemoryType.RELATION -> listOf(EditField.NAME, EditField.ROLE, EditField.NOTE)
    }

    /** Fields that may be left empty. A blank optional field means "not known", not "empty string". */
    val optional: Set<String> = when (original.type) {
        MemoryType.PROFILE -> emptySet()
        MemoryType.EVENT -> setOf(EditField.SCHEDULED_FOR, EditField.LOCATION)
        MemoryType.EPISODE -> setOf(EditField.EMOTIONAL_TONE, EditField.RELATIONS)
        MemoryType.RELATION -> setOf(EditField.NOTE)
    }

    private val initial: Map<String, String> = initialText()
    private val current: MutableMap<String, String> = initial.toMutableMap()

    fun text(key: String): String = current.getValue(key)

    fun set(key: String, value: String) {
        current[key] = value
    }

    /** True when any field differs from what was loaded, used only to skip no-op writes cheaply. */
    fun isDirty(): Boolean = current.any { (key, value) -> initial[key] != value }

    fun problem(): EditProblem? {
        if (keys.any { it !in optional && current.getValue(it).isBlank() }) {
            return EditProblem.REQUIRED_MISSING
        }
        timeKeys().forEach { key ->
            val value = current.getValue(key)
            if (value.isNotBlank() && value != initial[key] && parseTime(value) == null) {
                return EditProblem.TIME_UNPARSEABLE
            }
        }
        return null
    }

    /** Builds the typed edit. Call only when [problem] is null. */
    fun build(editedAt: Instant): MemoryEdit {
        check(problem() == null) { "draft has a problem and must not be built" }
        return when (original) {
            is ProfileMemory -> ProfileEdit(
                editedAt = editedAt,
                attribute = text(EditField.ATTRIBUTE).trim(),
                value = text(EditField.VALUE).trim(),
            )

            is EventMemory -> EventEdit(
                editedAt = editedAt,
                title = text(EditField.TITLE).trim(),
                scheduledFor = resolvedTime(
                    key = EditField.SCHEDULED_FOR,
                    originalValue = original.scheduledFor,
                ),
                location = blankToNull(text(EditField.LOCATION)),
            )

            is EpisodeMemory -> EpisodeEdit(
                editedAt = editedAt,
                summary = text(EditField.SUMMARY).trim(),
                occurredAt = resolvedTime(
                    key = EditField.OCCURRED_AT,
                    originalValue = original.occurredAt,
                )!!,
                emotionalTone = blankToNull(text(EditField.EMOTIONAL_TONE)),
                relations = text(EditField.RELATIONS)
                    .split(RELATION_SEPARATORS)
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toSet(),
            )

            is RelationMemory -> RelationEdit(
                editedAt = editedAt,
                name = text(EditField.NAME).trim(),
                role = text(EditField.ROLE).trim(),
                note = blankToNull(text(EditField.NOTE)),
            )
        }
    }

    /**
     * The original instant when the field's text is untouched, otherwise the text parsed afresh.
     *
     * This single branch is what keeps an unrelated edit from rewriting a timestamp.
     */
    private fun resolvedTime(key: String, originalValue: Instant?): Instant? {
        val value = text(key)
        if (value == initial[key]) return originalValue
        return if (value.isBlank()) null else parseTime(value)
    }

    private fun timeKeys(): List<String> = keys.filter { it == EditField.SCHEDULED_FOR || it == EditField.OCCURRED_AT }

    private fun initialText(): Map<String, String> = when (original) {
        is ProfileMemory -> mapOf(
            EditField.ATTRIBUTE to original.attribute,
            EditField.VALUE to original.value,
        )

        is EventMemory -> mapOf(
            EditField.TITLE to original.title,
            EditField.SCHEDULED_FOR to formatTime(original.scheduledFor),
            EditField.LOCATION to (original.location ?: ""),
        )

        is EpisodeMemory -> mapOf(
            EditField.SUMMARY to original.summary,
            EditField.OCCURRED_AT to formatTime(original.occurredAt),
            EditField.EMOTIONAL_TONE to (original.emotionalTone ?: ""),
            EditField.RELATIONS to original.relations.joinToString("、"),
        )

        is RelationMemory -> mapOf(
            EditField.NAME to original.name,
            EditField.ROLE to original.role,
            EditField.NOTE to (original.note ?: ""),
        )
    }

    companion object {
        /** The display format. Lossy on purpose: the untouched value never goes through it. */
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        private val RELATION_SEPARATORS = Regex("[、,，]")

        private val ACCEPTED_PATTERNS = listOf("yyyy-MM-dd HH:mm", "yyyy-MM-dd'T'HH:mm")

        fun formatTime(instant: Instant?): String =
            instant?.atZone(ZoneId.systemDefault())?.format(TIME_FORMAT) ?: ""

        /** ISO first, then the two local shapes the field invites. Null when nothing matches. */
        fun parseTime(raw: String): Instant? {
            val value = raw.trim()
            if (value.isEmpty()) return null
            try {
                return Instant.parse(value)
            } catch (_: DateTimeParseException) {
                // Fall through to the local formats.
            }
            for (pattern in ACCEPTED_PATTERNS) {
                try {
                    return LocalDateTime.parse(value, DateTimeFormatter.ofPattern(pattern))
                        .atZone(ZoneId.systemDefault())
                        .toInstant()
                } catch (_: DateTimeParseException) {
                    // Try the next shape.
                }
            }
            return null
        }

        private fun blankToNull(value: String): String? = value.trim().ifEmpty { null }
    }
}
