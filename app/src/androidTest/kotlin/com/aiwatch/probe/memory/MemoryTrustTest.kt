package com.aiwatch.probe.memory

import android.app.Activity
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import com.aiwatch.memory.CanonicalMemory
import com.aiwatch.memory.CharacterScope
import com.aiwatch.memory.DefaultMemoryGateway
import com.aiwatch.memory.Importance
import com.aiwatch.memory.MemoryGateway
import com.aiwatch.memory.MemoryId
import com.aiwatch.memory.MemorySource
import com.aiwatch.memory.MemoryStatus
import com.aiwatch.memory.MemoryStore
import com.aiwatch.memory.ProfileMemory
import com.aiwatch.memory.Provenance
import com.aiwatch.memory.ScopedMemoryIdentity
import com.aiwatch.memory.scopedIdentity
import com.aiwatch.probe.R
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Instrumentation for the memory trust surface.
 *
 * The gateway underneath is the **real** `DefaultMemoryGateway` over a test-only `MemoryStore` double:
 * only the storage boundary is substituted, so confirm/reject/forget are the production semantics and
 * this is evidence about the screen and the contract together.
 *
 * What this proves: the trust UI works against the MemoryGateway contract.
 * What it does not prove: that a user manages live server memories. There is no deployed canonical
 * memory service, and no fake LLM or fake persistence is used to paper over that.
 */
class MemoryTrustTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @After
    fun clearGateway() {
        MemoryGatewayRegistry.override = null
    }

    // ------------------------------------------------------------------ the product path

    @Test
    fun withoutAGatewayTheScreenSaysSoAndInventsNothing() {
        MemoryGatewayRegistry.override = null
        val activity = launch()
        try {
            val shown = onMain { texts(activity) }
            assertTrue(
                "the unavailable state is missing",
                shown.any { it == activity.getString(R.string.memory_unavailable_title) },
            )
            assertTrue(
                "the screen must not imply it knows nothing about the user",
                shown.any { it.contains(activity.getString(R.string.memory_unavailable_note)) },
            )
            // Nothing that looks like a memory card, and no action affordances at all.
            // Nothing that looks like a memory card, and no way to act on a record.
            val actions = onMain {
                flatten(activity.window.decorView).mapNotNull { it.tag?.toString() }.filter { tag ->
                    tag.startsWith(MemoryTrustActivity.TAG_CONFIRM) ||
                        tag.startsWith(MemoryTrustActivity.TAG_IGNORE) ||
                        tag.startsWith(MemoryTrustActivity.TAG_DELETE)
                }
            }
            assertTrue("no memory action should be reachable without a gateway: $actions", actions.isEmpty())
            capture(activity, "memory_unavailable")
        } finally {
            close(activity)
        }
    }

    // ------------------------------------------------------------------ staged memories

    @Test
    fun aStagedCandidateIsConfirmedThroughTheRealGateway() {
        val gateway = gatewayWith(profile("m1", MemoryStatus.STAGED, "food.dislike", "香菜", "我真的不喜欢香菜"))
        MemoryGatewayRegistry.override = gateway
        val activity = launch()
        try {
            awaitCard(activity, "food.dislike")
            assertEquals(MemoryStatus.STAGED, statusOf(gateway, "m1"))
            capture(activity, "memory_staged_card")

            clickTagged(activity, MemoryTrustActivity.TAG_CONFIRM + "m1")

            awaitState("\"m1\" to be confirmed") { statusOf(gateway, "m1") == MemoryStatus.CONFIRMED }
        } finally {
            close(activity)
        }
    }

    @Test
    fun anIgnoredCandidateIsLeftOutOfConversationButStaysInspectable() {
        val gateway = gatewayWith(profile("m1", MemoryStatus.STAGED, "food.dislike", "香菜", "我真的不喜欢香菜"))
        MemoryGatewayRegistry.override = gateway
        val activity = launch()
        try {
            awaitCard(activity, "food.dislike")

            clickTagged(activity, MemoryTrustActivity.TAG_IGNORE + "m1")

            // Rejected, not deleted: the negative signal is kept.
            awaitState("\"m1\" to be rejected") { statusOf(gateway, "m1") == MemoryStatus.REJECTED }
            assertNotNull("a rejected memory must still exist", runBlocking { gateway.list() }.firstOrNull { it.id.value == "m1" })

            // And it is reachable through the filter rather than having vanished.
            cycleToRejected(activity)
            awaitState("\"m1\" to be visible under 已忽略") {
                onMain { texts(activity).any { line -> line.contains("food.dislike") } }
            }
        } finally {
            close(activity)
        }
    }

    // ------------------------------------------------------------------ deletion

    @Test
    fun aConfirmedMemoryTakesTwoTapsToDeleteAndTheFirstTapDeletesNothing() {
        val gateway = gatewayWith(profile("m1", MemoryStatus.CONFIRMED, "food.dislike", "香菜", "我真的不喜欢香菜"))
        MemoryGatewayRegistry.override = gateway
        val activity = launch()
        try {
            awaitCard(activity, "food.dislike")
            assertNotNull(onMain { findTagged(activity, MemoryTrustActivity.TAG_DELETE + "m1") })

            // First tap only asks.
            clickTagged(activity, MemoryTrustActivity.TAG_DELETE + "m1")
            onMain { assertNotNull(findTagged(activity, MemoryTrustActivity.TAG_DELETE_CONFIRM + "m1")) }
            assertEquals(
                "the first tap must not delete anything",
                MemoryStatus.CONFIRMED,
                statusOf(gateway, "m1"),
            )

            // The confirmation can be backed out of, leaving the memory intact.
            clickTagged(activity, MemoryTrustActivity.TAG_DELETE_CANCEL + "m1")
            onMain {
                assertNotNull(findTagged(activity, MemoryTrustActivity.TAG_DELETE + "m1"))
                assertNull(findTagged(activity, MemoryTrustActivity.TAG_DELETE_CONFIRM + "m1"))
            }
            assertEquals(MemoryStatus.CONFIRMED, statusOf(gateway, "m1"))

            // Second attempt, confirmed.
            clickTagged(activity, MemoryTrustActivity.TAG_DELETE + "m1")
            clickTagged(activity, MemoryTrustActivity.TAG_DELETE_CONFIRM + "m1")

            awaitState("the memory to disappear") { statusOf(gateway, "m1") == null }
        } finally {
            close(activity)
        }
    }

    // ------------------------------------------------------------------ what the card shows

    @Test
    fun theUsersOwnWordsAreShownSoTheClaimCanBeJudged() {
        val gateway = gatewayWith(profile("m1", MemoryStatus.STAGED, "food.dislike", "香菜", "我真的不喜欢香菜"))
        MemoryGatewayRegistry.override = gateway
        val activity = launch()
        try {
            awaitCard(activity, "food.dislike")
            val shown = onMain { texts(activity) }
            // The excerpt is forbidden in the model prompt and required here.
            assertTrue("the provenance excerpt must be visible on the card", shown.any { it.contains("我真的不喜欢香菜") })
            // The raw attribute path is shown as stored, next to the user's sentence.
            assertTrue("the stored fact must be visible verbatim", shown.any { it.contains("food.dislike：香菜") })
            assertTrue(
                "the source must be stated",
                shown.any { it.contains(activity.getString(R.string.memory_source_conversation)) },
            )
        } finally {
            close(activity)
        }
    }

    @Test
    fun pendingMemoriesAreListedBeforeSettledOnes() {
        val gateway = gatewayWith(
            profile("settled", MemoryStatus.CONFIRMED, "food.like", "芹菜", "我爱吃芹菜"),
            profile("pending", MemoryStatus.STAGED, "food.dislike", "香菜", "我真的不喜欢香菜"),
        )
        MemoryGatewayRegistry.override = gateway
        val activity = launch()
        try {
            awaitCard(activity, "food.dislike")
            val lines = onMain { texts(activity) }
            val pendingAt = lines.indexOfFirst { it.contains("food.dislike：香菜") }
            val settledAt = lines.indexOfFirst { it.contains("food.like：芹菜") }
            assertTrue("both cards should be present: $lines", pendingAt >= 0 && settledAt >= 0)
            assertTrue("a pending decision must lead the list", pendingAt < settledAt)
        } finally {
            close(activity)
        }
    }

    @Test
    fun theScreenOffersNoEditingBecauseTheContractHasNone() {
        val gateway = gatewayWith(
            profile("settled", MemoryStatus.CONFIRMED, "food.like", "芹菜", "我爱吃芹菜"),
            profile("pending", MemoryStatus.STAGED, "food.dislike", "香菜", "我真的不喜欢香菜"),
        )
        MemoryGatewayRegistry.override = gateway
        val activity = launch()
        try {
            awaitCard(activity, "food.dislike")
            val labels = onMain {
                flatten(activity.window.decorView).mapNotNull {
                    when (it) {
                        is TextView -> it.text?.toString()
                        else -> null
                    }
                }
            }
            listOf("编辑", "修改", "Edit").forEach { banned ->
                assertTrue(
                    "edit is not in this increment and must not be offered: found \"$banned\"",
                    labels.none { it.contains(banned) },
                )
            }
        } finally {
            close(activity)
        }
    }

    /**
     * Regression guard for a defect that every other assertion here missed.
     *
     * Three full-width header controls pushed all card content below the fold, and a weighted title
     * beside two wrap_content buttons collapsed to zero width. In both cases the views existed, were
     * attached and were clickable, so existence-based assertions passed while the screen showed almost
     * nothing. Only the captured frame revealed it, so the geometry is pinned here by measurement.
     */
    @Test
    fun theHeaderFitsThePanelAndActuallyShowsItsTitle() {
        val gateway = gatewayWith(profile("m1", MemoryStatus.STAGED, "food.dislike", "香菜", "我真的不喜欢香菜"))
        MemoryGatewayRegistry.override = gateway
        val activity = launch()
        try {
            awaitCard(activity, "food.dislike")
            onMain {
                val panelWidth = activity.window.decorView.width
                val title = flatten(activity.window.decorView)
                    .filterIsInstance<TextView>()
                    .firstOrNull { it.text.toString() == activity.getString(R.string.memory_title) }
                assertNotNull("the screen has no title", title)
                assertTrue("the title is not laid out: width=${title!!.width}", title.width > 0)
                assertTrue("the title is not visible", title.isShown)
                assertTrue(
                    "the title is narrower than its own text: ${title.width}px",
                    title.width >= title.paint.measureText(title.text.toString()),
                )

                val controls = listOf(
                    findTagged(activity, MemoryTrustActivity.TAG_FILTER),
                    flatten(activity.window.decorView).filterIsInstance<android.widget.Button>()
                        .firstOrNull { it.text.toString() == activity.getString(R.string.product_back) },
                )
                controls.forEach { view ->
                    assertNotNull("a header control is missing", view)
                    assertTrue("a header control escaped the panel: ${view!!.width}px", view.width < panelWidth)
                }

                // What actually matters is not "the header is short" but "the first memory is
                // identifiable without scrolling". The previous version of this screen showed no card
                // content at all, so the headline being fully inside the panel is the real criterion.
                val cardHeadline = flatten(activity.window.decorView)
                    .filterIsInstance<TextView>()
                    .firstOrNull { it.text.toString().contains("food.dislike") }!!
                val location = IntArray(2).also { cardHeadline.getLocationInWindow(it) }
                assertTrue(
                    "the first card's headline is not fully visible: y=${location[1]} " +
                        "height=${cardHeadline.height} of ${activity.window.decorView.height}",
                    location[1] + cardHeadline.height <= activity.window.decorView.height,
                )
            }
        } finally {
            close(activity)
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun launch(): Activity {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MemoryTrustActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        instrumentation.waitForIdleSync()
        return activity
    }

    private fun close(activity: Activity) {
        instrumentation.runOnMainSync { activity.finish() }
        instrumentation.waitForIdleSync()
    }

    private fun <T> onMain(block: () -> T): T {
        val holder = arrayOfNulls<Any?>(1)
        instrumentation.runOnMainSync { holder[0] = block() }
        @Suppress("UNCHECKED_CAST")
        return holder[0] as T
    }

    /**
     * Visual evidence at the real panel geometry.
     *
     * Drawn from the activity's own view tree rather than `uiAutomation.takeScreenshot()`. The latter
     * composites whatever the system window contains, which on an emulator with an overridden size is
     * not necessarily the same frame the user sees; `decorView.draw` records exactly the tree that was
     * measured, which is the thing under test.
     */
    private fun capture(activity: Activity, name: String) {
        onMain {
            val view = activity.window.decorView
            if (view.width <= 0 || view.height <= 0) return@onMain
            val bitmap = android.graphics.Bitmap.createBitmap(
                view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888,
            )
            view.draw(android.graphics.Canvas(bitmap))
            val dir = instrumentation.targetContext.getExternalFilesDir(null) ?: return@onMain
            java.io.File(dir, "$name.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    /**
     * The measurement that a screenshot can only suggest: two labels sharing a ~185dp row.
     *
     * A clipped label still passes every "the button exists and is clickable" assertion, so this
     * compares each button's measured width against its own text metrics plus padding. That is the
     * failure mode the layout actually risks on this screen.
     */
    @Test
    fun bothActionButtonsAreWideEnoughForTheirLabels() {
        val gateway = gatewayWith(profile("m1", MemoryStatus.STAGED, "food.dislike", "香菜", "我真的不喜欢香菜"))
        MemoryGatewayRegistry.override = gateway
        val activity = launch()
        try {
            awaitCard(activity, "food.dislike")
            onMain {
                val buttons = listOf(
                    findTagged(activity, MemoryTrustActivity.TAG_IGNORE + "m1"),
                    findTagged(activity, MemoryTrustActivity.TAG_CONFIRM + "m1"),
                )
                buttons.forEach { view ->
                    assertNotNull("an action button is missing", view)
                    val button = view as android.widget.Button
                    val needed = button.paint.measureText(button.text.toString())
                    val available = (button.width - button.paddingLeft - button.paddingRight).toFloat()
                    assertTrue(
                        "label \"${button.text}\" needs ${needed}px but only ${available}px is inside the button",
                        needed <= available,
                    )
                    assertTrue("action button is not visible", button.isShown)
                    assertTrue("action button is too small to touch: ${button.width}x${button.height}", button.height >= 40)
                }
            }
        } finally {
            close(activity)
        }
    }

    private fun texts(activity: Activity): List<String> =
        flatten(activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }

    private fun findTagged(activity: Activity, tag: String): View? =
        flatten(activity.window.decorView).firstOrNull { it.tag == tag }

    private fun clickTagged(activity: Activity, tag: String) {
        awaitState("tag $tag to exist") { onMain { findTagged(activity, tag) != null } }
        instrumentation.runOnMainSync {
            val view = findTagged(activity, tag)
            assertNotNull("no view tagged $tag", view)
            view!!.performClick()
        }
        instrumentation.waitForIdleSync()
    }

    private fun cycleToRejected(activity: Activity) {
        // 全部 -> 待确认 -> 已记住 -> 已忽略
        repeat(3) {
            clickTagged(activity, MemoryTrustActivity.TAG_FILTER)
        }
    }

    private fun awaitCard(activity: Activity, needle: String) =
        awaitState("a card containing \"$needle\"") {
            onMain { texts(activity).any { it.contains(needle) } }
        }

    private fun awaitState(what: String, timeoutMs: Long = 8000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        fail("timed out waiting for $what")
    }

    private fun statusOf(gateway: MemoryGateway, id: String): MemoryStatus? =
        runBlocking { gateway.list() }.firstOrNull { it.id.value == id }?.status

    private fun gatewayWith(vararg records: CanonicalMemory): MemoryGateway {
        val store = MapStore()
        records.forEach { store.seed(it) }
        return DefaultMemoryGateway(store)
    }

    private fun profile(
        id: String,
        status: MemoryStatus,
        attribute: String,
        value: String,
        excerpt: String,
    ) = ProfileMemory(
        id = MemoryId(id),
        importance = Importance.NORMAL,
        status = status,
        recordedAt = Instant.parse("2026-09-27T10:00:00Z"),
        source = MemorySource.CONVERSATION,
        provenance = Provenance(sessionId = "s", messageId = "m", excerpt = excerpt, extractor = "test"),
        characterScope = CharacterScope("xiaozhi"),
        attribute = attribute,
        value = value,
    )

    /**
     * A test-only `MemoryStore` double. Only the storage boundary is replaced; the gateway above it is
     * the production implementation, so the semantics under test are real.
     */
    private class MapStore : MemoryStore {
        private val records = LinkedHashMap<String, CanonicalMemory>()
        fun seed(memory: CanonicalMemory) {
            records[memory.id.value] = memory
        }

        override suspend fun getById(id: MemoryId): CanonicalMemory? = records[id.value]

        override suspend fun findAllByScopedIdentity(identity: ScopedMemoryIdentity): List<CanonicalMemory> =
            records.values.filter { it.scopedIdentity == identity }

        override suspend fun put(memory: CanonicalMemory) {
            records[memory.id.value] = memory
        }

        override suspend fun delete(id: MemoryId): Boolean = records.remove(id.value) != null

        override suspend fun list(): List<CanonicalMemory> = records.values.toList()
    }

    private fun flatten(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { flatten(view.getChildAt(it)) } else emptyList()
}
