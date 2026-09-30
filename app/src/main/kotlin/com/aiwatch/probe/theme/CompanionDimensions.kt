package com.aiwatch.probe.theme

/**
 * Geometry for the 410x502@320dpi emulator baseline, not a measured target device.
 *
 * At the explicitly selected 320dpi test density the canvas is 205x251dp. Physical screen PPI does
 * not determine Android logical density. CD12Max metrics must still be measured independently.
 *
 * This file was originally written against a 410x502 dp assumption and the layout overflowed the panel
 * at the real density (the transcript and the push-to-talk capsule were pushed off-screen). Every value
 * below is derived from 205x251 dp. All sizes live here on purpose so that a density correction is a
 * one-file change.
 *
 * Daylight budget: header 48 + transcript 48 + PTT 48 + gaps/bottom 14 = 158dp.
 * The stage gets remaining height (93dp at the baseline), and grows on larger windows.
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
