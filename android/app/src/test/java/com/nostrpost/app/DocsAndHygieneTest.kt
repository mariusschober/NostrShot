package com.nostrpost.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** NOSTRSHOT-36: guard the docs/claims and generated-file hygiene that used to drift. */
class DocsAndHygieneTest {

    private fun rootFile(path: String): File {
        val candidates = listOf(File("../../$path"), File("../$path"), File(path))
        return candidates.firstOrNull { it.isFile }
            ?: error("missing $path (cwd=${File(".").absolutePath})")
    }

    @Test
    fun androidGitignoreCoversBuildAndSigningMaterial() {
        val ignore = rootFile("android/.gitignore").readText()
        listOf(".gradle/", "build/", "local.properties", "*.jks", "*.keystore").forEach {
            assertTrue("missing $it", ignore.contains(it))
        }
    }

    @Test
    fun rootLicenseExists() {
        val license = rootFile("LICENSE").readText()
        assertTrue(license.contains("MIT License"))
        assertTrue(license.contains("WITHOUT WARRANTY"))
    }

    @Test
    fun docsDoNotRecommendMissingXcodeProject() {
        val readme = rootFile("README.md").readText()
        assertFalse(readme.contains("xcodeproj"))
        assertTrue(readme.contains("swift run"))
    }

    @Test
    fun securityDocClaimsMatchImplementation() {
        val security = rootFile("android/SECURITY.md").readText()
        assertTrue(security.contains("allowBackup=\"false\""))
        assertTrue(security.contains("exact `created_at`"))
        assertTrue(security.contains("never** creates or deletes the wrapping key"))
    }
}
