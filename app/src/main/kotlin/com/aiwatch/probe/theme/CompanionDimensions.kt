package com.aiwatch.probe.theme

/**
 * Fixed Daylight design tokens; runtime geometry lives in CompanionLayoutSpec.
 *
 * At the explicitly selected 320dpi test density the canvas is 205x251dp. Physical screen PPI does
 * not determine Android logical density. CD12Max metrics must still be measured independently.
 *
 * The target constants describe a permanent regression fixture, not a production layout constraint.
 * Fonts remain in sp and are never reduced to make a small window pass.
 */
object CompanionDimensions {
    const val targetWidthDp = 205
    const val targetHeightDp = 251
    const val targetDensityDpi = 320

    const val edgeMarginDp = 10
    const val topBarHeightDp = 48

    /** Character stage: a third of the panel, so it stays the visual centre without crowding the chat. */
    const val stageHeightDp = 84
    const val stageRadiusDp = 16

    const val bubbleMaxWidthFraction = 0.78f
    const val bubblePaddingHorizontalDp = 9
    const val bubblePaddingVerticalDp = 6
    const val bubbleCornerLargeDp = 12
    const val bubbleCornerSmallDp = 3
    const val bubbleTextSp = 12f
    const val bubbleLineSpacingMultiplier = 1.12f

    const val pttHeightDp = 48
    const val pttRadiusDp = 22
    const val pttTextSp = 12f
    const val pttPressedScale = 0.97f

    /** Home shows only the tail of the transcript; older messages scroll. */
    const val visibleMessageCount = 4
}
