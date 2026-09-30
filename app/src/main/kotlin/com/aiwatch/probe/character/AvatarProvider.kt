package com.aiwatch.probe.character

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Log

/**
 * Resolves the art shown on the character stage.
 *
 * Splitting this out is what keeps the customer's Live2D assets, and any future animated avatar, from
 * becoming a hard dependency of the Home screen: the stage only ever asks for a [Drawable].
 */
interface AvatarProvider {
    /** Returns the stage art, or null when nothing usable is available and the placeholder should stay. */
    fun stageDrawable(context: Context): Drawable?

    fun describe(): String
}

/**
 * Loads a still from `assets/`. The directory is gitignored, so a fresh clone has no asset and the view
 * uses the bundled original Daylight illustration instead of failing.
 */
class AssetAvatarProvider(private val assetPath: String) : AvatarProvider {

    override fun stageDrawable(context: Context): Drawable? = runCatching {
        context.assets.open(assetPath).use { stream ->
            BitmapFactory.decodeStream(stream)?.let { BitmapDrawable(context.resources, it) }
        }
    }.onFailure { Log.i(TAG, "character asset '$assetPath' unavailable: ${it.message}") }
        .getOrNull() ?: context.getDrawable(com.aiwatch.probe.R.drawable.companion_daylight)

    override fun describe(): String = "asset:$assetPath"

    private companion object { const val TAG = "AvatarProvider" }
}

/** Used when no asset is present; the stage draws its own designed placeholder. */
class PlaceholderAvatarProvider : AvatarProvider {
    override fun stageDrawable(context: Context): Drawable? = null
    override fun describe(): String = "placeholder"
}

object AvatarProviders {
    fun forSource(source: AvatarSource): AvatarProvider = when (source) {
        is AvatarSource.StaticImage -> AssetAvatarProvider(source.assetPath)
    }
}
