package com.aiwatch.live2d

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Color
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import android.util.AttributeSet
import com.aiwatch.live2d.oracle.OfficialOracleRuntime
import com.live2d.sdk.cubism.framework.rendering.android.CubismOffscreenManagerAndroid
import com.live2d.sdk.cubism.framework.rendering.android.CubismShaderAndroid
import com.live2d.sdk.cubism.framework.CubismFramework
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Live2D avatar container.
 *
 * The View owns the [AvatarRuntime] and destroys it on detach, so nothing survives the View. The listener
 * is notified with a result on the main thread, which is what lets Home fall back to the static avatar
 * instead of showing a blank frame.
 */
class Live2DAvatarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    val hostMode: HostMode = HostMode.PRODUCT,
    val runtimeBackend: RuntimeBackend = RuntimeBackend.OURS,
    plainShaderProbeEnabled: Boolean = false,
) : GLSurfaceView(context, attrs) {

    /**
     * Host configuration under test for P2B-1A-O1a. This is the ONLY thing that varies between the two
     * runs; the runtime, texture manager, model, projection and update/draw logic are identical.
     *
     * PRODUCT      — RGBA EGL config + TRANSLUCENT holder + media overlay + transparent clear (current).
     * HARNESS_LIKE — platform defaults + opaque clear, i.e. exactly what the known-good verification
     *                harness runs with. It is the PRIOR POSITIVE CONTROL configuration, not a new design.
     */
    enum class HostMode { PRODUCT, HARNESS_LIKE }

    /**
     * P2B-1A-O1b: which model semantics drive the frame.
     *
     * OURS     — `CubismRuntimeOwner` (the product runtime under investigation).
     * OFFICIAL — `OfficialOracleRuntime`, the official Sample's model/update/draw semantics, debug-only.
     *
     * Everything else (View, Surface, EGL context, host mode, textures, viewport, model assets) is shared,
     * so a difference in output can only come from the semantics under test.
     */
    enum class RuntimeBackend { OURS, OFFICIAL }

    interface Listener {
        fun onLive2DReady(surfaceGeneration: Int)
        fun onLive2DFailed(reason: String)
    }

    var listener: Listener? = null

    /** Model to load. Overridable so callers are not hard-wired to one asset and tests can force a miss. */
    var modelDirectory: String = CubismRuntimeOwner.DEFAULT_MODEL_DIR
    var modelJson: String = CubismRuntimeOwner.DEFAULT_MODEL_JSON
    /** Opaque stage colour avoids platform-specific transparent SurfaceView composition. */
    @Volatile var clearColorArgb: Int = Color.TRANSPARENT

    private val runtime: AvatarRuntime = when (runtimeBackend) {
        RuntimeBackend.OURS -> CubismRuntimeOwner(context.applicationContext)
        RuntimeBackend.OFFICIAL -> OfficialOracleRuntime(context.applicationContext)
    }
    private val releaseLatch = CountDownLatch(1)

    @Volatile private var releaseRequested = false
    @Volatile private var released = false
    @Volatile private var generations = 0
    @Volatile private var surfaceWidth = 0
    @Volatile private var surfaceHeight = 0
    @Volatile private var pmaLogged = false
    @Volatile private var readBackLogged = false

    val runtimeFailure: String? get() = runtime.failureMessage

    /**
     * Live2D-independent rasterisation probe. Enabled only by instrumentation; it exists so a black frame
     * can be attributed to the host/context rather than to the Cubism path — a distinction no earlier
     * measurement could make.
     */
    private val plainShaderProbe = if (plainShaderProbeEnabled) PlainShaderProbe() else null

    /** Outcome of the last probe draw, or null when the probe is not enabled / has not run yet. */
    val plainShaderProbeResult: String? get() = plainShaderProbe?.lastResult
    val framesDrawn: Long get() = runtime.framesDrawn
    val surfaceGenerations: Int get() = generations
    val isReady: Boolean get() = runtime.isReady

    init {
        setEGLContextClientVersion(2)
        // Transparent-surface recipe adapted (pattern only, no source copied) from
        // marce1994/OpenClaw-Companion @ ee581192c9efb46210864652addc315eebe85028 (MIT): request an EGL
        // config WITH an alpha channel and mark the surface translucent, so the avatar composites over the
        // static avatar instead of punching an opaque hole. Without alpha the surface is opaque and a
        // transparent clear reads as solid black.
        //
        // O1a keeps this switchable because it is the one variable introduced during R1 that was never
        // independently controlled, while the harness that demonstrably produced pixels ran on defaults.
        if (hostMode == HostMode.PRODUCT) {
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            holder.setFormat(PixelFormat.TRANSLUCENT)
            setZOrderMediaOverlay(true)
        }
        // HARNESS_LIKE intentionally sets none of the above and keeps the platform defaults.
        // Forced false on purpose: it makes the harness exercise a real EGL context loss and rebuild,
        // which is the case that must not crash and must re-create the runtime shaders and textures.
        preserveEGLContextOnPause = false
        setRenderer(FrameRenderer())
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    /**
     * O1d: direct framebuffer evidence, read on the GL thread right after the draw call returns. PixelCopy
     * cannot distinguish "the model never rasterised" from "composition lost it"; this can.
     */
    private fun probeFramebuffer(width: Int, height: Int) {
        val buffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
        val pixels = ByteArray(width * height * 4)
        buffer.get(pixels)
        val histogram = HashMap<Int, Int>()
        var index = 0
        while (index < pixels.size) {
            val colour = ((pixels[index].toInt() and 0xFF) shl 24) or
                ((pixels[index + 1].toInt() and 0xFF) shl 16) or
                ((pixels[index + 2].toInt() and 0xFF) shl 8) or
                (pixels[index + 3].toInt() and 0xFF)
            histogram[colour] = (histogram[colour] ?: 0) + 1
            index += 4
        }
        val backgroundCount = histogram.maxByOrNull { it.value }?.value ?: 0
        Log.i(
            TAG,
            "GLREADPIXELS size=${width}x$height distinct=${histogram.size} " +
                "nonBackground=${pixels.size / 4 - backgroundCount} " +
                "glError=0x${Integer.toHexString(GLES20.glGetError())}",
        )
    }
    private inner class FrameRenderer : GLSurfaceView.Renderer {
        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            // Home can be closed before EGL finishes creating its first surface.
            // Never allocate a new model after the queued release has already completed.
            if (releaseRequested) return
            // Official R5 LAppDelegate.onSurfaceCreated: programs belong to an EGL context.
            if (CubismFramework.isStarted()) {
                CubismShaderAndroid.getInstance().releaseInvalidShaderProgram()
                CubismShaderAndroid.deleteInstance()
            }
            // A new EGL context means every previous GL object is gone. Rebuild instead of reusing, which
            // is also what proves the shaders are loaded again after a context loss.
            if (runtime.isReady) runtime.release()
            val ready = runtime.initialize(modelDirectory, modelJson)
            if (ready) generations++
            if (!ready) post { listener?.onLive2DFailed(runtime.failureMessage ?: "unknown Live2D failure") }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            // O1c / C1: the official Sample sets the viewport explicitly in LAppDelegate.onSurfaceChanged.
            // Neither our runtime nor the oracle was doing it, and CubismRendererAndroid.doDrawModel()
            // reads GL_VIEWPORT (for the mask path) and relies on it for the draw itself — a wrong or
            // zero-sized viewport makes everything invisible with no GL error.
            GLES20.glViewport(0, 0, width, height)
            surfaceWidth = width
            surfaceHeight = height
            runtime.resize(width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            if (releaseRequested) {
                if (!released) {
                    runtime.release()
                    plainShaderProbe?.release()
                    released = true
                    releaseLatch.countDown()
                }
                return
            }
            // O1a varies the clear colour with the host mode: the known-good harness clears to opaque
            // white, the product overlay clears transparently.
            if (hostMode == HostMode.HARNESS_LIKE) {
                GLES20.glClearColor(1.0f, 1.0f, 1.0f, 1.0f)
            } else {
                val color = clearColorArgb
                GLES20.glClearColor(Color.red(color) / 255f, Color.green(color) / 255f,
                    Color.blue(color) / 255f, Color.alpha(color) / 255f)
            }
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            if (!runtime.isReady) return
            // Defensive re-assert of the viewport (see onSurfaceChanged).
            GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
            // The sample brackets every frame with the offscreen manager's begin/end process calls; the
            // Cubism 5 renderer expects the offscreen render targets to be in the in-frame state.
            // Official Sample alpha contract, part 3: LAppView.preModelDraw() sets the model blend
            // function every frame. CubismShaderAndroid also sets it per drawable blend mode via
            // glBlendFuncSeparate, but the app-side default is ours to set.
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            if (!pmaLogged) {
                pmaLogged = true
                Log.i(
                    TAG,
                    "PMA_CONTRACT rendererPremultiplied=${runtime.premultipliedAlpha} " +
                        "blendSrc=GL_ONE(${GLES20.GL_ONE}) " +
                        "blendDst=GL_ONE_MINUS_SRC_ALPHA(0x${Integer.toHexString(GLES20.GL_ONE_MINUS_SRC_ALPHA)}) " +
                        "glError=0x${Integer.toHexString(GLES20.glGetError())}",
                )
            }
            // Defensive: draw into the window framebuffer explicitly rather than relying on whatever the
            // previous stage left bound. Measured (O1d-2): for this model the render-target path is NOT
            // taken at all (blendModeEnabled=false, modelRenderTargets=0), so this is hygiene, not a fix.
            // The incomplete-mipmap fix belongs in CubismTextureManager, not this framebuffer binding.
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
            val offscreen = CubismOffscreenManagerAndroid.getInstance()
            offscreen.beginFrameProcess()
            try {
                runtime.update()
                runtime.draw()
                if (runtime.framesDrawn == 1L) post { listener?.onLive2DReady(generations) }
                // Runs last so it owns the final state of the frame. It uses no Cubism type at all.
                // Runs every frame on purpose: the host clears the framebuffer each frame, so a one-shot
                // probe would be wiped before the test's PixelCopy ever executes.
                plainShaderProbe?.let { probe ->
                    if (surfaceWidth > 0 && surfaceHeight > 0) {
                        probe.draw(surfaceWidth, surfaceHeight)
                    }
                }
                if (!readBackLogged && surfaceWidth > 0 && surfaceHeight > 0) {
                    readBackLogged = true
                    probeFramebuffer(surfaceWidth, surfaceHeight)
                }
            } finally {
                offscreen.endFrameProcess()
            }
        }
    }

    /**
     * Releases the runtime on the GL thread. Called from the View's own detach path, so the GL side of
     * the runtime cannot outlive the View. The host must call this BEFORE onPause while EGL is current.
     * A timeout is logged; it must never fall back to racing GL/native deletion on the UI thread.
     */
    fun releaseRuntime(timeoutMillis: Long = 3000L) {
        if (released) return
        releaseRequested = true
        queueEvent {
            if (!released) {
                runtime.release()
                plainShaderProbe?.release()
                released = true
                releaseLatch.countDown()
            }
        }
        requestRender()
        val drained = runCatching { releaseLatch.await(timeoutMillis, TimeUnit.MILLISECONDS) }
            .getOrDefault(false)
        if (!drained) {
            // Never race the renderer by deleting its native model or GL textures on the UI thread.
            Log.w(TAG, "GL release pending; UI-thread native/GL deletion refused")
        }
        listener = null
    }

    override fun onDetachedFromWindow() {
        releaseRuntime()
        super.onDetachedFromWindow()
    }
    private companion object {
        const val TAG = CubismRuntimeOwner.TAG
    }
}

