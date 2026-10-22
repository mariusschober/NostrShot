package com.nostrpost.app.security

import org.junit.Assert.assertEquals
import org.junit.Test

/** NOSTRSHOT-13: the user-visible protection claim must reflect the actual key. */
class ProtectionLevelTest {

    @Test
    fun strongBoxIsReportedAsStrongBox() {
        assertEquals(
            "StrongBox",
            protectionLevelLabel(insideSecureHardware = true, strongBox = true)
        )
    }

    @Test
    fun teeIsReportedAsHardwareBacked() {
        assertEquals(
            "Hardware-backed",
            protectionLevelLabel(insideSecureHardware = true, strongBox = false)
        )
    }

    @Test
    fun softwareKeyIsNeverReportedAsHardware() {
        assertEquals(
            "Software",
            protectionLevelLabel(insideSecureHardware = false, strongBox = false)
        )
    }
}
