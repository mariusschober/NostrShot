package com.nostrpost.app

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * NOSTRSHOT-01 regression guard.
 *
 * The exported MainActivity must never accept a publish command or a private key from an
 * Intent. Any app can send an explicit Intent to an exported activity, so automation extras
 * such as `auto_content` / `auto_import_nsec` are a remote-control backdoor. This test fails
 * if such hooks are reintroduced anywhere in production sources.
 */
class IntentAutomationSecurityTest {

    private fun mainSourceDirs(): List<File> {
        val roots = listOf(File("src/main"), File("app/src/main"))
        val found = roots.filter { it.isDirectory }
        if (found.isEmpty()) {
            fail("Could not locate src/main from ${File(".").absolutePath}")
        }
        return found
    }

    @Test
    fun `production sources contain no intent-driven publish or key import extras`() {
        val forbidden = listOf("auto_content", "auto_import_nsec")
        val offenders = mutableListOf<String>()
        mainSourceDirs().forEach { dir ->
            dir.walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                .forEach { file ->
                    val text = file.readText()
                    forbidden.filter { text.contains(it) }.forEach { needle ->
                        offenders += "${file.path}: $needle"
                    }
                }
        }
        assertTrue(
            "Intent-driven automation hooks must not exist in production sources: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun `manifest exports only documented components`() {
        val manifest = mainSourceDirs()
            .map { File(it, "AndroidManifest.xml") }
            .firstOrNull { it.isFile }
            ?: fail("Could not locate AndroidManifest.xml").let { return }
        val text = manifest.readText()
        // MainActivity stays exported for the launcher/share target, but there must be no
        // additional exported activity that could be used to bypass the composer.
        val exportedActivities = Regex("<activity\\b[^>]*android:exported=\"true\"")
            .findAll(text)
            .count()
        assertTrue("Only MainActivity should be an exported activity", exportedActivities <= 1)
    }
}
