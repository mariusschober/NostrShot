package com.nostrpost.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.util.WeakHashMap

/**
 * Reference-counted owner of [WindowManager.LayoutParams.FLAG_SECURE] for one window.
 *
 * A controller only clears the flag if it was the one that added it and no other holder is
 * still active. This lets several screens request a secure window without one screen clearing
 * protection that was already present or requested by another screen.
 */
class SecureWindowFlagController(
    private val hasSecureFlag: () -> Boolean,
    private val setSecureFlag: () -> Unit,
    private val clearSecureFlag: () -> Unit
) {
    private var holders = 0
    private var addedByUs = false

    fun acquire() {
        if (holders == 0) {
            addedByUs = !hasSecureFlag()
            if (addedByUs) setSecureFlag()
        }
        holders++
    }

    fun release() {
        if (holders == 0) return
        holders--
        if (holders == 0 && addedByUs) {
            clearSecureFlag()
            addedByUs = false
        }
    }
}

private val controllers = WeakHashMap<Window, SecureWindowFlagController>()

private fun controllerFor(window: Window): SecureWindowFlagController =
    synchronized(controllers) {
        controllers.getOrPut(window) {
            SecureWindowFlagController(
                hasSecureFlag = {
                    window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
                },
                setSecureFlag = { window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) },
                clearSecureFlag = { window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
            )
        }
    }

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Keeps the current window secure (screenshot/recents redaction) while this composable is in
 * the composition. Multiple screens may call this at once; the flag is released only when the
 * last caller leaves and only if this helper added it.
 */
@Composable
fun SecureWindow(enabled: Boolean = true) {
    val context = LocalContext.current
    val window = remember(context) { context.findActivity()?.window } ?: return
    val controller = remember(window) { controllerFor(window) }
    DisposableEffect(controller, enabled) {
        if (enabled) controller.acquire()
        onDispose { if (enabled) controller.release() }
    }
}
