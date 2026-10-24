package com.nostrpost.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** NOSTRSHOT-40: secure-window ownership is reference counted. */
class SecureWindowFlagTest {

    private var secure = false
    private var setCalls = 0
    private var clearCalls = 0

    private fun controller() = SecureWindowFlagController(
        hasSecureFlag = { secure },
        setSecureFlag = {
            secure = true
            setCalls++
        },
        clearSecureFlag = {
            secure = false
            clearCalls++
        }
    )

    @Test
    fun acquireSetsFlagAndLastReleaseClearsIt() {
        val controller = controller()

        controller.acquire()
        assertTrue(secure)
        assertEquals(1, setCalls)

        controller.release()
        assertFalse(secure)
        assertEquals(1, clearCalls)
    }

    @Test
    fun externalFlagIsNotCleared() {
        secure = true
        val controller = controller()

        controller.acquire()
        assertTrue(secure)
        assertEquals(0, setCalls)

        controller.release()
        assertTrue(secure)
        assertEquals(0, clearCalls)
    }

    @Test
    fun overlappingScreensKeepFlagUntilLastRelease() {
        val first = controller()
        val second = controller()

        first.acquire()
        second.acquire()
        assertEquals(1, setCalls)

        second.release()
        assertTrue(secure)
        assertEquals(0, clearCalls)

        first.release()
        assertFalse(secure)
        assertEquals(1, clearCalls)
    }

    @Test
    fun repeatedReleaseDoesNotClearSomeoneElsesFlag() {
        secure = true
        val controller = controller()

        controller.release()
        controller.release()

        assertTrue(secure)
        assertEquals(0, clearCalls)
    }

    @Test
    fun reacquireAfterReleaseSetsTheFlagAgain() {
        val controller = controller()

        controller.acquire()
        controller.release()
        controller.acquire()

        assertTrue(secure)
        assertEquals(2, setCalls)
        controller.release()
        assertFalse(secure)
        assertEquals(2, clearCalls)
    }
}
