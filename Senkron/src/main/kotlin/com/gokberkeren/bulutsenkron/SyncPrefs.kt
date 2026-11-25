package com.gokberkeren.bulutsenkron

import android.content.Context

class SyncPrefs(
    context: Context,
    private val privateFirebaseUrl: String,
    private val privateShortCode: String
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var serverInput: String
        get() {
            val saved = prefs.getString(KEY_SERVER_INPUT, "")?.trim().orEmpty()
            if (saved.isNotEmpty()) return saved
            // Auto-inherit server/short-code from Birlikteİzleyelim if already configured on this device
            val shared = appContext.getSharedPreferences("birlikte_izle_prefs", Context.MODE_PRIVATE)
                .getString("server_input", "")?.trim().orEmpty()
            if (shared.isNotEmpty()) {
                prefs.edit().putString(KEY_SERVER_INPUT, shared).apply()
            }
            return shared
        }
        set(value) = prefs.edit().putString(KEY_SERVER_INPUT, value.trim()).apply()

    var profilePin: String
        get() = prefs.getString(KEY_PIN, "")?.trim().orEmpty()
        set(value) = prefs.edit().putString(KEY_PIN, value.trim()).apply()

    var lastSyncSummary: String
        get() = prefs.getString(KEY_LAST_SUMMARY, "Henüz eşitlenmedi") ?: "Henüz eşitlenmedi"
        set(value) = prefs.edit().putString(KEY_LAST_SUMMARY, value).apply()

    fun isUsingPrivateShortCode(): Boolean {
        return privateShortCode.isNotBlank() &&
            privateFirebaseUrl.isNotBlank() &&
            serverInput.equals(privateShortCode, ignoreCase = true)
    }

    fun resolveEndpointUrl(): String? {
        val pin = profilePin
        if (!PIN_REGEX.matches(pin)) return null
        val input = serverInput
        return when {
            isUsingPrivateShortCode() ->
                "${privateFirebaseUrl.trimEnd('/')}/$privateShortCode/rooms/$pin/cloudsync_v5.json"
            input.startsWith("https://", ignoreCase = true) || input.startsWith("http://", ignoreCase = true) ->
                "${input.trimEnd('/')}/rooms/$pin/cloudsync_v5.json"
            else -> null
        }
    }

    fun isServerConfigured(): Boolean {
        val input = serverInput
        return isUsingPrivateShortCode() ||
            input.startsWith("https://", ignoreCase = true) ||
            input.startsWith("http://", ignoreCase = true)
    }

    companion object {
        private const val PREFS_NAME = "bulut_senkron_prefs"
        private const val KEY_SERVER_INPUT = "server_input"
        private const val KEY_PIN = "profile_pin"
        private const val KEY_LAST_SUMMARY = "last_summary"
        private val PIN_REGEX = Regex("^\\d{6}$")
    }
}
