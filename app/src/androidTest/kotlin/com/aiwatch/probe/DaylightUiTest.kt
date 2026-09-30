package com.aiwatch.probe

import android.content.Intent
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.test.platform.app.InstrumentationRegistry
import com.aiwatch.probe.character.AvatarStageView
import com.aiwatch.probe.home.CompanionActivity
import com.aiwatch.probe.theme.CompanionColors
import com.aiwatch.probe.voice.PushToTalkView
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow

class DaylightUiTest {
    @Test
    fun textAndStateColorsHaveReadableContrastOnLightSurfaces() {
        fun luminance(color: Int): Double {
            fun channel(value: Int): Double = (value / 255.0).let {
                if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4)
            }
            return 0.2126 * channel(Color.red(color)) + 0.7152 * channel(Color.green(color)) +
                0.0722 * channel(Color.blue(color))
        }
        val background = luminance(CompanionColors.background)
        assertTrue("default background must be light", background > 0.9)
        for (ink in listOf(CompanionColors.primaryText, CompanionColors.secondaryText,
            CompanionColors.companion, CompanionColors.listening, CompanionColors.thinking,
            CompanionColors.speaking)) {
            val ratio = (background + 0.05) / (luminance(ink) + 0.05)
            assertTrue("insufficient text contrast: $ratio", ratio >= 4.5)
        }
    }

    @Test
    fun bundledIllustrationAndAccessibleControlsFitTheWindow() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            CompanionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                fun flatten(v: View): List<View> = listOf(v) + if (v is ViewGroup)
                    (0 until v.childCount).flatMap { flatten(v.getChildAt(it)) } else emptyList()
                val views = flatten(activity.window.decorView)
                val stage = views.filterIsInstance<AvatarStageView>().single()
                assertTrue("missing bundled artwork", flatten(stage).filterIsInstance<ImageView>()
                    .any { it.drawable != null && it.visibility == View.VISIBLE })
                val density = activity.resources.displayMetrics.density
                val controls = listOf(views.filterIsInstance<PushToTalkView>().single(),
                    views.single { it.contentDescription == "设置" })
                for (control in controls) {
                    assertTrue("touch target width", control.width >= 48 * density)
                    assertTrue("touch target height", control.height >= 48 * density)
                    val bounds = android.graphics.Rect()
                    assertTrue("control is hidden", control.getGlobalVisibleRect(bounds))
                    assertEquals("control clipped vertically", control.height, bounds.height())
                }
                assertTrue("stage lost its height", stage.height >= 60 * density)
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
