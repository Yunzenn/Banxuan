package com.aiwatch.probe.theme

import android.graphics.Color

/**
 * Daylight palette: warm paper surfaces, readable slate ink and a restrained blue accent.
 *
 * Deliberately excluded, per the P0-1 brief: WeChat green, high-saturation pink, neon RGB, heavy
 * glassmorphism, and Material default purple.
 */
object CompanionColors {
    val background = Color.parseColor("#FAF8F5")
    val surface = Color.parseColor("#F0EDE8")
    val surfaceElevated = Color.parseColor("#E7EEF3")
    val primaryText = Color.parseColor("#303D4A")
    val secondaryText = Color.parseColor("#626D78")

    /** Accent used for the companion's own presence. */
    val companion = Color.parseColor("#476782")
    val listening = Color.parseColor("#32658A")
    val thinking = Color.parseColor("#765B83")
    val speaking = Color.parseColor("#476E61")

    val userBubble = Color.parseColor("#E3EDF5")
    val assistantBubble = Color.parseColor("#FFFFFF")

    /** Text on top of [userBubble]; the mint fill is light, so the label must be dark. */
    val onUserBubble = Color.parseColor("#303D4A")

    val hairline = Color.parseColor("#DCE3E8")

    /** Very low alpha glow behind the character stage; the stage must stay the visual centre. */
    val stageGlow = Color.parseColor("#EAF0F3")
}
