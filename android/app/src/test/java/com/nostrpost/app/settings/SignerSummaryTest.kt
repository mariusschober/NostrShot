package com.nostrpost.app.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** NOSTRSHOT-25: never claim "Amber detected" for arbitrary NIP-55 signers. */
class SignerSummaryTest {

    @Test
    fun usesActualSignerLabel() {
        val label = firstSignerLabel(listOf("Nostr Signer"))
        assertTrue(label.contains("Nostr Signer"))
        assertFalse(label.contains("Amber"))
    }

    @Test
    fun neutralWhenLabelUnknown() {
        assertTrue(firstSignerLabel(emptyList()).isEmpty())
    }

    @Test
    fun blankLabelFallsBackToEmpty() {
        assertTrue(firstSignerLabel(listOf("  ")).isEmpty())
    }
}
