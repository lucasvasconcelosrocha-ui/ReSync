package com.resync.app.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

val Context.accountDataStore: DataStore<Preferences> by preferencesDataStore(name = "account_preferences")

class AccountPreferences(private val context: Context) {

    private object PreferencesKeys {
        val ACTIVE_ACCOUNT_EMAIL = stringPreferencesKey("active_cloud_account_email")
    }

    val activeAccountEmailFlow: Flow<String?> = context.accountDataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(androidx.datastore.preferences.core.emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.ACTIVE_ACCOUNT_EMAIL]
        }

    suspend fun setActiveAccountEmail(email: String?) {
        context.accountDataStore.edit { preferences ->
            if (email != null) {
                preferences[PreferencesKeys.ACTIVE_ACCOUNT_EMAIL] = email
            } else {
                preferences.remove(PreferencesKeys.ACTIVE_ACCOUNT_EMAIL)
            }
        }
    }
}
