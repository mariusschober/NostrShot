package com.nostrpost.app

/**
 * Route names for [MainActivity]'s single-activity navigation.
 *
 * Only the current route is saved across configuration changes. Some routes additionally need
 * in-memory state (the imported key waiting for confirmation), which is intentionally never
 * written to saved instance state.
 */
object AppRoute {
    const val COMPOSER = "composer"
    const val SETUP = "setup"
    const val IMPORT = "import"
    const val CONFIRM = "confirm"
    const val CREATE = "create"
    const val SETTINGS = "settings"

    /**
     * Returns the route that can actually be shown for [requested].
     *
     * "confirm" requires the pending imported identity. After a configuration change that state
     * is gone, so the user goes back to the import screen instead of seeing an empty confirmation.
     * Unknown or missing routes fall back to the composer.
     */
    fun resolve(requested: String?, hasPendingImport: Boolean): String {
        val route = requested ?: return COMPOSER
        return when (route) {
            COMPOSER, SETUP, IMPORT, CREATE, SETTINGS -> route
            CONFIRM -> if (hasPendingImport) CONFIRM else IMPORT
            else -> COMPOSER
        }
    }
}
