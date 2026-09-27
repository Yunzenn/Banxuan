package com.aiwatch.probe.memory

import com.aiwatch.memory.CanonicalMemory
import com.aiwatch.memory.EpisodeMemory
import com.aiwatch.memory.EventMemory
import com.aiwatch.memory.MemoryStatus
import com.aiwatch.memory.ProfileMemory
import com.aiwatch.memory.RelationMemory
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Text rendering for the memory trust surface.
 *
 * Deliberately free of Android types so the wording can be asserted directly instead of through a
 * view hierarchy, and free of resource strings: it composes the *content*, the activity supplies the
 * surrounding labels. The only literals here are separators.
 *
 * Two rules are load-bearing:
 *
 * * The **raw attribute path** is shown (`food.dislike`) rather than a friendly label. A friendly
 *   label needs either an agreed attribute vocabulary or labels the user supplies; inventing a
 *   mapping here would put the UI's guess where the fact should be. The excerpt below the card is
 *   what makes the raw path readable, and it is the user's own sentence.
 * * The **excerpt is shown**. It is forbidden in the model prompt, where it would re-inject raw
 *   utterance into the system context, but it is exactly what this screen is for: letting the user
 *   judge why the companion believes something. Those two rules are a boundary, not a conflict.
 */
internal object MemoryDisplay {

    private val dayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日")
    private val dayTimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日 HH:mm")

    /** The one line that says what the companion believes. Resource-free: separators only. */
    fun headline(memory: CanonicalMemory): String = when (memory) {
        is ProfileMemory -> "${memory.attribute}：${memory.value}"
        is EventMemory -> whenText(memory) + " · " + memory.title
        is EpisodeMemory -> memory.summary
        is RelationMemory -> "${memory.name}（${memory.role}）"
    }

    /** True when an event has no resolved time, so the card can say so instead of inventing one. */
    fun isUndated(memory: CanonicalMemory): Boolean =
        memory is EventMemory && memory.scheduledFor == null

    fun eventLocation(memory: CanonicalMemory): String? = (memory as? EventMemory)?.location

    fun episodeTone(memory: CanonicalMemory): String? = (memory as? EpisodeMemory)?.emotionalTone

    fun episodeRelations(memory: CanonicalMemory): List<String> =
        (memory as? EpisodeMemory)?.relations?.toList().orEmpty()

    fun relationNote(memory: CanonicalMemory): String? = (memory as? RelationMemory)?.note

    /** The instant an event or episode is about; null for the timeless types. */
    fun aboutTime(memory: CanonicalMemory): Instant? = when (memory) {
        is EventMemory -> memory.scheduledFor
        is EpisodeMemory -> memory.occurredAt
        is ProfileMemory, is RelationMemory -> null
    }

    fun recordedOn(recordedAt: Instant): String = dayFormat.format(recordedAt.atZone(ZoneId.systemDefault()))

    fun scheduledAt(memory: CanonicalMemory): String? {
        val at = aboutTime(memory) ?: return null
        val pattern = if (memory is EventMemory) dayTimeFormat else dayFormat
        return pattern.format(at.atZone(ZoneId.systemDefault()))
    }

    private fun whenText(memory: EventMemory): String {
        val scheduled = memory.scheduledFor ?: memory.recordedAt
        return dayTimeFormat.format(scheduled.atZone(ZoneId.systemDefault()))
    }

    /**
     * Order for the default view: what needs a decision first, then what she knows, then what was
     * turned down. Within a group, newest first, so the most recent thing the companion noticed leads
     * its group. The status grouping is the point - a pending confirmation should never be buried
     * under a long list of settled facts.
     */
    fun defaultOrder(memories: List<CanonicalMemory>): List<CanonicalMemory> =
        memories.sortedWith(
            compareBy<CanonicalMemory> { rank(it.status) }.thenByDescending { recency(it) },
        )

    private fun rank(status: MemoryStatus): Int = when (status) {
        MemoryStatus.STAGED -> 0
        MemoryStatus.CONFIRMED -> 1
        MemoryStatus.REJECTED -> 2
    }

    private fun recency(memory: CanonicalMemory): Long = when (memory) {
        is EventMemory -> (memory.scheduledFor ?: memory.recordedAt).toEpochMilli()
        is EpisodeMemory -> memory.occurredAt.toEpochMilli()
        is ProfileMemory -> memory.recordedAt.toEpochMilli()
        is RelationMemory -> memory.recordedAt.toEpochMilli()
    }
}
