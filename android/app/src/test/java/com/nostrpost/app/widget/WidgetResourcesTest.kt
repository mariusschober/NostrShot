package com.nostrpost.app.widget

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** NOSTRSHOT-23: real adaptive launcher icon and a non-clipped widget preview. */
class WidgetResourcesTest {

    private fun file(path: String): File =
        listOf(File(path), File("app/$path")).firstOrNull { it.isFile }
            ?: error("missing $path (cwd=${File(".").absolutePath})")

    @Test
    fun launcherUsesAdaptiveIconWithMonochromeLayer() {
        val manifest = file("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("@mipmap/ic_launcher"))
        val icon = file("src/main/res/mipmap-anydpi-v26/ic_launcher.xml").readText()
        assertTrue(icon.contains("<adaptive-icon"))
        assertTrue(icon.contains("<monochrome"))
        assertTrue(icon.contains("ic_launcher_foreground"))
    }

    @Test
    fun widgetUsesPreviewLayoutAndFitsOneCell() {
        val widget = file("src/main/res/xml/widget_quick_post.xml").readText()
        assertTrue(widget.contains("previewLayout"))
        assertTrue(widget.contains("targetCellWidth=\"1\""))
        assertTrue(widget.contains("minHeight=\"56dp\""))
        val preview = file("src/main/res/layout/widget_preview.xml").readText()
        assertTrue(preview.contains("Post"))
    }
}
