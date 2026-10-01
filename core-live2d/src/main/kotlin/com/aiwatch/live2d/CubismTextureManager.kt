package com.aiwatch.live2d

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import com.live2d.sdk.cubism.framework.ICubismModelSetting
import com.live2d.sdk.cubism.framework.rendering.android.CubismRendererAndroid

/**
 * GL-thread ownership of the model's GL textures.
 *
 * This exists as its own type on purpose: [CubismAssets] stays pure I/O (AssetManager -> bytes), and all
 * GL state lives here. Mixing the two is what made the previous host hard to reason about.
 *
 * The upload sequence follows the pattern proven in `llz121517/mea-pet-mobile` (MIT), whose
 * `Live2dTextureManager` documents the trap that matters here: texture loading must use an
 * **Application-scoped** AssetManager, otherwise textures silently fail. That repo also uploads and then
 * rebinds through `renderer.bindTexture` after a GL context change, which is exactly what the Cubism 5
 * renderer needs because `drawMeshAndroid()` skips a drawable whose texture is not bound — silently, with
 * no GL error.
 *
 * Adapted as a pattern, not copied verbatim; no upstream source text is reproduced here. Upstream:
 * llz121517/mea-pet-mobile @ dc460e5d41cfe0592d841b19360b476af65d0538 (MIT).
 */
internal class CubismTextureManager {

    /** Decoded texture dimensions, recorded before upload so callers can report memory/latency. */
    class TextureStats(val name: String, val width: Int, val height: Int, val sourceBytes: Int) {
        /** Uncompressed RGBA size the GPU has to hold for this texture. */
        val decodedBytes: Long get() = width.toLong() * height.toLong() * 4L
    }

    private val glIds = ArrayList<Int>()
    private val stats = ArrayList<TextureStats>()

    val textureStats: List<TextureStats> get() = stats
    val textureCount: Int get() = glIds.size

    /**
     * Decodes and uploads every texture the model setting declares. Must run on the GL thread with the
     * target context current. Any failure throws so the owner can fall back rather than render nothing.
     */
    fun load(assets: AssetManager, modelDirectory: String, setting: ICubismModelSetting): List<TextureStats> {
        release()
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        for (index in 0 until setting.textureCount) {
            val path = modelDirectory + setting.getTextureFileName(index)
            val encoded = CubismAssets.readRequired(assets, path)
            stats.add(readStats(path, encoded))
            val bounds = stats.last()
            require(bounds.width > 0 && bounds.height > 0) { "Invalid texture bounds: $path" }
            val deviceLimit = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, deviceLimit, 0)
            require(deviceLimit[0] > 0) { "No GL texture capacity" }
            // Same strategy as Wanyu's Live2DRenderer: bounded decode for a small viewport.
            // Keep the original atlas untouched; normalized Cubism UV coordinates still apply.
            val limit = minOf(2048, deviceLimit[0])
            var sample = 1
            while ((maxOf(bounds.width, bounds.height) + sample - 1) / sample > limit) sample *= 2
            // Official Sample alpha contract, part 1: BitmapFactory.Options.inPremultiplied = true.
            // Uploading premultiplied pixels while telling the renderer otherwise is an invalid
            // combination — the flag feeds Cubism's shaderIndex/shader-variant selection, not just blending.
            val options = BitmapFactory.Options().apply {
                inPremultiplied = true
                inSampleSize = sample
            }
            val bitmap = BitmapFactory.decodeByteArray(encoded, 0, encoded.size, options)
                ?: throw IllegalStateException("texture '$path' could not be decoded")
            val premultiplied = bitmap.isPremultiplied
            val id = try {
                Log.i(TAG, "texture budget source=${bounds.width}x${bounds.height} upload=${bitmap.width}x${bitmap.height} max=$limit")
                upload(bitmap)
            } finally { bitmap.recycle() }
            glIds.add(id)
            Log.i(TAG, "texture[$index] $path ${stats.last().width}x${stats.last().height} " +
                "glId=$id premultiplied=$premultiplied decodedBytes=${stats.last().decodedBytes}")
        }
        return stats
    }

    /** Binds every uploaded texture into the renderer, from GL texture unit 0. */
    fun bindAll(renderer: CubismRendererAndroid) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        glIds.forEachIndexed { index, id -> renderer.bindTexture(index, id) }
    }

    /**
     * The attribution assertion required by P2B-1A-R1: every model texture index the renderer will look up
     * must be present in `renderer.getBoundTextures()`, and every GL id must still be a live texture in the
     * CURRENT context (`glIsTexture`). A false here means the uploads belong to a dead/replaced context,
     * which is the one failure mode that produces visible drawables, bound-looking state and no GL error.
     */
    fun describeBinding(renderer: CubismRendererAndroid, expectedCount: Int): String {
        val bound = renderer.boundTextures
        val missing = (0 until expectedCount).filter { bound[it] == null }
        val invalid = glIds.filterIndexed { _, id -> !GLES20.glIsTexture(id) }
        return "uploaded=${glIds.size} glIds=$glIds bound=$bound missingIndices=$missing " +
            "invalidGlIds=$invalid allValid=${invalid.isEmpty() && missing.isEmpty()}"
    }

    fun release() {
        if (glIds.isNotEmpty()) {
            val ids = glIds.toIntArray()
            runCatching { GLES20.glDeleteTextures(ids.size, ids, 0) }
            glIds.clear()
        }
        stats.clear()
    }

    private fun upload(bitmap: Bitmap): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        if (ids[0] == 0) throw IllegalStateException("glGenTextures returned 0")
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        // R5 CubismShaderAndroid.setUpTexture resets MIN_FILTER to LINEAR_MIPMAP_LINEAR
        // on EVERY draw. A base-level-only texture is incomplete and samples black, even
        // when glIsTexture, binding, shaders and glGetError all look healthy. The official
        // LAppTextureManager.createTextureFromPngFile generates this chain after upload.
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) {
            GLES20.glDeleteTextures(1, ids, 0)
            error("Texture upload failed: GL 0x${Integer.toHexString(error)}")
        }
        return ids[0]
    }

    /** Bounds-only pass: records the real texture dimensions without decoding pixels. */
    private fun readStats(name: String, encoded: ByteArray): TextureStats {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(encoded, 0, encoded.size, options)
        return TextureStats(name, options.outWidth, options.outHeight, encoded.size)
    }

    private companion object {
        const val TAG = CubismRuntimeOwner.TAG
    }
}
