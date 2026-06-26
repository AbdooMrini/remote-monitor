package com.remotemonitor.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

/** Encrypted persistent storage for session tokens using DataStore. */
private val Context.dataStore by preferencesDataStore(name = "secure_session")

class SessionManager(private val context: Context) {

    companion object {
        private val KEY_ACCESS_TOKEN   = stringPreferencesKey("access_token")
        private val KEY_REFRESH_TOKEN  = stringPreferencesKey("refresh_token")
        private val KEY_DEVICE_TOKEN   = stringPreferencesKey("device_token")
        private val KEY_USER_EMAIL     = stringPreferencesKey("user_email")
        private val KEY_USER_ID        = stringPreferencesKey("user_id")
        private val KEY_SERVER_URL     = stringPreferencesKey("server_url")
    }

    // ── Accessors (blocking — use only outside coroutine context) ──────────
    val accessToken:   String? get() = runBlocking { getFlow(KEY_ACCESS_TOKEN).first() }
    val refreshToken:  String? get() = runBlocking { getFlow(KEY_REFRESH_TOKEN).first() }
    val deviceToken:   String? get() = runBlocking { getFlow(KEY_DEVICE_TOKEN).first() }
    val userEmail:     String? get() = runBlocking { getFlow(KEY_USER_EMAIL).first() }
    val userId:        String? get() = runBlocking { getFlow(KEY_USER_ID).first() }
    val serverUrl:     String? get() = runBlocking { getFlow(KEY_SERVER_URL).first() }

    val isLoggedIn: Boolean get() = !accessToken.isNullOrBlank() && !deviceToken.isNullOrBlank()

    // ── Flow versions (use in coroutines / LiveData) ───────────────────────
    val accessTokenFlow:  Flow<String?> get() = getFlow(KEY_ACCESS_TOKEN)
    val deviceTokenFlow:  Flow<String?> get() = getFlow(KEY_DEVICE_TOKEN)

    // ── Writers ───────────────────────────────────────────────────────────
    suspend fun saveSession(
        accessToken:  String,
        refreshToken: String,
        deviceToken:  String,
        email:        String,
        userId:       String,
    ) {
        context.dataStore.edit { prefs ->
            prefs[KEY_ACCESS_TOKEN]  = accessToken
            prefs[KEY_REFRESH_TOKEN] = refreshToken
            prefs[KEY_DEVICE_TOKEN]  = deviceToken
            prefs[KEY_USER_EMAIL]    = email
            prefs[KEY_USER_ID]       = userId
        }
    }

    suspend fun updateAccessToken(token: String) {
        context.dataStore.edit { it[KEY_ACCESS_TOKEN] = token }
    }

    suspend fun setServerUrl(url: String) {
        context.dataStore.edit { it[KEY_SERVER_URL] = url }
    }

    suspend fun clearSession() {
        context.dataStore.edit { it.clear() }
    }

    // ── Private helper ────────────────────────────────────────────────────
    private fun <T> getFlow(key: androidx.datastore.preferences.core.Preferences.Key<T>): Flow<T?> =
        context.dataStore.data.map { prefs -> prefs[key] }
}
