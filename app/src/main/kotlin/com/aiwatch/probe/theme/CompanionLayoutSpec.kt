package com.aiwatch.probe.theme

/** Geometry from the measured, inset-free Home window. Never uses device identity. */
data class CompanionLayoutSpec(
    val contentWidthDp: Int,
    val edgeDp: Int,
    val transcriptHeightDp: Int,
    val pttWidthDp: Int,
    val messageCount: Int,
) {
    companion object {
        fun forWindow(widthDp: Int, heightDp: Int): CompanionLayoutSpec {
            val content = widthDp.coerceIn(0, 480)
            val compact = heightDp < 400
            val edge = if (content < 300) 10 else 20
            return CompanionLayoutSpec(content, edge,
                // Preserve the existing 60dp stage even while system bars are visible at startup.
                // Controls (48 + 48), gaps (14), then stage (60); transcript remains scrollable.
                if (compact) (heightDp - 170).coerceIn(48, 64)
                else (heightDp * .28f).toInt().coerceIn(128, 240),
                (content - edge * 2).coerceIn(0, 320),
                if (compact) 4 else 8)
        }
    }
}
