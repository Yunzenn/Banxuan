package com.aiwatch.live2d

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import com.live2d.sdk.cubism.framework.CubismModelSettingJson
import com.live2d.sdk.cubism.framework.math.CubismMatrix44
import com.live2d.sdk.cubism.framework.math.CubismModelMatrix
import com.live2d.sdk.cubism.framework.model.CubismMoc
import com.live2d.sdk.cubism.framework.model.CubismModel
import com.live2d.sdk.cubism.framework.rendering.CubismRenderer
import com.live2d.sdk.cubism.framework.rendering.android.CubismOffscreenManagerAndroid
import com.live2d.sdk.cubism.framework.rendering.android.CubismRendererAndroid
import com.live2d.sdk.cubism.framework.effect.CubismEyeBlink
import com.live2d.sdk.cubism.framework.motion.CubismEyeBlinkUpdater
import com.live2d.sdk.cubism.framework.motion.CubismPhysicsUpdater
import com.live2d.sdk.cubism.framework.motion.CubismUpdateScheduler
import com.live2d.sdk.cubism.framework.physics.CubismPhysics

/**
 * Owns one Live2D runtime: moc, model, renderer, GL textures and the MVP matrix.
 *
 * Lifetime is explicit and per-instance. There is deliberately no static instance and no Activity
 * reference anywhere in this class — the official sample's `LAppDelegate` / `LAppPal` singletons are what
 * made its renderer state leak across Activity recreation, and the verification harness hit exactly that
 * (see `evidence/tests/cubism_runtime_smoke/README.md`). Here the owning View constructs the owner, and
 * releases it on detach, so the GL side of the runtime dies with the View.
 *
 * GL resources are created and destroyed on the GL thread only. GPU calls from elsewhere are invalid;
 * [Live2DAvatarView] therefore performs release from inside `onDrawFrame`.
 */
class CubismRuntimeOwner(private val appContext: Context) : AvatarRuntime {

    enum class State { NEW, READY, FAILED, RELEASED }

    @Volatile var state: State = State.NEW
        private set

    /** Human-readable reason when [state] is [State.FAILED]; surfaced so Home can fall back visibly. */
    var failure: String? = null
        private set

    /** Frames drawn since the last surface creation; used by the GL-recreation assertion. */
    @Volatile private var frames = 0L
    override val framesDrawn: Long get() = frames

    override val isReady: Boolean get() = state == State.READY
    override val premultipliedAlpha: Boolean get() = renderer?.isPremultipliedAlpha ?: false
    override val failureMessage: String? get() = failure

    /** How many surface (re)creations this owner has initialised for. */
    var surfaceGenerations: Int = 0
        private set

    private var moc: CubismMoc? = null
    private var model: CubismModel? = null
    private var renderer: CubismRendererAndroid? = null
    private var modelMatrix: CubismModelMatrix? = null
    private val textures = CubismTextureManager()
    private val projection = CubismMatrix44.create()
    private var viewWidth = 0
    private var viewHeight = 0
    private var rendererWidth = 0
    private var rendererHeight = 0
    private var scheduler = CubismUpdateScheduler()
    private var lastFrameNanos = 0L

    /**
     * Loads the model and builds its GL resources. Must be called on the GL thread. Any failure is
     * contained: it is recorded in [failure], all partially created resources are released, and the
     * method returns false so the caller can fall back to the static avatar instead of showing a blank
     * or crashing Home.
     */
    override fun initialize(modelDir: String, modelJson: String): Boolean {
        if (state == State.READY) return true
        release()
        return try {
            val assets = appContext.assets
            CubismFrameworkBootstrap.ensureStarted(appContext)
            val setting = CubismModelSettingJson(CubismAssets.readRequired(assets, modelDir + modelJson))
            val createdMoc = CubismMoc.create(CubismAssets.readRequired(assets, modelDir + setting.modelFileName))
            moc = createdMoc
            val createdModel = createdMoc.createModel()
            model = createdModel
            // Official R5 LAppModel setup/update ordering, not a second animation engine.
            if (setting.eyeBlinkParameterCount > 0) {
                scheduler.addUpdatableList(CubismEyeBlinkUpdater({ false }, CubismEyeBlink.create(setting)))
            }
            if (setting.physicsFileName.isNotEmpty()) {
                val physics = CubismPhysics.create(CubismAssets.readRequired(assets, modelDir + setting.physicsFileName))
                scheduler.addUpdatableList(CubismPhysicsUpdater(physics))
            }
            scheduler.sortUpdatableList()
            createdModel.saveParameters()

            // Textures are uploaded now, on this GL thread, by the dedicated texture manager. The renderer
            // is created lazily in ensureRenderer() once the surface size is known, and binds them there.
            textures.load(assets, modelDir, setting)
            modelMatrix = CubismModelMatrix.create(createdModel.canvasWidth, createdModel.canvasHeight)
            surfaceGenerations++
            frames = 0L
            state = State.READY
            failure = null
            Log.i(TAG, "Live2D ready: $modelDir$modelJson textures=${textures.textureCount} " +
                "generation=$surfaceGenerations")
            true
        } catch (t: Throwable) {
            failure = t.message ?: t::class.java.simpleName
            Log.e(TAG, "Live2D initialisation failed; caller must fall back to the static avatar", t)
            release()
            state = State.FAILED
            false
        }
    }

    override fun resize(width: Int, height: Int) {
        viewWidth = width
        viewHeight = height
    }

    /**
     * Official blink/physics updaters only. Motion, expression and lip sync are not synthesized.
     */
    override fun update() {
        val current = model ?: return
        current.loadParameters()
        current.saveParameters()
        val now = System.nanoTime()
        val delta = if (lastFrameNanos == 0L) 0f else ((now - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.1f)
        lastFrameNanos = now
        scheduler.onLateUpdate(current, delta)
        current.update()
    }

    /** Draws one frame. Must be called on the GL thread. */
    override fun draw() {
        val currentModel = model ?: return
        if (viewWidth <= 0 || viewHeight <= 0) return
        val currentRenderer = ensureRenderer(viewWidth, viewHeight)

        val aspectRatio = viewWidth.toFloat() / viewHeight.toFloat()
        val displayRatio = viewHeight.toFloat() / viewWidth.toFloat()
        val canvasRatio = currentModel.canvasHeight / currentModel.canvasWidth

        projection.loadIdentity()
        val matrix = modelMatrix
        if (canvasRatio < displayRatio) {
            matrix?.setWidth(2.0f)
            projection.scale(1.0f, aspectRatio)
        } else {
            matrix?.setHeight(2.0f)
            projection.scale(1.0f / aspectRatio, 1.0f)
        }
        if (matrix != null) {
            CubismMatrix44.multiply(matrix.array, projection.array, projection.array)
        }
        currentRenderer.setMvpMatrix(projection)
        currentRenderer.setRenderTargetSize(viewWidth, viewHeight)
        if (frames == 0L) {
            logFirstFrameDiagnostics(currentModel, currentRenderer)
        }
        currentRenderer.drawModel()
        if (frames == 0L) {
            // drawMeshAndroid() also silently skips when the shader program is 0, so the program binding
            // is the last branch that can produce "bound textures, visible drawables, no GL error, blank".
            val program = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_CURRENT_PROGRAM, program, 0)
            Log.i(
                TAG,
                "diag afterDraw currentProgram=${program[0]} " +
                    "glError=0x${Integer.toHexString(GLES20.glGetError())}",
            )
        }
        frames++
    }

    /**
     * First-frame diagnostics only.
     *
     * `CubismRendererAndroid.drawMeshAndroid()` opens with
     * `if (textures.get(model.getDrawableTextureIndex(index)) == null) return;` — a drawable whose
     * texture is not bound is skipped **silently, with no GL error**. A blank surface with a clean error
     * queue is therefore most likely an unbound-texture problem, not a mask problem, so this reports the
     * texture/graphics state that decides it instead of guessing.
     */
    private fun logFirstFrameDiagnostics(model: CubismModel, renderer: CubismRendererAndroid) {
        val bound = renderer.boundTextures
        val maskCounts = model.drawableMaskCounts
        val drawableCount = model.drawableCount
        var visible = 0
        val sample = StringBuilder()
        for (index in 0 until drawableCount) {
            if (!model.getDrawableDynamicFlagIsVisible(index)) continue
            visible++
            if (visible > 4) continue
            val textureIndex = model.getDrawableTextureIndex(index)
            sample.append('[').append(index)
                .append(" tex=").append(textureIndex)
                .append(" bound=").append(bound[textureIndex] != null)
                .append(" vtx=").append(model.getDrawableVertexCount(index))
                .append(" idx=").append(model.getDrawableVertexIndexCount(index))
                .append(" masks=").append(if (index < maskCounts.size) maskCounts[index] else -1)
                .append(" op=").append(String.format(java.util.Locale.US, "%.2f", model.getDrawableOpacity(index)))
                .append("] ")
        }
        Log.i(
            TAG,
            "diag drawables=$drawableCount visible=$visible modelOpacity=${model.modelOpacity} " +
                textures.describeBinding(renderer, textures.textureCount) + " sample=$sample",
        )
    }

    /**
     * Creates or recreates the renderer for the current surface size.
     *
     * The size is not cosmetic: `CubismRendererAndroid.create(width, height)` derives its clipping mask
     * buffers from it, so a renderer created at 1x1 makes a masked model (Haru has masks) draw absolutely
     * nothing while still reporting success. The sample passes the real window size at creation; this
     * defers creation until the first draw, when the surface size is actually known, and rebuilds on
     * resize so an EGL context recreation cannot leave a stale-sized renderer behind.
     */
    private fun ensureRenderer(width: Int, height: Int): CubismRendererAndroid {
        renderer?.let { existing ->
            if (rendererWidth == width && rendererHeight == height) return existing
            runCatching { existing.close() }
        }
        val currentModel = model ?: throw IllegalStateException("renderer requested before the model loaded")
        val created = CubismRendererAndroid.create(width, height) as? CubismRendererAndroid
            ?: throw IllegalStateException("CubismRendererAndroid.create did not return an Android renderer")
        created.initialize(currentModel)
        created.isPremultipliedAlpha(true)
        textures.bindAll(created)
        renderer = created
        rendererWidth = width
        rendererHeight = height
        Log.i(
            TAG,
            "renderer created ${width}x$height textures=${textures.textureCount} " +
                textures.describeBinding(created, textures.textureCount),
        )
        return created
    }

    /** Frees every resource this owner created. Idempotent; safe to call more than once. */
    override fun release() {
        renderer?.let { runCatching { it.close() } }
        renderer = null
        val currentMoc = moc
        model?.let { current -> currentMoc?.let { runCatching { it.deleteModel(current) } } }
        model = null
        currentMoc?.let { runCatching { it.delete() } }
        moc = null
        textures.release()
        modelMatrix = null
        rendererWidth = 0
        rendererHeight = 0
        frames = 0L
        scheduler = CubismUpdateScheduler()
        lastFrameNanos = 0L
        state = State.RELEASED
    }

    companion object {
        const val TAG = "CubismOwner"

        /** Models cleared for local testing by the user. Assets stay in the ignored SDK tree. */
        const val DEFAULT_MODEL_DIR = "Haru/"
        const val DEFAULT_MODEL_JSON = "Haru.model3.json"

        /** Alternative cleared model, used to prove the owner is not hard-wired to one asset. */
        const val ALT_MODEL_DIR = "Hiyori/"
        const val ALT_MODEL_JSON = "Hiyori.model3.json"
    }
}

/** Convenience view of the renderer type without leaking the framework type into callers. */
internal val CubismRenderer.androidOrNull: CubismRendererAndroid? get() = this as? CubismRendererAndroid
