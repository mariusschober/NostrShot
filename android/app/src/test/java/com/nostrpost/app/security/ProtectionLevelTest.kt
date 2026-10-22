package com.nostrpost.app.security

import android.security.keystore.KeyProperties
import org.junit.Assert.assertEquals
import org.junit.Test

/** NOSTRSHOT-13: the user-visible protection claim must reflect the actual key. */
class ProtectionLevelTest {

    @Test
    fun strongBoxIsReportedAsStrongBox() {
        assertEquals(
            "StrongBox",
            protectionLevelLabel(insideSecureHardware = true, securityLevel = KeyProperties.SECURITY_LEVEL_STRONGBOX)
        )
    }

    @Test
    fun teeIsReportedAsHardwareBacked() {
        assertEquals(
            "Hardware-backed",
            protectionLevelLabel(insideSecureHardware = true, securityLevel = KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT)
        )
    }

    @Test
    fun softwareKeyIsNeverReportedAsHardware() {
        assertEquals(
            "Software",
            protectionLevelLabel(insideSecureHardware = false, securityLevel = KeyProperties.SECURITY_LEVEL_SOFTWARE)
        )
    }
}
