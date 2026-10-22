package com.nostrpost.app.composer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** NOSTRSHOT-06: shares are merged, never truncated or silently replaced. */
class SharedTextTest {

    @Test
    fun emptyDraftTakesSharedText() {
        assertEquals("hello nostr", mergeSharedText("", "  hello nostr  "))
    }

    @Test
    fun existingDraftIsPreservedAndSeparated() {
        assertEquals("my draft\n\nshared", mergeSharedText("my draft\n", "shared"))
    }

    @Test
    fun longSharesAreNotTruncated() {
        val long = "x".repeat(2000)
        val merged = mergeSharedText("", long)
        assertEquals(long.length, merged.length)
        assertFalse(merged.contains("Trimmed"))
    }

    @Test
    fun blankShareLeavesDraftUntouched() {
        assertEquals("keep me", mergeSharedText("keep me", "   \n "))
    }
}
