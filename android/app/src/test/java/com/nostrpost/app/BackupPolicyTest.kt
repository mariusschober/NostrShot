package com.nostrpost.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * NOSTRSHOT-38: the app holds a private key and never exports user data through Android backup.
 * Keystore-wrapped secrets cannot be restored on another device anyway, so allowing backup only
 * creates ambiguity and future leak risk.
 */
class BackupPolicyTest {

    private fun manifest(): File =
        listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml"))
            .firstOrNull { it.isFile }
            ?: error("manifest not found (cwd=${File(".").absolutePath})")

    @Test
    fun backupsAreDisabled() {
        val xml = manifest().readText()
        assertTrue(xml.contains("android:allowBackup=\"false\""))
        assertFalse(xml.contains("android:allowBackup=\"true\""))
    }

    @Test
    fun extractionRulesStillExcludeSecretAndPreferences() {
        val rules = listOf(
            File("src/main/res/xml/data_extraction_rules.xml"),
            File("app/src/main/res/xml/data_extraction_rules.xml")
        ).firstOrNull { it.isFile } ?: error("data extraction rules missing")
        val xml = rules.readText()
        assertTrue(xml.contains("nostr_secret.enc"))
        assertTrue(xml.contains("datastore"))
    }
}
