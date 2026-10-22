package com.nostrpost.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** NOSTRSHOT-37: visible Compose labels must come from resources, not inline literals. */
class LocalizationTest {

    private fun sourceDir(): File =
        listOf(File("src/main"), File("app/src/main")).firstOrNull { it.isDirectory }
            ?: error("src/main not found")

    @Test
    fun screensUseStringResources() {
        val screens = listOf(
            "java/com/nostrpost/app/composer/ComposerScreen.kt",
            "java/com/nostrpost/app/settings/SetupScreen.kt",
            "java/com/nostrpost/app/settings/ImportScreen.kt",
            "java/com/nostrpost/app/settings/SettingsScreen.kt",
            "java/com/nostrpost/app/settings/CreateIdentityScreen.kt",
            "java/com/nostrpost/app/settings/ConfirmIdentityScreen.kt"
        )
        screens.forEach { relative ->
            val file = File(sourceDir(), relative)
            assertTrue("missing $relative", file.isFile)
            val text = file.readText()
            assertTrue("$relative should use stringResource", text.contains("stringResource("))
            val hardcodedText = Regex("""Text\(\s*"[^"]+"""").findAll(text)
                .map { it.value }
                .filterNot { it.contains("\"✓\"") }
                .toList()
            assertTrue("$relative has hardcoded Text literals: $hardcodedText", hardcodedText.isEmpty())
        }
    }

    @Test
    fun englishStringsCoverCoreActions() {
        val strings = File(sourceDir(), "res/values/strings.xml").readText()
        listOf(
            "action_publish", "action_retry", "action_settings", "setup_title",
            "import_title", "settings_title", "confirm_title", "create_button"
        ).forEach { assertTrue("missing $it", strings.contains("name=\"$it\"")) }
        // A proof locale can be added later; the app is no longer restricted to English only.
        assertFalse(strings.contains("translatable=\"false\""))
    }
}
