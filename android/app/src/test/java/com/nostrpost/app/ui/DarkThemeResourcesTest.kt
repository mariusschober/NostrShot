package com.nostrpost.app.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** NOSTRSHOT-22: dark mode must not flash a white window/splash on cold start. */
class DarkThemeResourcesTest {

    private fun resource(path: String): File {
        val candidates = listOf(File(path), File("app/$path"))
        return candidates.firstOrNull { it.isFile }
            ?: error("Missing resource $path (cwd=${File(".").absolutePath})")
    }

    @Test
    fun nightThemeUsesDarkBackgroundAndLightBars() {
        val xml = resource("src/main/res/values-night/themes.xml").readText()
        assertTrue(xml.contains("window_background_dark"))
        assertTrue(xml.contains("windowLightStatusBar\">false"))
        assertTrue(xml.contains("windowLightNavigationBar\">false"))
        assertTrue(xml.contains("Theme.NostrPost.Splash"))
    }

    @Test
    fun splashBackgroundMatchesThemeInBothModes() {
        val light = resource("src/main/res/values/themes.xml").readText()
        val dark = resource("src/main/res/values-night/themes.xml").readText()
        assertTrue(light.contains("windowSplashScreenBackground"))
        assertTrue(dark.contains("windowSplashScreenBackground"))
        assertTrue(light.contains("postSplashScreenTheme"))
        assertTrue(dark.contains("postSplashScreenTheme"))
    }
}
