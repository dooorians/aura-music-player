package com.ikko.aura.data

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch

/**
 * The settings as they are, or none (every setting at its default) when the file cannot be read.
 *
 * DataStore's `data` throws an [IOException] when the file is unreadable, and every setting is collected in a
 * `viewModelScope` or read with `first()` there: one bad read took the app down, and again at every launch.
 * Anything that is not an I/O error is a bug and still goes through.
 */
internal fun Flow<Preferences>.orEmptyOnReadError(): Flow<Preferences> = catch { e ->
    if (e is IOException) emit(emptyPreferences()) else throw e
}
