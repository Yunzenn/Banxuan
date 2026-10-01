package com.aiwatch.probe.character

/** Public build: no proprietary runtime, reflection, or native library loading. */
class HomeAvatarBinding(private val stage: AvatarStageView) {
    fun resume() = Unit
    fun pause() = Unit
    fun close() = Unit
}
