package com.aiwatch.probe.memory

import android.app.Activity
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import com.aiwatch.memory.CanonicalMemory
import com.aiwatch.memory.CharacterScope
import com.aiwatch.memory.DefaultMemoryGateway
import com.aiwatch.memory.EditOutcome
import com.aiwatch.memory.EpisodeMemory
import com.aiwatch.memory.EventMemory
import com.aiwatch.memory.Importance
import com.aiwatch.memory.MemoryEdit
import com.aiwatch.memory.MemoryGateway
import com.aiwatch.memory.MemoryId
import com.aiwatch.memory.MemoryQuery
import com.aiwatch.memory.MemorySource
import com.aiwatch.memory.MemoryStatus
import com.aiwatch.memory.MemoryStore
import com.aiwatch.memory.ProfileMemory
import com.aiwatch.memory.Provenance
import com.aiwatch.memory.RelationMemory
import com.aiwatch.memory.ScopedMemoryIdentity
import com.aiwatch.memory.scopedIdentity
import com.aiwatch.probe.ProbeApplication
import com.aiwatch.probe.ProductTestApplication
import com.aiwatch.probe.R
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
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

    private val testApplication get() =
        instrumentation.targetContext.applicationContext as ProductTestApplication

    @Before
    fun ownIdentityFixture() {
        MemoryGatewayRegistry.override = null
        testApplication.beginIdentityFixture()
    }

    @After
    fun clearGateway() {
        try {
            MemoryGatewayRegistry.override = null
            instrumentation.waitForIdleSync()
        } finally {
            runBlocking { testApplication.endIdentityFixture() }
        }
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

    // ------------------------------------------------------------------ identity

    @Test
    fun cachedFirstFrameStaysLabelledWhenRefreshFails() {
        val record = profile("cached", MemoryStatus.STAGED, "cached.preference", "茶", "喜欢茶")
        val app = instrumentation.targetContext.applicationContext as ProbeApplication
        val syncedAt = Instant.parse("2026-09-27T10:00:00Z")
        runBlocking {
            val subject = app.identityStore.getOrCreate().deviceId
            app.memoryCache.clearSubject(subject)
            app.memoryCache.replaceFullSnapshot(subject, listOf(record), syncedAt)
        }
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        MemoryGatewayRegistry.override = object : MemoryGateway by gatewayWith(record) {
            override suspend fun list(
                statuses: Set<MemoryStatus>, characterScope: CharacterScope?,
            ): List<CanonicalMemory> {
                release.await()
                throw java.io.IOException("offline fixture")
            }
        }
        val activity = launch()
        try {
            awaitCard(activity, "cached.preference")
            onMain {
                val banner = findTagged(activity, MemoryTrustActivity.TAG_FRESHNESS) as TextView
                assertTrue(banner.text.contains(activity.getString(R.string.memory_cached)))
                assertTrue(banner.text.contains("2026-09-27"))
                assertTrue(!(findTagged(activity, MemoryTrustActivity.TAG_FILTER) as Button).isEnabled)
            }
            release.complete(Unit)
            awaitIdle(activity)
            onMain {
                val banner = findTagged(activity, MemoryTrustActivity.TAG_FRESHNESS) as TextView
                assertTrue(banner.text.contains(activity.getString(R.string.memory_stale)))
                assertTrue(texts(activity).any { it.contains("cached.preference") })
            }
            clickTagged(activity, MemoryTrustActivity.TAG_FILTER)
            onMain {
                assertTrue((findTagged(activity, MemoryTrustActivity.TAG_FRESHNESS) as TextView)
                    .text.contains(activity.getString(R.string.memory_stale)))
            }
        } finally {
            release.complete(Unit)
            close(activity)
        }
    }

    @Test
    fun neverSyncedIsNotAnEmptyMemoryList() {
        val gateway = object : MemoryGateway by gatewayWith() {
            override suspend fun list(
                statuses: Set<MemoryStatus>, characterScope: CharacterScope?,
            ): List<CanonicalMemory> = throw java.io.IOException("offline fixture")
        }
        val activity = launchWithGateway(gateway)
        try {
            awaitState("never synced banner") {
                onMain { texts(activity).any { it == activity.getString(R.string.memory_never_synced) } }
            }
            onMain {
                assertTrue(texts(activity).none { it == activity.getString(R.string.memory_empty) })
                assertTrue(!(findTagged(activity, MemoryTrustActivity.TAG_FILTER) as Button).isEnabled)
            }
        } finally {
            close(activity)
        }
    }

    @Test
    fun successfulRefreshReplacesCachedFrameAndItsBanner() {
        val old = profile("old", MemoryStatus.CONFIRMED, "old.preference", "茶", "旧记录")
        val fresh = profile("new", MemoryStatus.CONFIRMED, "new.preference", "水", "新记录")
        val app = instrumentation.targetContext.applicationContext as ProbeApplication
        runBlocking {
            val subject = app.identityStore.getOrCreate().deviceId
            app.memoryCache.clearSubject(subject)
            app.memoryCache.replaceFullSnapshot(subject, listOf(old), Instant.parse("2026-01-01T00:00:00Z"))
        }
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        MemoryGatewayRegistry.override = object : MemoryGateway by gatewayWith(fresh) {
            override suspend fun list(
                statuses: Set<MemoryStatus>, characterScope: CharacterScope?,
            ): List<CanonicalMemory> {
                release.await()
                return listOf(fresh)
            }
        }
        val activity = launch()
        try {
            awaitCard(activity, "old.preference")
            release.complete(Unit)
            awaitIdle(activity)
            onMain {
                assertTrue(texts(activity).any { it.contains("new.preference") })
                assertTrue(texts(activity).none { it.contains("old.preference") })
                val banner = findTagged(activity, MemoryTrustActivity.TAG_FRESHNESS) as TextView
                assertTrue(banner.text.contains(activity.getString(R.string.memory_fresh)))
                assertTrue(!banner.text.contains("2026-01-01"))
            }
        } finally {
            release.complete(Unit)
            close(activity)
        }
    }

    /**
     * The identity is the partition every read addresses, so a screen that cannot establish it must read
     * **nothing** and must not invent a substitute subject.
     *
     * Asserted against the store rather than against the rendering, deliberately: "the screen says
     * unavailable" is also true of an implementation that reads the authority and then chooses to hide
     * the result, and that implementation has already asked for a partition it could not name.
     *
     * The test Application owns a new, unopened identity store for each method. Damage precedes
     * its first read; the production store retains its normal process cache. No process reset or
     * behind-the-store rewrite of the persistent app identity is needed.
     */
    @Test
    fun aDamagedIdentityMakesTheSurfaceUnavailableWithoutInventingASubject() {
        val store = MapStore()
        store.seed(profile("m1", MemoryStatus.STAGED, "food.dislike", "香菜", "我真的不喜欢香菜"))
        MemoryGatewayRegistry.override = DefaultMemoryGateway(store)

        val file = identityFile()
        val original = if (file.exists()) file.readBytes() else null
        try {
            file.parentFile?.mkdirs()
            // A bad version word. The serializer raises CorruptionException for this and for truncation,
            // and per its own contract corruption must never silently rotate the identity.
            file.writeBytes(byteArrayOf(0, 0, 0, 9, 1, 2, 3))

            val activity = launch()
            try {
                val expected = activity.getString(R.string.memory_subject_unavailable_title)
                awaitState("the identity-unavailable screen") {
                    onMain { texts(activity).any { it == expected } }
                }
                assertEquals("nothing may be read under an unresolved subject", 0, store.reads)
                capture(activity, "memory_subject_unavailable")
            } finally {
                close(activity)
            }
        } finally {
            if (original == null) file.delete() else file.writeBytes(original)
        }
    }

    /**
     * The test Application's owned backing file. The same production serializer/store observes
     * damage, without modifying the real app identity or adding production reload behaviour.
     */
    private fun identityFile(): java.io.File =
        testApplication.fixtureIdentityFile

    // ------------------------------------------------------------------ the composition owner (W2-B)

    /** One real Activity visits all five paths; inspect ownership without adding production hooks. */
    @Test
    fun oneOwnerServesAllFivePathsWithoutSuccessRelisting() {
        val recording = RecordingGateway(gatewayWith(
            profile("a", MemoryStatus.STAGED, "first", "茶", "一"),
            profile("b", MemoryStatus.STAGED, "second", "茶", "二"),
            profile("c", MemoryStatus.CONFIRMED, "third", "茶", "三"),
            profile("d", MemoryStatus.CONFIRMED, "fourth", "茶", "四"),
        ))
        val activity = launchWithGateway(recording)
        try {
            awaitCard(activity, "first")
            awaitIdle(activity)
            val field = MemoryTrustActivity::class.java.getDeclaredField("repository").apply { isAccessible = true }
            val owner = onMain { field.get(activity) }
            assertNotNull(owner)
            listOf("confirm" to "a", "reject" to "b", "edit" to "c", "forget" to "d").forEach { (op, id) ->
                performMutation(activity, op, id)
                awaitIdle(activity)
                onMain { org.junit.Assert.assertSame("$op replaced the repository owner", owner, field.get(activity)) }
            }
            assertEquals(listOf("list", "confirm", "reject", "edit", "forget"), recording.journal)
        } finally {
            close(activity)
        }
    }

    @Test
    fun everyLostMutationResponseIsReconciledWithoutRetry() = verifyLostResponses(false)

    @Test
    fun failedReconciliationNeverRetriesAnyMutation() = verifyLostResponses(true)

    private fun verifyLostResponses(failReadBack: Boolean) {
        listOf("confirm", "reject", "edit", "forget").forEach { op ->
            val status = if (op == "confirm" || op == "reject") MemoryStatus.STAGED else MemoryStatus.CONFIRMED
            val recording = RecordingGateway(gatewayWith(profile("m1", status, "preference", "茶", "原话")))
            recording.loseResponseFor = op
            recording.failReconciliation = failReadBack
            val activity = launchWithGateway(recording)
            try {
                awaitCard(activity, "preference")
                awaitIdle(activity)
                performMutation(activity, op, "m1")
                awaitIdle(activity)
                assertEquals("$op must send once, then only read", listOf("list", op, "list"), recording.journal)
                onMain {
                    if (op == "edit") {
                        assertEquals("改过的内容", (findTagged(activity, MemoryTrustActivity.TAG_EDIT_FIELD + "value") as EditText).text.toString())
                        assertTrue(texts(activity).contains(activity.getString(R.string.memory_edit_indeterminate)))
                    } else {
                        assertTrue(texts(activity).contains(activity.getString(R.string.memory_indeterminate)))
                    }
                    if (failReadBack) {
                        val banner = findTagged(activity, MemoryTrustActivity.TAG_FRESHNESS) as TextView
                        assertTrue(banner.text.contains(activity.getString(R.string.memory_stale)))
                    }
                }
            } finally {
                close(activity)
            }
        }
    }

    private fun performMutation(activity: Activity, op: String, id: String) {
        when (op) {
            "confirm" -> clickTagged(activity, MemoryTrustActivity.TAG_CONFIRM + id)
            "reject" -> clickTagged(activity, MemoryTrustActivity.TAG_IGNORE + id)
            "edit" -> {
                openEditor(activity, id)
                setField(activity, "value", "改过的内容")
                clickTagged(activity, MemoryTrustActivity.TAG_EDIT_SAVE + id)
            }
            "forget" -> {
                clickTagged(activity, MemoryTrustActivity.TAG_DELETE + id)
                clickTagged(activity, MemoryTrustActivity.TAG_DELETE_CONFIRM + id)
            }
            else -> error("unexpected operation $op")
        }
    }

    /**
     * A successful mutation updates the projection from the authority's **return value**, and does not
     * re-list.
     *
     * This is the whole point of W2-B. Re-listing after a confirm would be a second projection of the
     * same authority through a different call, which is what the single-owner composition exists to
     * remove - and it is invisible to every behavioural assertion, because the screen ends up showing
     * the same records either way. Only the observed traffic can tell the two apart.
     */
    @Test
    fun aSuccessfulMutationUsesTheAuthorityReturnWithoutRelisting() {
        val recording = RecordingGateway(
            gatewayWith(profile("m1", MemoryStatus.STAGED, "food.dislike", "香菜", "我真的不喜欢香菜")),
        )
        val activity = launchWithGateway(recording)
        try {
            awaitCard(activity, "food.dislike")
            assertEquals("the initial load should be exactly one read", listOf("list"), recording.journal)

            clickTagged(activity, MemoryTrustActivity.TAG_CONFIRM + "m1")
            awaitState("the confirmation to be reported") {
                onMain { texts(activity).any { it == activity.getString(R.string.memory_confirm_done) } }
            }

            assertEquals(
                "a mutation must not be followed by a re-list; the authority already returned the record",
                listOf("list", "confirm"),
                recording.journal,
            )
            assertEquals(
                "the confirmed status must come from the authority's return, not from a re-read",
                MemoryStatus.CONFIRMED,
                runBlocking { recording.list() }.single().status,
            )
        } finally {
            close(activity)
        }
    }

    /**
     * An outcome the repository cannot classify is **sent exactly once** and then reconciled by reading.
     *
     * The failure injected here is a bare `IOException`, which is how a lost transport response actually
     * presents. Retrying would be the one unjustifiable response: if the authority did apply the
     * mutation, a retry applies it twice. So the journal must show one `confirm` and a read afterwards -
     * never a second `confirm`.
     */
    @Test
    fun anIndeterminateMutationIsSentExactlyOnceAndReconciledByRead() {
        val recording = RecordingGateway(
            gatewayWith(profile("m1", MemoryStatus.STAGED, "food.dislike", "香菜", "我真的不喜欢香菜")),
        )
        recording.failConfirmWith = java.io.IOException("response lost after the server committed")
        val activity = launchWithGateway(recording)
        try {
            awaitCard(activity, "food.dislike")
            clickTagged(activity, MemoryTrustActivity.TAG_CONFIRM + "m1")

            awaitState("the uncertainty to be reported") {
                onMain { texts(activity).any { it == activity.getString(R.string.memory_indeterminate) } }
            }
            awaitState("the read-back to land") { recording.journal.count { it == "list" } >= 2 }

            assertEquals(
                "the mutation must be sent exactly once",
                1,
                recording.journal.count { it == "confirm" },
            )
            assertEquals(
                "an indeterminate outcome is reconciled by reading, not by resending",
                listOf("list", "confirm", "list"),
                recording.journal,
            )
        } finally {
            close(activity)
        }
    }

    /**
     * An indeterminate **edit** keeps the draft.
     *
     * The user's typing is the only copy of what she meant, and we do not know whether the authority
     * accepted it. Closing the editor on an unknown outcome would destroy that input on the strength of a
     * response we never received - so the editor must survive the reconcile that follows.
     */
    @Test
    fun anIndeterminateEditKeepsTheDraftWhileReconciling() {
        val recording = RecordingGateway(gatewayWith(event("m1")))
        recording.failEditWith = java.io.IOException("response lost after the server committed")
        val activity = launchWithGateway(recording)
        try {
            awaitCard(activity, "去医院")
            openEditor(activity, "m1")
            setField(activity, "title", "改过的标题")
            clickTagged(activity, MemoryTrustActivity.TAG_EDIT_SAVE + "m1")

            awaitState("the uncertainty to be reported in the editor") {
                onMain {
                    texts(activity).any { it == activity.getString(R.string.memory_edit_indeterminate) }
                }
            }
            awaitState("the read-back to land") { recording.journal.count { it == "list" } >= 2 }

            onMain {
                assertNotNull(
                    "the editor closed on an outcome we could not confirm",
                    findTagged(activity, MemoryTrustActivity.TAG_EDIT_SAVE + "m1"),
                )
                val field = findTagged(activity, MemoryTrustActivity.TAG_EDIT_FIELD + "title") as EditText
                assertEquals("the draft was discarded", "改过的标题", field.text.toString())
            }
            assertEquals("the edit must be sent exactly once", 1, recording.journal.count { it == "edit" })
        } finally {
            close(activity)
        }
    }

    // ------------------------------------------------------------------ staged memories

    @Test
    fun aStagedCandidateIsConfirmedThroughTheRealGateway() {
        val gateway = gatewayWith(profile("m1", MemoryStatus.STAGED, "food.dislike", "香菜", "我真的不喜欢香菜"))
        val activity = launchWithGateway(gateway)
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
        val activity = launchWithGateway(gateway)
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
        val activity = launchWithGateway(gateway)
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
        val activity = launchWithGateway(gateway)
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
        val activity = launchWithGateway(gateway)
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

    // ------------------------------------------------------------------ editing

    @Test
    fun aConfirmedProfileCanBeEditedAndKeepsItsIdentity() {
        val gateway = gatewayWith(profile("m1", MemoryStatus.CONFIRMED, "food.dislike", "香菜", "我不喜欢香菜"))
        val activity = launchWithGateway(gateway)
        try {
            awaitCard(activity, "food.dislike")
            openEditor(activity, "m1")
            setField(activity, "value", "芹菜")
            saveEdit(activity, "m1")

            awaitState("the edit to be applied") {
                (runBlocking { gateway.list() }.singleOrNull() as? ProfileMemory)?.value == "芹菜"
            }
            val stored = runBlocking { gateway.list() }.single()
            assertEquals("m1", stored.id.value)
            assertEquals(MemoryStatus.CONFIRMED, stored.status)
            assertEquals(MemorySource.USER_EDIT, stored.source)
            assertEquals(
                "the sentence the fact came from is no longer why she believes it",
                "",
                stored.provenance.excerpt,
            )
        } finally {
            close(activity)
        }
    }

    @Test
    fun editingAStagedCandidateDoesNotConfirmIt() {
        val gateway = gatewayWith(profile("s1", MemoryStatus.STAGED, "food.dislike", "香菜", "我不喜欢香菜"))
        val activity = launchWithGateway(gateway)
        try {
            awaitCard(activity, "food.dislike")
            openEditor(activity, "s1")
            setField(activity, "value", "芹菜")
            saveEdit(activity, "s1")

            awaitState("the candidate to be edited but not confirmed") {
                (runBlocking { gateway.list() }.singleOrNull() as? ProfileMemory)?.value == "芹菜"
            }
            // Sync on the screen, not the gateway: the gateway is already updated above, so asserting
            // the UI without this can run while the editor is still open. See awaitIdle.
            awaitIdle(activity)
            assertEquals(
                "editing must not turn a candidate into something the companion treats as true",
                MemoryStatus.STAGED,
                statusOf(gateway, "s1"),
            )
            assertNotNull(
                "the user must still have to accept it",
                onMain { findTagged(activity, MemoryTrustActivity.TAG_CONFIRM + "s1") },
            )
        } finally {
            close(activity)
        }
    }

    @Test
    fun savingWithoutChangingAnythingPreservesTheOriginalProvenance() {
        val gateway = gatewayWith(profile("m1", MemoryStatus.CONFIRMED, "food.dislike", "香菜", "我不喜欢香菜"))
        val activity = launchWithGateway(gateway)
        try {
            awaitCard(activity, "food.dislike")
            openEditor(activity, "m1")
            saveEdit(activity, "m1")

            awaitState("the no-op save to report back") {
                onMain { texts(activity).any { it == activity.getString(R.string.memory_edit_unchanged) } }
            }
            val stored = runBlocking { gateway.list() }.single()
            assertEquals(MemorySource.CONVERSATION, stored.source)
            assertEquals(Instant.parse("2026-09-27T10:00:00Z"), stored.recordedAt)
            assertEquals("test", stored.provenance.extractor)
        } finally {
            close(activity)
        }
    }

    @Test
    fun anIdentityMovingEditKeepsTheIdAndShowsTheNewFact() {
        val gateway = gatewayWith(profile("m1", MemoryStatus.CONFIRMED, "food.dislike", "香菜", "我不喜欢香菜"))
        val activity = launchWithGateway(gateway)
        try {
            awaitCard(activity, "food.dislike")
            openEditor(activity, "m1")
            setField(activity, "attribute", "food.like")
            saveEdit(activity, "m1")

            awaitState("the identity to move") {
                (runBlocking { gateway.list() }.singleOrNull() as? ProfileMemory)?.attribute == "food.like"
            }
            assertEquals("m1", runBlocking { gateway.list() }.single().id.value)
            awaitState("the new fact to be shown") {
                onMain { texts(activity).any { it.contains("food.like：香菜") } }
            }
        } finally {
            close(activity)
        }
    }

    @Test
    fun anIdentityConflictKeepsTheEditorOpenWithTheDraftAndChangesNothing() {
        val gateway = gatewayWith(
            profile("m1", MemoryStatus.CONFIRMED, "food.dislike", "香菜", "我不喜欢香菜"),
            profile("m2", MemoryStatus.CONFIRMED, "food.like", "芹菜", "我爱吃芹菜"),
        )
        val activity = launchWithGateway(gateway)
        try {
            awaitCard(activity, "food.dislike")
            openEditor(activity, "m1")
            setField(activity, "attribute", "food.like")
            setField(activity, "value", "香菜")
            saveEdit(activity, "m1")

            awaitState("the conflict to be reported in the card") {
                onMain { texts(activity).any { it == activity.getString(R.string.memory_edit_conflict) } }
            }
            // The editor and the typing are still there: the gateway wrote nothing, and the draft is the
            // only copy of what she meant.
            onMain {
                assertNotNull(
                    "the editor closed on conflict",
                    findTagged(activity, MemoryTrustActivity.TAG_EDIT_SAVE + "m1"),
                )
                assertNotNull(
                    "the draft was discarded",
                    findTagged(activity, MemoryTrustActivity.TAG_EDIT_FIELD + "value"),
                )
            }
            val after = runBlocking { gateway.list() }.associateBy { it.id.value }
            assertEquals("food.dislike", (after.getValue("m1") as ProfileMemory).attribute)
            assertEquals("food.like", (after.getValue("m2") as ProfileMemory).attribute)
            assertEquals(2, after.size)
        } finally {
            close(activity)
        }
    }

    @Test
    fun aRejectedMemoryOffersNoEditAffordance() {
        val gateway = gatewayWith(
            ProfileMemory(
                id = MemoryId("r1"),
                importance = Importance.NORMAL,
                status = MemoryStatus.REJECTED,
                recordedAt = Instant.parse("2026-09-27T10:00:00Z"),
                source = MemorySource.CONVERSATION,
                provenance = Provenance(sessionId = "s", messageId = "m", excerpt = "我不喜欢香菜", extractor = "test"),
                characterScope = CharacterScope("xiaozhi"),
                attribute = "food.dislike",
                value = "香菜",
            ),
        )
        val activity = launchWithGateway(gateway)
        try {
            awaitCard(activity, "food.dislike")
            onMain {
                assertNull(
                    "a rejected memory must not offer an edit the gateway would refuse",
                    findTagged(activity, MemoryTrustActivity.TAG_EDIT + "r1"),
                )
            }
        } finally {
            close(activity)
        }
    }

    @Test
    fun cancellingAnEditNeverReachesTheGateway() {
        val recording = RecordingGateway(
            gatewayWith(profile("m1", MemoryStatus.CONFIRMED, "food.dislike", "香菜", "我不喜欢香菜")),
        )
        val activity = launchWithGateway(recording)
        try {
            awaitCard(activity, "food.dislike")
            openEditor(activity, "m1")
            setField(activity, "value", "芹菜")
            clickTagged(activity, MemoryTrustActivity.TAG_EDIT_CANCEL + "m1")

            awaitState("the original card to come back") {
                onMain {
                    findTagged(activity, MemoryTrustActivity.TAG_EDIT + "m1") != null &&
                        findTagged(activity, MemoryTrustActivity.TAG_EDIT_SAVE + "m1") == null
                }
            }
            assertEquals("cancel must not call edit()", 0, recording.editCalls)
            assertEquals("香菜", (runBlocking { recording.list() }.single() as ProfileMemory).value)
        } finally {
            close(activity)
        }
    }

    @Test
    fun everyCanonicalTypeOpensAnEditorWithItsOwnFields() {
        val gateway = gatewayWith(
            profile("p1", MemoryStatus.CONFIRMED, "food.dislike", "香菜", "我不喜欢香菜"),
            event("e1"),
            episode("x1"),
            relation("n1"),
        )
        val activity = launchWithGateway(gateway)
        try {
            awaitCard(activity, "food.dislike")
            assertEditorFields(activity, "p1", listOf("attribute", "value"))
            assertEditorFields(activity, "e1", listOf("title", "scheduledFor", "location"))
            assertEditorFields(activity, "x1", listOf("summary", "occurredAt", "emotionalTone", "relations"))
            assertEditorFields(activity, "n1", listOf("name", "role", "note"))
        } finally {
            close(activity)
        }
    }

    /**
     * The regression a lossy time format would hide.
     *
     * The field shows `yyyy-MM-dd HH:mm`, but the record holds `...T07:00:37.123Z`. Editing only the
     * title must not rewrite the instant, or the gateway would correctly see a content change, relabel
     * the record as a user edit and drop the original provenance - for a field nobody touched.
     */
    @Test
    fun editingAnEventTitleLeavesTheOriginalInstantExactlyAlone() {
        val exact = Instant.parse("2026-10-05T07:00:37.123Z")
        val gateway = gatewayWith(event("e1", exact))
        val activity = launchWithGateway(gateway)
        try {
            awaitCard(activity, "去医院")
            openEditor(activity, "e1")
            onMain {
                val field = findTagged(activity, MemoryTrustActivity.TAG_EDIT_FIELD + "scheduledFor") as EditText
                assertEquals(
                    "the field must show the formatted time, not the raw instant",
                    MemoryEditDraft.formatTime(exact),
                    field.text.toString(),
                )
            }
            setField(activity, "title", "去复查")
            saveEdit(activity, "e1")

            awaitState("the title edit to be applied") {
                (runBlocking { gateway.list() }.singleOrNull() as? EventMemory)?.title == "去复查"
            }
            val stored = runBlocking { gateway.list() }.single() as EventMemory
            assertEquals(
                "editing the title silently rewrote the time the user never touched",
                exact,
                stored.scheduledFor,
            )
        } finally {
            close(activity)
        }
    }

    @Test
    fun theEditFormFitsThePanelAndItsButtonsAreNotClipped() {
        val gateway = gatewayWith(profile("m1", MemoryStatus.CONFIRMED, "food.dislike", "香菜", "我不喜欢香菜"))
        val activity = launchWithGateway(gateway)
        try {
            awaitCard(activity, "food.dislike")
            openEditor(activity, "m1")
            onMain {
                val frameWidth = activity.window.decorView.width
                val frameHeight = activity.window.decorView.height

                val heading = flatten(activity.window.decorView)
                    .filterIsInstance<TextView>()
                    .firstOrNull {
                        it.text.toString() == activity.getString(
                            R.string.memory_edit_heading,
                            activity.getString(R.string.memory_type_profile),
                        )
                    }
                assertNotNull("the edit form has no heading", heading)
                assertTrue("the edit form heading is not visible", heading!!.isShown)

                // The first required field must be readable without scrolling, or the form opens showing
                // nothing to edit - the defect the trust surface already had once.
                val firstField = findTagged(activity, MemoryTrustActivity.TAG_EDIT_FIELD + "attribute")
                assertNotNull("the form has no first field", firstField)
                val location = IntArray(2).also { firstField!!.getLocationInWindow(it) }
                assertTrue(
                    "the first field is below the fold: y=${location[1]} of $frameHeight",
                    location[1] + firstField!!.height <= frameHeight,
                )

                listOf(
                    MemoryTrustActivity.TAG_EDIT_CANCEL + "m1",
                    MemoryTrustActivity.TAG_EDIT_SAVE + "m1",
                ).forEach { tag ->
                    val button = findTagged(activity, tag) as Button
                    val needed = button.paint.measureText(button.text.toString())
                    val available = (button.width - button.paddingLeft - button.paddingRight).toFloat()
                    assertTrue(
                        "label \"${button.text}\" is clipped: needs $needed, has $available",
                        needed <= available,
                    )
                    val buttonLocation = IntArray(2).also { button.getLocationInWindow(it) }
                    assertTrue(
                        "a control escaped the panel: x=${buttonLocation[0]} width=${button.width} of $frameWidth",
                        buttonLocation[0] >= 0 && buttonLocation[0] + button.width <= frameWidth + 1,
                    )
                }
            }
            // Outside the onMain block: capture() posts its own work to the main thread.
            capture(activity, "memory_edit_form")
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
        val activity = launchWithGateway(gateway)
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

    private fun openEditor(activity: Activity, id: String) {
        clickTagged(activity, MemoryTrustActivity.TAG_EDIT + id)
        awaitState("the editor for $id") {
            onMain { findTagged(activity, MemoryTrustActivity.TAG_EDIT_SAVE + id) != null }
        }
    }

    private fun saveEdit(activity: Activity, id: String) {
        clickTagged(activity, MemoryTrustActivity.TAG_EDIT_SAVE + id)
    }

    private fun setField(activity: Activity, key: String, value: String) {
        instrumentation.runOnMainSync {
            val field = findTagged(activity, MemoryTrustActivity.TAG_EDIT_FIELD + key)
            assertNotNull("no edit field tagged '$key'", field)
            (field as EditText).setText(value)
        }
        instrumentation.waitForIdleSync()
    }

    private fun assertEditorFields(activity: Activity, id: String, keys: List<String>) {
        openEditor(activity, id)
        onMain {
            keys.forEach { key ->
                assertNotNull(
                    "the editor for $id is missing the '$key' field",
                    findTagged(activity, MemoryTrustActivity.TAG_EDIT_FIELD + key),
                )
            }
        }
        clickTagged(activity, MemoryTrustActivity.TAG_EDIT_CANCEL + id)
    }

    /**
     * Records every call, so that "the authority's return was used instead of re-listing" and "an
     * indeterminate mutation was sent exactly once" are assertions about **observed traffic** rather
     * than about intent.
     *
     * Failures are injected as plain exceptions. The repository classifies anything that is not one of
     * the canonical typed rejections as indeterminate, so a bare `IOException` is exactly how a lost
     * transport response presents - it is not a contrived case.
     */
    private class RecordingGateway(private val delegate: MemoryGateway) : MemoryGateway {
        val journal = mutableListOf<String>()
        var editCalls = 0
        var failConfirmWith: Exception? = null
        var failEditWith: Exception? = null
        var loseResponseFor: String? = null
        var failReconciliation = false
        private var responseLost = false

        private fun <T> returned(op: String, value: T): T {
            if (loseResponseFor == op) {
                responseLost = true
                throw java.io.IOException("authority committed but its response was lost")
            }
            return value
        }

        override suspend fun stage(candidates: List<CanonicalMemory>) = delegate.stage(candidates)

        override suspend fun confirm(id: MemoryId): CanonicalMemory {
            journal += "confirm"
            failConfirmWith?.let { throw it }
            return returned("confirm", delegate.confirm(id))
        }

        override suspend fun reject(id: MemoryId): CanonicalMemory {
            journal += "reject"
            return returned("reject", delegate.reject(id))
        }

        override suspend fun remember(memory: CanonicalMemory) = delegate.remember(memory)

        override suspend fun edit(id: MemoryId, edit: MemoryEdit): EditOutcome {
            journal += "edit"
            editCalls++
            failEditWith?.let { throw it }
            return returned("edit", delegate.edit(id, edit))
        }

        override suspend fun recall(query: MemoryQuery) = delegate.recall(query)

        override suspend fun list(
            statuses: Set<MemoryStatus>,
            characterScope: CharacterScope?,
        ): List<CanonicalMemory> {
            journal += "list"
            if (responseLost && failReconciliation) throw java.io.IOException("read-back unavailable")
            return delegate.list(statuses, characterScope)
        }

        override suspend fun forget(id: MemoryId): Boolean {
            journal += "forget"
            return returned("forget", delegate.forget(id))
        }
    }

    private fun event(id: String, scheduledFor: Instant = Instant.parse("2026-10-05T07:00:00Z")) = EventMemory(
        id = MemoryId(id),
        importance = Importance.NORMAL,
        status = MemoryStatus.CONFIRMED,
        recordedAt = Instant.parse("2026-09-27T10:00:00Z"),
        source = MemorySource.CONVERSATION,
        provenance = Provenance(sessionId = "s", messageId = "m", excerpt = "下周三去医院", extractor = "test"),
        characterScope = CharacterScope("xiaozhi"),
        title = "去医院",
        scheduledFor = scheduledFor,
        location = "浙一",
    )

    private fun episode(id: String) = EpisodeMemory(
        id = MemoryId(id),
        importance = Importance.NORMAL,
        status = MemoryStatus.CONFIRMED,
        recordedAt = Instant.parse("2026-09-27T10:00:00Z"),
        source = MemorySource.CONVERSATION,
        provenance = Provenance(sessionId = "s", messageId = "m", excerpt = "昨天和室友吵架了", extractor = "test"),
        characterScope = CharacterScope("xiaozhi"),
        summary = "和室友吵架了",
        occurredAt = Instant.parse("2026-09-26T13:00:00Z"),
        emotionalTone = "委屈",
        relations = setOf("室友"),
    )

    private fun relation(id: String) = RelationMemory(
        id = MemoryId(id),
        importance = Importance.NORMAL,
        status = MemoryStatus.CONFIRMED,
        recordedAt = Instant.parse("2026-09-27T10:00:00Z"),
        source = MemorySource.CONVERSATION,
        provenance = Provenance(sessionId = "s", messageId = "m", excerpt = "小李是我室友", extractor = "test"),
        characterScope = CharacterScope("xiaozhi"),
        name = "小李",
        role = "室友",
    )

    private fun launch(): Activity {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MemoryTrustActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        instrumentation.waitForIdleSync()
        return activity
    }

    /**
     * Launch the screen against [gateway], with this subject's cached state cleared first.
     *
     * **Why the cache has to be cleared.** Each method runs in its own process but they all share one
     * `deviceId` and one on-disk database, and the fixtures reuse ids (`m1`, `s1`, `r1`). Once the
     * screen reads through a cache, a previous method's rows would still be there - and because the
     * repository emits the cached frame *before* the refresh, a test could observe another test's
     * memory under the same id. The cache surviving a restart is correct behaviour; the *fixture* is
     * what has to be explicit about starting clean.
     *
     * Clearing goes through the production `clearSubject(subjectId)` partition API rather than a
     * test-only seam, `deleteDatabase()` or `clearAllTables()`. That also keeps the subject-partition
     * semantics under constant use in instrumentation instead of only in the cache module's own tests.
     *
     * **Do not route every test through here.** Reading the identity to find the subject warms the
     * fixture store, so `aDamagedIdentityMakesTheSurfaceUnavailableWithoutInventingASubject` must use
     * plain [launch]: damage must precede that fixture's first read.
     * `withoutAGatewayTheScreenSaysSoAndInventsNothing` also stays on [launch], since it deliberately
     * has no gateway and must reach the screen without one.
     */
    private fun launchWithGateway(gateway: MemoryGateway): Activity {
        val app = instrumentation.targetContext.applicationContext as ProbeApplication
        val subject = runBlocking { app.identityStore.getOrCreate().deviceId }
        runBlocking { app.memoryCache.clearSubject(subject) }
        MemoryGatewayRegistry.override = gateway
        return launch()
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
        val activity = launchWithGateway(gateway)
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

    /**
     * Wait until the screen has finished with whatever it was doing.
     *
     * Asserting on the UI after a mutation needs a **UI-level** sync point. Waiting on the gateway
     * instead is a race: the authority's state changes before this screen has re-rendered, so an
     * assertion can run while the editor is still on screen and the card has not come back. That race
     * is real and was measured - `editingAStagedCandidateDoesNotConfirmIt` reported 2 pass / 2 fail on
     * one unchanged build before this helper existed.
     *
     * The filter button is disabled exactly while [MemoryTrustActivity] is busy and re-enabled after the
     * mutation has rendered, which makes it the honest signal rather than a guessed delay.
     */
    private fun awaitIdle(activity: Activity) {
        awaitState("the screen to become idle") {
            onMain { (findTagged(activity, MemoryTrustActivity.TAG_FILTER) as? Button)?.isEnabled == true }
        }
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

        /**
         * How many times storage was actually touched.
         *
         * The identity test turns on a path *never reaching the authority*, and "the screen renders
         * unavailable" does not establish that: an implementation that reads first and then decides to
         * hide the result renders identically, having already asked for a partition it could not name.
         */
        var reads = 0
            private set

        fun seed(memory: CanonicalMemory) {
            records[memory.id.value] = memory
        }

        override suspend fun getById(id: MemoryId): CanonicalMemory? {
            reads++
            return records[id.value]
        }

        override suspend fun findAllByScopedIdentity(identity: ScopedMemoryIdentity): List<CanonicalMemory> {
            reads++
            return records.values.filter { it.scopedIdentity == identity }
        }

        override suspend fun put(memory: CanonicalMemory) {
            records[memory.id.value] = memory
        }

        override suspend fun delete(id: MemoryId): Boolean = records.remove(id.value) != null

        override suspend fun list(): List<CanonicalMemory> {
            reads++
            return records.values.toList()
        }
    }

    private fun flatten(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { flatten(view.getChildAt(it)) } else emptyList()
}
