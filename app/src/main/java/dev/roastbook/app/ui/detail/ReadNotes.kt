package dev.roastbook.app.ui.detail

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Coffees whose "what happened" note the person closed. Closing hides it until the next read of
 * that coffee: [clear] runs when a read is asked for, so a later poor read says so again. Kept in
 * `shared_prefs/`, outside the backup include list: on a restored device the notes simply show again.
 */
@Singleton
class ReadNotes @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("read_notes", Context.MODE_PRIVATE)
    private val _dismissed = MutableStateFlow(prefs.getStringSet(KEY, emptySet()).orEmpty().toSet())
    val dismissed: StateFlow<Set<String>> = _dismissed

    fun dismiss(coffeeId: String) = save(_dismissed.value + coffeeId)
    fun clear(coffeeId: String) { if (coffeeId in _dismissed.value) save(_dismissed.value - coffeeId) }

    private fun save(ids: Set<String>) {
        _dismissed.value = ids
        prefs.edit { putStringSet(KEY, ids) }
    }

    private companion object { const val KEY = "dismissed" }
}
