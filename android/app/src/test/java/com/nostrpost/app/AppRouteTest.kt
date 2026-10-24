package com.nostrpost.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** NOSTRSHOT-43: a restored route is only shown when its in-memory state is still available. */
class AppRouteTest {

    @Test
    fun validRoutesArePreserved() {
        listOf(
            AppRoute.COMPOSER,
            AppRoute.SETUP,
            AppRoute.IMPORT,
            AppRoute.CREATE,
            AppRoute.SETTINGS
        ).forEach { route ->
            assertEquals(route, AppRoute.resolve(route, hasPendingImport = false))
        }
    }

    @Test
    fun confirmIsPreservedOnlyWhilePendingImportExists() {
        assertEquals(AppRoute.CONFIRM, AppRoute.resolve(AppRoute.CONFIRM, hasPendingImport = true))
        assertEquals(AppRoute.IMPORT, AppRoute.resolve(AppRoute.CONFIRM, hasPendingImport = false))
    }

    @Test
    fun missingOrUnknownRoutesFallBackToComposer() {
        assertEquals(AppRoute.COMPOSER, AppRoute.resolve(null, hasPendingImport = false))
        assertEquals(AppRoute.COMPOSER, AppRoute.resolve("", hasPendingImport = false))
        assertEquals(AppRoute.COMPOSER, AppRoute.resolve("not-a-route", hasPendingImport = true))
    }
}
