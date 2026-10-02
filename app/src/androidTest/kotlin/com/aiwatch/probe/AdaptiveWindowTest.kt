package com.aiwatch.probe

import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import com.aiwatch.probe.character.AvatarStageView
import com.aiwatch.probe.conversation.ConversationListView
import com.aiwatch.probe.conversation.ConversationState
import com.aiwatch.probe.conversation.MessageBubbleView
import com.aiwatch.probe.home.CompanionActivity
import com.aiwatch.probe.home.CompanionHomeView
import com.aiwatch.probe.memory.MemoryTrustActivity
import com.aiwatch.probe.product.SettingsActivity
import com.aiwatch.probe.theme.CompanionDimensions
import com.aiwatch.probe.voice.PushToTalkView
import org.junit.Assert.*
import org.junit.Test

/** Run once per real wm/fontScale fixture. No font overrides or fabricated configuration in the test. */
class AdaptiveWindowTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private fun <T> main(block: () -> T): T {
        var value: T? = null
        ins.runOnMainSync { value = block() }
        @Suppress("UNCHECKED_CAST") return value as T
    }
    private fun all(v: View): List<View> = listOf(v) + if (v is ViewGroup)
        (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    private inline fun <reified T : View> find(a: Activity): T = all(a.window.decorView).filterIsInstance<T>().first()
    private fun await(label: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < end) {
            if (main(condition)) return
            SystemClock.sleep(50)
        }
        fail("timeout: $label")
    }
    private fun bounds(v: View): Rect {
        val xy = IntArray(2); v.getLocationOnScreen(xy)
        return Rect(xy[0], xy[1], xy[0] + v.width, xy[1] + v.height)
    }
    private fun visible(v: View): Rect = Rect().also { assertTrue("not visible: ${v.javaClass.simpleName}", v.getGlobalVisibleRect(it)) }
    private fun fullyVisible(v: View) {
        assertEquals("clipped ${v.javaClass.simpleName}", bounds(v), visible(v))
    }
    private fun touch(v: View, action: Int, down: Long = SystemClock.uptimeMillis()) {
        val rect = main { visible(v) }
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, rect.exactCenterX(), rect.exactCenterY(), 0)
        try { ins.sendPointerSync(event) } finally { event.recycle() }
        ins.waitForIdleSync()
    }
    private fun tap(v: View) {
        val down = SystemClock.uptimeMillis()
        touch(v, MotionEvent.ACTION_DOWN, down); touch(v, MotionEvent.ACTION_UP, down)
    }
    private fun assertHome(a: CompanionActivity) = main {
        val home = find<CompanionHomeView>(a)
        val ptt = find<PushToTalkView>(a)
        val transcript = find<ConversationListView>(a)
        val stage = find<AvatarStageView>(a)
        val settings = all(home).first { it.contentDescription == "设置" }
        val safe = Rect(); a.window.decorView.getWindowVisibleDisplayFrame(safe)
        for (v in listOf(ptt, transcript, settings)) {
            fullyVisible(v)
            assertTrue("outside system safe frame: ${v.javaClass.simpleName}", safe.contains(bounds(v)))
        }
        assertTrue("transcript lost", transcript.height >= (48 * a.resources.displayMetrics.density).toInt())
        assertTrue("stage overlaps transcript", bounds(stage).bottom <= bounds(transcript).top)
        assertTrue("transcript overlaps PTT", bounds(transcript).bottom <= bounds(ptt).top)
        val density = a.resources.displayMetrics.density
        assertTrue("stage must retain compact baseline", stage.height >= 60 * density)
        assertTrue(ptt.height >= 48 * density)
        assertTrue(ptt.width <= 320 * density)
        val texts = all(home).filterIsInstance<TextView>().filter { it.isShown && it.getGlobalVisibleRect(Rect()) }
        for (i in texts.indices) for (j in i + 1 until texts.size) {
            assertFalse("text overlap: ${texts[i].text} / ${texts[j].text}", Rect.intersects(visible(texts[i]), visible(texts[j])))
        }
        val bubbles = texts.filterIsInstance<MessageBubbleView>()
        assertTrue("no visible subtitle", bubbles.isNotEmpty())
        for (bubble in bubbles) assertEquals("font must respect user scale", CompanionDimensions.bubbleTextSp *
            a.resources.displayMetrics.scaledDensity, bubble.textSize, .1f)
        println("ADAPTIVE window=${home.width}x${home.height} padding=${home.paddingLeft},${home.paddingTop},${home.paddingRight},${home.paddingBottom} " +
            "fontScale=${a.resources.configuration.fontScale} safe=$safe stage=${stage.height} transcript=${transcript.height} ptt=${ptt.width}x${ptt.height}")
    }

    @Test
    fun windowKeepsInteractionsReadableAndReachableAfterRecreation() {
        val args = InstrumentationRegistry.getArguments()
        val expectedScale = args.getString("expectedFontScale")!!.toFloat()
        var activity = ins.startActivitySync(Intent(ins.targetContext, CompanionActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(CompanionActivity.EXTRA_SCRIPTED_CONVERSATION, true)) as CompanionActivity
        var settingsActivity: Activity? = null
        var memoryActivity: Activity? = null
        try {
            await("layout") { find<PushToTalkView>(activity).width > 0 }
            assertEquals(expectedScale, activity.resources.configuration.fontScale, .01f)
            val metrics = activity.resources.displayMetrics
            assertEquals(args.getString("expectedWidthDp")!!.toInt(), (metrics.widthPixels / metrics.density).toInt())
            assertEquals(args.getString("expectedHeightDp")!!.toInt(),
                main { (activity.window.decorView.height / metrics.density).toInt() })
            assertTrue("Daylight system icon contrast flag lost", main {
                activity.window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR != 0
            })
            assertHome(activity)
            val ptt = main { find<PushToTalkView>(activity) }
            touch(ptt, MotionEvent.ACTION_DOWN)
            await("PTT listening") { activity.conversationState == ConversationState.LISTENING }
            touch(ptt, MotionEvent.ACTION_UP)
            await("PTT completed") { activity.conversationState == ConversationState.IDLE }
            assertTrue(main { activity.conversationMessages.size >= 3 })
            assertHome(activity)

            // Additional host safe-area padding. Real system bars are independently checked against
            // getWindowVisibleDisplayFrame; this is not a fabricated system-inset result.
            main { find<CompanionHomeView>(activity).setPadding(4, 6, 4, 6) }
            ins.waitForIdleSync()
            assertHome(activity)
            main { find<CompanionHomeView>(activity).setPadding(0, 0, 0, 0) }
            ins.waitForIdleSync()

            val recreated = ins.addMonitor(CompanionActivity::class.java.name, null, false)
            main { activity.recreate() }
            activity = ins.waitForMonitorWithTimeout(recreated, 8_000) as? CompanionActivity
                ?: throw AssertionError("recreation did not launch Home")
            ins.removeMonitor(recreated)
            await("recreated layout") { find<PushToTalkView>(activity).width > 0 }
            assertEquals(ConversationState.IDLE, main { activity.conversationState })
            assertHome(activity)

            val settingsMonitor = ins.addMonitor(SettingsActivity::class.java.name, null, false)
            tap(main { all(activity.window.decorView).first { it.contentDescription == "设置" } })
            settingsActivity = ins.waitForMonitorWithTimeout(settingsMonitor, 8_000)
            ins.removeMonitor(settingsMonitor)
            val settings = requireNotNull(settingsActivity)
            await("settings page") { all(settings.window.decorView).filterIsInstance<Button>().any { it.text.toString() == settings.getString(R.string.memory_entry) } }
            val entry = main { all(settings.window.decorView).filterIsInstance<Button>().first { it.text.toString() == settings.getString(R.string.memory_entry) } }
            main {
                val scroll = find<ScrollView>(settings)
                scroll.isSmoothScrollingEnabled = false
                entry.requestRectangleOnScreen(Rect(0, 0, entry.width, entry.height), true)
            }
            ins.waitForIdleSync()
            main { fullyVisible(entry) }
            val memoryMonitor = ins.addMonitor(MemoryTrustActivity::class.java.name, null, false)
            tap(entry)
            memoryActivity = ins.waitForMonitorWithTimeout(memoryMonitor, 8_000)
            ins.removeMonitor(memoryMonitor)
            assertNotNull("memory surface unreachable", memoryActivity)
            println("ADAPTIVE PASS: PTT touch/state, transcript, geometry, fonts, insets, recreate, Settings -> Memory")
        } finally {
            main { memoryActivity?.finish(); settingsActivity?.finish(); activity.finish() }
            ins.waitForIdleSync()
        }
    }
}
