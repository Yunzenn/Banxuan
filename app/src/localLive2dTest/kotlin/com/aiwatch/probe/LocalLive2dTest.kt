package com.aiwatch.probe

import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.test.platform.app.InstrumentationRegistry
import com.aiwatch.live2d.Live2DAvatarView
import com.aiwatch.probe.character.AvatarStageView
import com.aiwatch.probe.home.CompanionActivity
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Only compiled with the explicit local model option. PixelCopy reads the GL surface, not fallback art. */
class LocalLive2dTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun <T> main(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun stage(activity: CompanionActivity) = main {
        descendants(activity.window.decorView).filterIsInstance<AvatarStageView>().single()
    }
    private fun surface(activity: CompanionActivity) = main {
        descendants(stage(activity)).filterIsInstance<Live2DAvatarView>().singleOrNull()
    }
    private fun launch(fail: Boolean = false): CompanionActivity = instrumentation.startActivitySync(
        Intent(instrumentation.targetContext, CompanionActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("com.aiwatch.probe.LOCAL_MODEL_FAILURE", fail),
    ) as CompanionActivity
    private fun waitUntil(label: String, condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
        while (!condition() && System.nanoTime() < end) Thread.sleep(100)
        assertTrue(label, condition())
    }
    private fun assertModelPixels(view: Live2DAvatarView) {
        waitUntil("model frames were not drawn: ${view.runtimeFailure}") { view.framesDrawn >= 3 }
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val latch = CountDownLatch(1)
        var status = -1
        PixelCopy.request(view, bitmap, { status = it; latch.countDown() }, Handler(Looper.getMainLooper()))
        assertTrue("PixelCopy timeout", latch.await(10, TimeUnit.SECONDS))
        assertEquals(PixelCopy.SUCCESS, status)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        bitmap.recycle()
        val opaque = pixels.count { (it ushr 24) > 128 }
        val colors = pixels.toSet().size
        println("LOCAL_MODEL surface=${view.width}x${view.height} colors=$colors opaque=$opaque frames=${view.framesDrawn}")
        assertTrue("blank or diagnostic-only surface", colors > 64)
        assertTrue("model occupies no meaningful area", opaque > pixels.size / 100)
    }

    @Test fun modelProducesPixelsAndForegroundRecreatesResources() {
        val activity = launch()
        try {
            val first = requireNotNull(surface(activity))
            assertModelPixels(first)
            main { instrumentation.callActivityOnPause(activity) }
            assertNull("background must detach renderer", surface(activity))
            main { instrumentation.callActivityOnResume(activity) }
            val second = requireNotNull(surface(activity))
            assertNotSame("resume must not reuse dead GL resources", first, second)
            assertModelPixels(second)
            main { activity.finish() }
            waitUntil("finish did not release runtime") { !second.isReady }
        } finally { main { if (!activity.isFinishing) activity.finish() } }
    }

    @Test fun missingModelFallsBackToVisibleStaticArt() {
        val activity = launch(fail = true)
        try {
            waitUntil("failed renderer must be removed") { surface(activity) == null }
            assertTrue(main { descendants(stage(activity)).filterIsInstance<ImageView>()
                .any { it.visibility == View.VISIBLE && it.drawable != null } })
        } finally { main { activity.finish() } }
    }
}
