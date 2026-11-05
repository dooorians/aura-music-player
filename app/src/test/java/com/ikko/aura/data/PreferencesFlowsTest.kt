package com.ikko.aura.data

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import java.io.IOException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PreferencesFlowsTest {

    private val key = booleanPreferencesKey("online_lyrics")

    @Test
    fun `settings that read fine pass through untouched`() = runTest {
        val prefs = mutablePreferencesOf(key to false)
        assertEquals(listOf<Preferences>(prefs), flowOf<Preferences>(prefs).orEmptyOnReadError().toList())
    }

    /** Regression: an unreadable settings file threw out of every collector in a viewModelScope and took the app down. */
    @Test
    fun `an unreadable file reads as every setting at its default`() = runTest {
        val broken = flow<Preferences> { throw IOException("disk") }
        assertEquals(emptyPreferences(), broken.orEmptyOnReadError().first())
        assertEquals(true, broken.orEmptyOnReadError().map { it[key] ?: true }.first())
    }

    @Test
    fun `what was read before the error is kept, then the defaults follow`() = runTest {
        val prefs = mutablePreferencesOf(key to false)
        val flaky = flow<Preferences> { emit(prefs); throw IOException("later") }
        assertEquals(listOf(prefs, emptyPreferences()), flaky.orEmptyOnReadError().toList())
    }

    @Test
    fun `a bug that is not an io error still surfaces`() {
        val buggy = flow<Preferences> { throw IllegalStateException("bug") }
        assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { buggy.orEmptyOnReadError().first() } }
    }
}
