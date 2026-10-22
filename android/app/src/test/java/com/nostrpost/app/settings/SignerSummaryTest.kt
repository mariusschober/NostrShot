package com.nostrpost.app.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** NOSTRSHOT-25: never claim "Amber detected" for arbitrary NIP-55 signers. */
class SignerSummaryTest {

    @Test
    fun usesActualSignerLabel() {
        val summary = signerSummary(listOf("Nostr Signer"))
        assertTrue(summary.contains("Nostr Signer"))
        assertFalse(summary.contains("Amber"))
    }

    @Test
    fun neutralWhenLabelUnknown() {
        val summary = signerSummary(emptyList())
        assertTrue(summary.contains("NIP-55 signer detected"))
        assertFalse(summary.contains("Amber"))
    }

    @Test
    fun blankLabelFallsBackToNeutralText() {
        val summary = signerSummary(listOf("  "))
        assertTrue(summary.contains("NIP-55 signer detected"))
        assertFalse(summary.contains(":"))
    }
}
