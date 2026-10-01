package com.aiwatch.probe.character

import android.app.Activity
import android.widget.FrameLayout
import android.widget.Toast
import com.aiwatch.live2d.Live2DAvatarView
import com.aiwatch.probe.BuildConfig
import com.aiwatch.probe.theme.CompanionColors

/** Local model preview only. One View/runtime per foreground Home; background releases on GL thread. */
class HomeAvatarBinding(private val stage: AvatarStageView) {
    private var view: Live2DAvatarView? = null
    private var failed = false

    fun resume() {
        if (view != null || failed) return
        val current = Live2DAvatarView(stage.context).apply {
            clearColorArgb = CompanionColors.background
            modelDirectory = "local-model/"
            modelJson = BuildConfig.LOCAL_MODEL_JSON
            if ((stage.context as? Activity)?.intent?.getBooleanExtra("com.aiwatch.probe.LOCAL_MODEL_FAILURE", false) == true) {
                modelJson = "missing.model3.json"
            }
            contentDescription = "本地 Live2D 角色预览"
        }
        current.listener = object : Live2DAvatarView.Listener {
            override fun onLive2DReady(surfaceGeneration: Int) {
                if (view === current) stage.showStaticArt(false)
            }
            override fun onLive2DFailed(reason: String) {
                if (view !== current) return
                failed = true
                pause()
                Toast.makeText(stage.context, "Live2D 加载失败，已恢复静态角色", Toast.LENGTH_LONG).show()
            }
        }
        view = current
        stage.addView(current, FrameLayout.LayoutParams(-1, -1))
        current.onResume()
    }

    fun pause() {
        val old = view ?: return
        view = null
        old.listener = null
        old.releaseRuntime()
        old.onPause()
        stage.removeView(old)
        stage.showStaticArt(true)
    }

    fun close() = pause()
}
