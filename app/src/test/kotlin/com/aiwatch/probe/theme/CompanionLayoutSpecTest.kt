package com.aiwatch.probe.theme

import org.junit.Assert.*
import org.junit.Test

class CompanionLayoutSpecTest {
    @Test fun compactReservesControlsBeforeStage() {
        val spec = CompanionLayoutSpec.forWindow(205, 251)
        assertEquals(185, spec.pttWidthDp)
        assertEquals(64, spec.transcriptHeightDp)
        assertTrue(251 - 48 - 48 - 14 - spec.transcriptHeightDp > 0)
        val withBars = CompanionLayoutSpec.forWindow(205, 227)
        assertEquals(60, 227 - 48 - 48 - 14 - withBars.transcriptHeightDp)
    }
    @Test fun phoneGrowsTranscriptButCapsContentAndButton() {
        val spec = CompanionLayoutSpec.forWindow(411, 819)
        assertTrue(spec.transcriptHeightDp > 64)
        assertEquals(8, spec.messageCount)
        assertEquals(320, spec.pttWidthDp)
        assertEquals(480, CompanionLayoutSpec.forWindow(1000, 1000).contentWidthDp)
    }
}
