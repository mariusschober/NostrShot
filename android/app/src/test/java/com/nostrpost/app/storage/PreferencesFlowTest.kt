package com.nostrpost.app.storage

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.Preferences as DataStorePreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** NOSTRSHOT-21: a broken DataStore read must emit defaults instead of hanging or crashing. */
class PreferencesFlowTest {

    @Test
    fun ioFailureFallsBackToEmptyPreferences() = runBlocking {
        val broken = flow<DataStorePreferences> { throw IOException("disk on fire") }
        val recovered = broken.recoverWithEmpty().first()
        assertTrue(recovered.asMap().isEmpty())
    }

    @Test
    fun healthyFlowIsPassedThrough() = runBlocking {
        val healthy = flow {
            emit(mutablePreferencesOf(com.nostrpost.app.storage.Preferences.KEY_DRAFT to "kept"))
        }
        val value = healthy.recoverWithEmpty().first()
        assertEquals("kept", value[com.nostrpost.app.storage.Preferences.KEY_DRAFT])
        assertTrue(emptyPreferences().asMap().isEmpty())
    }
}
